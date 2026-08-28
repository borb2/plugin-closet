package me.sirborb.plugincloset.command;

import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.gui.BrowseMenu;
import me.sirborb.plugincloset.install.InstallManifest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

public final class PluginClosetCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("search", "list", "reload");

    private final PluginCloset plugin;

    private PluginClosetCommand(PluginCloset plugin) {
        this.plugin = plugin;
    }

    public static void register(PluginCloset plugin) {
        var command = plugin.getCommand("plugincloset");
        if (command == null) {
            plugin.getLogger().severe("Command 'plugincloset' is missing from paper-plugin.yml");
            return;
        }
        PluginClosetCommand handler = new PluginClosetCommand(plugin);
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("plugincloset.admin")) {
                sender.sendMessage(Component.text("You lack plugincloset.admin.", NamedTextColor.RED));
                return true;
            }
            plugin.index().cache().clear();
            plugin.reload();
            sender.sendMessage(Component.text("Config reloaded and cache cleared.", NamedTextColor.GREEN));
            return true;
        }

        if (!sender.hasPermission("plugincloset.use")) {
            sender.sendMessage(Component.text("You lack plugincloset.use.", NamedTextColor.RED));
            return true;
        }

        if (sub.equals("list")) {
            sendInstalled(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("The browser is in-game only. Try /"
                    + label + " list.", NamedTextColor.RED));
            return true;
        }

        BrowseMenu menu = new BrowseMenu(plugin, player);
        menu.open();
        if (sub.equals("search") && args.length > 1) {
            menu.setQuery(String.join(" ", List.of(args).subList(1, args.length)));
        }
        return true;
    }

    private void sendInstalled(CommandSender sender) {
        List<InstallManifest.InstalledEntry> entries = plugin.manifest().all();
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("PluginCloset has not installed anything yet.",
                    NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("Installed by PluginCloset:", NamedTextColor.GOLD));
        for (InstallManifest.InstalledEntry e : entries) {
            sender.sendMessage(Component.text("  " + e.sourceId() + " v" + e.installedVersion(),
                            NamedTextColor.WHITE)
                    .append(Component.text("  (" + e.source().display() + ", " + e.jarFileName() + ")",
                            NamedTextColor.DARK_GRAY)));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(prefix))
                .filter(s -> !s.equals("reload") || sender.hasPermission("plugincloset.admin"))
                .toList();
    }
}
