package me.sirborb.plugincloset.platform;

import me.sirborb.plugincloset.model.Platform;
import org.bukkit.Bukkit;

/**
 * What this server actually is, decided once at load.
 *
 * <p>ponytail: no scheduler abstraction here. We compile and run against the Paper API
 * only, so {@code Bukkit.getAsyncScheduler()} and friends are called directly at their use
 * sites. (The MVP spec claimed those fall back on plain Spigot — they do not, they throw,
 * which is exactly why plain Spigot is out of scope.)
 */
public final class RuntimePlatform {

    private static final boolean FOLIA = classPresent("io.papermc.paper.threadedregions.RegionizedServer");

    private RuntimePlatform() {
    }

    /** Which platform's jars this server should be offered. */
    public static Platform current() {
        return FOLIA ? Platform.FOLIA : Platform.PAPER;
    }

    /** The running Minecraft version, e.g. {@code 26.2}. */
    public static String minecraftVersion() {
        try {
            return Bukkit.getMinecraftVersion();
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // Shouldn't happen on Paper; fall back rather than fail the whole plugin.
            String bukkit = Bukkit.getBukkitVersion();   // e.g. "26.2-R0.1-SNAPSHOT"
            int dash = bukkit.indexOf('-');
            return dash > 0 ? bukkit.substring(0, dash) : bukkit;
        }
    }

    public static String describe() {
        return (FOLIA ? "Folia" : "Paper") + " " + minecraftVersion();
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
