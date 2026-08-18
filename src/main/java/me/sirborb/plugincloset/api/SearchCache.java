package me.sirborb.plugincloset.api;

import me.sirborb.plugincloset.model.Platform;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * TTL cache over search pages and version lists, so paging back and forth in the GUI does
 * not re-hit the APIs.
 *
 * <p>ponytail: a ConcurrentHashMap swept lazily on read. No eviction thread, no size cap —
 * entries are small and a browsing session is short. Add a cap if someone leaves the GUI
 * open across thousands of distinct queries.
 */
public final class SearchCache {

    private record Entry<T>(T value, long expiresAt) {
        boolean live(long now) {
            return now < expiresAt;
        }
    }

    private final ConcurrentHashMap<String, Entry<?>> map = new ConcurrentHashMap<>();
    private volatile Duration ttl;

    public SearchCache(Duration ttl) {
        this.ttl = ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    /** Cached value for the key, or {@code loader}'s result stored under it. */
    @SuppressWarnings("unchecked")
    public <T> T get(String key, Supplier<T> loader) {
        long now = System.nanoTime();
        Entry<?> hit = map.get(key);
        if (hit != null) {
            if (hit.live(now)) return (T) hit.value();
            map.remove(key, hit);
        }
        T value = loader.get();
        map.put(key, new Entry<>(value, now + ttl.toNanos()));
        return value;
    }

    public void clear() {
        map.clear();
    }

    /** The §3.3 cache key: source, query, sort, platform filter, offset. */
    public static String searchKey(String source, String query, SourceClient.Sort sort,
                                   Set<Platform> platforms, int offset, int limit) {
        StringBuilder b = new StringBuilder(source).append('|')
                .append(query == null ? "" : query).append('|')
                .append(sort).append('|');
        // Sorted so {PAPER,FOLIA} and {FOLIA,PAPER} share one entry.
        platforms.stream().map(Enum::name).sorted().forEach(n -> b.append(n).append(','));
        return b.append('|').append(offset).append('|').append(limit).toString();
    }

    public static String versionKey(String source, String sourceId, Platform platform) {
        return source + "|versions|" + sourceId + "|" + platform;
    }
}
