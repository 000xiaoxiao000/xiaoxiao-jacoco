package peruser;

import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.internal.flow.ClassProbesAdapter;
import org.jacoco.core.internal.flow.ClassProbesVisitor;
import org.jacoco.core.internal.flow.MethodProbesVisitor;
import org.jacoco.core.internal.instr.ClassInstrumenter;
import org.jacoco.core.internal.instr.IProbeArrayStrategy;
import org.jacoco.core.internal.instr.InstrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;

/**
 * 复刻 JaCoCo Instrumenter.instrument 的内部流程，但把探针策略换成 ThreadLocal 版。
 *
 * 之所以不用 org.jacoco.core.instr.Instrumenter：它内部强制用静态字段探针策略
 * （CondyProbeArrayStrategy / ClassFieldProbeArrayStrategy），并发覆盖会合并到全局数组，无法按用户分离。
 * 这里直接走 ClassProbesAdapter + ClassInstrumenter(IProbeArrayStrategy) 的内部链路。
 */
public final class InstrumenterFlow {

    private InstrumenterFlow() {
    }

    /**
     * 把一段原始类字节插桩为 ThreadLocal 探针版。
     *
     * @param buffer 原始（未插桩）类字节
     * @param vmName 类的 VM 内部名，形如 "sample/shop/CartService"
     * @return 插桩后的字节
     */
    public static byte[] instrument(byte[] buffer, String vmName) throws IOException {
        final long classId = CRC64.classId(buffer);

        // ---- 预扫描：拿到该类真实探针数（JaCoCo 自带 ProbeCounter 是包私有，这里自己实现一个）----
        final ClassReader pre = new ClassReader(buffer);
        final ProbeCounter counter = new ProbeCounter();
        final ClassProbesAdapter preAdapter = new ClassProbesAdapter(
                counter, InstrSupport.needsFrames(InstrSupport.getMajorVersion(pre)));
        pre.accept(preAdapter, ClassReader.EXPAND_FRAMES);
        final int probeCount = counter.count;

        // ---- 真正插桩 ----
        final ClassReader reader = new ClassReader(buffer);
        // EXPAND_FRAMES 由 reader 端展开栈映射，writer 不重算帧；COMPUTE_MAXS 让 writer 自动重算
        // 操作数栈上限，否则 RuntimeData.generateArgumentArray 注入的代码会导致 max stack 溢出（VerifyError）。
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final IProbeArrayStrategy strategy =
                new ThreadLocalProbeArrayStrategy(classId, vmName, probeCount);
        final ClassInstrumenter instrumenter = new ClassInstrumenter(strategy, writer);
        final ClassProbesAdapter adapter = new ClassProbesAdapter(
                instrumenter, InstrSupport.needsFrames(InstrSupport.getMajorVersion(reader)));
        reader.accept(adapter, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    /** 预扫描探针计数：继承 JaCoCo 公开的 ClassProbesVisitor，visitMethod 返回空实现即可。 */
    static final class ProbeCounter extends ClassProbesVisitor {
        int count = 0;

        @Override
        public MethodProbesVisitor visitMethod(int access, String name, String descriptor,
                                               String signature, String[] exceptions) {
            return new MethodProbesVisitor() {
            };
        }

        @Override
        public void visitTotalProbeCount(int probeCount) {
            this.count = probeCount;
        }
    }
}
