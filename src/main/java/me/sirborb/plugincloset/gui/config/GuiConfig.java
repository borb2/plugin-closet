package me.sirborb.plugincloset.gui.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * The {@code guis/} folder: one YAML file per menu, written out from the jar the first
 * time so the shipped layout *is* the default, and editable from there.
 *
 * <p>A file that fails to parse falls back to the jar copy rather than opening an empty
 * chest — a typo in the lore should not make the plugin unusable.
 */
public final class GuiConfig {

    /** Menu name to file, i.e. the set of layouts this plugin ships. */
    private static final List<String> MENUS = List.of("browse", "installed");

    private final Map<String, MenuSpec> menus = new HashMap<>();

    public GuiConfig(Plugin plugin) {
        Path dir = plugin.getDataFolder().toPath().resolve("guis");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not create the guis folder", e);
        }
        for (String name : MENUS) {
            menus.put(name, load(plugin, dir, name));
        }
    }

    /** Never null: a missing or broken file resolves to the layout inside the jar. */
    public MenuSpec menu(String name) {
        return menus.get(name);
    }

    private static MenuSpec load(Plugin plugin, Path dir, String name) {
        String resource = "guis/" + name + ".yml";
        File file = dir.resolve(name + ".yml").toFile();
        if (!file.exists()) plugin.saveResource(resource, false);

        if (file.exists()) {
            try {
                // load(), not loadConfiguration(): the latter swallows a parse error and
                // returns an empty config, which would open an empty chest.
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                return new MenuSpec(yaml);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "guis/" + name + ".yml did not parse;"
                        + " using the built-in layout. A MiniMessage tag with quoted"
                        + " arguments needs a single-quoted line, e.g."
                        + " '<sprite:\"minecraft:items\":item/porkchop>'.", e);
            }
        }
        try (InputStream in = plugin.getResource(resource)) {
            return new MenuSpec(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Missing bundled " + resource, e);
        }
    }
}
