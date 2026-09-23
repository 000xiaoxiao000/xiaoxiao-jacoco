package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 跨服务（出站 HTTP / RPC）key 透传织入：把 A 服务当前线程的 key 写进【出站请求】，
 * 让 B 服务（挂了同一个 agent 并配了 headerkey=）能原样读到同一个 key。业务代码一行都不用改。
 *
 * <pre>
 *   Feign       SynchronousMethodHandler.targetRequest(RequestTemplate) / RequestTemplate.request()
 *   OkHttp      Request$Builder.build()                        （顺带覆盖 Retrofit）
 *   Apache 4/5  InternalHttpClient / MinimalHttpClient.doExecute(...)
 *   Dubbo       AbstractClusterInvoker.invoke(Invocation)
 *   gRPC        ClientCallImpl.start(Listener, Metadata)
 *   Spring      AbstractClientHttpRequest.getHeaders()          （RestTemplate / WebClient 通用兜底）
 *              -> HttpKeyHook.out(载体)  把 key 写进 header（无 key 时零动作）
 * </pre>
 *
 * <p>织入点全部按【类名 + 方法签名】精确匹配，不依赖任何客户端的编译期 API；
 * 匹配不上就原样返回（安全降级，绝不破坏目标类）。这些客户端类通常不在 {@code includes=} 里，
 * 所以本织入独立于覆盖率插桩、在 {@code shouldInstrument} 之前单独走一条分支。
 *
 * <p>默认关闭（{@code httpkey=true} 才启用）：给业务请求加 header 属于【改变目标系统的数据】，
 * 必须由使用方显式点头。
 */
public final class HttpKeyWeaver {

    private static final String HOOK = "peruser/HttpKeyHook";
    private static final String HOOK_OUT = "(Ljava/lang/Object;)Ljava/lang/Object;";

    /**
     * 出站织入点：{类名, 方法名, 方法描述符, 载体槽位, 载体内部类名}。
     * 织入形态是「改写局部变量槽位」：ALOAD slot -> hook.out -> CHECKCAST -> ASTORE slot。
     */
    private static final String[][] OUTBOUND = {
            // Feign：双点都织（targetRequest 是主流路径，request() 兜底），靠 hook 幂等去重
            {"feign/SynchronousMethodHandler", "targetRequest",
                    "(Lfeign/RequestTemplate;)Lfeign/Request;", "1", "feign/RequestTemplate"},
            {"feign/RequestTemplate", "request",
                    "()Lfeign/Request;", "0", "feign/RequestTemplate"},
            // OkHttp 3.x / 4.x（Retrofit 也走这里）
            {"okhttp3/Request$Builder", "build",
                    "()Lokhttp3/Request;", "0", "okhttp3/Request$Builder"},
            {"com/squareup/okhttp/Request$Builder", "build",
                    "()Lcom/squareup/okhttp/Request;", "0", "com/squareup/okhttp/Request$Builder"},
            // Apache HttpClient 4.3+
            {"org/apache/http/impl/client/InternalHttpClient", "doExecute",
                    "(Lorg/apache/http/HttpHost;Lorg/apache/http/HttpRequest;Lorg/apache/http/protocol/HttpContext;)Lorg/apache/http/client/methods/CloseableHttpResponse;",
                    "2", "org/apache/http/HttpRequest"},
            {"org/apache/http/impl/client/MinimalHttpClient", "doExecute",
                    "(Lorg/apache/http/HttpHost;Lorg/apache/http/HttpRequest;Lorg/apache/http/protocol/HttpContext;)Lorg/apache/http/client/methods/CloseableHttpResponse;",
                    "2", "org/apache/http/HttpRequest"},
            // Apache HttpClient 5.x
            {"org/apache/hc/client5/http/impl/classic/InternalHttpClient", "doExecute",
                    "(Lorg/apache/hc/core5/http/HttpHost;Lorg/apache/hc/core5/http/ClassicHttpRequest;Lorg/apache/hc/core5/http/protocol/HttpContext;)Lorg/apache/hc/client5/http/impl/classic/CloseableHttpResponse;",
                    "2", "org/apache/hc/core5/http/ClassicHttpRequest"},
            // Dubbo 2.x / 3.x（消费者侧集群调用入口）
            {"org/apache/dubbo/rpc/cluster/support/AbstractClusterInvoker", "invoke",
                    "(Lorg/apache/dubbo/rpc/Invocation;)Lorg/apache/dubbo/rpc/Result;",
                    "1", "org/apache/dubbo/rpc/Invocation"},
            // gRPC
            {"io/grpc/internal/ClientCallImpl", "start",
                    "(Lio/grpc/ClientCall$Listener;Lio/grpc/Metadata;)V", "2", "io/grpc/Metadata"},
    };

