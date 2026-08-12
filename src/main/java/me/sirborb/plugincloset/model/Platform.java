package me.sirborb.plugincloset.model;

import java.util.Locale;

/** A server platform, plus how each source names it. */
public enum Platform {
    PAPER("paper", "PAPER"),
    FOLIA("folia", "FOLIA"),
    SPIGOT("spigot", null),
    BUKKIT("bukkit", null),
    VELOCITY("velocity", "VELOCITY"),
    BUNGEECORD("bungeecord", null),
    SPONGE("sponge", null),
    PURPUR("purpur", null),
    WATERFALL("waterfall", "WATERFALL"),
    UNKNOWN(null, null);

    private final String modrinthLoader;
    private final String hangarName;

    Platform(String modrinthLoader, String hangarName) {
        this.modrinthLoader = modrinthLoader;
        this.hangarName = hangarName;
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

    public static Platform fromHangarName(String name) {
        if (name == null) return UNKNOWN;
        return switch (name.toUpperCase(Locale.ROOT)) {
            case "PAPER" -> PAPER;
            case "FOLIA" -> FOLIA;
            case "WATERFALL" -> WATERFALL;
            case "VELOCITY" -> VELOCITY;
            default -> UNKNOWN;
        };
    }
}
