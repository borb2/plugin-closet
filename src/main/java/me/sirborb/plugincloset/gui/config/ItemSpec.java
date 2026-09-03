package me.sirborb.plugincloset.gui.config;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import me.sirborb.plugincloset.gui.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * One configured item: everything a server owner can change about a slot, parsed once at
 * load and stamped out per render.
 *
 * <p>Placeholders are {@code %name%} tokens the menu supplies. Two rules make the lore
 * fully data-driven without a scripting language: a line whose placeholder resolves to
 * nothing is dropped entirely, and a value containing newlines expands to one line each,
 * keeping the formatting that surrounds it.
 */
public final class ItemSpec {

    private final String material;
    private final String amount;
    private final String name;
    private final List<String> lore;
    private final List<String> enchantments;
    private final List<String> flags;
    private final List<String> attributes;
    private final String showIf;
    private final String customModelData;
    private final String itemModel;
    private final String skullOwner;
    private final String skullTexture;
    private final String color;
    private final String damage;
    private final String glow;
    private final Boolean unbreakable;
    private final Boolean hideTooltip;
    private final int[] slots;

    private ItemSpec(ConfigurationSection s) {
        this.material = s.getString("material", "STONE");
        this.amount = s.getString("amount", "1");
        this.name = s.getString("name");
        this.lore = s.getStringList("lore");
        this.enchantments = s.getStringList("enchantments");
        this.flags = s.getStringList("item-flags");
        this.attributes = s.getStringList("attributes");
        this.showIf = s.getString("show-if");
        this.customModelData = s.getString("custom-model-data");
        this.itemModel = s.getString("item-model");
        this.skullOwner = s.getString("skull-owner");
        this.skullTexture = s.getString("skull-texture");
        this.color = s.getString("color");
        this.damage = s.getString("damage");
        // A template rather than a flag, so the glint can follow state: glow: "%selected%".
        this.glow = s.getString("glow");
        this.unbreakable = s.contains("unbreakable") ? s.getBoolean("unbreakable") : null;
        this.hideTooltip = s.contains("hide-tooltip") ? s.getBoolean("hide-tooltip") : null;
        this.slots = Slots.parse(s.contains("slots") ? s.getString("slots") : s.getString("slot"));
    }

    static ItemSpec of(ConfigurationSection s) {
        return new ItemSpec(s);
    }

    /** Slots this item was configured into. Empty when the menu places it itself. */
    public int[] slots() {
        return slots;
    }

    /** False when {@code show-if} resolved to nothing or to {@code false}. */
    public boolean visible(Map<String, String> ph) {
        return showIf == null || truthy(fill(showIf, ph));
    }

    private static boolean truthy(String v) {
        return v != null && !v.isBlank() && !v.equalsIgnoreCase("false");
    }

    public ItemStack build(Map<String, String> ph, Logger log) {
        ItemStack item = new ItemStack(material(fill(material, ph), log),
                clamp(number(fill(amount, ph), 1)));
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (name != null) meta.displayName(Text.of(orEmpty(fill(name, ph))));
        meta.lore(lore(ph));

        for (String raw : enchantments) {
            String filled = fill(raw, ph);
            if (filled == null || filled.isBlank()) continue;
            String[] parts = filled.split(":");
            Enchantment ench = registry(Registry.ENCHANTMENT, parts[0]);
            if (ench == null) {
                log.warning("Unknown enchantment in guis config: " + raw);
                continue;
            }
            meta.addEnchant(ench, parts.length > 1 ? number(parts[1], 1) : 1, true);
        }
        for (String raw : flags) {
            try {
                meta.addItemFlags(ItemFlag.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                log.warning("Unknown item-flag in guis config: " + raw);
            }
        }
        for (String raw : attributes) {
            addAttribute(meta, fill(raw, ph), log);
        }

        if (glow != null) meta.setEnchantmentGlintOverride(truthy(fill(glow, ph)));
        if (unbreakable != null) meta.setUnbreakable(unbreakable);
        if (hideTooltip != null) meta.setHideTooltip(hideTooltip);
        if (customModelData != null) meta.setCustomModelData(number(fill(customModelData, ph), 0));
        if (itemModel != null) {
            NamespacedKey key = NamespacedKey.fromString(orEmpty(fill(itemModel, ph)));
            if (key != null) meta.setItemModel(key);
        }
        if (damage != null && meta instanceof Damageable d) {
            d.setDamage(number(fill(damage, ph), 0));
        }
        if (color != null && meta instanceof LeatherArmorMeta leather) {
            leather.setColor(Color.fromRGB(rgb(fill(color, ph))));
        }
        if (meta instanceof SkullMeta skull) applySkull(skull, ph, log);

        item.setItemMeta(meta);
        return item;
    }

    // --- lore ---

    private List<Component> lore(Map<String, String> ph) {
        List<Component> out = new ArrayList<>();
        for (String raw : lore) {
            String filled = fill(raw, ph);
            if (filled == null) continue;                       // a placeholder came back empty
            String[] lines = filled.split("\n", -1);
            // Each line is parsed on its own, so a value that wrapped onto several lines
            // would lose the colour it opened with and fall back to vanilla purple.
            String colour = leadingTags(lines[0]);
            for (int i = 0; i < lines.length; i++) {
                out.add(Text.of(i == 0 ? lines[i] : colour + lines[i]));
            }
        }
        return out;
    }

    /**
     * Substitute {@code %tokens%}. Returns null when a known placeholder resolved to
     * nothing, which is how conditional lore lines disappear. Unknown tokens are left
     * untouched, so a literal percent sign in configured text survives.
     */
    public static String fill(String template, Map<String, String> ph) {
        if (template == null) return null;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf('%', i);
            int close = open < 0 ? -1 : template.indexOf('%', open + 1);
            if (close < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open);
            String key = template.substring(open + 1, close);
            if (ph.containsKey(key)) {
                String value = ph.get(key);
                if (value == null || value.isEmpty()) return null;
                out.append(value);
            } else {
                out.append('%').append(key).append('%');
            }
            i = close + 1;
        }
        return out.toString();
    }

