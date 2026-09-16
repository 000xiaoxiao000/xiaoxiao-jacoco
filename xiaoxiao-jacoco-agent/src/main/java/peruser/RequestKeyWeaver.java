package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * 按请求头把【一次请求】归属到某个 key，实现并发按用户/用例的覆盖率分离（零改目标系统）。
 *
 * 不依赖任何具体 Web 框架：直接字节码织入所有 Servlet 容器的统一入口
 *   javax.servlet.http.HttpServlet / jakarta.servlet.http.HttpServlet 的
 *   service(ServletRequest, ServletResponse) 方法（Tomcat/Jetty/Undertow 都会调用它，
 *   Spring DispatcherServlet 也经由它进入）。容器内任意 HttpServletRequest 的请求都被钩住。
 *
 * 注入的等价 Java（request 位于方法局部变量 slot 1，类型为 ServletRequest）：
 * <pre>
 *   peruser.RequestKeyHook.tag(request, headerName);   // 入口：读 headerName，非空则 ThreadProbeStore.begin(key)
 *   try { &lt;原方法体&gt; } finally { peruser.RequestKeyHook.untag(); }  // 出口：ThreadProbeStore.end()
 * </pre>
 *
 * 不新增任何方法局部变量（RequestKeyHook 用 ThreadLocal 记状态），避免与原方法局部变量冲突。
 *
 * 兼容性：用【目标类加载器】解析公共父类，解析不到回退 java/lang/Object；否则 ASM 默认用 agent
 * 自己的类加载器去加载目标应用的类（如 Spring 的 HandlerAdapter），那些类不在 agent classpath
 * 上 -> ClassNotFoundException -> 整个织入崩溃（这是早期绑定 Spring DispatcherServlet 时的崩溃根因）。
 */
public final class RequestKeyWeaver {

    // 同时支持 javax.servlet（Spring Boot 2 / 传统 servlet）与 jakarta.servlet（Spring Boot 3 / Jakarta EE 9+）
    private static final String[] SERVLET_CLASSES = {
            "javax/servlet/http/HttpServlet",
            "jakarta/servlet/http/HttpServlet"
    };
    // 每个 servlet 命名空间对应的 service 方法描述符。
    // 两个重载都织入：容器通常走 service(ServletRequest, ServletResponse)，
    // 但若目标系统里有子类重写了它并直接 super.service(HttpServletRequest, HttpServletResponse)，
    // 只织入前者就会漏掉（表现为钩子一次都不触发、覆盖率全空）。
    // 两个都命中时会嵌套 push/pop，语义安全（见 RequestKeyHook / KeyBridge）。
    private static final String[][] SERVICE_DESC = {
            {"service", "(Ljavax/servlet/ServletRequest;Ljavax/servlet/ServletResponse;)V"},
            {"service", "(Ljakarta/servlet/ServletRequest;Ljakarta/servlet/ServletResponse;)V"},
            {"service", "(Ljavax/servlet/http/HttpServletRequest;Ljavax/servlet/http/HttpServletResponse;)V"},
            {"service", "(Ljakarta/servlet/http/HttpServletRequest;Ljakarta/servlet/http/HttpServletResponse;)V"}
    };

    private RequestKeyWeaver() {
    }

    public static boolean isTarget(String className) {
        if (className == null) return false;
        for (String c : SERVLET_CLASSES) {
            if (c.equals(className)) return true;
        }
        return false;
    }

    public static byte[] weave(byte[] buf, String headerName, ClassLoader loader) {
        ClassReader cr = new ClassReader(buf);
        ClassWriter cw = new LoaderAwareClassWriter(cr, loader);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                for (String[] s : SERVICE_DESC) {
                    if (s[0].equals(name) && s[1].equals(descriptor)) {
                        return new HookMethodAdapter(mv, headerName);
                    }
                }
                return mv;
            }
        };
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * 用目标类加载器解析公共超类，解析失败（如目标应用特有的类不在 agent classpath 上）一律回退
     * java/lang/Object。COMPUTE_FRAMES 在重算栈图时会调用本方法，必须足够健壮否则整个织入会炸。
     */
    private static final class LoaderAwareClassWriter extends ClassWriter {
        private final ClassLoader loader;

        LoaderAwareClassWriter(ClassReader cr, ClassLoader loader) {
            super(cr, COMPUTE_FRAMES | COMPUTE_MAXS);
            this.loader = loader;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (type1.equals(type2)) return type1;
            if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) {
                return "java/lang/Object";
            }
            Class<?> c = tryLoad(type1);
            Class<?> d = tryLoad(type2);
            if (c == null || d == null) return "java/lang/Object";
            if (c.isAssignableFrom(d)) return type1;
            if (d.isAssignableFrom(c)) return type2;
            for (Class<?> sup = c; sup != null; sup = sup.getSuperclass()) {
                if (sup.isAssignableFrom(d)) return Type.getInternalName(sup);
            }
            return "java/lang/Object";
        }

        private Class<?> tryLoad(String internal) {
            String cn = internal.replace('/', '.');
            ClassLoader[] ls = {loader, Thread.currentThread().getContextClassLoader(), getClass().getClassLoader()};
            for (ClassLoader l : ls) {
                if (l == null) continue;
                try {
                    return Class.forName(cn, false, l);
                } catch (Throwable ignore) {
                    // 尝试下一个类加载器
                }
            }
            try {
                return Class.forName(cn);
            } catch (Throwable ignore) {
                return null;
            }
        }
    }

    private static final class HookMethodAdapter extends MethodVisitor {
        private final String headerName;
        private final Label tryStart = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        HookMethodAdapter(MethodVisitor mv, String headerName) {
            super(Opcodes.ASM9, mv);
            this.headerName = headerName;
        }

        @Override
        public void visitCode() {
            mv.visitCode();
            // try {  (tryStart 在 tag 之前，确保 tag 抛错也能走到 finally 安全)
            mv.visitLabel(tryStart);
            // peruser.RequestKeyHook.tag(request, headerName)；request 在 slot 1（ServletRequest）
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitLdcInsn(headerName);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "peruser/RequestKeyHook",
                    "tag",
                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                    false);
            mv.visitTryCatchBlock(tryStart, end, handler, null);
        }

        @Override
        public void visitInsn(int opcode) {
            // 所有正常返回前先 untag（ATHROW 交给下方 try/catch 处理器统一处理，避免重复 finally）
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) || opcode == Opcodes.RETURN) {
                untagCall();
                mv.visitInsn(opcode);
                return;
            }
            mv.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // catch(Throwable): finally { untag(); throw; }
            mv.visitLabel(end);
            mv.visitLabel(handler);
            untagCall();
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(maxStack, maxLocals);
        }

        private void untagCall() {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                    "peruser/RequestKeyHook",
                    "untag",
                    "()V",
                    false);
        }
    }
}
