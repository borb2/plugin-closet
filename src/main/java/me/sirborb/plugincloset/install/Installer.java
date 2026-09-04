package me.sirborb.plugincloset.install;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.gui.BrowseMenu;
import me.sirborb.plugincloset.gui.Dialogs;
import me.sirborb.plugincloset.gui.InstalledMenu;
import me.sirborb.plugincloset.gui.Lore;
import me.sirborb.plugincloset.gui.Text;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import me.sirborb.plugincloset.platform.RuntimePlatform;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
                        throw new CompletionException(
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
                    if (error == null) {
                        record(listing.source(), listing.sourceId(), listing.name(), done);
                    }
                    onPlayerThread(player, () -> {
                        if (error != null) {
                            fail(player, listing.name(), error);
                            return;
                        }
                        announce(player, listing.name(), done);
                        menu.redraw();
                    });
                });
    }

    // --- managing something already installed ---

    /** Resolve the newest build for an installed plugin and replace its jar with it. */
    public void updateToLatest(Player player, InstallManifest.InstalledEntry entry, Runnable after) {
        Platform platform = RuntimePlatform.current();
        String mcVersion = RuntimePlatform.minecraftVersion();
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.MUTED + ">Looking for a newer "
                + Text.esc(entry.displayName()) + "..."));

        plugin.index().versions(entry.source(), entry.sourceId(), platform)
                .thenCompose(files -> {
                    VersionPicker.Pick pick = VersionPicker.pick(files, mcVersion);
                    if (pick.isEmpty()) {
                        throw new CompletionException(
                                new IllegalStateException(noVersionReason(files, mcVersion, platform)));
                    }
                    if (pick.file().versionLabel().equals(entry.installedVersion())) {
                        throw new CompletionException(new IllegalStateException(
                                "already on the newest build (" + entry.installedVersion() + ")"));
                    }
                    return install(entry, pick.file(), pick.match());
                })
                .whenComplete((done, error) -> finish(player, entry, done, error, after));
    }

    /** Install one specific version — the downgrade path, and any re-pick from the list. */
    public void installVersion(Player player, InstallManifest.InstalledEntry entry,
                               PluginVersionFile file, Runnable after) {
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.MUTED + ">Installing "
                + Text.esc(entry.displayName()) + " v" + Text.esc(file.versionLabel()) + "..."));
        install(entry, file, VersionPicker.Match.EXACT)
                .whenComplete((done, error) -> finish(player, entry, done, error, after));
    }

    /**
     * The shared tail of both: download the file, delete the jar it replaces, then write
     * the manifest. The old jar goes away inside {@link Downloader}, which is the only
     * place allowed to touch the plugins folder.
     */
    private CompletableFuture<Done> install(InstallManifest.InstalledEntry entry,
                                            PluginVersionFile file, VersionPicker.Match match) {
        return plugin.downloader().install(file, entry.jarFileName())
                .thenApply(result -> new Done(file, match, result));
    }

    private void finish(Player player, InstallManifest.InstalledEntry entry, Done done,
                        Throwable error, Runnable after) {
        if (error == null) {
            record(entry.source(), entry.sourceId(), entry.displayName(), done);
        }
        onPlayerThread(player, () -> {
            if (error != null) {
                fail(player, entry.displayName(), error);
            } else {
                announce(player, entry.displayName(), done);
            }
            if (after != null) after.run();
        });
    }

    /**
     * Delete a jar and forget it. Nothing is unloaded: a running plugin stays running
     * until the next restart, and saying so is the honest thing to do.
     */
    public void uninstall(Player player, InstalledMenu.Row row, Runnable after) {
        String file = row.jar().getFileName().toString();
        plugin.asyncExecutor().execute(() -> {
            boolean deleted;
            String problem = null;
            try {
                deleted = plugin.downloader().delete(file);
            } catch (Exception e) {
                deleted = false;
                problem = e.getMessage();
            }
            if (row.manifest() != null) {
                plugin.manifest().remove(row.manifest().key());
                try {
                    plugin.manifest().save();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Could not write installed.json", e);
                }
            }
            boolean gone = deleted;
            String why = problem;
            onPlayerThread(player, () -> {
                if (gone) {
                    player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.GREEN + ">✔ Deleted <"
                            + Text.SELECTED + ">" + Text.esc(file) + "<" + Text.BODY
                            + "> — it stays loaded until the server restarts."));
                } else {
                    player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED + ">✖ Could not delete "
                            + Text.esc(file) + (why == null ? "" : ": " + Text.esc(why))));
                }
                if (after != null) after.run();
            });
        });
    }

    /** Every published file for an installed plugin, newest first, for the version picker. */
    public CompletableFuture<List<PluginVersionFile>> availableVersions(
            InstallManifest.InstalledEntry entry) {
        return plugin.index().versions(entry.source(), entry.sourceId(), RuntimePlatform.current())
                .thenApply(files -> files.stream()
                        .filter(f -> !f.external())
                        .filter(f -> f.downloadUrl() != null && !f.downloadUrl().isBlank())
                        .sorted(java.util.Comparator.comparing(PluginVersionFile::datePublished).reversed())
                        .toList());
    }

    // --- installing from a pasted link ---

    /**
     * Download whatever the admin pasted. There is no source, no version and no checksum
     * behind this, so it is never recorded in the manifest — the installed menu will list
     * the jar the same way it lists anything else dropped in by hand.
     *
     * <p>Progress goes to a boss bar rather than the dialog: a Paper dialog is a snapshot
     * the client renders once, so it cannot animate. The dialog closes on the click and
     * the bar fills and disappears on its own.
     */
    public void installFromUrl(Player player, String url) {
        String link = url == null ? "" : url.trim();
        if (link.isEmpty()) return;

        BossBar bar = BossBar.bossBar(Text.line(Text.ACCENT, "Starting download..."),
                0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        player.showBossBar(bar);

        plugin.downloader().sizeOf(link).thenCompose(total -> {
            long[] lastShown = {0};
            return plugin.downloader().installUrl(link, read -> {
                // One update per percent: a boss bar is packets, and 64 KB chunks are many.
                long percent = total > 0 ? read * 100 / total : read / (512 * 1024);
                if (percent == lastShown[0]) return;
                lastShown[0] = percent;
                bar.progress(total > 0 ? Math.min(1f, (float) read / total) : 0f);
                bar.name(Text.line(Text.ACCENT, total > 0
                        ? "Downloading — " + percent + "%"
                        : "Downloading — " + Lore.bytes(read)));
            });
        }).whenComplete((result, error) -> onPlayerThread(player, () -> {
            if (error != null) {
                player.hideBossBar(bar);
                player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED + ">✖ Download failed: <"
                        + Text.BODY + ">" + Text.esc(rootMessage(error))));
                return;
            }
            bar.progress(1f);
            bar.color(BossBar.Color.GREEN);
            bar.name(Text.line(Text.GREEN, "Downloaded " + result.fileName()));
            // Let the full bar be seen before it goes away.
            player.getScheduler().runDelayed(plugin, ignored -> player.hideBossBar(bar), null, 40L);
            player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.GREEN + ">✔ Downloaded <"
                    + Text.SELECTED + ">" + Text.esc(result.fileName()) + " <" + Text.DIM + ">("
                    + Lore.bytes(result.bytes()) + ")<" + Text.BODY
                    + "> — restart the server to activate it."));
            player.sendMessage(Text.chat("<" + Text.YELLOW + ">  Nothing verified this file: "
                    + "it came from a link, not from Modrinth or Hangar."));
        }));
    }

    // --- log viewer ---

    /** Mint a one-player link to this plugin's log lines and send it as a clickable message. */
    public void sendLogLink(Player player, InstalledMenu.Row row) {
        String url = plugin.logViewer().grant(player, row.logName());
        if (url == null) {
            player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED
                    + ">The log viewer is off or could not bind its port — see log-viewer in config.yml."));
            return;
        }
        long minutes = Math.max(1, plugin.logViewer().linkFor().toMinutes());
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.BODY + ">Logs for <"
                + Text.SELECTED + ">" + Text.esc(row.logName()) + "<" + Text.BODY + ">: "
                + "<" + Text.ACCENT + "><u><click:open_url:'" + url + "'>" + url
                + "</click></u><" + Text.DIM + "> (only your computer can open it, "
                + minutes + " min)"));
    }

    // --- shared ---

    private record Done(PluginVersionFile file, VersionPicker.Match match, Downloader.Result result) {
    }

    private void record(me.sirborb.plugincloset.model.Source source, String sourceId,
                        String name, Done done) {
        plugin.manifest().put(new InstallManifest.InstalledEntry(
                source, sourceId, name,
                done.file().versionLabel(), Instant.now(), done.result().fileName()));
        try {
            plugin.manifest().save();
        } catch (Exception e) {
            // The jar is already in place; losing the manifest entry only costs the
            // "Installed" badge, so warn rather than pretend the install failed.
            plugin.getLogger().log(Level.WARNING, "Could not write installed.json", e);
        }
    }

    private void fail(Player player, String name, Throwable error) {
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED + ">✖ "
                + Text.esc(name) + " <" + Text.BODY + ">" + Text.esc(rootMessage(error))));
    }

    private void announce(Player player, String name, Done done) {
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.GREEN + ">✔ Downloaded <"
                + Text.SELECTED + ">" + Text.esc(name) + " v"
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
