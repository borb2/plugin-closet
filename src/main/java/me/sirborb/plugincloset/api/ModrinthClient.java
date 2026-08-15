package me.sirborb.plugincloset.api;

import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.model.Source;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public final class ModrinthClient implements SourceClient {

    private static final String BASE = "https://api.modrinth.com/v2";

    /** Modrinth allows 100; we never ask for more than half a GUI page. */
    private static final int MAX_LIMIT = 100;

    /** Every loader Modrinth recognises as a plugin, verified against /v2/tag/loader. */
    private static final Set<Platform> ALL = EnumSet.of(
            Platform.PAPER, Platform.FOLIA, Platform.SPIGOT, Platform.BUKKIT,
            Platform.PURPUR, Platform.VELOCITY, Platform.BUNGEECORD,
            Platform.WATERFALL, Platform.SPONGE);

    private final boolean enabled;
    private final String userAgent;

    public ModrinthClient(boolean enabled, String userAgent) {
        this.enabled = enabled;
        this.userAgent = userAgent;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public int maxLimit() {
        return MAX_LIMIT;
    }

    @Override
    public CompletableFuture<List<PluginListing>> search(String query, Sort sort, Set<Platform> platforms,
                                                         int offset, int limit) {
        StringBuilder url = new StringBuilder(BASE).append("/search?index=").append(indexFor(sort))
                .append("&limit=").append(Math.min(limit, MAX_LIMIT))
                .append("&offset=").append(Math.max(0, offset));
        if (query != null && !query.isBlank()) {
            url.append("&query=").append(SourceClient.urlEncode(query));
        }
        url.append("&facets=").append(SourceClient.urlEncode(facets(platforms)));
        return Http.getJson(url.toString(), userAgent, null).thenApply(ModrinthClient::parseSearch);
    }

    @Override
    public CompletableFuture<List<PluginVersionFile>> getVersions(String sourceId, Platform platform) {
        String loader = platform.modrinthLoader();
        String url = BASE + "/project/" + SourceClient.urlEncode(sourceId) + "/version"
                + (loader == null ? "" : "?loaders=" + SourceClient.urlEncode("[\"" + loader + "\"]"));
        return Http.getJson(url, userAgent, null).thenApply(ModrinthClient::parseVersions);
    }

    /**
     * Facet syntax: entries inside one array are OR'd, separate arrays are AND'd. So this
     * is (any selected loader) AND project_type:plugin.
     */
    public static String facets(Set<Platform> platforms) {
        Set<Platform> active = (platforms == null || platforms.isEmpty()) ? ALL : platforms;
        String loaders = active.stream()
                .map(Platform::modrinthLoader)
                .filter(java.util.Objects::nonNull)
                .map(l -> "\"loaders:" + l + "\"")
                .collect(Collectors.joining(","));
        if (loaders.isEmpty()) {
            return "[[\"project_type:plugin\"]]";
        }
        return "[[\"project_type:plugin\"],[" + loaders + "]]";
    }

    public static String indexFor(Sort sort) {
        return switch (sort) {
            case RELEVANCE -> "relevance";
            case DOWNLOADS -> "downloads";
            case FOLLOWS -> "follows";
            case NEWEST -> "newest";
            case UPDATED -> "updated";
        };
    }

    public static List<PluginListing> parseSearch(Object json) {
        List<PluginListing> out = new ArrayList<>();
        for (Object hit : Json.children(json, "hits")) {
            String id = Json.str(hit, "slug");
            if (id == null) id = Json.str(hit, "project_id");
            if (id == null) continue;

            // Loader names live in "categories" alongside real categories like "utility";
            // anything that is not a known loader maps to UNKNOWN and drops out here.
            Set<Platform> platforms = new LinkedHashSet<>();
            for (String c : Json.strings(hit, "categories")) {
                Platform p = Platform.fromModrinthLoader(c);
                if (p != Platform.UNKNOWN) platforms.add(p);
            }

            String author = Json.str(hit, "author");
            out.add(new PluginListing(
                    Source.MODRINTH,
                    id,
                    orEmpty(Json.str(hit, "title"), id),
                    orEmpty(Json.str(hit, "description"), ""),
                    author == null ? List.of() : List.of(author),
                    Json.num(hit, "downloads"),
                    Json.num(hit, "follows"),
                    instant(Json.str(hit, "date_created")),
                    instant(Json.str(hit, "date_modified")),
                    platforms,
                    // "latest_version" is a version *id*, not a label, so there is no
                    // usable label here. The GUI omits the line until versions load.
                    null,
                    Json.strings(hit, "versions"),
                    Json.str(hit, "icon_url")));
        }
        return out;
    }

    public static List<PluginVersionFile> parseVersions(Object json) {
        List<PluginVersionFile> out = new ArrayList<>();
        for (Object v : Json.arr(json)) {
            Map<String, Object> file = primaryFile(v);
            if (file.isEmpty()) continue;

            String label = Json.str(v, "version_number");
            if (label == null) label = Json.str(v, "name");

            List<String> loaders = Json.strings(v, "loaders");
            Platform platform = loaders.isEmpty()
                    ? Platform.UNKNOWN
                    : Platform.fromModrinthLoader(loaders.getFirst());

            out.add(new PluginVersionFile(
                    orEmpty(label, "unknown"),
                    platform,
                    Json.strings(v, "game_versions"),
                    Json.str(file, "url"),
                    Json.str(file, "filename"),
                    "SHA-512",
                    Json.str(Json.child(file, "hashes"), "sha512"),
                    instant(Json.str(v, "date_published")),
                    false));
        }
        return out;
    }

    /** The file flagged primary, else the first one. */
    private static Map<String, Object> primaryFile(Object version) {
        List<Object> files = Json.children(version, "files");
        for (Object f : files) {
            if (Boolean.TRUE.equals(Json.obj(f).get("primary"))) return Json.obj(f);
        }
        return files.isEmpty() ? Map.of() : Json.obj(files.getFirst());
    }

    /** Modrinth mixes trailing "Z" and "+00:00" offsets, so parse as an offset date-time. */
    public static Instant instant(String text) {
        if (text == null || text.isBlank()) return Instant.EPOCH;
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    private static String orEmpty(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
