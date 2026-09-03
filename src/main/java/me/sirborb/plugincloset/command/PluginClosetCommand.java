package me.sirborb.plugincloset.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.gui.BrowseMenu;
import me.sirborb.plugincloset.gui.Text;
import me.sirborb.plugincloset.install.InstallManifest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /plugincloset} command.
 *
 * <p>Registered through the Brigadier lifecycle API, not a {@code commands:} block:
 * paper-plugin.yml has no such section, so {@code getCommand()} would simply return null.
 * {@link BasicCommand} is used rather than hand-built Brigadier nodes because this command
 * is three flat subcommands and needs none of the tree.
 */
public final class PluginClosetCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of("search", "list", "reload");

    private final PluginCloset plugin;

    private PluginClosetCommand(PluginCloset plugin) {
        this.plugin = plugin;
    }

    public static void register(PluginCloset plugin) {
        PluginClosetCommand handler = new PluginClosetCommand(plugin);
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        "plugincloset",
                        "Browse and install plugins from Modrinth and Hangar.",
                        List.of("pcloset", "closet"),
                        handler));
    }

    @Override
    public String permission() {
        return "plugincloset.use";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("plugincloset.admin")) {
                sender.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED + ">You lack plugincloset.admin."));
                return;
            }
            plugin.index().cache().clear();
            plugin.reload();
            sender.sendMessage(Text.chat(Text.PREFIX + "<" + Text.GREEN + ">Config reloaded and cache cleared."));
            return;
        }

        if (sub.equals("list")) {
            sendInstalled(sender);
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED + ">"
                    + "The browser is in-game only. Try /plugincloset list."));
            return;
        }

        BrowseMenu menu = new BrowseMenu(plugin, player);
        menu.open();
        if (sub.equals("search") && args.length > 1) {
            menu.setQuery(String.join(" ", List.of(args).subList(1, args.length)));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) return List.of();
        String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        return SUBCOMMANDS.stream()
                .filter(s -> s.startsWith(prefix))
                .filter(s -> !s.equals("reload") || source.getSender().hasPermission("plugincloset.admin"))
                .toList();
    }

    private void sendInstalled(CommandSender sender) {
        List<InstallManifest.InstalledEntry> entries = plugin.manifest().all();
        if (entries.isEmpty()) {
            sender.sendMessage(Text.chat(Text.PREFIX + "<" + Text.MUTED + ">Nothing installed yet."));
            return;
        }
        sender.sendMessage(Text.chat("<b><" + Text.ACCENT + ">Installed by Plugin Closet"));
        for (InstallManifest.InstalledEntry e : entries) {
            sender.sendMessage(Text.chat("<" + Text.SELECTED + ">  " + Text.esc(e.displayName())
                    + " <" + Text.BODY + ">v" + Text.esc(e.installedVersion())
                    + " <" + Text.DIM + ">(" + Text.esc(e.source().display()) + ", "
                    + Text.esc(e.jarFileName()) + ")"));
        }
    }
}
