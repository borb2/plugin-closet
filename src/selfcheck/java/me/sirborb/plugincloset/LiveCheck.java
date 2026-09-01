package me.sirborb.plugincloset;

import me.sirborb.plugincloset.api.HangarClient;
import me.sirborb.plugincloset.api.ModrinthClient;
import me.sirborb.plugincloset.api.PluginIndex;
import me.sirborb.plugincloset.api.SearchCache;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * Hits both live APIs once and prints a merged page, to confirm the Modrinth User-Agent is
 * accepted and Hangar answers anonymously. Not part of the build — run it by hand with
 * {@code gradlew livecheck} when an API might have shifted.
 */
public final class LiveCheck {

    public static void main(String[] args) throws Exception {
        String userAgent = args.length > 0 ? args[0] : "PluginCloset/1.0 (+live-check)";
        String query = args.length > 1 ? args[1] : "worldedit";

        var index = new PluginIndex(
                new ModrinthClient(true, userAgent),
                new HangarClient(true, userAgent, ""),
                new SearchCache(Duration.ofMinutes(15)),
                Logger.getLogger("LiveCheck"));

        System.out.println("Query: " + query + "   (Paper, sorted by downloads)\n");
        List<PluginListing> page = index
                .search(query, SourceClient.Sort.DOWNLOADS, EnumSet.of(Platform.PAPER), 0, 36)
                .get();

        if (page.isEmpty()) {
            System.out.println("!! No results from either source - check the User-Agent and network.");
            return;
        }
        long modrinth = page.stream().filter(l -> l.source().name().equals("MODRINTH")).count();
        System.out.printf("%d results (%d Modrinth, %d Hangar)%n%n",
                page.size(), modrinth, page.size() - modrinth);

        for (PluginListing l : page.subList(0, Math.min(8, page.size()))) {
            System.out.printf("  %-9s %-28s %10d dl  %6d ★  %s%n",
                    l.source().display(), truncate(l.name()), l.downloads(), l.follows(),
                    l.platforms());
        }

        // And resolve one real download, which is the path that actually installs a jar.
        PluginListing first = page.getFirst();
        System.out.println("\nResolving versions for " + first.name() + " (" + first.source().display() + ")");
        List<PluginVersionFile> files = index.versions(first, Platform.PAPER).get();
        System.out.println("  " + files.size() + " files returned");

        VersionPicker.Pick pick = VersionPicker.pick(files, "26.2");
        if (pick.isEmpty()) {
            System.out.println("  no installable build for MC 26.2 "
                    + "(all external, or none supports it)");
        } else {
            PluginVersionFile f = pick.file();
            System.out.printf("  picked v%s (%s) %s%n    %s%n    %s %s%n",
                    f.versionLabel(), pick.match(), f.filename(), f.downloadUrl(),
                    f.hashAlgo(), f.hasHash() ? f.hashValue().substring(0, 16) + "..." : "(none)");
        }
    }

    private static String truncate(String s) {
        return s.length() <= 28 ? s : s.substring(0, 25) + "...";
    }
}
