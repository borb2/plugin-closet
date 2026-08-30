package me.sirborb.plugincloset;

import me.sirborb.plugincloset.api.HangarClient;
import me.sirborb.plugincloset.api.ModrinthClient;
import me.sirborb.plugincloset.api.PluginIndex;
import me.sirborb.plugincloset.api.SearchCache;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.command.PluginClosetCommand;
import me.sirborb.plugincloset.gui.BrowseMenu;
import me.sirborb.plugincloset.install.Downloader;
import me.sirborb.plugincloset.install.InstallManifest;
import me.sirborb.plugincloset.install.Installer;
import me.sirborb.plugincloset.platform.RuntimePlatform;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.time.Duration;
import java.util.logging.Level;

public final class PluginCloset extends JavaPlugin implements Listener {

    private PluginIndex index;
    private InstallManifest manifest;
    private Downloader downloader;
    private Installer installer;
    private SourceClient.Sort defaultSort;
    private boolean requireConfirmation;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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

        Path pluginsDir = getDataFolder().toPath().getParent();
        downloader = new Downloader(
                pluginsDir,
                getDataFolder().toPath().resolve("tmp"),
                userAgent,
                config.getInt("max-concurrent-downloads", 3));
    }

    public PluginIndex index() {
        return index;
    }

    public InstallManifest manifest() {
        return manifest;
    }

    public Downloader downloader() {
        return downloader;
    }

    public Installer installer() {
        return installer;
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
        if (!(event.getInventory().getHolder() instanceof BrowseMenu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getInventory()) return;
        menu.onClick(event.getRawSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof BrowseMenu) {
            event.setCancelled(true);
        }
    }
}
