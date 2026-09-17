package peruser;

import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.runtime.RemoteControlWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * 官方 JaCoCo TCP 协议（org.jacoco.core.runtime.RemoteControlWriter）的【私有扩展块】，
 * 用来支持「按 key 单独 dump」。
 *
 * <p>设计原则：**不对官方协议做任何破坏性改动**。
 * <ul>
 *   <li>官方客户端（jacococli dump）连上来只会发 BLOCK_CMDDUMP(0x40)，本 agent 照样回「所有 key 的并集」，行为不变；</li>
 *   <li>xiaoxiao-jacoco-cli 显式指定 --key 时才发 0x41，只有本 agent 认识；</li>
 *   <li>响应结束仍是官方的 BLOCK_CMDOK(0x20)，官方客户端也能正常收尾。</li>
 * </ul>
 *
 * <p>新增块（仅 xiaoxiao-jacoco 两端使用）：
 * <pre>
 *   0x41 BLOCK_CMDKEYDUMP  客户端 -> 服务端：UTF key + boolean reset
 *   0x42 BLOCK_CMDKEYS     客户端 -> 服务端：无 payload，请求列出当前所有 key
 *   0x21 BLOCK_KEYS        服务端 -> 客户端：int count + count 个 UTF key
 *   0x43 BLOCK_CMDSTATS    客户端 -> 服务端：int limit
 *   0x22 BLOCK_STATS       服务端 -> 客户端：运行期概况
 *   0x44 BLOCK_CMDCLASSES  客户端 -> 服务端：无 payload，请求导出被插桩类的原始字节码
 *   0x23 BLOCK_CLASSES     服务端 -> 客户端：long zip 长度 + zip 字节（被插桩类原始字节）
 *   0x45 BLOCK_CMDSETKEY   客户端 -> 服务端：UTF key（空串=清除），设定全局当前 key
 * </pre>
 */
final class PerKeyProtocol {

    /** 客户端 -> 服务端：只 dump 某一个 key。 */
    static final byte BLOCK_CMDKEYDUMP = 0x41;

    /** 客户端 -> 服务端：请求列出当前所有 key。 */
    static final byte BLOCK_CMDKEYS = 0x42;

    /** 客户端 -> 服务端：请求运行期概况（插桩了哪些类、各 key 采到多少）。 */
    static final byte BLOCK_CMDSTATS = 0x43;

    /** 客户端 -> 服务端：请求导出被插桩类的原始字节码（dumpclasses 命令）。 */
    static final byte BLOCK_CMDCLASSES = 0x44;

    /**
     * 客户端 -> 服务端：设定【全局当前 key】（进程外驱动，零改业务代码）。
     * payload: UTF key —— 空串表示清除。
     */
    static final byte BLOCK_CMDSETKEY = 0x45;

    /** 服务端 -> 客户端：key 列表响应。 */
    static final byte BLOCK_KEYS = 0x21;

    /** 服务端 -> 客户端：运行期概况响应。 */
    static final byte BLOCK_STATS = 0x22;

    /** 服务端 -> 客户端：被插桩类原始字节码的 zip 包响应。 */
    static final byte BLOCK_CLASSES = 0x23;

    private PerKeyProtocol() {
    }

    /**
     * 服务端的命令写出器。{@code out} 是 {@link ExecutionDataWriter} 的 protected 字段，
     * 通过子类拿到以写私有块。
     */
    static final class ServerWriter extends RemoteControlWriter {

        ServerWriter(OutputStream out) throws IOException {
            super(out);
        }

        /**
         * 回一个 key 列表（供 cli keys 命令使用），并附带运行期自检计数
         * （插桩了多少类 / 钩子触发了多少次 / 成功归属了多少次 / 建议的 includes）。
         * 这些数字是「.exec 里为什么没数据」的唯一可靠判据。
         */
        void sendKeys(Set<String> keys) throws IOException {
            out.writeByte(BLOCK_KEYS);
            out.writeInt(keys.size());
            for (String k : keys) {
                out.writeUTF(k);
            }
            out.writeLong(Diagnostics.classesSeen());
            out.writeLong(Diagnostics.classesInstrumented());
            out.writeLong(Diagnostics.requestsHooked());
            out.writeLong(Diagnostics.requestsTagged());
            out.writeLong(Diagnostics.classesNoLocation());
            List<String> sug = Diagnostics.suggestedIncludes();
            out.writeInt(sug.size());
            for (String s : sug) {
                out.writeUTF(s);
            }
            out.flush();
        }

        /**
         * 回一份运行期概况（供 cli stats 命令使用）：插桩了哪些类、各 key 采到了多少。
         * limit<=0 表示返回全部（上限由 agent 侧 INSTRUMENTED_LIMIT 决定）。
         */
        void sendStats(int limit) throws IOException {
            out.writeByte(BLOCK_STATS);
            out.writeLong(Diagnostics.classesSeen());
            out.writeLong(Diagnostics.classesInstrumented());
            out.writeLong(Diagnostics.classesNoLocation());
            out.writeLong(Diagnostics.requestsHooked());
            out.writeLong(Diagnostics.requestsTagged());

            final List<String> instrumented = Diagnostics.instrumentedClasses(limit);
            out.writeInt(instrumented.size());
            for (String n : instrumented) {
                out.writeUTF(n);
            }

            final List<String> skipped = Diagnostics.skippedSamples();
            out.writeInt(skipped.size());
            for (String s : skipped) {
                out.writeUTF(s);
            }

            final List<String> sug = Diagnostics.suggestedIncludes();
            out.writeInt(sug.size());
            for (String s : sug) {
                out.writeUTF(s);
            }

            final java.util.List<ThreadProbeStore.KeyStat> ks = ThreadProbeStore.perKeyStats();
            out.writeInt(ks.size());
            for (ThreadProbeStore.KeyStat k : ks) {
                out.writeUTF(k.key);
                out.writeInt(k.classes);
                out.writeInt(k.probes);
                out.writeInt(k.covered);
            }
            out.flush();
        }

        /**
         * 回一份被插桩类的原始字节码 zip 包（供 cli dumpclasses 命令使用）。
         * 先写 1 字节块类型，再写 8 字节 zip 字节长度，再写原始 zip 字节。
         */
        void sendClasses() throws IOException {
            final byte[] zip = ClassCache.toZip();
            out.writeByte(BLOCK_CLASSES);
            out.writeLong(zip.length);
            out.write(zip, 0, zip.length);
            out.flush();
        }
    }
}
