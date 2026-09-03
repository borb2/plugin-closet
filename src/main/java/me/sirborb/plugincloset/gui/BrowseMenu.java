package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.api.SourceClient;
import me.sirborb.plugincloset.gui.config.ItemSpec;
import me.sirborb.plugincloset.gui.config.MenuSpec;
import me.sirborb.plugincloset.gui.config.Slots;
import me.sirborb.plugincloset.install.InstallManifest;
import me.sirborb.plugincloset.model.Platform;
import me.sirborb.plugincloset.model.PluginListing;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The browse GUI. Layout, text and every item property come from {@code guis/browse.yml};
 * this class only supplies state and the placeholders that go with it.
 *
 * <p>ponytail: the menu *is* the {@link ClickableMenu}, so each open menu carries its own
 * state and the click listener recovers it with one instanceof. No UUID map, and nothing to
 * clean up on close.
 */
public final class BrowseMenu implements ClickableMenu {

    /** How long each platform icon is shown before the next one. */
    private static final long ROTATE_TICKS = 100L;

    private final PluginCloset plugin;
    private final Player player;
    private final MenuSpec spec;
    private final int[] listingSlots;
    private final Inventory inventory;

    private String query = "";
    private SourceClient.Sort sort;
    /** Index into {@link EggIcons#filters()}; -1 means every platform. */
    private int filter = -1;
    private int page;
    private List<PluginListing> results = List.of();
    private boolean loading;
    /** Ticks the rotating platform icons; one step per ROTATE_TICKS while open. */
    private int spin;
    /** Slot to item key for what is currently drawn; the click map, rebuilt every render. */
    private Map<Integer, String> layout = Map.of();

    public BrowseMenu(PluginCloset plugin, Player player) {
        this.plugin = plugin;
        this.player = player;
        this.spec = plugin.guis().menu("browse");
        this.listingSlots = Slots.parse(spec.string("listing-slots", "0-35"));
        this.sort = plugin.defaultSort();
        this.inventory = Bukkit.createInventory(this, spec.size(), spec.title(Map.of()));
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
        // Self-cancelling rather than hooked to InventoryCloseEvent: the menu already owns
        // its own state, and this way there is nothing to unregister if the player quits.
        player.getScheduler().runAtFixedRate(plugin, task -> {
            if (player.getOpenInventory().getTopInventory() != inventory) {
                task.cancel();
                return;
            }
            spin++;
            renderListings();
        }, null, ROTATE_TICKS, ROTATE_TICKS);
    }

    // --- click handling, dispatched from PluginCloset's listener ---

    @Override
    public void onClick(int slot, boolean right) {
        if (slot < 0 || slot >= inventory.getSize()) return;

        int index = listingIndex(slot);
        if (index >= 0) {
            if (index < results.size()) plugin.installer().begin(this, results.get(index));
            return;
        }
        switch (layout.getOrDefault(slot, "")) {
            case "filter" -> {
                filter = Lore.cycle(filter, EggIcons.filters().length, right);
                page = 0;
                refresh();
            }
            case "sort" -> {
                sort = right ? sort.prev() : sort.next();
                page = 0;
                refresh();
            }
            case "search" -> Dialogs.search(plugin, this);
            case "prev", "prev-off" -> {
                if (page > 0) {
                    page--;
                    refresh();
                }
            }
            case "next", "next-off" -> {
                // No total count from either API, so "next" is offered while the page is full.
                if (results.size() >= listingSlots.length) {
                    page++;
                    refresh();
                }
            }
            // Next tick, never inside the click event: opening an inventory while the
            // server is still resolving the old one is how ghost items happen.
            case "installed" -> onPlayerThread(() -> new InstalledMenu(plugin, player).open());
            case "close" -> player.closeInventory();
            default -> {
                // decoration: nothing to do
            }
        }
    }

    /** Which search result a slot holds, or -1 if the slot is not part of the grid. */
    private int listingIndex(int slot) {
        for (int i = 0; i < listingSlots.length; i++) {
            if (listingSlots[i] == slot) return i;
        }
        return -1;
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
        plugin.index().search(query, sort, selected(), page, listingSlots.length)
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
        renderListings();
        layout = spec.render(inventory, placeholders(), log());
    }

