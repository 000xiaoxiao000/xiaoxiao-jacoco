package peruser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 内嵌 HTTP 控制端点（不修改目标系统即可驱动覆盖率分离 + 实时 dump）。
 *
 * 端点（control=ADDR:PORT 启用）：
 *   GET /key?name=X            设定全局当前归属 key（外部驱动 A/B 分离）；不带 name 则返回当前 key
 *   GET /dump[?key=X][&reset=true]   写出 coverage-&lt;key&gt;.exec（合并写，重复 dump 累加不覆盖）
 *                              - 不带 key：写出【所有】 key；带 key=2：只写出 key=2（不影响其它 key）
 *                              - reset=true：写出后清空对应 key 的内存累计（等价于 jacococli dump --reset）
 *                                注意：因为采用合并写，即使 reset 清了内存，已落盘的 .exec 也不会丢失，
 *                                仍在线跑的 key 后续 dump 会把数据累加回来，多 key 并发互不干扰。
 *   GET /reset[?key=X]         清空累计探针；不带 key 清空全部，带 key=2 只清空 key=2（不影响其它 key）
 *   GET /keys                  列出当前所有 key（含 MERGE 模式说明）
 *   GET /health                健康检查
 *
 * 使用 JDK 自带的 com.sun.net.httpserver（Java 8 起即内置，无额外依赖）。
 */
public final class ControlServer {

    private ControlServer() {
    }

    public static void start(String addr, int port, String outDir) throws IOException {
        String host = (addr == null || addr.isEmpty()) ? "127.0.0.1" : addr;
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/key", new KeyHandler());
        server.createContext("/dump", new DumpHandler(outDir));
        server.createContext("/reset", new ResetHandler());
        server.createContext("/keys", new KeysHandler());
        server.createContext("/health", new HealthHandler());
        server.setExecutor(null); // 默认调度器（每个请求一个线程）
        server.start();
        System.out.println("[peruser] control server started: http://" + host + ":" + port
                + "  ( /key /dump /reset /keys /health )");
    }

    private abstract static class JsonHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String body = handleBody(ex);
                send(ex, 200, body);
            } catch (Exception e) {
                send(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        abstract String handleBody(HttpExchange ex) throws Exception;

        void send(HttpExchange ex, int code, String body) throws IOException {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        }
    }

    /** GET /key?name=X —— 设定全局当前归属 key。 */
    static final class KeyHandler extends JsonHandler {
        @Override
        String handleBody(HttpExchange ex) {
            String q = ex.getRequestURI().getQuery();
            String name = null;
            if (q != null) {
                for (String p : q.split("&")) {
                    if (p.startsWith("name=")) {
                        name = p.substring(5);
                        break;
                    }
                }
            }
            if (name != null && !name.isEmpty()) {
                ThreadProbeStore.setCurrentKey(name);
            }
            String cur = ThreadProbeStore.getCurrentKey();
            return "{\"key\":" + (cur == null ? "null" : "\"" + cur + "\"") + "}";
        }
    }

    /** GET /dump[?key=X][&reset=true] —— 写出所有 key（或指定 key）的 .exec，可选清空累计。 */
    static final class DumpHandler extends JsonHandler {
        private final String outDir;

        DumpHandler(String outDir) {
            this.outDir = outDir;
        }

        @Override
        String handleBody(HttpExchange ex) throws IOException {
            String q = ex.getRequestURI().getQuery();
            boolean reset = q != null && (q.contains("reset=true") || q.contains("reset=1"));
            String key = null;
            if (q != null) {
                for (String p : q.split("&")) {
                    if (p.startsWith("key=")) {
                        key = p.substring(4);
                        break;
                    }
                }
            }
            List<java.io.File> files;
            if (key != null && !key.isEmpty()) {
                files = CoverageStore.dumpKey(key, new java.io.File(outDir), reset);
            } else {
                files = CoverageStore.dumpAll(new java.io.File(outDir), reset);
            }
            StringBuilder sb = new StringBuilder("{\"files\":[");
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(files.get(i).getName()).append('"');
            }
            sb.append("],\"reset\":").append(reset)
              .append(",\"key\":").append(key == null ? "null" : "\"" + key + "\"")
              .append(",\"count\":").append(files.size()).append("}");
            return sb.toString();
        }
    }

    /** GET /reset[?key=X] —— 清空累计探针；不带 key 清空全部，带 key 只清空该 key（不影响其它 key）。 */
    static final class ResetHandler extends JsonHandler {
        @Override
        String handleBody(HttpExchange ex) {
            String q = ex.getRequestURI().getQuery();
            String key = null;
            if (q != null) {
                for (String p : q.split("&")) {
                    if (p.startsWith("key=")) {
                        key = p.substring(4);
                        break;
                    }
                }
            }
            boolean all = (key == null || key.isEmpty());
            if (ThreadProbeStore.isMerge()) {
                ThreadProbeStore.resetMerge();
            } else if (all) {
                ThreadProbeStore.resetAll();
            } else {
                ThreadProbeStore.resetKey(key);
            }
            return "{\"reset\":\"" + (all ? "all" : key) + "\"}";
        }
    }

    /** GET /keys —— 列出当前所有 key。 */
    static final class KeysHandler extends JsonHandler {
        @Override
        String handleBody(HttpExchange ex) {
            StringBuilder sb = new StringBuilder("{\"mode\":\"");
            if (ThreadProbeStore.isMerge()) {
                sb.append("merge\",\"mergeKey\":\"").append(ThreadProbeStore.mergeKey()).append("\"");
                sb.append(",\"currentKey\":").append(jsonKey(ThreadProbeStore.getCurrentKey()));
            } else {
                sb.append("perKey\"");
                sb.append(",\"currentKey\":").append(jsonKey(ThreadProbeStore.getCurrentKey()));
                Set<String> ks = ThreadProbeStore.keys();
                sb.append(",\"keys\":[");
                int i = 0;
                for (String k : ks) {
                    if (i++ > 0) sb.append(',');
                    sb.append('"').append(k).append('"');
                }
                sb.append("]");
            }
            sb.append("}");
            return sb.toString();
        }

        private static String jsonKey(String k) {
            return k == null ? "null" : "\"" + k + "\"";
        }
    }

    static final class HealthHandler extends JsonHandler {
        @Override
        String handleBody(HttpExchange ex) {
            return "{\"status\":\"ok\"}";
        }
    }
}
