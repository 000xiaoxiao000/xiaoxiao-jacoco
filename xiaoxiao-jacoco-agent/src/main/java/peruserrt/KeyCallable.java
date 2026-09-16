package peruserrt;

import java.util.concurrent.Callable;

/** 携带 key 的 Callable 包装：执行期间设置 key，结束后回退到进入前的值（线程池复用安全）。 */
public final class KeyCallable implements Callable<Object> {

    private final Callable<Object> delegate;
    private final String key;

    public KeyCallable(Callable<Object> delegate, String key) {
        this.delegate = delegate;
        this.key = key;
    }

    @Override
    public Object call() throws Exception {
        final String prev = KeyBridge.get();
        KeyBridge.set(key);
        try {
            return delegate.call();
        } finally {
            KeyBridge.set(prev);
        }
    }
}
