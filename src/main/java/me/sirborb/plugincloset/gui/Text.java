package me.sirborb.plugincloset.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Every string the GUI shows goes through MiniMessage, and every colour lives here.
 *
 * <p>Two rules worth keeping: item text is italic in vanilla unless turned off, and text
 * that came from an API is escaped before it is parsed — a plugin description containing
 * {@code <red>} must render as those characters, not recolour the rest of the lore.
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Buttons and headings. */
    public static final String ACCENT = "#ffba08";
    /** The chosen entry in a scroll list. */
    public static final String SELECTED = "#ffffff";
    /** The entries around it. */
    public static final String MUTED = "#6e7581";
    /** Body copy: descriptions, values. */
    public static final String BODY = "#c9cdd4";
    /** Hints and footnotes. */
    public static final String DIM = "#565d68";

    public static final String GREEN = "#4ade80";
    public static final String YELLOW = "#fbbf24";
    public static final String RED = "#f87171";
    public static final String BLUE = "#60a5fa";
    public static final String PINK = "#c084fc";

    /** Chat prefix, so plugin messages are distinguishable from server spam. */
    public static final String PREFIX = "<" + ACCENT + ">Plugin Closet <" + DIM + ">| ";

    private Text() {
    }

    /** Parse MiniMessage for an item name or lore line. */
    public static Component of(String mini) {
        return MM.deserialize(mini).decoration(TextDecoration.ITALIC, false);
    }

    /** One coloured line of text that is already trusted (no tags of its own). */
    public static Component line(String hex, String plain) {
        return of("<" + hex + ">" + esc(plain));
    }

    /** Chat messages: same parsing, but italic is not forced off outside of items. */
    public static Component chat(String mini) {
        return MM.deserialize(mini);
    }

    /** Neutralise MiniMessage tags in text that came from Modrinth or Hangar. */
    public static String esc(String raw) {
        return raw == null ? "" : MM.escapeTags(raw);
    }
}
