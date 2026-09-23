package peruser;

/**
 * 「当前线程真正生效的归属 key」的唯一判定口径。
 *
 * <p>MQ 透传（{@link MqKeyHook}）与 HTTP 透传（{@link HttpKeyHook}）都必须用同一份口径，
 * 否则会出现「覆盖归属到 A key、却把 B key 透传出去」的错位。
 *
 * <p>优先级与 {@link ThreadProbeStore#getProbes(Object[])} 完全一致：
 * <pre>
 *   线程 key（含异步/parallelStream 传递） -&gt; 外部控制端点设的全局 key -&gt; autokey 模式的合并 key
 * </pre>
 */
public final class EffectiveKey {

    private EffectiveKey() {
    }

    /** 当前应归属 / 应透传的 key；没有归属时为 null（此时探针必须零动作）。 */
    public static String get() {
        String k = peruserrt.KeyBridge.get();
        if (k != null) {
            return k;
        }
        k = ThreadProbeStore.getCurrentKey();
        if (k != null) {
            return k;
        }
        return ThreadProbeStore.isMerge() ? ThreadProbeStore.mergeKey() : null;
    }
}
