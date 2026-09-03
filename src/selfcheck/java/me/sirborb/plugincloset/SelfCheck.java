package me.sirborb.plugincloset;

import me.sirborb.plugincloset.api.HangarClient;
import me.sirborb.plugincloset.api.Json;
import me.sirborb.plugincloset.api.ModrinthClient;
import me.sirborb.plugincloset.api.PluginIndex;
import me.sirborb.plugincloset.api.SearchCache;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.gui.Lore;
import me.sirborb.plugincloset.gui.config.ItemSpec;
import me.sirborb.plugincloset.gui.config.Slots;
import me.sirborb.plugincloset.install.Downloader;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.model.Source;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Assertions over the parsers, the version picker, and the filename sanitiser — the parts
 * most likely to be silently wrong. Run with {@code gradlew selfcheck} (asserts enabled).
 *
 * <p>ponytail: a main() with asserts, not a test framework. The fixtures are real captured
 * responses from both APIs.
 */
public final class SelfCheck {

    private static int checks;

    public static void main(String[] args) {
        boolean assertionsOn = false;
        assert assertionsOn = true;
        if (!assertionsOn) throw new IllegalStateException("run with -ea, or these assertions do nothing");

        json();
        modrinthSearch();
        modrinthVersions();
        hangarSearch();
        hangarVersionsExternal();
        hangarVersionsHosted();
        platformMapping();
        facets();
        versionPicking();
        merging();
        cacheKeys();
        filenameSanitising();
        scrollSelects();
        guiConfig();

        System.out.println("SelfCheck: " + checks + " assertions passed.");
    }

    private static void json() {
        Object o = Json.parse("{\"a\":1,\"b\":[true,null,\"x\\ny\"],\"c\":{\"d\":-2.5e2}}");
        check(Json.num(o, "a") == 1);
        check(Json.arr(Json.obj(o).get("b")).get(2).equals("x\ny"));
        check(Json.num(Json.child(o, "c"), "d") == -250);
        check(Json.parse("[]") instanceof List<?> l && l.isEmpty());
        check(Json.str(Json.parse("{\"k\":\"\\u00e9\"}"), "k").equals("\u00e9"));

        // Missing and wrong-typed fields must degrade, not throw.
        check(Json.str(o, "nope") == null);
        check(Json.num(o, "b") == 0L);
        check(Json.strings(o, "nope").isEmpty());

        // Round-trip through the writer.
        String written = Json.write(Map.of("n", "a\"b", "v", 3.0));
        Object back = Json.parse(written);
        check(Json.str(back, "n").equals("a\"b"));
        check(Json.num(back, "v") == 3);

        check(throwsOn(() -> Json.parse("{\"a\":1")));
        check(throwsOn(() -> Json.parse("{} trailing")));
    }

    private static void modrinthSearch() {
        List<PluginListing> hits = ModrinthClient.parseSearch(fixture("modrinth-search.json"));
        check(!hits.isEmpty());
        PluginListing we = hits.getFirst();
        check(we.source() == Source.MODRINTH);
        check(we.sourceId().equals("worldedit"));
        check(we.name().equals("WorldEdit"));
        check(we.downloads() > 1_000_000);
        check(we.follows() > 0);
        check(we.authors().equals(List.of("me4502")));
        check(we.iconUrl() != null && we.iconUrl().startsWith("https://"));
        check(we.dateUpdated().isAfter(we.datePublished()));

        // Loader names come out of "categories"; real categories must not become platforms.
        check(we.platforms().contains(Platform.PAPER));
        check(we.platforms().contains(Platform.FOLIA));
        check(we.platforms().contains(Platform.SPIGOT));
        check(!we.platforms().contains(Platform.UNKNOWN));
        check(we.platforms().size() < 9); // "utility", "library" etc. were dropped

        // latest_version is an opaque id, so no label is claimed rather than a fake one.
        check(we.latestVersionLabel() == null);
        check(we.supportedMcVersions().contains("1.21.4"));
    }

