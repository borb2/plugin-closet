package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.gui.config.MenuSpec;
import me.sirborb.plugincloset.install.InstallManifest.InstalledEntry;
import me.sirborb.plugincloset.gui.InstalledMenu.Row;
import me.sirborb.plugincloset.gui.InstalledMenu.Status;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One installed jar: what is known about it, and the four things that can be done to it —
 * update, change version, uninstall, read its log lines.
 *
 * <p>Layout comes from {@code guis/manage.yml}. The per-status colours are deliberately
 * read out of {@code installed.yml} instead of being duplicated here: one jar has one
 * health state, and an owner who recolours it should not have to do it twice.
 */
public final class ManageMenu implements ClickableMenu {

    private final PluginCloset plugin;
    private final Player player;
    private final MenuSpec spec;
    private final MenuSpec installedSpec;
    private final Inventory inventory;

    private final Row row;
    private final String latest;
    /** True while a download is in flight, so the buttons cannot be double-clicked. */
    private boolean busy;
    private Map<Integer, String> layout = Map.of();

    public ManageMenu(PluginCloset plugin, Player player, Row row, String latest) {
        this.plugin = plugin;
        this.player = player;
        this.row = row;
        this.latest = latest == null ? "" : latest;
        this.spec = plugin.guis().menu("manage");
        this.installedSpec = plugin.guis().menu("installed");
        this.inventory = Bukkit.createInventory(this, spec.size(),
                spec.title(Map.of("name", Text.esc(row.name()))));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player player() {
        return player;
    }

    public Row row() {
        return row;
    }

    public void open() {
        render();
        player.openInventory(inventory);
    }

    /** Called when an action finished: rescan happens in the installed menu, so go there. */
    public void backToList() {
        onPlayerThread(() -> new InstalledMenu(plugin, player).open());
    }

    @Override
    public void onClick(int slot, boolean right) {
        String key = layout.getOrDefault(slot, "");
        if (busy && !key.equals("close") && !key.equals("back")) return;

        switch (key) {
            case "back" -> backToList();
            case "close" -> player.closeInventory();
            case "update" -> {
                InstalledEntry entry = row.manifest();
                if (entry == null) return;
                busy = true;
                render();
                plugin.installer().updateToLatest(player, entry, this::backToList);
            }
            case "versions" -> {
                InstalledEntry entry = row.manifest();
                if (entry == null) return;
                Dialogs.chooseVersion(plugin, this, entry);
            }
            case "uninstall" -> Dialogs.confirmUninstall(plugin, this);
            case "logs" -> plugin.installer().sendLogLink(player, row);
            default -> {
                // the info panel and decoration
            }
        }
    }

    // --- rendering ---

    private void render() {
        inventory.clear();
        layout = spec.render(inventory, placeholders(), plugin.getLogger());
    }

    private Map<String, String> placeholders() {
        Status status = row.status();
        boolean known = row.manifest() != null;
        Map<String, String> ph = new HashMap<>();
        ph.put("name", Text.esc(row.name()));
        ph.put("version", Text.esc(row.version()));
        ph.put("latest", Text.esc(latest));
        ph.put("source", row.source());
        ph.put("file", Text.esc(row.jar().getFileName().toString()));
        ph.put("size", Lore.bytes(row.size()));
        ph.put("installed", Lore.relative(row.installed()));
        ph.put("loaded", row.loadedName() == null ? "" : Text.esc(row.loadedName()));
        ph.put("status_material", look(status, "material", "LIGHT_GRAY_DYE"));
        ph.put("status_color", look(status, "color", Text.MUTED));
        ph.put("status_label", look(status, "label", status.name()));
        ph.put("status_detail", look(status, "detail", ""));
        // A jar nobody here installed has no source to ask, so those two buttons go grey.
        ph.put("known", known ? "true" : "");
        ph.put("unknown", known ? "" : "true");
        ph.put("outdated", status == Status.OUTDATED ? "true" : "");
        ph.put("busy", busy ? "true" : "");
        ph.put("not_busy", busy ? "" : "true");
        ph.put("logs", plugin.logViewer().running() ? "true" : "");
        ph.put("no_logs", plugin.logViewer().running() ? "" : "true");
        return ph;
    }

    /** {@code status.<name>.<field>} out of installed.yml, so the two menus agree. */
    private String look(Status status, String field, String fallback) {
        return installedSpec.string("status." + status.name().toLowerCase(Locale.ROOT) + "."
                + field, fallback);
    }

    void onPlayerThread(Runnable task) {
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }
}
