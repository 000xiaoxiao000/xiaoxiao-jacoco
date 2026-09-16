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
            final ObjectName name = new ObjectName("org.jacoco:type=Runtime");
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
