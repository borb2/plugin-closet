package me.sirborb.plugincloset.gui.config;

import me.sirborb.plugincloset.gui.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** One file under {@code plugins/PluginCloset/guis/}: a title, a size, and its items. */
public final class MenuSpec {

    private final ConfigurationSection root;
    private final String title;
    private final int size;
    private final Map<String, ItemSpec> items = new LinkedHashMap<>();
    private final String optionSelected;
    private final String optionNormal;

    MenuSpec(ConfigurationSection root) {
        this.root = root;
        this.title = root.getString("title", "");
        // Chest inventories are rows of nine; anything else throws when the menu opens.
        int configured = root.getInt("size", 54);
        this.size = Math.max(9, Math.min(54, configured - configured % 9));
        this.optionSelected = root.getString("options.selected",
                "<" + Text.ACCENT + ">▶ <" + Text.SELECTED + ">%option%");
        this.optionNormal = root.getString("options.normal",
                "<" + Text.DIM + ">  %option%");

        ConfigurationSection section = root.getConfigurationSection("items");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection item = section.getConfigurationSection(key);
                if (item != null) items.put(key, ItemSpec.of(item));
            }
        }
        // Templates the menu stamps out itself — "listing", "entry" — sit at the top level
        // because they have no slot of their own. Anything with a material is an item.
        for (String key : root.getKeys(false)) {
            ConfigurationSection item = root.getConfigurationSection(key);
            if (item != null && item.isString("material")) items.putIfAbsent(key, ItemSpec.of(item));
        }
    }

    public int size() {
        return size;
    }

    public Component title(Map<String, String> ph) {
        String filled = ItemSpec.fill(title, ph);
        return Text.of(filled == null ? "" : filled);
    }

    public ItemSpec item(String key) {
        return items.get(key);
    }

    /** A free-form value the menu needs, e.g. the per-status colours in installed.yml. */
    public String string(String path, String fallback) {
        return root.getString(path, fallback);
    }

    /**
     * Draw every configured item, in file order, and report what ended up where. Items
     * whose {@code show-if} fails are skipped, which is how disabled arrows and empty
     * states swap without any of it being hard-coded.
     *
     * <p>The returned slot-to-key map is what click handling switches on. It has to come
     * from the draw rather than from the config, because items are allowed to overlap —
     * the filler covers the whole control strip and the buttons are drawn over it, so only
     * the item actually visible in a slot should answer for a click on it.
     */
    public Map<Integer, String> render(Inventory inventory, Map<String, String> ph, Logger log) {
        Map<Integer, String> placed = new HashMap<>();
        for (Map.Entry<String, ItemSpec> entry : items.entrySet()) {
            ItemSpec spec = entry.getValue();
            int[] slots = spec.slots();
            if (slots.length == 0 || !spec.visible(ph)) continue;
            ItemStack stack = spec.build(ph, log);
            for (int slot : slots) {
                if (slot < 0 || slot >= inventory.getSize()) continue;
                inventory.setItem(slot, stack.clone());
                placed.put(slot, entry.getKey());
            }
        }
        return placed;
    }

    /** Build a scroll-select list as one multi-line placeholder value. */
    public String optionList(List<String> labels, int selected) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < labels.size(); i++) {
            if (i > 0) out.append('\n');
            String template = i == selected ? optionSelected : optionNormal;
            out.append(template.replace("%option%", Text.esc(labels.get(i))));
        }
        return out.toString();
    }
}
