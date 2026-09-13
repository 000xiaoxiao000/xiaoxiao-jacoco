package peruser;

/**
 * 请求级 key 钩子，由 {@link RequestKeyWeaver} 注入到 Servlet 统一入口
 * （javax/jakarta.servlet.http.HttpServlet.service）的入口/出口调用，框架无关。
 *
 * 设计约束（避免引入对 javax.servlet / jakarta.servlet 的依赖、也不占方法局部变量）：
 *   - tag 接收 Object 类型的 request，用反射读 getHeader(NAME)，零编译期依赖；
 *     同时兼容 javax.servlet.http.HttpServletRequest 与 jakarta.servlet.http.HttpServletRequest
 *     （两者都有 getHeader(String) 方法）；
 *   - 本次请求是否打标记在 ThreadLocal 里，untag 据此决定是否调用 ThreadProbeStore.end()。
 *
 * 这样 A、B 并发请求分别带 X-Coverage-Key:1 / :2 时，各自线程的 KEY 互不干扰，
 * 配合 ThreadProbeStore 的线程级 key 路径（getProbes path 1）即可并发按用户分离覆盖率。
 */
public final class RequestKeyHook {

    private static final ThreadLocal<Boolean> TAGGED = new ThreadLocal<>();

    private RequestKeyHook() {
    }

    /**
     * 在请求入口调用：读请求头，非空则把本次请求归属到该 key（线程级）。
     *
     * @param request    真实的 HttpServletRequest（或任意实现了 getHeader(String) 的对象）
     * @param headerName 请求头名，如 X-Coverage-Key
     */
    public static void tag(Object request, String headerName) {
        String key = null;
        if (request != null && headerName != null && !headerName.isEmpty()) {
            try {
                Object v = request.getClass()
                        .getMethod("getHeader", String.class)
                        .invoke(request, headerName);
                key = (v instanceof String) ? (String) v : null;
            } catch (Throwable ignore) {
                // 读不到头就当作无 key，回退到全局 CURRENT_KEY
            }
        }
        if (key != null && !key.isEmpty()) {
            ThreadProbeStore.begin(key);
            TAGGED.set(Boolean.TRUE);
        } else {
            TAGGED.set(Boolean.FALSE);
        }
    }

    /** 在请求出口（finally）调用：若本次打过标，提交该 key 的探针累计。 */
    public static void untag() {
        if (Boolean.TRUE.equals(TAGGED.get())) {
            ThreadProbeStore.end();
        }
        TAGGED.remove();
    }
}
