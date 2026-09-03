package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.install.InstallManifest.InstalledEntry;
import me.sirborb.plugincloset.platform.RuntimePlatform;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything Plugin Closet has installed, with a live health check on each entry.
 *
 * <p>"Is it working" is answered from two facts the manifest cannot know on its own: whether
 * the jar is still on disk, and whether a loaded plugin was actually loaded *from* that jar.
 * A jar that arrived after this server booted is waiting for a restart, not broken — those
 * two cases look identical to the plugin manager and must not both be reported as failures.
 */
public final class InstalledMenu implements ClickableMenu {

    /** ponytail: one page. 36 Plugin-Closet installs is already an unusual server. */
    private static final int ENTRY_SLOTS = 36;

    private static final int SLOT_BACK = 45;
    private static final int SLOT_SUMMARY = 49;
    private static final int SLOT_REFRESH = 51;
    private static final int SLOT_CLOSE = 53;

    private enum Status {
        OK(Material.LIME_DYE, Text.GREEN, "Up to date"),
        OUTDATED(Material.YELLOW_DYE, Text.YELLOW, "Update available"),
        BROKEN(Material.RED_DYE, Text.RED, "Not loading"),
        PENDING(Material.LIGHT_BLUE_DYE, Text.BLUE, "Restart to activate"),
        UNCHECKED(Material.LIGHT_GRAY_DYE, Text.MUTED, "Not checked");

        final Material icon;
        final String hex;
        final String label;

        Status(Material icon, String hex, String label) {
            this.icon = icon;
            this.hex = hex;
            this.label = label;
        }
    }

    private final PluginCloset plugin;
    private final Player player;
    private final Inventory inventory;

    /** Latest installable version per manifest key; "" means the lookup failed. */
    private final Map<String, String> latest = new ConcurrentHashMap<>();
    private List<InstalledEntry> entries = List.of();
    /** Manifest key to health, recomputed by {@link #rescan()} rather than per lookup. */
    private Map<String, Status> statuses = Map.of();
    private boolean checking;

    public InstalledMenu(PluginCloset plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.inventory = Bukkit.createInventory(this, 54,
                Text.of("<b><" + Text.ACCENT + ">Installed Plugins"));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        rescan();
        render();
        player.openInventory(inventory);
        checkUpdates();
    }

