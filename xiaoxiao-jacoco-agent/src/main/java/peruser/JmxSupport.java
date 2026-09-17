package peruser;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * jmx=true 时把 {@link JacocoRuntime} 注册到平台 MBeanServer，
 * 对象名与官方 JaCoCo 一致：org.jacoco:type=Runtime。
 */
final class JmxSupport {

    private JmxSupport() {
    }

    static void register(Options options, IAgentOutput output) {
        try {
            final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            // 用探针自己的 MBean 名，不占用官方 JaCoCo 的 org.jacoco:type=Runtime：
            // 目标系统若已挂了官方 jacoco agent，同名注册会（或覆盖）影响它 —— 那是探针在干扰目标系统。
            // 注意别写成以 org.jacoco 开头的字面量：pom 里有 org.jacoco 的 shade 重定位，
            // 会把常量池里的这类字符串一起改掉。
            final ObjectName name = new ObjectName("com.xiaoxiao.jacoco:type=Runtime");
            if (server.isRegistered(name)) {
                server.unregisterMBean(name);
            }
            server.registerMBean(new JacocoRuntime(options, output), name);
            System.out.println("[xiaoxiao-jacoco] JMX registered: " + name);
        } catch (Exception e) {
            System.err.println("[xiaoxiao-jacoco] JMX registration failed: " + e);
        }
    }
}
