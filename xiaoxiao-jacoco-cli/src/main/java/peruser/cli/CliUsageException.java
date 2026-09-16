package peruser.cli;

/**
 * 用法错误：打印命令用法并以退出码 2 结束（与官方 jacococli 的行为一致）。
 */
public final class CliUsageException extends Exception {

    public CliUsageException(String msg) {
        super(msg);
    }
}
