package me.sirborb.plugincloset.install;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.gui.BrowseMenu;
import me.sirborb.plugincloset.gui.Dialogs;
import me.sirborb.plugincloset.gui.Text;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.platform.RuntimePlatform;
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

    /**
     * One click on a listing. Goes straight to the download by default, which is the
     * literal one-click behaviour asked for; {@code require-confirmation: true} inserts a
     * dialog first for admins who want the safety net.
     */
    public void begin(BrowseMenu menu, PluginListing listing) {
        if (plugin.requireConfirmation()) {
            Dialogs.confirmInstall(plugin, menu, listing, () -> download(menu, listing));
        } else {
            download(menu, listing);
        }
    }

    private void download(BrowseMenu menu, PluginListing listing) {
        Player player = menu.player();
        Platform platform = RuntimePlatform.current();
        String mcVersion = RuntimePlatform.minecraftVersion();

        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.MUTED + ">Resolving "
                + Text.esc(listing.name()) + "..."));

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
                .whenComplete((done, error) -> {
                    // The manifest write is disk I/O, so it stays off the region thread;
                    // only the messaging and redraw need the player's own thread.
                    if (error == null) record(listing, done);
                    onPlayerThread(player, () -> {
                        if (error != null) {
                            player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED + ">✖ "
                                    + Text.esc(listing.name()) + " <" + Text.BODY + ">"
                                    + Text.esc(rootMessage(error))));
                            return;
                        }
                        announce(player, listing, done);
                        menu.redraw();
                    });
                });
    }

    private record Done(PluginVersionFile file, VersionPicker.Match match, Downloader.Result result) {
    }

    private void record(PluginListing listing, Done done) {
        plugin.manifest().put(new InstallManifest.InstalledEntry(
                listing.source(), listing.sourceId(), listing.name(),
                done.file().versionLabel(), Instant.now(), done.result().fileName()));
        try {
            plugin.manifest().save();
        } catch (Exception e) {
            // The jar is already in place; losing the manifest entry only costs the
            // "Installed" badge, so warn rather than pretend the install failed.
            plugin.getLogger().log(Level.WARNING, "Could not write installed.json", e);
        }
    }

    private void announce(Player player, PluginListing listing, Done done) {
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.GREEN + ">✔ Downloaded <"
                + Text.SELECTED + ">" + Text.esc(listing.name()) + " v"
                + Text.esc(done.file().versionLabel()) + "<" + Text.BODY
                + "> — restart the server to activate it."));
        if (done.match() == VersionPicker.Match.SAME_MAJOR) {
            player.sendMessage(Text.chat("<" + Text.YELLOW + ">  No build for exactly "
                    + Text.esc(RuntimePlatform.minecraftVersion()) + "; installed one built for "
                    + Text.esc(String.join(", ", done.file().gameVersions())) + "."));
        }
        if (!done.result().hashVerified()) {
            player.sendMessage(Text.chat("<" + Text.YELLOW + ">  The source published no "
                    + "checksum, so the download could not be verified."));
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
