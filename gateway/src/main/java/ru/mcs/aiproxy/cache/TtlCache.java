package ru.mcs.aiproxy.cache;

import java.util.concurrent.ConcurrentHashMap;

public class TtlCache<K, V> {
    private final long ttlMillis;
    private final ConcurrentHashMap<K, Entry<V>> map = new ConcurrentHashMap<>();

    public TtlCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public V get(K key) {
        Entry<V> entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAt < System.currentTimeMillis()) {
            map.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    public void put(K key, V value) {
        put(key, value, ttlMillis);
    }

    public void put(K key, V value, long ttlMillis) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    private record Entry<V>(V value, long expiresAt) {
    }
}
