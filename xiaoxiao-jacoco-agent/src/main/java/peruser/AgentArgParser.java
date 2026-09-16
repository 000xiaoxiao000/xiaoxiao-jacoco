package peruser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent 参数串（key=value,key=value...）分词器：支持双引号包裹的值（值内含 ',' / '=' 也安全），
 * 与 JaCoCo agent 官方的 CommandLineSupport 解析规则一致。
 *
 * 本类只负责「把参数串拆成 key -> value 的映射」，官方参数的默认值处理交给
 * org.jacoco.core.runtime.AgentOptions，peruser 扩展参数由 Options 读取。
 */
final class AgentArgParser {

    private AgentArgParser() {
    }

    static Map<String, String> parse(String args) {
        Map<String, String> map = new LinkedHashMap<>();
        if (args == null) return map;
        List<String> pairs = split(args, ',');
        for (String pair : pairs) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            if (i < 0) {
                map.put(pair.trim(), "");
            } else {
                map.put(pair.substring(0, i).trim(), unquote(pair.substring(i + 1).trim()));
            }
        }
        return map;
    }

    /** 按 ch 分割字符串，跳过双引号内部的 ch。 */
    private static List<String> split(String s, char ch) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                quote = !quote;
                token.append(c);
            } else if (c == ch && !quote) {
                result.add(token.toString());
                token.setLength(0);
            } else {
                token.append(c);
            }
        }
        result.add(token.toString());
        return result;
    }

    /** 去掉包裹的双引号（若成对存在）。 */
    private static String unquote(String v) {
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
}