    /** The run of MiniMessage tags a line opens with, e.g. {@code <#c9cdd4>} or {@code <b><red>}. */
    public static String leadingTags(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == '<') {
            int close = line.indexOf('>', i);
            if (close < 0) break;
            i = close + 1;
        }
        return line.substring(0, i);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // --- field parsing ---

    private void applySkull(SkullMeta skull, Map<String, String> ph, Logger log) {
        try {
            if (skullTexture != null) {
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID(), null);
                profile.setProperty(new ProfileProperty("textures", orEmpty(fill(skullTexture, ph))));
                skull.setPlayerProfile(profile);
            } else if (skullOwner != null) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(orEmpty(fill(skullOwner, ph))));
            }
        } catch (Exception e) {
            log.warning("Could not apply skull config: " + e.getMessage());
        }
    }

    /** {@code attribute:amount[:operation[:slot-group]]}, e.g. {@code attack_damage:5}. */
    private static void addAttribute(ItemMeta meta, String raw, Logger log) {
        String[] p = raw == null ? new String[0] : raw.split(":");
        if (p.length < 2) {
            log.warning("Attribute needs at least name:amount — got " + raw);
            return;
        }
        Attribute attribute = registry(Registry.ATTRIBUTE, p[0]);
        if (attribute == null) {
            log.warning("Unknown attribute in guis config: " + raw);
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(p[1].trim());
        } catch (NumberFormatException e) {
            log.warning("Attribute amount is not a number: " + raw);
            return;
        }
        AttributeModifier.Operation op = AttributeModifier.Operation.ADD_NUMBER;
        if (p.length > 2) {
            try {
                op = AttributeModifier.Operation.valueOf(p[2].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                log.warning("Unknown attribute operation in guis config: " + raw);
            }
        }
        EquipmentSlotGroup group = p.length > 3
                ? EquipmentSlotGroup.getByName(p[3].trim().toLowerCase(Locale.ROOT))
                : EquipmentSlotGroup.ANY;
        // The key only has to be unique per modifier on one item; the text is that identity.
        meta.addAttributeModifier(attribute, new AttributeModifier(
                new NamespacedKey("plugincloset", "gui-"
                        + Integer.toHexString(raw.hashCode() & 0x7fffffff)),
                amount, op, group == null ? EquipmentSlotGroup.ANY : group));
    }

    private static <T extends Keyed> T registry(Registry<T> registry, String name) {
        NamespacedKey key = NamespacedKey.fromString(name.trim().toLowerCase(Locale.ROOT));
        return key == null ? null : registry.get(key);
    }

    private static Material material(String name, Logger log) {
        Material m = name == null ? null : Material.matchMaterial(name.trim());
        if (m == null || !m.isItem()) {
            log.warning("Unknown material in guis config: " + name);
            return Material.STONE;
        }
        return m;
    }

    private static int number(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int amount) {
        return Math.max(1, Math.min(99, amount));
    }

    private static int rgb(String s) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim().replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }
}
