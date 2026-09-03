package me.sirborb.plugincloset.gui;

import org.bukkit.inventory.InventoryHolder;

/** A menu that owns its own inventory and handles its own clicks. */
public interface ClickableMenu extends InventoryHolder {

    /** @param right true for a right-click, which scroll-selects run backwards */
    void onClick(int slot, boolean right);
}
