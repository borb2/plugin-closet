package me.sirborb.plugincloset.gui;

import me.sirborb.plugincloset.model.Platform;
import org.bukkit.Material;

/**
 * Vanilla-material stand-ins for each platform, so the MVP needs no resource pack.
 *
 * <p>Kept out of {@link Platform} on purpose: that enum stays free of Bukkit types so the
 * model and API layers can be exercised without a server on the classpath.
 */
public final class EggIcons {

    private EggIcons() {
    }

    public static Material of(Platform platform) {
        return switch (platform) {
            case FOLIA -> Material.AZALEA_LEAVES;
            case PAPER -> Material.PAPER;
            case BUKKIT -> Material.WATER_BUCKET;
            case SPIGOT -> Material.LEVER;          // reads as a tap handle
            case VELOCITY -> Material.FEATHER;
            case BUNGEECORD -> Material.TRIPWIRE_HOOK;
            case SPONGE -> Material.SPONGE;
            case PURPUR -> Material.PURPUR_BLOCK;
            case WATERFALL -> Material.BUCKET;      // plain bucket, so it reads apart from Bukkit
            case UNKNOWN -> Material.BARRIER;
        };
    }

    /** The platforms offered as filter toggles, in row order. */
    public static Platform[] filterRow() {
        return new Platform[]{
                Platform.PAPER, Platform.FOLIA, Platform.SPIGOT, Platform.BUKKIT,
                Platform.PURPUR, Platform.VELOCITY, Platform.BUNGEECORD, Platform.WATERFALL
        };
    }

    /** Icon for a listing: its most specific supported platform. */
    public static Material forListing(java.util.Set<Platform> platforms) {
        for (Platform p : new Platform[]{Platform.PAPER, Platform.FOLIA, Platform.PURPUR,
                Platform.SPIGOT, Platform.BUKKIT, Platform.VELOCITY,
                Platform.BUNGEECORD, Platform.WATERFALL, Platform.SPONGE}) {
            if (platforms.contains(p)) return of(p);
        }
        return Material.BOOK;
    }
}
