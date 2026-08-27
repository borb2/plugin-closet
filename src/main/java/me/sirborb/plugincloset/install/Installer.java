package me.sirborb.plugincloset.install;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.gui.BrowseMenu;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.platform.RuntimePlatform;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.logging.Level;

/** Ties a GUI click to a resolved version, a verified download, and the manifest. */
public final class Installer {

    private final PluginCloset plugin;

    public Installer(PluginCloset plugin) {
        this.plugin = plugin;
    }

    /** One click on a listing. Everything here runs off the main thread. */
    public void begin(BrowseMenu menu, PluginListing listing) {
        Player player = menu.player();
        Platform platform = RuntimePlatform.current();
        String mcVersion = RuntimePlatform.minecraftVersion();

        player.sendMessage(Component.text("Resolving " + listing.name() + "...", NamedTextColor.GRAY));

        plugin.index().versions(listing, platform)
                .thenCompose(files -> {
                    VersionPicker.Pick pick = VersionPicker.pick(files, mcVersion);
                    if (pick.isEmpty()) {
                        throw new java.util.concurrent.CompletionException(
                                new IllegalStateException(noVersionReason(files, mcVersion, platform)));
                    }
                    PluginVersionFile file = pick.file();
                    String previous = plugin.manifest().get(listing)
                            .map(InstallManifest.InstalledEntry::jarFileName)
                            .orElse(null);
                    return plugin.downloader().install(file, previous)
                            .thenApply(result -> new Done(file, pick.match(), result));
                })
                .whenComplete((done, error) -> onPlayerThread(player, () -> {
                    if (error != null) {
                        player.sendMessage(Component.text("✖ " + listing.name() + ": "
                                + rootMessage(error), NamedTextColor.RED));
                        return;
                    }
                    record(listing, done);
                    announce(player, listing, done);
                    menu.redraw();
                }));
    }

    private record Done(PluginVersionFile file, VersionPicker.Match match, Downloader.Result result) {
    }

    private void record(PluginListing listing, Done done) {
        plugin.manifest().put(new InstallManifest.InstalledEntry(
                listing.source(), listing.sourceId(), done.file().versionLabel(),
                Instant.now(), done.result().fileName()));
        try {
            plugin.manifest().save();
        } catch (Exception e) {
            // The jar is already in place; losing the manifest entry only costs the
            // "Installed" badge, so warn rather than pretend the install failed.
            plugin.getLogger().log(Level.WARNING, "Could not write installed.json", e);
        }
    }

    private void announce(Player player, PluginListing listing, Done done) {
        player.sendMessage(Component.text("✔ Downloaded " + listing.name() + " v"
                + done.file().versionLabel() + ". Restart the server to activate it.",
                NamedTextColor.GREEN));
        if (done.match() == VersionPicker.Match.SAME_MAJOR) {
            player.sendMessage(Component.text("  No build for exactly "
                    + RuntimePlatform.minecraftVersion() + "; installed one built for "
                    + String.join(", ", done.file().gameVersions()) + ".", NamedTextColor.YELLOW));
        }
        if (!done.result().hashVerified()) {
            player.sendMessage(Component.text("  The source published no checksum, "
                    + "so the download could not be verified.", NamedTextColor.YELLOW));
        }
    }

    /** Say *why* nothing was installable — "no versions found" alone is not actionable. */
    private static String noVersionReason(List<PluginVersionFile> files, String mcVersion, Platform platform) {
        if (files.isEmpty()) {
            return "no " + platform + " builds are published for this plugin";
        }
        boolean allExternal = files.stream().allMatch(PluginVersionFile::external);
        if (allExternal) {
            return "this plugin is hosted off-site (" + files.getFirst().downloadUrl()
                    + ") and has to be downloaded manually";
        }
        return "no build supports MC " + mcVersion + " (published: "
                + String.join(", ", files.getFirst().gameVersions()) + ")";
    }

    private void onPlayerThread(Player player, Runnable task) {
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
