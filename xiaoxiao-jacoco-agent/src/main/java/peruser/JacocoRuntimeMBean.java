package peruser;

/**
 * JMX 管理接口（jmx=true 时注册），对象名与官方一致：org.jacoco:type=Runtime。
 *
 * 通过它可以不重启目标系统、也不需要任何 HTTP 端点，就能在运行时：
 *   - 查看 / 修改 sessionId
 *   - 触发一次 dump（可选清空内存累计）
 *   - 清空累计探针
 */
public interface JacocoRuntimeMBean {

    /** 版本信息。 */
    String getVersion();

    /** 当前 session 标识。 */
    String getSessionId();

    /** 修改 session 标识。 */
    void setSessionId(String id);

    /**
     * 立即写出一次覆盖率。
     *
     * @param reset 写出后是否清空内存累计
     * @return 执行结果说明
     */
    String dump(boolean reset);

    /** 清空内存累计探针（不影响已落盘的 exec）。 */
    void reset();
}
