package me.sirborb.plugincloset.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.install.InstallManifest.InstalledEntry;
import me.sirborb.plugincloset.model.PluginListing;
import me.sirborb.plugincloset.model.PluginVersionFile;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Every dialog: the search box, the install confirmation, the uninstall confirmation, the
 * version picker and the paste-a-link installer.
 *
 * <p>Paper's Dialog API is the modern replacement for the old anvil-rename text-input hack.
 * Paper-only by design; {@code /plugincloset search <query>} does the same job from chat.
 *
 * <p>One property shapes all of these: a dialog is rendered once by the client and cannot
 * be updated afterwards. So anything that has to be waited for is resolved <em>before</em>
 * the dialog opens (the version list), and anything that progresses afterwards is shown on
 * a boss bar instead (the download).
 */
public final class Dialogs {

    private static final String QUERY_KEY = "query";
    private static final String URL_KEY = "url";

    /** A version list can run to hundreds; a dialog is not a scrollable list. */
    private static final int MAX_VERSION_BUTTONS = 12;

    private Dialogs() {
    }

    /** Ask for a search string, then reopen the menu with it applied. */
    public static void search(PluginCloset plugin, BrowseMenu menu) {
        Player player = menu.player();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.of("<b><" + Text.ACCENT + ">Search plugins"))
                        .inputs(List.of(DialogInput.text(QUERY_KEY, Text.line(Text.BODY, "Search")).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Text.line(Text.ACCENT, "Search"), null, 100,
                                DialogAction.customClick((view, audience) -> {
                                    // 26.2 names this getText; older guides say getString.
                                    String query = view.getText(QUERY_KEY);
                                    onPlayerThread(plugin, player, () -> {
                                        menu.open();
                                        menu.setQuery(query);
                                    });
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Text.line(Text.MUTED, "Cancel"), null, 100,
                                DialogAction.customClick((view, audience) ->
                                        onPlayerThread(plugin, player, menu::open),
                                        ClickCallback.Options.builder().build())))));

