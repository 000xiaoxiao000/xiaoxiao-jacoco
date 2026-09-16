package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步归属织入：让线程池 / 异步任务里的覆盖率也能归到发起请求的 key。
 *
 * 问题：key 存在 ThreadLocal 里，异步子线程拿不到 —— @Async、CompletableFuture、线程池
 * 提交的任务要么丢失覆盖、要么记到别的 key 上。
 *
 * 方案（与 TTL 思路一致）：在【提交时刻】捕获 key，在【执行时刻】设置并在 finally 回退。
 * 织入 JDK 的统一入口，覆盖绝大多数异步框架（Spring @Async、CompletableFuture、
 * ThreadPoolTaskExecutor、Executors 各种池、ScheduledExecutor）：
 *
 * <pre>
 *   java/util/concurrent/ThreadPoolExecutor            execute(Runnable)
 *   java/util/concurrent/AbstractExecutorService       submit(Runnable) / submit(Runnable,T) / submit(Callable)
 *   java/util/concurrent/ForkJoinPool                  execute(Runnable) / submit(Runnable)
 *   java/util/concurrent/ScheduledThreadPoolExecutor   schedule(Runnable,long,TimeUnit) / schedule(Callable,long,TimeUnit)
 * </pre>
 *
 * 注入的等价 Java（以 execute 为例，command 位于局部变量 slot 1）：
 * <pre>
 *   command = peruserrt.KeyBridge.wrap(command);   // 无 key 时原样返回，零开销
 *   &lt;原方法体&gt;
 * </pre>
 *
 * 注意：这些类由 bootstrap 加载，因此只能调用 bootstrap 可见的 peruserrt.KeyBridge
 * （由 BootClassInjector 注入）。周期性任务（scheduleAtFixedRate / scheduleWithFixedDelay）
 * 不织入 —— 它们脱离单次请求生命周期，归属没有意义。
 */
public final class AsyncKeyWeaver {

    private static final String BRIDGE = "peruserrt/KeyBridge";
    private static final String WRAP_RUNNABLE = "(Ljava/lang/Runnable;)Ljava/lang/Runnable;";
    private static final String WRAP_CALLABLE = "(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Callable;";

    /** 目标类 -> 需要织入的 {方法名, 方法描述符, 包装方法, 包装描述符, 参数槽位} */
    private static final Map<String, List<String[]>> TARGETS = new HashMap<>();

    static {
        TARGETS.put("java/util/concurrent/ThreadPoolExecutor", Arrays.<String[]>asList(
                new String[]{"execute", "(Ljava/lang/Runnable;)V", "wrap", WRAP_RUNNABLE, "1"}
        ));
        TARGETS.put("java/util/concurrent/AbstractExecutorService", Arrays.asList(
                new String[]{"submit", "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;", "wrap", WRAP_RUNNABLE, "1"},
                new String[]{"submit", "(Ljava/lang/Runnable;Ljava/lang/Object;)Ljava/util/concurrent/Future;", "wrap", WRAP_RUNNABLE, "1"},
                new String[]{"submit", "(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;", "wrapCallable", WRAP_CALLABLE, "1"}
        ));
        TARGETS.put("java/util/concurrent/ForkJoinPool", Arrays.asList(
                new String[]{"execute", "(Ljava/lang/Runnable;)V", "wrap", WRAP_RUNNABLE, "1"},
                new String[]{"submit", "(Ljava/lang/Runnable;)Ljava/util/concurrent/ForkJoinTask;", "wrap", WRAP_RUNNABLE, "1"}
        ));
        TARGETS.put("java/util/concurrent/ScheduledThreadPoolExecutor", Arrays.asList(
                new String[]{"schedule", "(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;", "wrap", WRAP_RUNNABLE, "1"},
                new String[]{"schedule", "(Ljava/util/concurrent/Callable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;", "wrapCallable", WRAP_CALLABLE, "1"}
        ));
    }

    private AsyncKeyWeaver() {
    }

    public static boolean isTarget(String className) {
        return className != null && TARGETS.containsKey(className);
    }

    /** 需要织入的 JDK 类（点分名），供 agent 启动后对已加载的类做 retransform。 */
    public static java.util.Collection<String> targetClassNames() {
        java.util.List<String> out = new java.util.ArrayList<>(TARGETS.size());
        for (String vm : TARGETS.keySet()) {
            out.add(vm.replace('/', '.'));
        }
        return out;
    }

    public static byte[] weave(byte[] buf) {
        final ClassReader cr = new ClassReader(buf);
        // 只重算 maxs：JDK 类带 stack map frame，不能 COMPUTE_FRAMES（会触发 getCommonSuperClass 加载类）
        final ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null) {
                    return null;
                }
                final List<String[]> methods = TARGETS.get(cr.getClassName());
                if (methods != null) {
                    for (String[] m : methods) {
                        if (m[0].equals(name) && m[1].equals(descriptor)) {
                            // 静态方法没有 this，参数从 slot 0 开始；这些 JDK 方法都是实例方法，slot 1 是任务
                            int slot = ((access & Opcodes.ACC_STATIC) == 0) ? Integer.parseInt(m[4]) : 0;
                            return new WrapMethodAdapter(mv, m[2], m[3], slot);
                        }
                    }
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    private static final class WrapMethodAdapter extends MethodVisitor {
        private final String wrapMethod;
        private final String wrapDesc;
        private final int slot;

        WrapMethodAdapter(MethodVisitor mv, String wrapMethod, String wrapDesc, int slot) {
            super(Opcodes.ASM9, mv);
            this.wrapMethod = wrapMethod;
            this.wrapDesc = wrapDesc;
            this.slot = slot;
        }

        @Override
        public void visitCode() {
            mv.visitCode();
            // 任务参数 = KeyBridge.wrap(任务参数)
            mv.visitVarInsn(Opcodes.ALOAD, slot);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, wrapMethod, wrapDesc, false);
            mv.visitVarInsn(Opcodes.ASTORE, slot);
        }
    }
}
