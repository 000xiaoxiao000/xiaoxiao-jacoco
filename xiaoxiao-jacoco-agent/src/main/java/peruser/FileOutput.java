package peruser;

import java.io.File;
import java.util.List;

/**
 * output=file（默认）：把每个 key 的覆盖率写成 &lt;outdir&gt;/&lt;prefix&gt;-&lt;key&gt;.exec。
 *
 * - append=true（默认）：按 classId OR 合并写，多次 dump 累加不覆盖；
 * - append=false       ：覆盖写。
 */
final class FileOutput implements IAgentOutput {

    private final Options options;

    FileOutput(Options options) {
        this.options = options;
    }

    @Override
    public void startup() {
        // file 模式无需启动任何服务，首次写文件时自动 mkdirs
    }

    @Override
    public void writeExecutionData(boolean reset) throws Exception {
        List<File> files = CoverageStore.dumpAll(options, reset);
        for (File f : files) {
            System.out.println("[xiaoxiao-jacoco] wrote " + f.getAbsolutePath());
        }
        if (files.isEmpty()) {
            System.out.println("[xiaoxiao-jacoco] no coverage data to write (no key collected any probe)");
        }
    }

    @Override
    public void shutdown() {
        // no-op
    }
}
