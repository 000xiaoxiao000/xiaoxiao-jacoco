package peruser;

import org.jacoco.core.internal.instr.IProbeArrayStrategy;
import org.jacoco.core.runtime.RuntimeData;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * 把探针数组存进 ThreadLocal（而非 JaCoCo 默认的静态字段）。
 *
 * JaCoCo 官方 Instrumenter 对 Java 11+ 类用 CondyProbeArrayStrategy —— 探针数组在类初始化时解析一次、
 * 存进静态字段 $jacocoData，被所有线程共享，因此并发覆盖互相污染、无法区分归属。
 *
 * 这里改成：每个方法入口（storeInstance 在 method entry 被调用）都去取【当前线程】的探针数组，存入局部变量；
 * 方法体内的探针 arr[i]=true 即落在当前线程的数组上。这样单实例并发时，A、B 的探针天然按线程隔离，
 * 由 CoverageTracer 在请求/用例结束时按 key 合并即可。
 */
public class ThreadLocalProbeArrayStrategy implements IProbeArrayStrategy {

    private final long classId;
    private final String name;
    private final int probeCount; // 该类真实探针数（预扫描得到），用于精确分配数组

    public ThreadLocalProbeArrayStrategy(long classId, String name, int probeCount) {
        this.classId = classId;
        this.name = name;
        this.probeCount = probeCount;
    }

    @Override
    public int storeInstance(MethodVisitor mv, boolean clinit, int variable) {
        // 构造 Object[] {classId, name, probeCount} 传给探针存储
        RuntimeData.generateArgumentArray(classId, name, probeCount, mv);
        // ThreadProbeStore.getProbes(Object[]) -> 当前线程的 boolean[] 探针数组
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "peruser/ThreadProbeStore", "getProbes", "([Ljava/lang/Object;)[Z", false);
        mv.visitTypeInsn(Opcodes.CHECKCAST, "[Z");
        // 存入局部变量 variable（ProbeInserter 后续用它做 arr[i]=true）
        mv.visitVarInsn(Opcodes.ASTORE, variable);
        return variable;
    }

    @Override
    public void addMembers(ClassVisitor cv, int probeCount) {
        // 无需静态成员：探针不进静态字段，全部走 ThreadLocal
    }
}