    /** Just the result grid. Redrawn on its own to rotate the platform icons. */
    private void renderListings() {
        ItemSpec listing = spec.item("listing");
        for (int i = 0; i < listingSlots.length; i++) {
            int slot = listingSlots[i];
            if (loading || listing == null || i >= results.size()
                    || slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            inventory.setItem(slot, listing.build(listingPlaceholders(results.get(i)), log()));
        }
    }

    private Map<String, String> placeholders() {
        Platform[] all = EggIcons.filters();
        Platform current = filter < 0 ? null : all[filter];
        boolean hasPrev = page > 0;
        boolean hasNext = results.size() >= listingSlots.length;

        List<String> platforms = new ArrayList<>();
        platforms.add("All");
        for (Platform p : all) platforms.add(p.display());

        List<String> sorts = new ArrayList<>();
        for (SourceClient.Sort s : SourceClient.Sort.values()) sorts.add(s.display());

        Map<String, String> ph = new HashMap<>();
        ph.put("query", Text.esc(query));
        ph.put("query_empty", query.isEmpty() ? "Showing everything" : "");
        ph.put("sort", sort.display());
        ph.put("sort_list", spec.optionList(sorts, sort.ordinal()));
        ph.put("platform", current == null ? "All" : current.display());
        ph.put("platform_list", spec.optionList(platforms, filter + 1));
        ph.put("platform_icon", current == null
                ? spec.string("filter-all-material", Material.CHEST.name())
                : EggIcons.of(current).name());
        ph.put("platform_selected", current == null ? "" : "true");
        ph.put("platform_note",
                current != null && current.hangarName() == null ? "Modrinth only" : "");
        ph.put("page", Integer.toString(page + 1));
        ph.put("has_prev", hasPrev ? "true" : "");
        ph.put("no_prev", hasPrev ? "" : "true");
        ph.put("has_next", hasNext ? "true" : "");
        ph.put("no_next", hasNext ? "" : "true");
        ph.put("installed_count", Integer.toString(plugin.manifest().all().size()));
        ph.put("loading", loading ? "true" : "");
        ph.put("no_results", !loading && results.isEmpty() ? "true" : "");
        return ph;
    }

    /** Everything one search result can put into its configured name, lore or material. */
    private Map<String, String> listingPlaceholders(PluginListing listing) {
        int width = spec.string("description-width", "40").matches("\\d+")
                ? Integer.parseInt(spec.string("description-width", "40"))
                : 40;

        Map<String, String> ph = new HashMap<>();
        ph.put("name", Text.esc(listing.name()));
        ph.put("icon", EggIcons.forListing(listing.platforms(), spin).name());
        ph.put("authors", Text.esc(String.join(", ", listing.authors())));
        ph.put("description", Text.esc(String.join("\n", Lore.wrap(listing.description(), width()))));
        ph.put("downloads", Lore.downloads(listing.downloads()));
        ph.put("follows", Lore.downloads(listing.follows()));
        ph.put("updated", Lore.relative(listing.dateUpdated()));
        // Modrinth's search gives a version *id*, not a label, so that line is simply
        // dropped rather than showing an opaque id.
        ph.put("latest", Text.esc(listing.latestVersionLabel()));
        ph.put("mc", Text.esc(Lore.versions(listing.supportedMcVersions(), 3)));
        ph.put("platforms", platformList(listing.platforms(), width()));
        ph.put("source", listing.source().display());

        Optional<InstallManifest.InstalledEntry> installed = plugin.manifest().get(listing);
        ph.put("installed_version",
                installed.map(e -> Text.esc(e.installedVersion())).orElse(""));
        return ph;
    }

    /** Lore wrap column, so a resource pack with a wider font can widen the tooltip. */
    private int width() {
        String raw = spec.string("description-width", "40");
        return raw.matches("\\d+") ? Integer.parseInt(raw) : 40;
    }

    private java.util.logging.Logger log() {
        return plugin.getLogger();
    }

    /**
     * Every platform in its own colour, bullet-separated and wrapped: the %platforms% lore
     * line. Width counts the names only — the colour tags are not on screen.
     */
    static String platformList(Set<Platform> platforms, int width) {
        StringBuilder out = new StringBuilder();
        int len = 0;
        for (Platform p : EggIcons.ordered(platforms)) {
            String name = p.display();
            if (len == 0) {
                // nothing on this line yet
            } else if (len + 3 + name.length() > width) {
                out.append('\n');
                len = 0;
            } else {
                out.append("<white> • ");
                len += 3;
            }
            out.append('<').append(EggIcons.color(p)).append('>').append(name);
            len += name.length();
        }
        return out.toString();
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
