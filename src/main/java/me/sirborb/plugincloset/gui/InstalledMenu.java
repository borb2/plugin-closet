package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.VersionPicker;
import me.sirborb.plugincloset.gui.config.ItemSpec;
import me.sirborb.plugincloset.gui.config.MenuSpec;
import me.sirborb.plugincloset.gui.config.Slots;
import me.sirborb.plugincloset.install.InstallManifest.InstalledEntry;
import me.sirborb.plugincloset.platform.RuntimePlatform;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Every jar in the server's plugins folder, with a live health check on each one. Layout
 * and per-status colours come from {@code guis/installed.yml}.
 *
 * <p>The folder is the source of truth, not the manifest: a plugin installed by hand is
 * still a plugin the owner wants to see. The manifest only adds what the folder cannot
 * say — where a jar came from, and therefore whether something newer exists.
 *
 * <p>"Is it working" is answered from two facts: whether the jar is still on disk, and
 * whether a loaded plugin was actually loaded *from* that jar. A jar that arrived after
 * this server booted is waiting for a restart, not broken — those two cases look identical
 * to the plugin manager and must not both be reported as failures.
 */
public final class InstalledMenu implements ClickableMenu {

    /** The four health states. Their look lives in the config, keyed by lower-case name. */
    private enum Status {OK, OUTDATED, BROKEN, PENDING}

    /** One jar, plus whatever the manifest and the plugin manager know about it. */
    private record Row(Path jar, String name, String version, String source,
                       Instant installed, InstalledEntry manifest, Status status) {
    }

    private final PluginCloset plugin;
    private final Player player;
    private final MenuSpec spec;
    private final int[] entrySlots;
    private final Inventory inventory;

    /** Latest installable version per manifest key; "" means the lookup failed. */
    private final Map<String, String> latest = new ConcurrentHashMap<>();
    private List<Row> entries = List.of();
    private boolean checking;
    /** Slot to item key for what is currently drawn; the click map, rebuilt every render. */
    private Map<Integer, String> layout = Map.of();

    public InstalledMenu(PluginCloset plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.spec = plugin.guis().menu("installed");
        this.entrySlots = Slots.parse(spec.string("entry-slots", "0-35"));
        this.inventory = Bukkit.createInventory(this, spec.size(), spec.title(Map.of()));
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
        switch (layout.getOrDefault(slot, "")) {
            // Deferred for the same reason BrowseMenu defers opening this one.
            case "back" -> onPlayerThread(() -> new BrowseMenu(plugin, player).open());
            case "refresh", "refreshing" -> {
                if (!checking) {
                    // Drop the cached version lookups so this really re-asks the sources.
                    plugin.index().cache().clear();
                    latest.clear();
                    rescan();
                    checkUpdates();
                }
            }
            case "close" -> player.closeInventory();
            default -> {
                // entries and decoration are read-only
            }
        }
    }

    /**
     * List the plugins folder, score every jar once, and order it broken-first: if
     * something is wrong it should be the first thing in the chest. Scoring touches the
     * disk, so it happens here rather than inside a comparator or a render loop.
     */
    private void rescan() {
        Map<Path, Plugin> loaded = loadedPlugins();
        Map<Path, InstalledEntry> manifest = new HashMap<>();
        for (InstalledEntry e : plugin.manifest().all()) {
            if (e.jarFileName() != null && !e.jarFileName().isBlank()) {
                manifest.put(path(e.jarFileName()), e);
            }
        }

        List<Row> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(plugin.pluginsDir())) {
            files.map(p -> p.toAbsolutePath().normalize())
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .forEach(p -> rows.add(row(p, loaded.get(p), manifest.get(p))));
        } catch (IOException e) {
            log().warning("Could not list the plugins folder: " + e.getMessage());
        }
        // A jar we installed that has since gone missing still deserves a red entry.
        manifest.forEach((jar, entry) -> {
            if (!Files.exists(jar)) rows.add(row(jar, null, entry));
        });

