package me.sirborb.plugincloset.model;

import java.util.Locale;

/**
 * A server platform, plus how each source names it.
 *
 * <p>Modrinth knows every one of these as a loader facet. Hangar's Platform enum is only
 * PAPER/WATERFALL/VELOCITY — verified against its live OpenAPI spec — so the rest map to
 * null and simply contribute no Hangar results. Notably Hangar has no FOLIA: a Folia
 * server asks Hangar for PAPER jars, which is what {@link #hangarName()} returns.
 */
public enum Platform {
    PAPER("paper", "PAPER"),
    FOLIA("folia", "PAPER"),      // Hangar has no FOLIA; Paper jars are the right ask.
    SPIGOT("spigot", null),
    BUKKIT("bukkit", null),
    VELOCITY("velocity", "VELOCITY"),
    BUNGEECORD("bungeecord", null),
    SPONGE("sponge", null),
    PURPUR("purpur", null),
    FABRIC("fabric", null),
    FORGE("forge", null),
    NEOFORGE("neoforge", null),
    QUILT("quilt", null),
    WATERFALL("waterfall", "WATERFALL"),
    UNKNOWN(null, null);

    private final String modrinthLoader;
    private final String hangarName;

    Platform(String modrinthLoader, String hangarName) {
        this.modrinthLoader = modrinthLoader;
        this.hangarName = hangarName;
    }

    /** How the platform spells its own name, e.g. NeoForge. */
    public String display() {
        return switch (this) {
            case NEOFORGE -> "NeoForge";
            case BUNGEECORD -> "BungeeCord";
            default -> name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
        };
    }

    /** Modrinth loader facet value, or null if this platform has none. */
    public String modrinthLoader() {
        return modrinthLoader;
    }

    /** Hangar Platform enum value to request, or null if Hangar cannot serve this. */
    public String hangarName() {
        return hangarName;
    }

    public static Platform fromModrinthLoader(String loader) {
        if (loader == null) return UNKNOWN;
        String needle = loader.toLowerCase(Locale.ROOT);
        for (Platform p : values()) {
            if (needle.equals(p.modrinthLoader)) return p;
        }
        return UNKNOWN;
    }

    /**
     * Hangar's platform key back to an enum. PAPER maps to PAPER, never FOLIA — a Hangar
     * listing cannot tell us whether the jar is Folia-aware.
     */
    public static Platform fromHangarName(String name) {
        if (name == null) return UNKNOWN;
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "PAPER" -> PAPER;
            case "WATERFALL" -> WATERFALL;
            case "VELOCITY" -> VELOCITY;
            default -> UNKNOWN;
        };
    }
}
