package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.install.InstallManifest;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The browse GUI: a 54-slot chest, 36 listing slots and a control strip along the bottom.
 *
 * <p>ponytail: the menu *is* the {@link ClickableMenu}, so each open menu carries its own
 * state and the click listener recovers it with one instanceof. No UUID map, and nothing to
 * clean up on close.
 */
public final class BrowseMenu implements ClickableMenu {

    public static final int LISTING_SLOTS = 36;

    // Bottom row. Everything from 36 up is filled first, so the controls read as one strip
    // rather than floating in an empty half of the chest.
    private static final int SLOT_SEARCH = 45;
    private static final int SLOT_SORT = 46;
    private static final int SLOT_FILTER = 47;
    private static final int SLOT_PREV = 48;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_INSTALLED = 51;
    private static final int SLOT_CLOSE = 53;

    private final PluginCloset plugin;
    private final Player player;
    private final Inventory inventory;

    private String query = "";
    private SourceClient.Sort sort;
    /** Index into {@link EggIcons#filters()}; -1 means every platform. */
    private int filter = -1;
    private int page;
    private List<PluginListing> results = List.of();
    private boolean loading;

    public BrowseMenu(PluginCloset plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.sort = plugin.defaultSort();
        this.inventory = Bukkit.createInventory(this, 54,
                Text.of("<b><" + Text.ACCENT + ">Plugin Closet"));
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

    @Override
    public void onClick(int slot, boolean right) {
        if (slot < 0 || slot >= 54) return;

        if (slot < LISTING_SLOTS) {
            if (slot < results.size()) plugin.installer().begin(this, results.get(slot));
            return;
        }
        switch (slot) {
            case SLOT_FILTER -> {
                filter = Lore.cycle(filter, EggIcons.filters().length, right);
                page = 0;
                refresh();
            }
            case SLOT_SORT -> {
                sort = right ? sort.prev() : sort.next();
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
            // Next tick, never inside the click event: opening an inventory while the
            // server is still resolving the old one is how ghost items happen.
            case SLOT_INSTALLED -> onPlayerThread(() -> new InstalledMenu(plugin, player).open());
            case SLOT_CLOSE -> player.closeInventory();
            default -> {
                // page indicator and filler: nothing to do
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
        plugin.index().search(query, sort, selected(), page, LISTING_SLOTS)
                .whenComplete((listings, error) -> onPlayerThread(() -> {
                    loading = false;
                    if (error != null) {
                        results = List.of();
                        player.sendMessage(Text.chat("<" + Text.RED + ">Search failed: "
                                + Text.esc(rootMessage(error))));
                    } else {
                        results = listings;
                    }
                    render();
                }));
    }

    /** The filter as the index expects it: empty for "all", otherwise the one platform. */
    private Set<Platform> selected() {
        return filter < 0 ? Set.of() : Set.of(EggIcons.filters()[filter]);
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
                inventory.setItem(i, i == 22
                        ? Items.simple(Material.CLOCK, Text.ACCENT, "Searching...")
                        : null);
            } else if (i < results.size()) {
                inventory.setItem(i, listingItem(results.get(i)));
            }
        }
        if (!loading && results.isEmpty()) {
            inventory.setItem(22, Items.simple(Material.BARRIER, Text.MUTED, "No results"));
        }

        for (int i = LISTING_SLOTS; i < 54; i++) {
            inventory.setItem(i, Items.filler());
        }
        boolean hasPrev = page > 0;
        boolean hasNext = results.size() >= LISTING_SLOTS;
        inventory.setItem(SLOT_SEARCH, searchItem());
        inventory.setItem(SLOT_SORT, sortItem());
        inventory.setItem(SLOT_FILTER, filterItem());
        inventory.setItem(SLOT_PREV, Items.simple(hasPrev ? Material.ARROW : Material.GRAY_DYE,
                hasPrev ? Text.ACCENT : Text.DIM, "Previous Page"));
        inventory.setItem(SLOT_PAGE, Items.simple(Material.MAP, Text.ACCENT, "Page " + (page + 1)));
        inventory.setItem(SLOT_NEXT, Items.simple(hasNext ? Material.ARROW : Material.GRAY_DYE,
                hasNext ? Text.ACCENT : Text.DIM, "Next Page"));
        inventory.setItem(SLOT_INSTALLED, installedItem());
        inventory.setItem(SLOT_CLOSE, Items.simple(Material.BARRIER, Text.RED, "Close"));
    }

    private ItemStack listingItem(PluginListing listing) {
        ItemStack item = new ItemStack(EggIcons.forListing(listing.platforms()));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(Text.ACCENT, listing.name()));

        List<Component> lore = new ArrayList<>();
        if (!listing.authors().isEmpty()) {
            lore.add(Text.line(Text.DIM, "by " + String.join(", ", listing.authors())));
        }
        lore.add(Component.empty());
        for (String line : Lore.wrap(listing.description(), 40)) {
            lore.add(Text.line(Text.BODY, line));
        }
        lore.add(Component.empty());
        lore.add(Text.of("<" + Text.YELLOW + ">⬇ " + Lore.downloads(listing.downloads())
                + "   <" + Text.PINK + ">★ " + Lore.downloads(listing.follows())));
        lore.add(field("Updated", Lore.relative(listing.dateUpdated())));
        // Modrinth's search gives a version *id*, not a label, so that line is simply
        // omitted rather than showing an opaque id.
        if (listing.latestVersionLabel() != null) {
            lore.add(field("Latest", listing.latestVersionLabel()));
        }
        lore.add(field("MC", Lore.versions(listing.supportedMcVersions(), 3)));
        lore.add(field("Source", listing.source().display()));

        Optional<InstallManifest.InstalledEntry> installed = plugin.manifest().get(listing);
        installed.ifPresent(entry -> {
            lore.add(Component.empty());
            lore.add(Text.line(Text.GREEN, "✔ Installed " + entry.installedVersion()));
        });

        lore.add(Component.empty());
        lore.add(Text.line(Text.ACCENT, "Click to download"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * One item for the whole platform filter. The lore is the list itself, with the current
     * entry in white, so a click scrolls it rather than toggling one of eight buttons.
     */
    private ItemStack filterItem() {
        Platform[] all = EggIcons.filters();
        Platform current = filter < 0 ? null : all[filter];

        ItemStack item = new ItemStack(current == null ? Material.CHEST : EggIcons.of(current));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(Text.ACCENT,
                "Platform: " + (current == null ? "All" : title(current.name()))));

        List<Component> lore = new ArrayList<>();
        lore.add(Items.option("All", current == null));
        for (Platform p : all) {
            lore.add(Items.option(title(p.name()), p == current));
        }
        if (current != null && current.hangarName() == null) {
            lore.add(Component.empty());
            lore.add(Text.line(Text.DIM, "Modrinth only"));
        }
        lore.add(Component.empty());
        lore.add(Items.scrollHint());
        meta.lore(lore);
        meta.setEnchantmentGlintOverride(current != null);
        item.setItemMeta(meta);
        return item;
    }

    /** Same scroll-select shape as the platform filter, over the sort orders. */
    private ItemStack sortItem() {
        ItemStack item = new ItemStack(Material.COMPARATOR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(Text.ACCENT, "Sort: " + sort.display()));

        List<Component> lore = new ArrayList<>();
        for (SourceClient.Sort s : SourceClient.Sort.values()) {
            lore.add(Items.option(s.display(), s == sort));
        }
        lore.add(Component.empty());
        lore.add(Items.scrollHint());
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack searchItem() {
        ItemStack item = new ItemStack(Material.OAK_SIGN);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(Text.ACCENT, "Search"));
        meta.lore(List.of(
                query.isEmpty()
                        ? Text.line(Text.MUTED, "Showing everything")
                        : Text.line(Text.SELECTED, "“" + query + "”"),
                Component.empty(),
                Text.line(Text.DIM, "Click to type a search")));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack installedItem() {
        ItemStack item = new ItemStack(Material.COMMAND_BLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(Text.ACCENT, "Installed Plugins"));
        meta.lore(List.of(
                Text.line(Text.BODY, plugin.manifest().all().size()
                        + " installed by Plugin Closet"),
                Component.empty(),
                Text.line(Text.DIM, "Click to review and check for updates")));
        item.setItemMeta(meta);
        return item;
    }

    private static Component field(String label, String value) {
        return Text.of("<" + Text.MUTED + ">" + label + ": <" + Text.BODY + ">" + Text.esc(value));
    }

    static String title(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
