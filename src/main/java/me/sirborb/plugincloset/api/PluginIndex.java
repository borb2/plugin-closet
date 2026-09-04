package me.sirborb.plugincloset.api;

import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.model.Source;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Both sources behind one search call: fans out, merges, re-ranks, caches.
 *
 * <p>A page is split evenly because Hangar refuses a limit above 25 while a full GUI page
 * is 36. If one source is disabled or errors, the other fills the whole page.
 */
public final class PluginIndex {

    private final ModrinthClient modrinth;
    private final HangarClient hangar;
    private final SearchCache cache;
    private final Logger log;

    public PluginIndex(ModrinthClient modrinth, HangarClient hangar, SearchCache cache, Logger log) {
        this.modrinth = modrinth;
        this.hangar = hangar;
        this.cache = cache;
        this.log = log;
    }

    public SearchCache cache() {
        return cache;
    }

    /**
     * One GUI page of merged results.
     *
     * @param page      zero-based
     * @param pageSize  total slots to fill across both sources
     */
    public CompletableFuture<List<PluginListing>> search(String query, SourceClient.Sort sort,
                                                         Set<Platform> platforms, int page, int pageSize) {
        List<SourceClient> active = new ArrayList<>();
        // A source that cannot serve the filter is dropped here, not asked and discarded:
        // that way the sources left split the whole page instead of half of it.
        if (modrinth.enabled() && modrinth.serves(platforms)) active.add(modrinth);
        if (hangar.enabled() && hangar.serves(platforms)) active.add(hangar);
        if (active.isEmpty()) return CompletableFuture.completedFuture(List.of());

        int share = Math.max(1, pageSize / active.size());
        List<CompletableFuture<List<PluginListing>>> calls = new ArrayList<>();
        for (SourceClient c : active) {
            int limit = Math.min(share, c.maxLimit());
            calls.add(searchOne(c, query, sort, platforms, page * limit, limit));
        }

        return CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> {
                    List<List<PluginListing>> perSource = calls.stream().map(CompletableFuture::join).toList();
                    return merge(perSource, sort, pageSize);
                });
    }

    private CompletableFuture<List<PluginListing>> searchOne(SourceClient client, String query,
                                                             SourceClient.Sort sort, Set<Platform> platforms,
                                                             int offset, int limit) {
        String source = client instanceof HangarClient ? "HANGAR" : "MODRINTH";
        String key = SearchCache.searchKey(source, query, sort, platforms, offset, limit);
        return cache.<CompletableFuture<List<PluginListing>>>get(key,
                        () -> client.search(query, sort, platforms, offset, limit))
                // One source failing must not empty the page.
                .exceptionally(t -> {
                    log.log(Level.WARNING, source + " search failed: " + rootMessage(t));
                    return List.of();
                });
    }

    /**
     * Interleave for relevance (both APIs already returned their own relevance order, and
     * re-sorting would destroy it); otherwise re-rank the combined list so the page reads
     * as one ordered list instead of two stacked blocks.
     */
    public static List<PluginListing> merge(List<List<PluginListing>> perSource, SourceClient.Sort sort, int pageSize) {
        List<PluginListing> merged = new ArrayList<>();
        if (sort == SourceClient.Sort.RELEVANCE) {
            int max = perSource.stream().mapToInt(List::size).max().orElse(0);
            for (int i = 0; i < max; i++) {
                for (List<PluginListing> list : perSource) {
                    if (i < list.size()) merged.add(list.get(i));
                }
            }
        } else {
            perSource.forEach(merged::addAll);
            merged.sort(comparator(sort));
        }
        return merged.size() > pageSize ? merged.subList(0, pageSize) : merged;
    }

    private static Comparator<PluginListing> comparator(SourceClient.Sort sort) {
        return switch (sort) {
            case DOWNLOADS -> Comparator.comparingLong(PluginListing::downloads).reversed();
            case FOLLOWS -> Comparator.comparingLong(PluginListing::follows).reversed();
            case NEWEST -> Comparator.comparing(PluginListing::datePublished).reversed();
            case UPDATED -> Comparator.comparing(PluginListing::dateUpdated).reversed();
            case RELEVANCE -> Comparator.comparingInt(l -> 0);
        };
    }

    /** Files for a listing on the given platform, cached. */
    public CompletableFuture<List<PluginVersionFile>> versions(PluginListing listing, Platform platform) {
        return versions(listing.source(), listing.sourceId(), platform);
    }

    /** Same, for callers holding a manifest entry rather than a search result. */
    public CompletableFuture<List<PluginVersionFile>> versions(Source source, String sourceId,
                                                               Platform platform) {
        SourceClient client = source == Source.HANGAR ? hangar : modrinth;
        String key = SearchCache.versionKey(source.name(), sourceId, platform);
        return cache.<CompletableFuture<List<PluginVersionFile>>>get(key,
                () -> client.getVersions(sourceId, platform));
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.toString();
    }
}
