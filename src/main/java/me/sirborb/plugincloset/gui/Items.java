package me.sirborb.plugincloset.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** The item shapes both menus share, so the two look like one plugin. */
public final class Items {

    private Items() {
    }

    /** A control with a name and nothing else. */
    public static ItemStack simple(Material material, String hex, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Text.line(hex, name));
        item.setItemMeta(meta);
        return item;
    }

    /** One row of a scroll-select list: white when chosen, muted otherwise. */
    public static Component option(String label, boolean selected) {
        return Text.of(selected
                ? "<" + Text.ACCENT + ">▶ <" + Text.SELECTED + ">" + Text.esc(label)
                : "<" + Text.DIM + ">  " + Text.esc(label));
    }

    /** The one hint line every scroll-select carries. */
    public static Component scrollHint() {
        return Text.of("<" + Text.DIM + ">Left-click ▼   Right-click ▲");
    }

    /** Blank pane behind the controls; named empty so no item name shows on hover. */
    public static ItemStack filler() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.empty());
        item.setItemMeta(meta);
        return item;
    }

    /** A control with a name and a fixed set of lore lines. */
    public static ItemStack labelled(Material material, String hex, String name,
                                     List<Component> lore) {
        ItemStack item = simple(material, hex, name);
        ItemMeta meta = item.getItemMeta();
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }
}
