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

    /**
     * 「不吃取值」的纯开关 option。
     *
     * 判定顺序改为反向白名单：除了这些开关（以及下面 OPTIONAL_VALUE_OPTS 的特例），
     * 其它所有 --xxx 都默认吃掉下一个 token 作为取值。这样新增带值的 option 不需要来这里登记，
     * 否则它的取值会被当成位置参数，报出一堆莫名其妙的错误（典型的："xxx (Is a directory)"）。
     */
    private static final Set<String> BOOLEAN_OPTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "help", "quiet", "reset", "clear", "merge", "verbose", "debug", "force")));

    /** 「可带可不带取值」的 option：--flag 或 --flag <value> 都合法。 */
    private static final Set<String> OPTIONAL_VALUE_OPTS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("perkey")));

    private CliArgs() {
    }

    /** 所有位置参数（非 option token，也不吃下一个 token）。 */
    static List<String> positional(String[] args) {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null || a.startsWith("--")) {
                String key = keyOf(a);
                if (key != null && !a.contains("=") && i + 1 < args.length) {
                    if (BOOLEAN_OPTS.contains(key)) {
                        // 纯开关，不吃取值
                    } else if (OPTIONAL_VALUE_OPTS.contains(key) && args[i + 1] != null
                            && !args[i + 1].startsWith("--")) {
                        // perkey 带值：只有下一个 token 不是 option 时才算它的取值
                        i++;
                    } else {
                        // 其余一律默认「吃取值」
                        i++;
                    }
                }
                continue;
            }
            list.add(a);
        }
        return list;
    }

    /**
     * 「可带可不带取值」的 option 取值：
     *   --perkey 1   -> "1"（下一个 token 不是 option 才当取值）
     *   --perkey=1   -> "1"
     *   --perkey     -> ""（仅开关，不带值）
     *   未出现        -> null
     */
    static String flagOrValue(String[] args, String key) {
        String opt = "--" + key;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) continue;
            if (a.equals(opt)) {
                if (i + 1 < args.length && args[i + 1] != null && !args[i + 1].startsWith("--")) {
                    return args[i + 1];
                }
                return "";
            }
            if (a.startsWith(opt + "=")) {
                return a.substring(opt.length() + 1);
            }
        }
        return null;
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
