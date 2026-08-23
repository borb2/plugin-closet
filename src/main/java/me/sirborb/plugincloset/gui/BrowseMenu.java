package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.install.InstallManifest;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The browse GUI: a 54-slot chest, 36 listing slots, a filter row and a control row.
 *
 * <p>ponytail: the menu *is* the {@link InventoryHolder}, so each open menu carries its own
 * state and the click listener recovers it with one instanceof. No UUID map, and nothing to
 * clean up on close.
 */
public final class BrowseMenu implements InventoryHolder {

    public static final int LISTING_SLOTS = 36;

    // Control row slots.
    private static final int SLOT_SORT = 44;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_SEARCH = 47;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_CLOSE = 51;
    private static final int SLOT_NEXT = 53;

    private final PluginCloset plugin;
    private final Player player;
    private final Inventory inventory;

    private String query = "";
    private SourceClient.Sort sort;
    private final Set<Platform> platforms = EnumSet.noneOf(Platform.class);
    private int page;
    private List<PluginListing> results = List.of();
    private boolean loading;

    public BrowseMenu(PluginCloset plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.sort = plugin.defaultSort();
        this.inventory = Bukkit.createInventory(this, 54,
                Component.text("Plugin Closet", NamedTextColor.DARK_AQUA));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Player player() {
        return player;
    }

    public void open() {
        render();
        player.openInventory(inventory);
        refresh();
    }

    // --- click handling, dispatched from PluginCloset's listener ---

    public void onClick(int slot) {
        if (slot < 0 || slot >= 54) return;

        if (slot < LISTING_SLOTS) {
            if (slot < results.size()) plugin.installer().begin(this, results.get(slot));
            return;
        }
        Platform[] row = EggIcons.filterRow();
        if (slot >= 36 && slot - 36 < row.length) {
            Platform p = row[slot - 36];
            if (!platforms.remove(p)) platforms.add(p);
            page = 0;
            refresh();
            return;
        }
        switch (slot) {
            case SLOT_SORT -> {
                sort = sort.next();
                page = 0;
                refresh();
            }
            case SLOT_SEARCH -> Dialogs.search(plugin, this);
            case SLOT_PREV -> {
                if (page > 0) {
                    page--;
                    refresh();
                }
            }
            case SLOT_NEXT -> {
                // No total count from either API, so "next" is offered while the page is full.
                if (results.size() >= LISTING_SLOTS) {
                    page++;
                    refresh();
                }
            }
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                // page indicator and spacers: nothing to do
            }
        }
    }

    public void setQuery(String query) {
        this.query = query == null ? "" : query.trim();
        this.page = 0;
        refresh();
    }

    /** Re-render after an install so the "Installed" line appears without reopening. */
    public void redraw() {
        render();
    }

    // --- loading ---

    private void refresh() {
        loading = true;
        render();
        plugin.index().search(query, sort, platforms, page, LISTING_SLOTS)
                .whenComplete((listings, error) -> onPlayerThread(() -> {
                    loading = false;
                    if (error != null) {
                        results = List.of();
                        player.sendMessage(Component.text("Search failed: " + rootMessage(error),
                                NamedTextColor.RED));
                    } else {
                        results = listings;
                    }
                    render();
                }));
    }