        rows.sort((a, b) -> {
            int byStatus = Integer.compare(a.status().ordinal(), b.status().ordinal());
            return byStatus != 0 ? byStatus : a.name().compareToIgnoreCase(b.name());
        });
        entries = List.copyOf(rows);
    }

    private Path path(String jarFileName) {
        return plugin.pluginsDir().resolve(jarFileName).toAbsolutePath().normalize();
    }

    private Row row(Path jar, Plugin loaded, InstalledEntry entry) {
        String file = jar.getFileName().toString();
        String name = entry != null ? entry.displayName()
                : loaded != null ? loaded.getName()
                : file.substring(0, file.length() - ".jar".length());
        // A blank value drops its lore line, so unknown jars simply show less.
        String version = entry != null ? entry.installedVersion()
                : loaded != null ? loaded.getPluginMeta().getVersion()
                : "";
        Instant installed = entry != null ? entry.installedAt() : modified(jar);
        return new Row(jar, name, version == null ? "" : version,
                entry != null ? entry.source().display() : "",
                installed, entry, status(jar, loaded, entry, installed));
    }

    /** When the jar landed, for jars we did not install ourselves. */
    private Instant modified(Path jar) {
        try {
            return Files.getLastModifiedTime(jar).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    // --- update check ---

    private void checkUpdates() {
        checking = true;
        render();

        List<CompletableFuture<?>> calls = new ArrayList<>();
        for (Row row : entries) {
            InstalledEntry entry = row.manifest();
            if (entry == null) continue;        // nothing to ask: we don't know the source
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
     * The jar each loaded plugin came from. The code source is the only reliable link back
     * to a file: {@code JavaPlugin#getFile} is protected, and plugin display names rarely
     * match their jar name.
     */
    private Map<Path, Plugin> loadedPlugins() {
        Map<Path, Plugin> jars = new HashMap<>();
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            try {
                var source = p.getClass().getProtectionDomain().getCodeSource();
                if (source == null) continue;
                jars.put(Path.of(source.getLocation().toURI()).toAbsolutePath().normalize(), p);
            } catch (Exception ignored) {
                // Some loaders hide the code source; that plugin simply goes uncounted.
            }
        }
        return jars;
    }

    private Status status(Path jar, Plugin loaded, InstalledEntry entry, Instant installed) {
        if (!Files.exists(jar)) return Status.BROKEN;
        if (loaded == null) {
            return installed.isAfter(plugin.startedAt()) ? Status.PENDING : Status.BROKEN;
        }
        // Running. Only a known source can say whether something newer exists.
        if (entry == null) return Status.OK;
        String newest = latest.get(entry.key());
        if (newest == null || newest.isEmpty()) return Status.OK;
        return newest.equals(entry.installedVersion()) ? Status.OK : Status.OUTDATED;
    }

    /** {@code status.<name>.<field>} out of installed.yml, with the shipped value as fallback. */
    private String look(Status status, String field, String fallback) {
        return spec.string("status." + status.name().toLowerCase(Locale.ROOT) + "." + field,
                fallback);
    }

    // --- rendering ---

    private void render() {
        inventory.clear();
        ItemSpec entry = spec.item("entry");
        for (int i = 0; i < entrySlots.length && i < entries.size(); i++) {
            int slot = entrySlots[i];
            if (entry == null || slot < 0 || slot >= inventory.getSize()) continue;
            inventory.setItem(slot, entry.build(entryPlaceholders(entries.get(i)), log()));
        }
        layout = spec.render(inventory, placeholders(), log());
    }

    private Map<String, String> placeholders() {
        Map<String, String> ph = new HashMap<>();
        ph.put("count", Integer.toString(entries.size()));
        ph.put("checking", checking ? "true" : "");
        ph.put("not_checking", checking ? "" : "true");
        ph.put("empty", entries.isEmpty() ? "true" : "");
        ph.put("status_counts", statusCounts());
        return ph;
    }

    /** One line per health state that has anything in it, as a multi-line placeholder. */
    private String statusCounts() {
        int[] counts = new int[Status.values().length];
        for (Row e : entries) counts[e.status().ordinal()]++;

        StringBuilder out = new StringBuilder();
        for (Status s : Status.values()) {
            if (counts[s.ordinal()] == 0) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append('<').append(look(s, "color", Text.MUTED)).append(">● <")
                    .append(Text.BODY).append('>').append(counts[s.ordinal()]).append(' ')
                    .append(look(s, "label", s.name()).toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? "<" + Text.DIM + ">Nothing to report" : out.toString();
    }

    private Map<String, String> entryPlaceholders(Row row) {
        Status status = row.status();
        Map<String, String> ph = new HashMap<>();
        ph.put("name", Text.esc(row.name()));
        ph.put("version", Text.esc(row.version()));
        // Only worth a line when there is actually something newer to install.
        ph.put("latest", status == Status.OUTDATED
                ? Text.esc(latest.get(row.manifest().key())) : "");
        ph.put("source", row.source());
        ph.put("file", Text.esc(row.jar().getFileName().toString()));
        ph.put("installed", Lore.relative(row.installed()));
        ph.put("status_material", checking
                ? spec.string("checking-material", "CLOCK")
                : look(status, "material", "LIGHT_GRAY_DYE"));
        ph.put("status_color", look(status, "color", Text.MUTED));
        ph.put("status_label", look(status, "label", status.name()));
        ph.put("status_detail", look(status, "detail", ""));
        return ph;
    }

    private java.util.logging.Logger log() {
        return plugin.getLogger();
    }

    private void onPlayerThread(Runnable task) {
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }
}
