package peruser;

import java.lang.reflect.Method;

/**
 * 反射小工具：探针要读写 feign / okhttp / apache / dubbo / grpc / spring 的载体对象，
 * 但【不能】在编译期依赖任何一个（目标系统可能一个都没用），所以全部走反射。
 *
 * <p>设计约束：
 * <ul>
 *   <li>方法不存在一律返回 null / false —— 由调用方决定降级，绝不抛给业务；</li>
 *   <li>沿类层次 + 接口层次查找，实现类常常是非 public 的，找到后 setAccessible；</li>
 *   <li>不做任何缓存（织入点的方法调用频率是「每个请求几次」级别，不值得为此持有强引用）。</li>
 * </ul>
 */
final class Reflect {

    private Reflect() {
    }

    /**
     * 沿类层次（含接口）查找方法。找不到返回 null。
     * 实现类可能是包可见 / 非 public 的，所以逐个 getDeclaredMethod 并 setAccessible。
     */
    static Method findMethod(Class<?> c, String name, Class<?>... ptypes) {
        if (c == null) {
            return null;
        }
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Method m = k.getDeclaredMethod(name, ptypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) {
                // 继续往上找
            } catch (Throwable ignore) {
                return null;
            }
        }
        for (Class<?> itf : allInterfaces(c)) {
            try {
                Method m = itf.getMethod(name, ptypes);
                m.setAccessible(true);
                return m;
            } catch (Throwable ignore) {
                // 下一个接口
            }
        }
        return null;
    }

    /** 无参调用：方法不存在返回 null。 */
    static Object invoke(Object target, String name) {
        if (target == null) {
            return null;
        }
        try {
            Method m = findMethod(target.getClass(), name);
            return (m == null) ? null : m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 单 String 参调用：方法不存在或调用失败返回 null。 */
    static Object invokeStr(Object target, String name, String arg) {
        if (target == null) {
            return null;
        }
        try {
            Method m = findMethod(target.getClass(), name, String.class);
            return (m == null) ? null : m.invoke(target, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 类名精确匹配（不能用 instanceof：本类不编译期依赖任何三方客户端）。 */
    static boolean isClass(Object o, String... names) {
        if (o == null) {
            return false;
        }
        final String n = o.getClass().getName();
        for (String name : names) {
            if (name.equals(n)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.Set<Class<?>> allInterfaces(Class<?> c) {
        java.util.Set<Class<?>> out = new java.util.LinkedHashSet<Class<?>>();
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            collect(k.getInterfaces(), out);
        }
        return out;
    }

    private static void collect(Class<?>[] ifs, java.util.Set<Class<?>> out) {
        if (ifs == null) {
            return;
        }
        for (Class<?> i : ifs) {
            if (i != null && out.add(i)) {
                collect(i.getInterfaces(), out);
            }
        }
    }
}
