package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * parallelStream 的 key 归属织入（零改目标系统）。
 *
 * <p>问题：{@code stream.parallel().forEach(...)} 的任务不走线程池的提交入口
 * （{@code Executor.execute/submit}），而是直接 {@code ForkJoinTask.fork()} 进 commonPool，
 * 再由池里的 worker 线程 {@code doExec()} 执行。提交线程的 key 无法随任务过去，
 * 覆盖率要么丢失、要么记到别的 key 上。此前只能让业务代码在流里显式
 * {@code CoverageTracer.start(key)} —— 那是让【目标系统适应探针】，本项目不接受。
 *
 * <p>方案：织入 JDK 的 {@code java.util.concurrent.ForkJoinTask} 两个方法，探针自己完成传递：
 * <pre>
 *   fork()   （提交线程执行）:  KeyBridge.markFork(this);      // 有 key 才挂，无 key 直接返回
 *   doExec() （工作线程执行）:  KeyBridge.forkEnter(this);     // 入口：取出 key 设进当前线程
 *                              &lt;原方法体&gt;
 *                              KeyBridge.forkExit(this);      // 所有出口：回退并清表
 * </pre>
 *
 * <p><b>默认关闭</b>（{@code streamkey=true} 才启用）：ForkJoinTask 是并行计算的热路径，
 * 探针不应当在用户没要求时就往里面插指令 —— 这是「探针不能影响目标系统」的底线。
 * 开启后也只在【当前线程有 key】时才写暂存表，没有 key 的应用线程开销为零。
 *
 * <p>只织 {@code fork()} 与 {@code doExec()} 两个点，不织 {@code ForkJoinPool} 内部与
 * {@code ForkJoinWorkerThread}，避免触碰并行框架的核心调度逻辑。
 */
public final class StreamKeyWeaver {

    /** ForkJoinTask 由 bootstrap 加载，只能调用 bootstrap 可见的 peruserrt.KeyBridge。 */
    private static final String BRIDGE = "peruserrt/KeyBridge";
    private static final String TARGET = "java/util/concurrent/ForkJoinTask";

    /** fork() 的 VM 签名：public final ForkJoinTask<V> fork() */
    private static final String FORK_NAME = "fork";
    private static final String FORK_DESC = "()Ljava/util/concurrent/ForkJoinTask;";

    /** doExec() 的 VM 签名：final int doExec() —— 所有 ForkJoinTask 子类最终都经它执行 */
    private static final String EXEC_NAME = "doExec";
    private static final String EXEC_DESC = "()I";

    private StreamKeyWeaver() {
    }

    public static boolean isTarget(String className) {
        return TARGET.equals(className);
    }

    public static java.util.Collection<String> targetClassNames() {
        return java.util.Collections.singletonList(TARGET.replace('/', '.'));
    }

    public static byte[] weave(byte[] buf) {
        final ClassReader cr = new ClassReader(buf);
        // JDK 类带 stack map frame，只重算 maxs，不用 COMPUTE_FRAMES（会触发类加载）
        final ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                            String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (mv == null) {
                    return null;
                }
                if (FORK_NAME.equals(name) && FORK_DESC.equals(descriptor)) {
                    return new ForkMethodAdapter(mv);
                }
                if (EXEC_NAME.equals(name) && EXEC_DESC.equals(descriptor)) {
                    return new ExecMethodAdapter(mv);
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /** 载入 this 并调用 bridge 的 void(Object) 静态方法。 */
    private static void callBridge(MethodVisitor mv, String method) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, method, "(Ljava/lang/Object;)V", false);
    }

    /** fork()：方法体开头 markFork(this)。 */
    private static final class ForkMethodAdapter extends MethodVisitor {
        ForkMethodAdapter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            mv.visitCode();
            callBridge(mv, "markFork");
        }
    }

    /**
     * doExec()：开头 forkEnter(this)，所有出口（各 return 与 athrow）前 forkExit(this)。
     * 不用 try/catch 包体，因此不改变 stack map frame，织入风险最小。
     */
    private static final class ExecMethodAdapter extends MethodVisitor {
        ExecMethodAdapter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            mv.visitCode();
            callBridge(mv, "forkEnter");
        }

        @Override
        public void visitInsn(int opcode) {
            boolean isReturn = (opcode >= Opcodes.IRETURN && opcode <= Opcodes.ARETURN)
                    || opcode == Opcodes.RETURN
                    || opcode == Opcodes.ATHROW;
            if (isReturn) {
                callBridge(mv, "forkExit");
            }
            mv.visitInsn(opcode);
        }
    }
}
