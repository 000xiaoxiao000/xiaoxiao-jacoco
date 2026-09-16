package peruser.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * cli 命令行参数解析（与官方 jacococli 风格一致：--key value，支持同一 key 重复出现，也支持 --key=value）。
 */
final class CliArgs {

    /** 需要「吃掉」下一个 token 作为取值的 option。 */
    private static final Set<String> VALUE_OPTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "classfiles", "sourcefiles",
            "html", "xml", "csv", "encoding", "name", "tabwidth",
            "execdir", "baseline", "baseline-out",
            "destfile", "dest", "address", "port", "retry", "append", "key")));

    private CliArgs() {
    }

    /** 所有位置参数（非 option token，也不吃下一个 token）。 */
    static List<String> positional(String[] args) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null || a.startsWith("--")) {
                String key = keyOf(a);
                // 只跳过「确实绑定到下一个 token 的取值」，避免把不带值的开关后的值误吞
                if (VALUE_OPTS.contains(key) && keyOf(a) != null && !a.contains("=") && i + 1 < args.length) {
                    i++;
                }
                continue;
            }
            list.add(a);
        }
        return list;
    }

    /** 取某个 option 的所有取值（重复传参时会有多个），支持 --key value 与 --key=value。 */
    static List<String> values(String[] args, String key) {
        List<String> list = new ArrayList<>();
        String opt = "--" + key;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) continue;
            if (a.equals(opt) && i + 1 < args.length) {
                list.add(args[i + 1]);
                i++;
            } else if (a.startsWith(opt + "=")) {
                list.add(a.substring(opt.length() + 1));
            }
        }
        return list;
    }

    /** 取某个 option 的单个取值（取第一个，无则返回 def）。 */
    static String value(String[] args, String key, String def) {
        List<String> v = values(args, key);
        return v.isEmpty() ? def : v.get(0);
    }

    /** 是否带了某个开关型 option（--flag 或 --flag=true）。 */
    static boolean has(String[] args, String... keys) {
        for (String a : args) {
            if (a == null) continue;
            for (String k : keys) {
                String opt = "--" + k;
                if (a.equals(opt) || a.startsWith(opt + "=")) return true;
            }
        }
        return false;
    }

    /** 收集路径列表：支持重复传参，也支持单参数里用 ';' 分隔。 */
    static List<String> pathList(String[] args, String... keys) {
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            for (String v : values(args, k)) {
                for (String part : v.split(";")) {
                    String p = part.trim();
                    if (!p.isEmpty()) out.add(p);
                }
            }
        }
        return out;
    }

    /** 收集「不在已知列表里」的 option（用于提示拼错 / 该命令不支持的参数，避免被静默忽略）。 */
    static java.util.List<String> unknownOptions(String[] args, String... known) {
        final java.util.Set<String> ok = new java.util.HashSet<>(java.util.Arrays.asList(known));
        final java.util.List<String> bad = new java.util.ArrayList<>();
        for (String a : args) {
            if (a == null || !a.startsWith("--")) continue;
            String k = keyOf(a);
            if (k != null && !k.isEmpty() && !ok.contains(k)) bad.add("--" + k);
        }
        return bad;
    }

    /** 打印未知 option 告警（有则逐条打印，返回是否有）。 */
    static boolean warnUnknown(String[] args, String... known) {
        final java.util.List<String> bad = unknownOptions(args, known);
        for (String b : bad) {
            System.err.println("[xiaoxiao-jacoco-cli] warning: 忽略未知选项 " + b
                    + "（该命令不支持，拼错或放错命令了？用 help 查看用法）");
        }
        return !bad.isEmpty();
    }

    private static String keyOf(String token) {
        if (token == null || !token.startsWith("--")) return null;
        String k = token.substring(2);
        int eq = k.indexOf('=');
        return eq >= 0 ? k.substring(0, eq) : k;
    }
}
