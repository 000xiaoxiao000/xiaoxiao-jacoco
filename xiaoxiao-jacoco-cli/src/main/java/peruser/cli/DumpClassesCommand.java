package peruser.cli;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * dumpclasses 命令：从 agent 内存缓存里一次性拉回【被插桩类的原始字节码】，解压到本地目录，
 * 直接当覆盖率报告的 classfiles 分母。
 *
 * <pre>
 *   dumpclasses [--address &lt;address&gt;] [--port &lt;port&gt;] [--outdir &lt;dir&gt;] [--zip &lt;file&gt;] [--retry &lt;count&gt;]
 * </pre>
 *
 * <p>解决的核心痛点：docker / k8s 等环境<b>禁止 scp、不给被测机密码</b>，classdumpdir 落盘在容器里本地也读不到、
 * 构建产物 jar 也在镜像仓库里不好拿。本命令走 agent 已有的 tcpserver 通道（与 dump/keys/stats 同源），
 * 把内存缓存的原始 class 打成 zip 流回本地，彻底免容器访问、免镜像访问。
 *
 * <p>输出的目录结构就是 {@code com/foo/Bar.class}，可直接喂
 * {@code report --classfiles <outdir>}（AnalyzePaths 对目录递归扫描 .class）。
 *
 * <p>这是 xiaoxiao-jacoco 的专有扩展（私有块 0x44/0x23）。官方 agent 不认识该块，会退化成一次普通 dump，
 * 此时本命令会提示「agent 不支持 dumpclasses，请升级 xiaoxiao-jacoco-agent」。
 */
public final class DumpClassesCommand {

    private DumpClassesCommand() {
    }

    public static void execute(String[] args) throws Exception {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        final String address = CliArgs.value(args, "address", "127.0.0.1");
        final int port = Integer.parseInt(CliArgs.value(args, "port", "6300"));
        final String outdir = CliArgs.value(args, "outdir", "classes");
        final String zipFile = CliArgs.value(args, "zip", "");
        final int retry = Integer.parseInt(CliArgs.value(args, "retry", "0"));
        final boolean quiet = CliArgs.has(args, "quiet");
        CliArgs.warnUnknown(args, "address", "port", "outdir", "zip", "retry", "quiet", "help");

        final byte[] zip = RemoteDump.fetchClasses(address, port, retry, 1000L);

        // 可选：同时保存一份原始 zip（便于归档 / 复用）
        if (!zipFile.isEmpty()) {
            final File zf = new File(zipFile);
            final File parent = zf.getParentFile();
            if (parent != null) parent.mkdirs();
            try (FileOutputStream fos = new FileOutputStream(zf)) {
                fos.write(zip);
            }
            if (!quiet) {
                System.out.println("[xiaoxiao-jacoco-cli] saved raw zip: " + zf.getAbsolutePath()
                        + " (" + (zip.length / 1024) + " KB)");
            }
        }

        final int count = extract(zip, new File(outdir));
        System.out.println("[xiaoxiao-jacoco-cli] dumped " + count + " classes from " + address + ":" + port
                + " -> " + new File(outdir).getAbsolutePath());
        System.out.println("[xiaoxiao-jacoco-cli] 接下来生成报告（classfiles 指向解压目录）：");
        System.out.println("  java -jar xiaoxiao-jacoco-cli.jar report <exec> --classfiles " + outdir
                + " --html reports");
        System.out.println("  # 多个 key：report --execdir coverage --classfiles " + outdir + " --html reports");
    }

    /** 把 zip 解压到 outdir（保留 com/foo/Bar.class 目录结构），带 zip-slip 防护。 */
    private static int extract(byte[] zip, File outdir) throws IOException {
        outdir.mkdirs();
        final String base = outdir.getCanonicalPath() + File.separator;
        int n = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                final File f = new File(outdir, e.getName());
                final String canonical = f.getCanonicalPath();
                if (!canonical.startsWith(base)) {
                    throw new IOException("illegal zip entry (zip slip): " + e.getName());
                }
                final File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    final byte[] buf = new byte[8192];
                    int r;
                    while ((r = zis.read(buf)) != -1) {
                        fos.write(buf, 0, r);
                    }
                }
                n++;
            }
        }
        return n;
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar dumpclasses [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --address <address>   agent tcpserver 地址，默认 127.0.0.1");
        System.out.println("  --port <port>         agent tcpserver 端口，默认 6300");
        System.out.println("  --outdir <dir>        解压目录，默认 classes（直接当 report 的 --classfiles）");
        System.out.println("  --zip <file>          可选：同时保存一份原始 zip（便于归档/复用）");
        System.out.println("  --retry <count>       连接失败重试次数，默认 0");
        System.out.println("  --quiet               减少输出");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  dumpclasses --address 172.16.11.13 --port 6300");
        System.out.println("  dumpclasses --address 172.16.11.13 --port 6300 --outdir libs --zip classes.zip");
        System.out.println("  # 然后：report coverage/coverage-1.exec --classfiles libs --html reports");
    }
}