    /**
     * Spring 通用兜底：织 getHeaders() 的【返回值】（不是入口 —— 入口织会递归调用自己）。
     * 一个类覆盖 RestTemplate / WebClient 的全部 ClientHttpRequest 实现。
     */
    private static final String[] HEADERS_RETURN = {
            "org/springframework/http/client/AbstractClientHttpRequest",
            "org/springframework/http/client/reactive/AbstractClientHttpRequest",
    };
    private static final String GET_HEADERS = "getHeaders";
    private static final String GET_HEADERS_DESC = "()Lorg/springframework/http/HttpHeaders;";
    private static final String HTTP_HEADERS = "org/springframework/http/HttpHeaders";

    private HttpKeyWeaver() {
    }

    /** 快速预判断：O(1) 类名匹配，避免对每个类都做字节码解析。 */
    public static boolean isTarget(String className) {
        if (className == null) {
            return false;
        }
        for (String[] p : OUTBOUND) {
            if (p[0].equals(className)) {
                return true;
            }
        }
        for (String c : HEADERS_RETURN) {
            if (c.equals(className)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 织入出站 key 注入。返回 null 表示未命中（调用方保持原字节码）。
     *
     * @param loader 目标类的类加载器，用于确认它能看见 hook（看不见就不织，避免运行时 NoClassDefFoundError）
     */
    public static byte[] weave(byte[] buf, String className, ClassLoader loader) {
        if (!hookVisible(loader)) {
            return null;
        }
        byte[] out = weaveOutbound(buf, className);
        return (out != null) ? out : weaveHeadersReturn(buf, className);
    }

    /** 「改写槽位」形态：ALOAD slot -> hook.out -> CHECKCAST -> ASTORE slot。 */
    private static byte[] weaveOutbound(byte[] buf, String className) {
        int idx = -1;
        for (int i = 0; i < OUTBOUND.length; i++) {
            if (OUTBOUND[i][0].equals(className)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return null;
        }
        final String method = OUTBOUND[idx][1];
        final String desc = OUTBOUND[idx][2];
        final int slot = Integer.parseInt(OUTBOUND[idx][3]);
        final String cast = OUTBOUND[idx][4];
        final boolean[] hit = {false};

        ClassReader cr = new ClassReader(buf);
        // 三方客户端类：只重算 maxs，不 COMPUTE_FRAMES（可能引用 agent 看不到的类）
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
                        // 载体 = HttpKeyHook.out(载体)   无 key 时 hook 原样返回，请求字节不变
                        mv.visitVarInsn(Opcodes.ALOAD, slot);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "out", HOOK_OUT, false);
                        mv.visitTypeInsn(Opcodes.CHECKCAST, cast);
                        mv.visitVarInsn(Opcodes.ASTORE, slot);
                    }
                };
            }
        }, 0);
        return hit[0] ? cw.toByteArray() : null;
    }

    /**
     * 「包住返回值」形态：在 ARETURN 之前插 hook.out 并 CHECKCAST。
     * 只对 getHeaders() 用 —— 它的返回值就是可写的 header 容器。
     */
    private static byte[] weaveHeadersReturn(byte[] buf, String className) {
        boolean match = false;
        for (String c : HEADERS_RETURN) {
            if (c.equals(className)) {
                match = true;
            }
        }
        if (!match) {
            return null;
        }
        final boolean[] hit = {false};
        ClassReader cr = new ClassReader(buf);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null || !GET_HEADERS.equals(name) || !GET_HEADERS_DESC.equals(descriptor)) {
                    return mv;
                }
                hit[0] = true;
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "out", HOOK_OUT, false);
                            mv.visitTypeInsn(Opcodes.CHECKCAST, HTTP_HEADERS);
                        }
                        mv.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        return hit[0] ? cw.toByteArray() : null;
    }

    /**
     * 织入前确认该类加载器能解析 hook。Spring Boot 可执行 jar 的 LaunchedURLClassLoader
     * 委派到系统类加载器（agent jar 在 -javaagent 机制下挂在系统类路径上），正常可见；
     * 插件化 / 隔离类加载器场景可能看不见，此时【不织】比运行时抛 NoClassDefFoundError 安全。
     */
    private static boolean hookVisible(ClassLoader loader) {
        try {
            Class.forName("peruser.HttpKeyHook", false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
