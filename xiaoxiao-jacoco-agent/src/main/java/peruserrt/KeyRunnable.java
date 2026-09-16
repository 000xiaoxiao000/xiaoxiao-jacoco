package peruserrt;

/** 携带 key 的 Runnable 包装：执行期间设置 key，结束后回退到进入前的值（线程池复用安全）。 */
public final class KeyRunnable implements Runnable {

    private final Runnable delegate;
    private final String key;

    public KeyRunnable(Runnable delegate, String key) {
        this.delegate = delegate;
        this.key = key;
    }

    @Override
    public void run() {
        final String prev = KeyBridge.get();
        KeyBridge.set(key);
        try {
            delegate.run();
        } finally {
            KeyBridge.set(prev);
        }
    }
}
