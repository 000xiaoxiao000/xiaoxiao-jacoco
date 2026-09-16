package peruser;

/**
 * agent 的输出方式抽象（对应官方 output=file|tcpserver|tcpclient|none）。
 *
 * 与官方保持一致的行为：
 *   - file      ：JVM 关闭（dumponexit=true）或 JMX 触发时把覆盖率写入 exec 文件；
 *   - tcpserver ：启动一个 TCP 服务等待 jacococli / xiaoxiao-jacoco-cli 的 dump 请求；
 *   - tcpclient ：启动时连上采集端，退出时把数据推给它；
 *   - none      ：不产出数据（探针仍在内存里累计）。
 */
interface IAgentOutput {

    /** 启动输出相关服务（如监听端口 / 建立连接）。 */
    void startup() throws Exception;

    /**
     * 写出当前覆盖率。
     *
     * @param reset 写出后是否清空各 key 的内存累计（jacococli dump --reset 语义）
     */
    void writeExecutionData(boolean reset) throws Exception;

    /** 停止输出相关服务。 */
    void shutdown() throws Exception;
}
