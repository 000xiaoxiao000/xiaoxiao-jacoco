package peruser;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

/**
 * 用【目标类加载器】解析公共超类的 ClassWriter。
 *
 * ASM 在 COMPUTE_FRAMES 重算栈图时会调用 {@link #getCommonSuperClass}，默认实现用
 * agent 自己的类加载器去加载目标应用的类（Spring / MQ 客户端等），那些类不在 agent classpath
 * 上 -&gt; ClassNotFoundException -&gt; 整个织入崩溃。这里按 目标类加载器 -&gt; 线程上下文 -&gt; agent
 * 的顺序尝试，全部失败一律回退 java/lang/Object（宁可栈图保守，也不能让织入炸掉目标类）。
 */
final class LoaderAwareClassWriter extends ClassWriter {

    private final ClassLoader loader;

    LoaderAwareClassWriter(ClassReader cr, ClassLoader loader) {
        super(cr, COMPUTE_FRAMES | COMPUTE_MAXS);
        this.loader = loader;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) return type1;
        if (type1.equals("java/lang/Object") || type2.equals("java/lang/Object")) {
            return "java/lang/Object";
        }
        Class<?> c = tryLoad(type1);
        Class<?> d = tryLoad(type2);
        if (c == null || d == null) return "java/lang/Object";
        if (c.isAssignableFrom(d)) return type1;
        if (d.isAssignableFrom(c)) return type2;
        for (Class<?> sup = c; sup != null; sup = sup.getSuperclass()) {
            if (sup.isAssignableFrom(d)) return Type.getInternalName(sup);
        }
        return "java/lang/Object";
    }

    private Class<?> tryLoad(String internal) {
        String cn = internal.replace('/', '.');
        ClassLoader[] ls = {loader, Thread.currentThread().getContextClassLoader(), getClass().getClassLoader()};
        for (ClassLoader l : ls) {
            if (l == null) continue;
            try {
                return Class.forName(cn, false, l);
            } catch (Throwable ignore) {
                // 试下一个
            }
        }
        try {
            return Class.forName(cn);
        } catch (Throwable ignore) {
            return null;
        }
    }
}
