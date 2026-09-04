package me.sirborb.plugincloset;

import me.sirborb.plugincloset.api.HangarClient;
import me.sirborb.plugincloset.api.ModrinthClient;
import me.sirborb.plugincloset.api.PluginIndex;
import me.sirborb.plugincloset.api.SearchCache;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.command.PluginClosetCommand;
import me.sirborb.plugincloset.gui.ClickableMenu;
import me.sirborb.plugincloset.gui.config.GuiConfig;
import me.sirborb.plugincloset.install.Downloader;
import me.sirborb.plugincloset.install.InstallManifest;
import me.sirborb.plugincloset.install.Installer;
import me.sirborb.plugincloset.platform.RuntimePlatform;
import me.sirborb.plugincloset.web.LogViewer;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;

public final class PluginCloset extends JavaPlugin implements Listener {

    private PluginIndex index;
    private GuiConfig guis;
    private InstallManifest manifest;
    private Downloader downloader;
    private Installer installer;
    private LogViewer logViewer;
    private SourceClient.Sort defaultSort;
    private boolean requireConfirmation;
    private Instant startedAt;

    @Override
    public void onEnable() {
        startedAt = Instant.now();
        saveDefaultConfig();
        logViewer = new LogViewer(this);
        reload();

        manifest = new InstallManifest(getDataFolder().toPath().resolve("installed.json"));
        try {
            manifest.load();
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not read installed.json; starting empty", e);
        }
        installer = new Installer(this);

        getServer().getPluginManager().registerEvents(this, this);
        PluginClosetCommand.register(this);

        getLogger().info("Ready on " + RuntimePlatform.describe()
                + " (downloads resolve as " + RuntimePlatform.current() + ")");
    }

    @Override
    public void onDisable() {
        if (logViewer != null) logViewer.stop();
    }

    /** Rebuild everything driven by config. Called on enable and by /plugincloset reload. */
    public void reload() {
        reloadConfig();
        var config = getConfig();

        String userAgent = config.getString("sources.modrinth.user-agent",
                "PluginCloset/1.0 (+set-your-contact-here)");
        var modrinth = new ModrinthClient(
                config.getBoolean("sources.modrinth.enabled", true), userAgent);
        var hangar = new HangarClient(
                config.getBoolean("sources.hangar.enabled", true),
                userAgent,
                config.getString("sources.hangar.api-key", ""));

        var cache = new SearchCache(Duration.ofMinutes(config.getInt("cache-ttl-minutes", 15)));
        index = new PluginIndex(modrinth, hangar, cache, getLogger());

        defaultSort = SourceClient.Sort.parse(
                config.getString("default-sort"), SourceClient.Sort.RELEVANCE);
        requireConfirmation = config.getBoolean("require-confirmation", false);
        guis = new GuiConfig(this);

        Path pluginsDir = getDataFolder().toPath().getParent();
        downloader = new Downloader(
                pluginsDir,
                getDataFolder().toPath().resolve("tmp"),
                userAgent,
                config.getInt("max-concurrent-downloads", 3),
                asyncExecutor());

        logViewer.reload();
    }

    /** The read-only web log viewer. Never null; ask {@link LogViewer#running()} first. */
    public LogViewer logViewer() {
        return logViewer;
    }

    /** The editable menu layouts under {@code guis/}. */
    public GuiConfig guis() {
        return guis;
    }

    public PluginIndex index() {
        return index;
    }

    public InstallManifest manifest() {
        return manifest;
    }

    /** The server's plugins folder, i.e. the parent of this plugin's own data folder. */
    public Path pluginsDir() {
        return getDataFolder().toPath().toAbsolutePath().normalize().getParent();
    }

    /**
     * When this plugin enabled. A jar installed after this cannot have been loaded yet, so
     * the installed-plugins menu can tell "waiting for a restart" from "refused to load".
     */
    public Instant startedAt() {
        return startedAt;
    }

    public Downloader downloader() {
        return downloader;
    }

    public Installer installer() {
        return installer;
    }

    /**
     * Bukkit's async scheduler as an {@link Executor}. This is the only scheduler that may
     * block on Folia, and it is deliberately not the HTTP client's own pool.
     */
    public Executor asyncExecutor() {
        return task -> Bukkit.getAsyncScheduler().runNow(this, ignored -> task.run());
    }

    public SourceClient.Sort defaultSort() {
        return defaultSort;
    }

    public boolean requireConfirmation() {
        return requireConfirmation;
    }

    // --- GUI events ---

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // Cancel first, ask questions later: nothing in this menu is ever takeable.
        if (!(event.getInventory().getHolder() instanceof ClickableMenu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) return;
        menu.onClick(event.getRawSlot(), event.isRightClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ClickableMenu) {
            event.setCancelled(true);
        }
    }
}
