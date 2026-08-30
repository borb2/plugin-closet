package me.sirborb.plugincloset.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.sirborb.plugincloset.PluginCloset;
import me.sirborb.plugincloset.model.PluginListing;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The two dialogs: the search box and the optional install confirmation.
 *
 * <p>Paper's Dialog API is the modern replacement for the old anvil-rename text-input hack.
 * Paper-only by design; {@code /plugincloset search <query>} does the same job from chat.
 */
public final class Dialogs {

    private static final String QUERY_KEY = "query";

    private Dialogs() {
    }

    /** Ask for a search string, then reopen the menu with it applied. */
    public static void search(PluginCloset plugin, BrowseMenu menu) {
        Player player = menu.player();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("Search plugins"))
                        .inputs(List.of(DialogInput.text(QUERY_KEY, Component.text("Search")).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Search"), null, 100,
                                DialogAction.customClick((view, audience) -> {
                                    // 26.2 names this getText; older guides say getString.
                                    String query = view.getText(QUERY_KEY);
                                    onPlayerThread(plugin, player, () -> {
                                        menu.open();
                                        menu.setQuery(query);
                                    });
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 100,
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
                .base(DialogBase.builder(Component.text("Install " + listing.name() + "?"))
                        .body(List.of(
                                DialogBody.plainMessage(Component.text(
                                        "From " + listing.source().display()
                                                + " - it will be active after a restart.")),
                                DialogBody.plainMessage(Component.text(listing.description()))))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(Component.text("Install"), null, 100,
                                DialogAction.customClick((view, audience) -> {
                                    onPlayerThread(plugin, player, () -> {
                                        menu.open();
                                        onConfirm.run();
                                    });
                                }, ClickCallback.Options.builder().build())),
                        ActionButton.create(Component.text("Cancel"), null, 100,
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
