package me.sirborb.plugincloset.api;

import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** One plugin index. Implemented by {@link ModrinthClient} and {@link HangarClient}. */
public interface SourceClient {

    /** Sort orders offered in the GUI, mapped per-source by each implementation. */
    enum Sort {
        RELEVANCE, DOWNLOADS, FOLLOWS, NEWEST, UPDATED;

        public String display() {
            return switch (this) {
                case RELEVANCE -> "Relevance";
                case DOWNLOADS -> "Downloads";
                case FOLLOWS -> "Followers";
                case NEWEST -> "Newest";
                case UPDATED -> "Recently Updated";
            };
        }

        public Sort next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public Sort prev() {
            return values()[(ordinal() - 1 + values().length) % values().length];
        }

        public static Sort parse(String name, Sort fallback) {
            if (name == null) return fallback;
            for (Sort s : values()) {
                if (s.name().equalsIgnoreCase(name)) return s;
            }
            return fallback;
        }
    }

    boolean enabled();

    /**
     * @param platforms inclusive-OR filter; empty means "every platform this source knows"
     * @param offset    index of the first result, not a page number
     * @param limit     caller must respect the source's cap ({@link #maxLimit()})
     */
    CompletableFuture<List<PluginListing>> search(String query, Sort sort, Set<Platform> platforms,
                                                  int offset, int limit);

    /**
     * Every file this source publishes for {@code platform}, newest first. Choosing which
     * one suits the running MC version is {@link VersionPicker}'s job, not the client's.
     */
    CompletableFuture<List<PluginVersionFile>> getVersions(String sourceId, Platform platform);

    /** Largest page size this source accepts in one request. */
    int maxLimit();

    static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