    /**
     * Run on the thread that owns this player. On Folia that is their region thread; the
     * entity scheduler is correct on both Paper and Folia, which the global scheduler is not.
     */
    private void onPlayerThread(Runnable task) {
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    // --- rendering ---

    private void render() {
        inventory.clear();
        for (int i = 0; i < LISTING_SLOTS; i++) {
            if (loading) {
                inventory.setItem(i, i == 22 ? simple(Material.CLOCK, "Searching...") : null);
            } else if (i < results.size()) {
                inventory.setItem(i, listingItem(results.get(i)));
            }
        }
        if (!loading && results.isEmpty()) {
            inventory.setItem(22, simple(Material.BARRIER, "No results"));
        }

        Platform[] row = EggIcons.filterRow();
        for (int i = 0; i < row.length; i++) {
            inventory.setItem(36 + i, filterItem(row[i]));
        }
        inventory.setItem(SLOT_SORT, sortItem());
        inventory.setItem(SLOT_PREV, page > 0
                ? simple(Material.ARROW, "Previous page")
                : simple(Material.GRAY_DYE, "Previous page"));
        inventory.setItem(SLOT_SEARCH, searchItem());
        inventory.setItem(SLOT_PAGE, simple(Material.MAP, "Page " + (page + 1)));
        inventory.setItem(SLOT_CLOSE, simple(Material.BARRIER, "Close"));
        inventory.setItem(SLOT_NEXT, results.size() >= LISTING_SLOTS
                ? simple(Material.ARROW, "Next page")
                : simple(Material.GRAY_DYE, "Next page"));
    }

    private ItemStack listingItem(PluginListing listing) {
        ItemStack item = new ItemStack(EggIcons.forListing(listing.platforms()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(listing.name(), NamedTextColor.AQUA));

        List<Component> lore = new ArrayList<>();
        if (!listing.authors().isEmpty()) {
            lore.add(plain("by " + String.join(", ", listing.authors()), NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        for (String line : Lore.wrap(listing.description(), 40)) {
            lore.add(plain(line, NamedTextColor.WHITE));
        }
        lore.add(Component.empty());
        lore.add(plain("⬇ " + Lore.downloads(listing.downloads()), NamedTextColor.YELLOW)
                .append(plain("   ★ " + Lore.downloads(listing.follows()), NamedTextColor.LIGHT_PURPLE)));
        lore.add(plain("Updated: " + Lore.relative(listing.dateUpdated()), NamedTextColor.GRAY));
        // Modrinth's search gives a version *id*, not a label, so that line is simply
        // omitted rather than showing an opaque id.
        if (listing.latestVersionLabel() != null) {
            lore.add(plain("Latest: " + listing.latestVersionLabel(), NamedTextColor.GRAY));
        }
        lore.add(plain("MC: " + Lore.versions(listing.supportedMcVersions(), 3), NamedTextColor.GRAY));
        lore.add(plain("Source: " + listing.source().display(), NamedTextColor.DARK_GRAY));

        Optional<InstallManifest.InstalledEntry> installed = plugin.manifest().get(listing);
        installed.ifPresent(entry -> {
            lore.add(Component.empty());
            lore.add(plain("Installed: " + entry.installedVersion(), NamedTextColor.GREEN));
        });

        lore.add(Component.empty());
        lore.add(plain("Click to download", NamedTextColor.YELLOW));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack filterItem(Platform platform) {
        boolean active = platforms.contains(platform);
        ItemStack item = new ItemStack(EggIcons.of(platform));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name(platform), active ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        meta.lore(List.of(
                plain(active ? "Filtering by this platform" : "Click to filter by this platform",
                        NamedTextColor.DARK_GRAY),
                plain(platform.hangarName() == null ? "Modrinth only" : "Modrinth + Hangar",
                        NamedTextColor.DARK_GRAY)));
        meta.setEnchantmentGlintOverride(active);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack sortItem() {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Sort: " + sort.display(), NamedTextColor.GOLD));
        meta.lore(List.of(plain("Click to cycle", NamedTextColor.DARK_GRAY)));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack searchItem() {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Search", NamedTextColor.GOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(plain(query.isEmpty() ? "Showing everything" : "Query: " + query, NamedTextColor.GRAY));
        lore.add(plain("Click to type a search", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack simple(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain(name, NamedTextColor.WHITE));
        item.setItemMeta(meta);
        return item;
    }

    /** Item text is italic by default in vanilla; turn that off everywhere. */
    private static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static String name(Platform platform) {
        String n = platform.name();
        return n.charAt(0) + n.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
