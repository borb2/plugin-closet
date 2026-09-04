package me.sirborb.plugincloset.api;

import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.model.Source;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Hangar, verified against its live OpenAPI spec.
 *
 * <p>Three things the MVP spec got wrong and this client compensates for:
 * Hangar's Platform enum is only PAPER/WATERFALL/VELOCITY (no FOLIA); hashes are sha256,
 * not sha512; and a single request caps at 25 results. The download route also always
 * needs a platform segment — but we never build it, because the versions response already
 * carries {@code downloads[PLATFORM].downloadUrl}.
 */
public final class HangarClient implements SourceClient {

    private static final String BASE = "https://hangar.papermc.io/api/v1";

    /** Hangar rejects limit > 25. This is why a GUI page is split across sources. */
    private static final int MAX_LIMIT = 25;

    private final boolean enabled;
    private final String userAgent;
    private final String apiKey;

    public HangarClient(boolean enabled, String userAgent, String apiKey) {
        this.enabled = enabled;
        this.userAgent = userAgent;
        this.apiKey = apiKey;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public int maxLimit() {
        return MAX_LIMIT;
    }

    /** Only PAPER/WATERFALL/VELOCITY exist here; a Spigot or Fabric filter excludes Hangar. */
    @Override
    public boolean serves(Set<Platform> platforms) {
        return platforms == null || platforms.isEmpty() || !hangarNames(platforms).isEmpty();
    }

    @Override
    public CompletableFuture<List<PluginListing>> search(String query, Sort sort, Set<Platform> platforms,
                                                         int offset, int limit) {
        StringBuilder url = new StringBuilder(BASE).append("/projects?limit=")
                .append(Math.min(limit, MAX_LIMIT))
                .append("&offset=").append(Math.max(0, offset));
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(SourceClient.urlEncode(query));
        }
        // Hangar has no "relevance" sort; omitting the param gives its default ordering,
        // which with prioritizeExactMatch=true is the closest thing to relevance.
        String sortParam = sortFor(sort);
        if (sortParam != null) {
            url.append("&sort=").append(sortParam);
        }
        // Hangar accepts a single platform, not a list. With several selected we cannot
        // express the OR, so we filter client-side instead of sending a wrong narrowing.
        Set<String> wanted = hangarNames(platforms);
        if (wanted.size() == 1) {
            url.append("&platform=").append(wanted.iterator().next());
        }
        return Http.getJson(url.toString(), userAgent, apiKey)
                .thenApply(json -> filterToPlatforms(parseSearch(json), platforms));
    }

    @Override
    public CompletableFuture<List<PluginVersionFile>> getVersions(String sourceId, Platform platform) {
        String hangarPlatform = platform.hangarName();
        if (hangarPlatform == null) {
            // Hangar cannot serve this platform at all (Spigot, Bukkit, Sponge, Purpur).
            return CompletableFuture.completedFuture(List.of());
        }
        String url = BASE + "/projects/" + SourceClient.urlEncode(sourceId)
                + "/versions?limit=" + MAX_LIMIT + "&platform=" + hangarPlatform;
        return Http.getJson(url, userAgent, apiKey)
                .thenApply(json -> parseVersions(json, hangarPlatform));
    }

    public static String sortFor(Sort sort) {
        return switch (sort) {
            case RELEVANCE -> null;          // no such sort on Hangar
            case DOWNLOADS -> "downloads";
            case FOLLOWS -> "stars";         // Hangar's name for the same idea
            case NEWEST -> "newest";
            case UPDATED -> "updated";
        };
    }

    private static Set<String> hangarNames(Set<Platform> platforms) {
        Set<String> out = new LinkedHashSet<>();
        if (platforms == null) return out;
        for (Platform p : platforms) {
            if (p.hangarName() != null) out.add(p.hangarName());
        }
        return out;
    }

    /**
     * Drop listings that support none of the selected platforms. Needed because Hangar's
     * search takes only one platform, so a multi-select filter is finished client-side —
     * and because a filter Hangar cannot express at all (Spigot, Bukkit, Purpur, the mod
     * loaders) must return nothing rather than every Paper plugin.
     */
    public static List<PluginListing> filterToPlatforms(List<PluginListing> listings, Set<Platform> platforms) {
        if (platforms == null || platforms.isEmpty()) return listings;
        Set<String> wanted = hangarNames(platforms);
        List<PluginListing> out = new ArrayList<>();
        for (PluginListing l : listings) {
            for (Platform p : l.platforms()) {
                if (p.hangarName() != null && wanted.contains(p.hangarName())) {
                    out.add(l);
                    break;
                }
            }
        }
        return out;
    }

    public static List<PluginListing> parseSearch(Object json) {
        List<PluginListing> out = new ArrayList<>();
        for (Object p : Json.children(json, "result")) {
            String slug = Json.str(Json.child(p, "namespace"), "slug");
            if (slug == null) continue;

            Map<String, Object> supported = Json.child(p, "supportedPlatforms");
            Set<Platform> platforms = new LinkedHashSet<>();
            List<String> mcVersions = new ArrayList<>();
            for (Map.Entry<String, Object> e : supported.entrySet()) {
                Platform plat = Platform.fromHangarName(e.getKey());
                if (plat != Platform.UNKNOWN) platforms.add(plat);
                for (Object v : Json.arr(e.getValue())) {
                    if (v instanceof String s && !mcVersions.contains(s)) mcVersions.add(s);
                }
            }

            Map<String, Object> stats = Json.child(p, "stats");
            out.add(new PluginListing(
                    Source.HANGAR,
                    slug,
                    orElse(Json.str(p, "name"), slug),
                    orElse(Json.str(p, "description"), ""),
                    Json.strings(p, "memberNames"),
                    Json.num(stats, "downloads"),
                    Json.num(stats, "stars"),
                    ModrinthClient.instant(Json.str(p, "createdAt")),
                    ModrinthClient.instant(Json.str(p, "lastUpdated")),
                    platforms,
                    null,
                    mcVersions,
                    Json.str(p, "avatarUrl")));
        }
        return out;
    }

    public static List<PluginVersionFile> parseVersions(Object json, String hangarPlatform) {
        List<PluginVersionFile> out = new ArrayList<>();
        for (Object v : Json.children(json, "result")) {
            Map<String, Object> download = Json.child(Json.child(v, "downloads"), hangarPlatform);
            if (download.isEmpty()) continue;

            String downloadUrl = Json.str(download, "downloadUrl");
            String externalUrl = Json.str(download, "externalUrl");
            // No Hangar-hosted file: the "download" is someone's release page, which is
            // usually HTML, not a jar. Keep it, flagged, so the GUI can say why.
            boolean external = downloadUrl == null && externalUrl != null;

            Map<String, Object> fileInfo = Json.child(download, "fileInfo");
            List<String> gameVersions = new ArrayList<>();
            for (Object g : Json.arr(Json.child(v, "platformDependencies").get(hangarPlatform))) {
                if (g instanceof String s) gameVersions.add(s);
            }

            out.add(new PluginVersionFile(
                    orElse(Json.str(v, "name"), "unknown"),
                    Platform.fromHangarName(hangarPlatform),
                    gameVersions,
                    external ? externalUrl : downloadUrl,
                    Json.str(fileInfo, "name"),
                    "SHA-256",
                    Json.str(fileInfo, "sha256Hash"),
                    ModrinthClient.instant(Json.str(v, "createdAt")),
                    external));
        }
        return out;
    }

    private static String orElse(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