    private static void modrinthVersions() {
        List<PluginVersionFile> files = ModrinthClient.parseVersions(fixture("modrinth-versions.json"));
        check(!files.isEmpty());
        PluginVersionFile f = files.getFirst();
        check(f.versionLabel().equals("7.4.5"));
        check(f.downloadUrl().startsWith("https://cdn.modrinth.com/"));
        check(f.filename().endsWith(".jar"));
        check(f.hashAlgo().equals("SHA-512"));
        check(f.hasHash() && f.hashValue().length() > 100);
        check(!f.external());
        check(f.gameVersions().contains("1.21.4"));
        check(f.datePublished().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
    }

    private static void hangarSearch() {
        List<PluginListing> hits = HangarClient.parseSearch(fixture("hangar-search.json"));
        check(!hits.isEmpty());
        PluginListing e = hits.getFirst();
        check(e.source() == Source.HANGAR);
        check(e.sourceId().equals("Essentials"));   // namespace.slug, not the display name
        check(e.name().equals("Essentials"));
        check(e.downloads() > 0);
        check(e.follows() > 0);                     // stats.stars
        check(e.platforms().contains(Platform.PAPER));
        check(!e.platforms().contains(Platform.FOLIA)); // Hangar has no FOLIA, ever
        check(e.supportedMcVersions().contains("26.1.2"));
        check(!e.authors().isEmpty());
    }

    private static void hangarVersionsExternal() {
        // EssentialsX publishes to Hangar but hosts jars on GitHub: downloadUrl is null and
        // externalUrl points at a *release page*, which is HTML. Must never be installed.
        List<PluginVersionFile> files = HangarClient.parseVersions(fixture("hangar-versions.json"), "PAPER");
        check(!files.isEmpty());
        PluginVersionFile f = files.getFirst();
        check(f.external());
        check(f.downloadUrl().contains("github.com"));
        check(!f.hasHash());                        // fileInfo was null
        check(f.platform() == Platform.PAPER);
        check(f.gameVersions().contains("26.1.2"));

        // And the picker must refuse it even though it is the only version present.
        check(VersionPicker.pick(files, "26.1.2").isEmpty());
    }

    private static void hangarVersionsHosted() {
        List<PluginVersionFile> files =
                HangarClient.parseVersions(fixture("hangar-versions-hosted.json"), "PAPER");
        check(!files.isEmpty());
        PluginVersionFile f = files.getFirst();
        check(!f.external());
        check(f.downloadUrl().startsWith("https://hangarcdn.papermc.io/"));
        check(f.hashAlgo().equals("SHA-256"));
        check(f.hasHash() && f.hashValue().length() == 64);
        check(f.filename().endsWith(".jar"));
    }

    private static void platformMapping() {
        check(Platform.fromModrinthLoader("folia") == Platform.FOLIA);
        check(Platform.fromModrinthLoader("PAPER") == Platform.PAPER);
        check(Platform.fromModrinthLoader("neoforge") == Platform.NEOFORGE);
        check(Platform.fromModrinthLoader("quilt") == Platform.QUILT);
        check(Platform.fromModrinthLoader("utility") == Platform.UNKNOWN);
        check("NeoForge".equals(Platform.NEOFORGE.display()));
        check("Bukkit".equals(Platform.BUKKIT.display()));
        check(Platform.fromModrinthLoader(null) == Platform.UNKNOWN);

        // The correction that matters: Folia asks Hangar for Paper jars.
        check(Platform.FOLIA.hangarName().equals("PAPER"));
        check(Platform.PAPER.hangarName().equals("PAPER"));
        check(Platform.SPIGOT.hangarName() == null);
        check(Platform.BUKKIT.hangarName() == null);
        check(Platform.PURPUR.hangarName() == null);
        check(Platform.SPONGE.hangarName() == null);
        // ...and a Hangar PAPER listing never claims Folia support.
        check(Platform.fromHangarName("PAPER") == Platform.PAPER);
        check(Platform.fromHangarName("FOLIA") == Platform.UNKNOWN);
    }

    private static void facets() {
        String all = ModrinthClient.facets(Set.of());
        check(all.contains("project_type:plugin"));
        check(all.contains("loaders:paper") && all.contains("loaders:folia"));

        String one = ModrinthClient.facets(EnumSet.of(Platform.PAPER));
        check(one.equals("[[\"project_type:plugin\"],[\"loaders:paper\"]]"));

        // Selecting only platforms Modrinth cannot express must not emit an empty OR group.
        String none = ModrinthClient.facets(EnumSet.of(Platform.UNKNOWN));
        check(none.equals("[[\"project_type:plugin\"]]"));

        check(ModrinthClient.indexFor(SourceClient.Sort.FOLLOWS).equals("follows"));
        check(HangarClient.sortFor(SourceClient.Sort.FOLLOWS).equals("stars"));
        check(HangarClient.sortFor(SourceClient.Sort.RELEVANCE) == null); // no such sort
    }

    private static void versionPicking() {
        PluginVersionFile old = file("1.0", List.of("1.21.4"), "2024-01-01T00:00:00Z");
        PluginVersionFile mid = file("2.0", List.of("26.1", "26.1.2"), "2026-05-01T00:00:00Z");
        PluginVersionFile now = file("3.0", List.of("26.2"), "2026-08-01T00:00:00Z");
        List<PluginVersionFile> all = List.of(old, mid, now);

        check(VersionPicker.pick(all, "26.2").match() == VersionPicker.Match.EXACT);
        check(VersionPicker.pick(all, "26.2").file().versionLabel().equals("3.0"));

        // 26.1.5 has no exact build, but the 26.1 line does.
        VersionPicker.Pick fallback = VersionPicker.pick(all, "26.1.5");
        check(fallback.match() == VersionPicker.Match.SAME_MAJOR);
        check(fallback.file().versionLabel().equals("2.0"));

        check(VersionPicker.pick(all, "1.8.8").match() == VersionPicker.Match.NONE);
        check(VersionPicker.pick(List.of(), "26.2").isEmpty());

        check(VersionPicker.majorLine("26.1.2").equals("26.1"));
        check(VersionPicker.majorLine("1.21.4").equals("1.21"));
        check(VersionPicker.majorLine("26.2").equals("26.2"));
    }

    private static void merging() {
        List<PluginListing> a = List.of(listing("a1", 100), listing("a2", 5));
        List<PluginListing> b = List.of(listing("b1", 50), listing("b2", 900));

        // Downloads: one ranked list across both sources, not two stacked blocks.
        List<PluginListing> byDownloads =
                PluginIndex.merge(List.of(a, b), SourceClient.Sort.DOWNLOADS, 36);
        check(byDownloads.stream().map(PluginListing::sourceId).toList()
                .equals(List.of("b2", "a1", "b1", "a2")));

        // Relevance: each source's own order preserved, interleaved.
        List<PluginListing> byRelevance =
                PluginIndex.merge(List.of(a, b), SourceClient.Sort.RELEVANCE, 36);
        check(byRelevance.stream().map(PluginListing::sourceId).toList()
                .equals(List.of("a1", "b1", "a2", "b2")));

        check(PluginIndex.merge(List.of(a, b), SourceClient.Sort.DOWNLOADS, 3).size() == 3);
        // A dead source leaves the other filling the page.
        check(PluginIndex.merge(List.of(a, List.of()), SourceClient.Sort.DOWNLOADS, 36).size() == 2);
    }

    private static void cacheKeys() {
        String x = SearchCache.searchKey("MODRINTH", "q", SourceClient.Sort.NEWEST,
                EnumSet.of(Platform.PAPER, Platform.FOLIA), 0, 18);
        String y = SearchCache.searchKey("MODRINTH", "q", SourceClient.Sort.NEWEST,
                EnumSet.of(Platform.FOLIA, Platform.PAPER), 0, 18);
        check(x.equals(y));  // filter order must not split the cache entry

        check(!x.equals(SearchCache.searchKey("HANGAR", "q", SourceClient.Sort.NEWEST,
                EnumSet.of(Platform.PAPER, Platform.FOLIA), 0, 18)));
        check(!x.equals(SearchCache.searchKey("MODRINTH", "q", SourceClient.Sort.NEWEST,
                EnumSet.of(Platform.PAPER, Platform.FOLIA), 18, 18)));

        SearchCache cache = new SearchCache(Duration.ofMinutes(15));
        check(cache.get("k", () -> "first").equals("first"));
        check(cache.get("k", () -> "second").equals("first"));  // served from cache
        cache.clear();
        check(cache.get("k", () -> "third").equals("third"));

        SearchCache expiring = new SearchCache(Duration.ZERO);
        check(expiring.get("k", () -> "a").equals("a"));
        check(expiring.get("k", () -> "b").equals("b"));        // TTL already elapsed
    }

    private static void filenameSanitising() {
        // This decides what name a network-supplied file gets inside plugins/.
        check(Downloader.safeJarName("Chunky-Bukkit-1.5.3.jar").equals("Chunky-Bukkit-1.5.3.jar"));
        check(Downloader.safeJarName("sub/dir/thing.jar").equals("thing.jar"));
        check(Downloader.safeJarName("sub\\dir\\thing.jar").equals("thing.jar"));
        check(Downloader.safeJarName("../../evil.jar").equals("evil.jar"));
        check(Downloader.safeJarName("..\\..\\evil.jar").equals("evil.jar"));
        check(Downloader.safeJarName("odd name (1).jar").equals("odd_name__1_.jar"));

        // Anything that is not a plain jar is refused outright.
        check(Downloader.safeJarName("payload.jar.exe") == null);
        check(Downloader.safeJarName("notajar.txt") == null);
        check(Downloader.safeJarName("plugin.JAR") != null);   // case-insensitive
        check(Downloader.safeJarName("..") == null);
        check(Downloader.safeJarName(".jar") == null);         // no stem
        check(Downloader.safeJarName("") == null);
        check(Downloader.safeJarName(null) == null);
    }

    // --- helpers ---

    private static PluginVersionFile file(String label, List<String> gameVersions, String published) {
        return new PluginVersionFile(label, Platform.PAPER, gameVersions,
                "https://example.invalid/" + label + ".jar", label + ".jar",
                "SHA-256", "0".repeat(64), Instant.parse(published), false);
    }

    private static PluginListing listing(String id, long downloads) {
        return new PluginListing(Source.MODRINTH, id, id, "", List.of(), downloads, 0,
                Instant.EPOCH, Instant.EPOCH, Set.of(Platform.PAPER), null, List.of(), null);
    }

    private static Object fixture(String name) {
        try (InputStream in = SelfCheck.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return Json.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not read fixture " + name, e);
        }
    }

    /** The scroll-selects: -1 is "All", and both directions must wrap past either end. */
    private static void scrollSelects() {
        int n = 8;                                  // as many platforms as the filter offers
        check(Lore.cycle(-1, n, false) == 0);       // All -> first
        check(Lore.cycle(0, n, false) == 1);
        check(Lore.cycle(n - 1, n, false) == -1);   // last -> All, forwards
        check(Lore.cycle(-1, n, true) == n - 1);    // All -> last, backwards
        check(Lore.cycle(0, n, true) == -1);
        check(Lore.cycle(3, n, true) == 2);
        // Every index round-trips, and a full lap of either direction returns home.
        for (int i = -1; i < n; i++) {
            check(Lore.cycle(Lore.cycle(i, n, false), n, true) == i);
            int forward = i;
            for (int step = 0; step <= n; step++) forward = Lore.cycle(forward, n, false);
            check(forward == i);
        }

        for (SourceClient.Sort sort : SourceClient.Sort.values()) {
            check(sort.next().prev() == sort);
            check(sort.prev().next() == sort);
            check(sort.display() != null && !sort.display().isBlank());
        }
        check(SourceClient.Sort.RELEVANCE.prev() == SourceClient.Sort.UPDATED);
        check(SourceClient.Sort.UPDATED.next() == SourceClient.Sort.RELEVANCE);
    }

    /** The two rules the guis/ files depend on: slot ranges, and conditional lore lines. */
    private static void guiConfig() {
        check(java.util.Arrays.equals(Slots.parse("45"), new int[]{45}));
        check(Slots.parse("0-3").length == 4);
        check(java.util.Arrays.equals(Slots.parse("36-38,53"), new int[]{36, 37, 38, 53}));
        check(Slots.parse(null).length == 0);
        check(Slots.parse("nonsense").length == 0);

        Map<String, String> ph = Map.of("name", "Vault", "latest", "", "multi", "a\nb");
        check("<red>Vault".equals(ItemSpec.fill("<red>%name%", ph)));
        check(ItemSpec.fill("Latest: %latest%", ph) == null);       // empty value drops the line
        check("x\nb".equals(ItemSpec.fill("x\nb", ph)));
        check("a\nb!".equals(ItemSpec.fill("%multi%!", ph)));
        check("100%".equals(ItemSpec.fill("100%", ph)));
        check("%unknown%".equals(ItemSpec.fill("%unknown%", ph)));  // left for another plugin

        // Wrapped lore keeps its colour: line two of a description is not vanilla purple.
        check("<#c9cdd4>".equals(ItemSpec.leadingTags("<#c9cdd4>a long description")));
        check("<b><red>".equals(ItemSpec.leadingTags("<b><red>x")));
        check(ItemSpec.leadingTags("plain").isEmpty());
        check(ItemSpec.leadingTags("<unclosed").isEmpty());
    }

    private static boolean throwsOn(Runnable r) {
        try {
            r.run();
            return false;
        } catch (RuntimeException expected) {
            return true;
        }
    }

    private static void check(boolean condition) {
        checks++;
        assert condition : "assertion #" + checks + " failed";
    }
}
