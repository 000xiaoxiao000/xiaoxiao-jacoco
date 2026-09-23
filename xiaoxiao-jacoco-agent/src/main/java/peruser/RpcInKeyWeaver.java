package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 跨服务【入站】key 归属织入（二期）：让没有 Servlet 入口的 B 服务（Dubbo provider / gRPC server /
 * Spring WebFlux）也能从调用载体里读回 key，补齐 "A 出站 httpkey + B 入站" 的闭环。业务代码一行都不用改。
 *
 * <pre>
 *   Dubbo provider  AbstractInvoker.invoke(Invocation)        —— 所有 protocol 的最终执行入口
 *                   ContextFilter.invoke(Invoker, Invocation) —— provider 侧 filter（@Activate(PROVIDER)）
 *   gRPC server     ServerCalls$UnaryServerCallHandler.startCall(ServerCall, Metadata)
 *                   ServerCalls$StreamingServerCallHandler.startCall(ServerCall, Metadata)
 *                        -> RpcInKeyHook.enter(载体)   入口：读 key -> ThreadProbeStore.begin(key)
 *   gRPC server     ServerCallImpl.close(Status, Metadata)    —— 本次调用结束
 *                        -> RpcInKeyHook.exit()        出口：ThreadProbeStore.end()
 *   WebFlux         InvocableHandlerMethod.lambda$invoke$N(ServerWebExchange, BindingContext, Object[])
 *                        -> enter(exchange) / exit()   业务 controller 方法就在该 lambda 内同步执行
 * </pre>
 *
 * <p>两种织入形态：
 * <ul>
 *   <li><b>SCOPED</b>（Dubbo / WebFlux）：try { 原方法体 } finally { exit() } —— 业务在同一线程同步执行，配对最稳；</li>
 *   <li><b>分离配对</b>（gRPC）：enter 在 startCall、exit 在 close，两者在同一条
 *       SerializingExecutor 线程上串行执行；万一 close 没走到，下一次 enter 会兜底收尾
 *       （见 {@link RpcInKeyHook#enter} 的 drainDangling），不会把 key 泄漏到复用线程上。</li>
 * </ul>
 *
 * <p>WebFlux 的坑：不要在 {@code HttpWebHandlerAdapter#handle}（统一入站口）上做 SCOPED ——
 * 它返回 {@code Mono}、立即返回，业务要等 subscribe 才执行，try/finally 会在业务跑之前就退出。
 * 真正的业务窗口是 {@code InvocableHandlerMethod} 里那个 lambda（5.3.x / 6.2.x 实测
 * {@code Method.invoke} 就在其中同步调用），且它手里有 exchange，能直接读请求头。
 *
 * <p>与 {@link HttpKeyWeaver} 一样：按【类名 + 方法签名】精确匹配，不依赖任何框架的编译期 API，
 * 匹配不上原样返回；这些框架类通常不在 {@code includes=} 里，故独立于覆盖率插桩走单独分支。
 *
 * <p>入站是只读动作（不给请求加任何字段），因此默认随 {@code headerkey=} / {@code httpkey=true} 生效，
 * 可用 {@code rpckey=false} 显式关闭。
 */
public final class RpcInKeyWeaver {

    private static final String HOOK = "peruser/RpcInKeyHook";
    private static final String ENTER_DESC = "(Ljava/lang/Object;)V";
    private static final String EXIT_DESC = "()V";

    /**
     * {类名, 方法名, 描述符, 载体槽位, 匹配方式}：整个方法体被 try/finally 包住。
     * 匹配方式：{@code "prefix"} 表示按方法名前缀匹配（javac 生成的 lambda 名编号会随版本变），
     * 其余（含空串）按方法名全等匹配。
     */
    private static final String[][] SCOPED = {
            // Dubbo 3.x / 2.7（org.apache.dubbo）
            {"org/apache/dubbo/rpc/protocol/AbstractInvoker", "invoke",
                    "(Lorg/apache/dubbo/rpc/Invocation;)Lorg/apache/dubbo/rpc/Result;", "1", ""},
            {"org/apache/dubbo/rpc/filter/ContextFilter", "invoke",
                    "(Lorg/apache/dubbo/rpc/Invoker;Lorg/apache/dubbo/rpc/Invocation;)Lorg/apache/dubbo/rpc/Result;", "2", ""},
            // Dubbo 2.6 及更早（com.alibaba.dubbo）
            {"com/alibaba/dubbo/rpc/protocol/AbstractInvoker", "invoke",
                    "(Lcom/alibaba/dubbo/rpc/Invocation;)Lcom/alibaba/dubbo/rpc/Result;", "1", ""},
            {"com/alibaba/dubbo/rpc/filter/ContextFilter", "invoke",
                    "(Lcom/alibaba/dubbo/rpc/Invoker;Lcom/alibaba/dubbo/rpc/Invocation;)Lcom/alibaba/dubbo/rpc/Result;", "2", ""},
            // Spring WebFlux（非 Servlet 入站）：注解式 Controller 的业务方法调用点。
            // lambda$invoke$N 是 javac 生成的私有方法，N 会随 Spring 版本/编译顺序变，故按前缀匹配。
            // 5.3.x 与 6.2.x 实测：方法体内 java/lang/reflect/Method.invoke（即业务方法）是同步执行的，
            // 因此 try/finally 恰好框住「业务方法执行窗口」，与 Servlet 侧 HttpServlet.service 的语义一致。
            {"org/springframework/web/reactive/result/method/InvocableHandlerMethod", "lambda$invoke$",
                    "(Lorg/springframework/web/server/ServerWebExchange;Lorg/springframework/web/reactive/BindingContext;"
                            + "[Ljava/lang/Object;)Lreactor/core/publisher/Mono;", "1", "prefix"},
    };

    /** {类名, 方法名, 描述符, 载体槽位}：只在方法开头织 enter，出口在别处（见 EXIT_AT）。 */
    private static final String[][] ENTER_AT = {
            {"io/grpc/stub/ServerCalls$UnaryServerCallHandler", "startCall",
                    "(Lio/grpc/ServerCall;Lio/grpc/Metadata;)Lio/grpc/ServerCall$Listener;", "2"},
            {"io/grpc/stub/ServerCalls$StreamingServerCallHandler", "startCall",
                    "(Lio/grpc/ServerCall;Lio/grpc/Metadata;)Lio/grpc/ServerCall$Listener;", "2"},
    };

    /** {类名, 方法名, 描述符}：只在方法开头织 exit。 */
    private static final String[][] EXIT_AT = {
            // 1.27 ~ 1.70 的 ServerCallImpl.close 签名一致
            {"io/grpc/internal/ServerCallImpl", "close", "(Lio/grpc/Status;Lio/grpc/Metadata;)V"},
    };

    private RpcInKeyWeaver() {
    }

    /** 快速预判断：O(1) 类名匹配，避免对每个类都做字节码解析。 */
    public static boolean isTarget(String className) {
        if (className == null) {
            return false;
        }
        for (String[][] table : new String[][][]{SCOPED, ENTER_AT, EXIT_AT}) {
            for (String[] p : table) {
                if (p[0].equals(className)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 织入入站 key 归属。返回 null 表示未命中（调用方保持原字节码）。
     *
     * @param loader 目标类的类加载器，用于确认它能看见 hook（看不见就不织，避免运行时 NoClassDefFoundError）
     */
    public static byte[] weave(byte[] buf, String className, ClassLoader loader) {
        if (!hookVisible(loader)) {
            return null;
        }
        byte[] out = weaveScoped(buf, className, loader);
        if (out != null) {
            return out;
        }
        out = weaveEnter(buf, className);
        if (out != null) {
            return out;
        }
        return weaveExit(buf, className);
    }

    // ===================== Dubbo：try { 原方法体 } finally { exit() } =====================

    private static byte[] weaveScoped(byte[] buf, String className, ClassLoader loader) {
        int idx = -1;
        for (int i = 0; i < SCOPED.length; i++) {
            if (SCOPED[i][0].equals(className)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        final String method = SCOPED[idx][1];
        final String desc = SCOPED[idx][2];
        final int slot = Integer.parseInt(SCOPED[idx][3]);
        final boolean prefix = "prefix".equals(SCOPED[idx][4]);
        final boolean[] hit = {false};

        ClassReader cr = new ClassReader(buf);
        // 加 try/catch 块会引入新的栈帧，必须 COMPUTE_FRAMES；用目标类加载器解析公共超类
        ClassWriter cw = new LoaderAwareClassWriter(cr, loader);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !desc.equals(descriptor)) {
                    return mv;
                }
                boolean nameHit = prefix ? (name != null && name.startsWith(method)) : method.equals(name);
                if (!nameHit) {
                    return mv;
                }
                hit[0] = true;
                return new ScopedMethodAdapter(mv, slot);
            }
        }, ClassReader.EXPAND_FRAMES);
        return hit[0] ? cw.toByteArray() : null;
    }

    /** 入口 enter(载体) + 出口 exit()（正常返回与异常路径各一次，语义等价于 finally）。 */
    private static final class ScopedMethodAdapter extends MethodVisitor {
        private final int slot;
        private final Label tryStart = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        ScopedMethodAdapter(MethodVisitor mv, int slot) {
            super(Opcodes.ASM9, mv);
            this.slot = slot;
        }

        @Override
        public void visitCode() {
            mv.visitCode();
            mv.visitLabel(tryStart);
            // RpcInKeyHook.enter(载体)
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "enter", ENTER_DESC, false);
            mv.visitTryCatchBlock(tryStart, end, handler, null);
        }

        @Override
        public void visitInsn(int opcode) {
            if ((opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN) || opcode == Opcodes.RETURN) {
                exitCall();
            }
            mv.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            mv.visitLabel(end);
            mv.visitLabel(handler);
            exitCall();
            mv.visitInsn(Opcodes.ATHROW);
            mv.visitMaxs(maxStack, maxLocals);
        }

        private void exitCall() {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "exit", EXIT_DESC, false);
        }
    }

    // ===================== gRPC：入口 /出口分离 =====================

    private static byte[] weaveEnter(byte[] buf, String className) {
        int idx = -1;
        for (int i = 0; i < ENTER_AT.length; i++) {
            if (ENTER_AT[i][0].equals(className)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        final String method = ENTER_AT[idx][1];
        final String desc = ENTER_AT[idx][2];
        final int slot = Integer.parseInt(ENTER_AT[idx][3]);
        final boolean[] hit = {false};

        ClassReader cr = new ClassReader(buf);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !method.equals(name) || !desc.equals(descriptor)) {
                    return mv;
                }
                hit[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        mv.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "enter", ENTER_DESC, false);
                    }
                };
            }
        }, 0);
        return hit[0] ? cw.toByteArray() : null;
    }

    private static byte[] weaveExit(byte[] buf, String className) {
        int idx = -1;
        for (int i = 0; i < EXIT_AT.length; i++) {
            if (EXIT_AT[i][0].equals(className)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        final String method = EXIT_AT[idx][1];
        final String desc = EXIT_AT[idx][2];
        final boolean[] hit = {false};

        ClassReader cr = new ClassReader(buf);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !method.equals(name) || !desc.equals(descriptor)) {
                    return mv;
                }
                hit[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitCode() {
                        mv.visitCode();
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "exit", EXIT_DESC, false);
                    }
                };
            }
        }, 0);
        return hit[0] ? cw.toByteArray() : null;
    }

    /** 与 HttpKeyWeaver 同款保护：类加载器看不见 hook 就不织，宁可不生效也不能运行时炸。 */
    private static boolean hookVisible(ClassLoader loader) {
        try {
            Class.forName("peruser.RpcInKeyHook", false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