    @Override
    public void onClick(int slot, boolean right) {
        switch (slot) {
            // Deferred for the same reason BrowseMenu defers opening this one.
            case SLOT_BACK -> onPlayerThread(() -> new BrowseMenu(plugin, player).open());
            case SLOT_REFRESH -> {
                if (!checking) {
                    // Drop the cached version lookups so this really re-asks the sources.
                    plugin.index().cache().clear();
                    latest.clear();
                    rescan();
                    checkUpdates();
                }
            }
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                // entries and filler are read-only
            }
        }
    }

    /**
     * Re-read the manifest, score every entry once, and order it broken-first: if something
     * is wrong it should be the first thing in the chest. Scoring touches the disk, so it
     * happens here rather than inside a comparator or a render loop.
     */
    private void rescan() {
        Set<Path> loaded = loadedJars();
        List<InstalledEntry> all = new ArrayList<>(plugin.manifest().all());
        Map<String, Status> scored = new HashMap<>();
        for (InstalledEntry e : all) scored.put(e.key(), status(e, loaded));

        all.sort((a, b) -> {
            int byStatus = Integer.compare(scored.get(a.key()).ordinal(),
                    scored.get(b.key()).ordinal());
            return byStatus != 0 ? byStatus : a.displayName().compareToIgnoreCase(b.displayName());
        });
        entries = List.copyOf(all);
        statuses = Map.copyOf(scored);
    }

    private Status status(InstalledEntry entry) {
        return checking ? Status.UNCHECKED : statuses.getOrDefault(entry.key(), Status.UNCHECKED);
    }

    // --- update check ---

    private void checkUpdates() {
        checking = true;
        render();

        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (InstalledEntry entry : entries) {
            calls.add(plugin.index()
                    .versions(entry.source(), entry.sourceId(), RuntimePlatform.current())
                    .thenApply(files -> {
                        VersionPicker.Pick pick =
                                VersionPicker.pick(files, RuntimePlatform.minecraftVersion());
                        return pick.isEmpty() ? "" : pick.file().versionLabel();
                    })
                    // A source being down must not hide the rest of the list.
                    .exceptionally(t -> "")
                    .thenAccept(version -> latest.put(entry.key(), version)));
        }
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                .whenComplete((done, error) -> onPlayerThread(() -> {
                    checking = false;
                    rescan();
                    render();
                }));
    }

    // --- status ---

    /**
     * Absolute paths of the jars the server actually loaded a plugin from. The code source
     * is the only reliable link back to a file: {@code JavaPlugin#getFile} is protected, and
     * plugin display names rarely match their jar name.
     */
    private Set<Path> loadedJars() {
        Set<Path> jars = new HashSet<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            try {
                var source = p.getClass().getProtectionDomain().getCodeSource();
                if (source == null) continue;
                jars.add(Path.of(source.getLocation().toURI()).toAbsolutePath().normalize());
            } catch (Exception ignored) {
                // Some loaders hide the code source; that plugin simply goes uncounted.
            }
        }
        return jars;
    }

    private Status status(InstalledEntry entry, Set<Path> loaded) {
        Path jar = plugin.pluginsDir().resolve(entry.jarFileName()).normalize();
        if (!Files.exists(jar)) return Status.BROKEN;
        if (!loaded.contains(jar)) {
            return entry.installedAt().isAfter(plugin.startedAt())
                    ? Status.PENDING
                    : Status.BROKEN;
        }
        String newest = latest.get(entry.key());
        if (newest == null || newest.isEmpty()) return Status.UNCHECKED;
        return newest.equals(entry.installedVersion()) ? Status.OK : Status.OUTDATED;
    }

    // --- rendering ---

    private void render() {
        inventory.clear();
        for (int i = 0; i < ENTRY_SLOTS && i < entries.size(); i++) {
            inventory.setItem(i, entryItem(entries.get(i)));
        }
        if (entries.isEmpty()) {
            inventory.setItem(22, Items.labelled(Material.BARRIER, Text.MUTED, "Nothing installed yet",
                    List.of(Text.line(Text.DIM, "Plugins you install here will be listed."))));
        }

        for (int i = ENTRY_SLOTS; i < 54; i++) {
            inventory.setItem(i, Items.filler());
        }
        inventory.setItem(SLOT_BACK, Items.simple(Material.ARROW, Text.ACCENT, "Back to Browse"));
        inventory.setItem(SLOT_SUMMARY, summaryItem());
        inventory.setItem(SLOT_REFRESH, checking
                ? Items.simple(Material.CLOCK, Text.MUTED, "Checking...")
                : Items.labelled(Material.SPYGLASS, Text.ACCENT, "Check for Updates",
                        List.of(Text.line(Text.DIM, "Re-asks Modrinth and Hangar"))));
        inventory.setItem(SLOT_CLOSE, Items.simple(Material.BARRIER, Text.RED, "Close"));
    }

    private ItemStack entryItem(InstalledEntry entry) {
        Status status = status(entry);
        ItemStack item = new ItemStack(checking ? Material.CLOCK : status.icon);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(Text.ACCENT, entry.displayName()));

        List<Component> lore = new ArrayList<>();
        lore.add(field("Version", entry.installedVersion()));
        String newest = latest.get(entry.key());
        if (status == Status.OUTDATED) {
            lore.add(Text.of("<" + Text.MUTED + ">Latest: <" + Text.YELLOW + ">"
                    + Text.esc(newest)));
        }
        lore.add(field("Source", entry.source().display()));
        lore.add(field("File", entry.jarFileName()));
        lore.add(field("Installed", Lore.relative(entry.installedAt())));
        lore.add(Component.empty());
        lore.add(Text.of("<" + status.hex + ">● " + status.label));
        lore.add(Text.line(Text.DIM, detail(status)));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String detail(Status status) {
        return switch (status) {
            case OK -> "Loaded and running the newest build.";
            case OUTDATED -> "Reinstall from Browse to update.";
            case BROKEN -> "The jar is missing or the server refused it.";
            case PENDING -> "Downloaded, waiting on a server restart.";
            case UNCHECKED -> "Could not reach the source for a version.";
        };
    }

    private ItemStack summaryItem() {
        int[] counts = new int[Status.values().length];
        for (InstalledEntry e : entries) counts[status(e).ordinal()]++;

        List<Component> lore = new ArrayList<>();
        for (Status s : Status.values()) {
            if (counts[s.ordinal()] > 0) {
                lore.add(Text.of("<" + s.hex + ">● <" + Text.BODY + ">"
                        + counts[s.ordinal()] + " " + s.label.toLowerCase(java.util.Locale.ROOT)));
            }
        }
        if (lore.isEmpty()) lore.add(Text.line(Text.DIM, "Nothing to report"));
        return Items.labelled(Material.BOOK, Text.ACCENT,
                entries.size() + " Installed", lore);
    }

    private static Component field(String label, String value) {
        return Text.of("<" + Text.MUTED + ">" + label + ": <" + Text.BODY + ">" + Text.esc(value));
    }

    private void onPlayerThread(Runnable task) {
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }
}
