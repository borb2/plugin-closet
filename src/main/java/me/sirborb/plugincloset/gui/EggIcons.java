package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.model.Platform;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Vanilla-material stand-ins for each platform, plus the colour that goes with it, so the
 * MVP needs no resource pack.
 *
 * <p>Kept out of {@link Platform} on purpose: that enum stays free of Bukkit types so the
 * model and API layers can be exercised without a server on the classpath.
 */
public final class EggIcons {

    /**
     * Display order for a listing's platforms — alphabetical, as the sources list them.
     * Proxies are deliberately absent: this menu installs server plugins, and a Velocity or
     * BungeeCord badge on a listing only tells you about a jar you cannot use here.
     */
    private static final Platform[] ORDER = {
            Platform.BUKKIT, Platform.FABRIC, Platform.FOLIA, Platform.FORGE,
            Platform.NEOFORGE, Platform.PAPER, Platform.PURPUR, Platform.QUILT,
            Platform.SPIGOT
    };

    private EggIcons() {
    }

    public static Material of(Platform platform) {
        return switch (platform) {
            case BUKKIT -> Material.BUCKET;         // the name is the sprite
            case FABRIC -> Material.FIELD_MASONED_BANNER_PATTERN;
            case FOLIA -> Material.OAK_LEAVES;
            case FORGE -> Material.ANVIL;
            case NEOFORGE -> Material.FOX_SPAWN_EGG;
            case PAPER -> Material.PAPER;
            case PURPUR -> Material.PURPUR_BLOCK;
            case QUILT -> Material.WHITE_WOOL;
            case SPIGOT -> Material.BREWING_STAND;
            case VELOCITY -> Material.FEATHER;
            case BUNGEECORD -> Material.TRIPWIRE_HOOK;
            case WATERFALL -> Material.WATER_BUCKET;
            case SPONGE -> Material.SPONGE;
            case UNKNOWN -> Material.BARRIER;
        };
    }

    /** The platform's own brand colour, for the lore line. */
    public static String color(Platform platform) {
        return switch (platform) {
            case BUKKIT -> "#F6AF7B";
            case FABRIC -> "#DBB69B";
            case FOLIA -> "#A5E388";
            case FORGE -> "#959EEF";
            case NEOFORGE -> "#F99E6B";
            case PAPER -> "#EEAAAA";
            case PURPUR -> "#C3ABF7";
            case QUILT -> "#C796F9";
            case SPIGOT -> "#F1CC84";
            default -> Text.BODY;
        };
    }

    /** The platforms the filter scrolls through, in order. */
    public static Platform[] filters() {
        return new Platform[]{
                Platform.PAPER, Platform.FOLIA, Platform.SPIGOT, Platform.BUKKIT,
                Platform.PURPUR
        };
    }

    /** A listing's platforms in display order, dropping any this build has no icon for. */
    public static List<Platform> ordered(Set<Platform> platforms) {
        List<Platform> out = new ArrayList<>();
        for (Platform p : ORDER) {
            if (platforms.contains(p)) out.add(p);
        }
        return out;
    }

    /**
     * Icon for a listing. A plugin that runs on several platforms has several icons and no
     * good reason to prefer one, so the caller's tick picks: they cycle, one per second.
     */
    public static Material forListing(Set<Platform> platforms, int spin) {
        List<Platform> shown = ordered(platforms);
        return shown.isEmpty()
                ? Material.BOOK
                : of(shown.get(Math.floorMod(spin, shown.size())));
    }
}
