package peruser.cli;

/**
 * version 命令：输出版本与构建信息（官方 jacococli version 对应）。
 *
 * Usage: version [--quiet] [--help]
 */
public final class VersionCommand {

    private VersionCommand() {
    }

    public static void execute(String[] args) {
        if (CliArgs.has(args, "help")) {
            usage();
            return;
        }
        System.out.println("xiaoxiao-jacoco-cli");
        System.out.println("  version: " + CommandLine.VERSION);
        System.out.println("  compatible with: JaCoCo " + org.jacoco.core.JaCoCo.VERSION
                + " (commit " + org.jacoco.core.JaCoCo.COMMITID_SHORT + ", runtime package "
                + org.jacoco.core.JaCoCo.RUNTIMEPACKAGE + ")");
        System.out.println("  java: " + System.getProperty("java.version")
                + " (" + System.getProperty("java.vm.name") + ")");
        System.out.println("  commands: report merge dump instrument classinfo execinfo version help");
    }

    static void usage() {
        System.out.println("Usage: java -jar xiaoxiao-jacoco-cli.jar version [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --quiet   减少输出");
    }
}