        // Showing a dialog closes the chest, so both buttons reopen the menu.
        player.closeInventory();
        player.showDialog(dialog);
    }

    /**
     * Confirm before installing, used only when {@code require-confirmation: true}. The
     * default is false, matching the one-click behaviour the spec asked for.
     */
    public static void confirmInstall(PluginCloset plugin, BrowseMenu menu,
                                      PluginListing listing, Runnable onConfirm) {
        Player player = menu.player();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.of("<b><" + Text.ACCENT + ">Install "
                        + Text.esc(listing.name()) + "?"))
                        .body(List.of(
                                DialogBody.plainMessage(Text.line(Text.BODY,
                                        "From " + listing.source().display()
                                                + " — active after a restart.")),
                                DialogBody.plainMessage(Text.line(Text.MUTED,
                                        listing.description()))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Text.line(Text.GREEN, "Install"), null, 100,
                                DialogAction.customClick((view, audience) -> {
                                    onPlayerThread(plugin, player, () -> {
                                        menu.open();
                                        onConfirm.run();
                                    });
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Text.line(Text.MUTED, "Cancel"), null, 100,
                                DialogAction.customClick((view, audience) ->
                                        onPlayerThread(plugin, player, menu::open),
                                        ClickCallback.Options.builder().build())))));

        player.closeInventory();
        player.showDialog(dialog);
    }

    /** Confirm before deleting a jar. Always shown: deleting is not a one-click action. */
    public static void confirmUninstall(PluginCloset plugin, ManageMenu menu) {
        Player player = menu.player();
        InstalledMenu.Row row = menu.row();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.of("<b><" + Text.RED + ">Uninstall "
                                + Text.esc(row.name()) + "?"))
                        .body(List.of(
                                DialogBody.plainMessage(Text.line(Text.BODY,
                                        "Deletes " + row.jar().getFileName()
                                                + " from the plugins folder.")),
                                DialogBody.plainMessage(Text.line(Text.MUTED,
                                        row.loadedName() == null
                                                ? "Nothing is running from this jar."
                                                : row.loadedName()
                                                        + " stays loaded until the server restarts.")),
                                DialogBody.plainMessage(Text.line(Text.MUTED,
                                        "Its config folder is left alone."))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Text.line(Text.RED, "Delete the jar"), null, 120,
                                DialogAction.customClick((view, audience) ->
                                        onPlayerThread(plugin, player, () ->
                                                plugin.installer().uninstall(player, row, menu::backToList)),
                                        ClickCallback.Options.builder().build())),
                        ActionButton.create(Text.line(Text.MUTED, "Cancel"), null, 100,
                                DialogAction.customClick((view, audience) ->
                                        onPlayerThread(plugin, player, menu::open),
                                        ClickCallback.Options.builder().build())))));

        player.closeInventory();
        player.showDialog(dialog);
    }

    /**
     * Ask the source what it publishes, then offer those builds as buttons. Resolved
     * first and shown second, because there is no way to fill a dialog in later.
     */
    public static void chooseVersion(PluginCloset plugin, ManageMenu menu, InstalledEntry entry) {
        Player player = menu.player();
        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.MUTED + ">Asking "
                + entry.source().display() + " for every build..."));

        plugin.installer().availableVersions(entry).whenComplete((files, error) ->
                onPlayerThread(plugin, player, () -> {
                    if (error != null || files == null || files.isEmpty()) {
                        player.sendMessage(Text.chat(Text.PREFIX + "<" + Text.RED
                                + ">No installable builds came back from "
                                + entry.source().display() + "."));
                        menu.open();
                        return;
                    }
                    showVersions(plugin, menu, entry, files);
                }));
    }

    private static void showVersions(PluginCloset plugin, ManageMenu menu, InstalledEntry entry,
                                     List<PluginVersionFile> files) {
        Player player = menu.player();
        List<ActionButton> buttons = new ArrayList<>();
        for (PluginVersionFile file : files.subList(0, Math.min(files.size(), MAX_VERSION_BUTTONS))) {
            boolean current = file.versionLabel().equals(entry.installedVersion());
            buttons.add(ActionButton.create(
                    Text.line(current ? Text.GREEN : Text.ACCENT,
                            (current ? "● " : "") + file.versionLabel()),
                    Text.of("<" + Text.BODY + ">" + Text.esc(entry.source().display())
                            + " <" + Text.DIM + ">| MC <" + Text.BODY + ">"
                            + Text.esc(String.join(", ", file.gameVersions()))
                            + " <" + Text.DIM + ">| " + Lore.relative(file.datePublished())),
                    150,
                    DialogAction.customClick((view, audience) -> onPlayerThread(plugin, player, () -> {
                        if (current) {
                            menu.open();      // already on it: nothing to download
                            return;
                        }
                        plugin.installer().installVersion(player, entry, file, menu::backToList);
                    }), ClickCallback.Options.builder().build())));
        }

        String more = files.size() > MAX_VERSION_BUTTONS
                ? " — newest " + MAX_VERSION_BUTTONS + " of " + files.size() : "";
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.of("<b><" + Text.ACCENT + ">"
                                + Text.esc(entry.displayName()) + " versions"))
                        .body(List.of(
                                DialogBody.plainMessage(Text.line(Text.BODY,
                                        "From " + entry.source().display() + more)),
                                DialogBody.plainMessage(Text.line(Text.MUTED,
                                        "Installed: v" + entry.installedVersion()
                                                + ". Picking another deletes the old jar."))))
                        .build())
                .type(DialogType.multiAction(buttons,
                        ActionButton.create(Text.line(Text.MUTED, "Cancel"), null, 100,
                                DialogAction.customClick((view, audience) ->
                                        onPlayerThread(plugin, player, menu::open),
                                        ClickCallback.Options.builder().build())),
                        2)));

        player.closeInventory();
        player.showDialog(dialog);
    }

    /**
     * Paste a direct link to a jar. The progress bar that follows is a boss bar rather
     * than part of this dialog, for the reason given at the top of this class.
     */
    public static void customInstall(PluginCloset plugin, BrowseMenu menu) {
        Player player = menu.player();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Text.of("<b><" + Text.ACCENT + ">Install from a link"))
                        .body(List.of(
                                DialogBody.plainMessage(Text.line(Text.BODY,
                                        "A direct download link ending in .jar.")),
                                DialogBody.plainMessage(Text.line(Text.MUTED,
                                        "Nothing verifies this file. Only paste links you trust."))))
                        .inputs(List.of(DialogInput.text(URL_KEY, Text.line(Text.BODY, "Link"))
                                .width(300)
                                .maxLength(512)
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Text.line(Text.GREEN, "Download"), null, 100,
                                DialogAction.customClick((view, audience) -> {
                                    String url = view.getText(URL_KEY);
                                    onPlayerThread(plugin, player, () -> {
                                        menu.open();
                                        plugin.installer().installFromUrl(player, url);
                                    });
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Text.line(Text.MUTED, "Cancel"), null, 100,
                                DialogAction.customClick((view, audience) ->
                                        onPlayerThread(plugin, player, menu::open),
                                        ClickCallback.Options.builder().build())))));

        player.closeInventory();
        player.showDialog(dialog);
    }

    /**
     * Dialog callbacks are not guaranteed to arrive on the thread that owns this player,
     * and reopening an inventory must be.
     */
    private static void onPlayerThread(PluginCloset plugin, Player player, Runnable task) {
        player.getScheduler().run(plugin, ignored -> task.run(), null);
    }
}
