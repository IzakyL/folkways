package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.charter.CharterRefusal;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.ColonyMetrics;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

// Ending a colony for good: a warning button, and a page that asks for the phrase typed out before it does it. Only
// the founder may.
final class Razing {

    private static final int WARNING_COLOR = 0xFFB0554A;

    private boolean confirming;
    private String typed = "";
    private Integer leaving;

    // The button that asks; it lives wherever the page puts it.
    Button asker(Player player) {
        return warning(named("folkways.metrics.raze", new Button()
            .setText(Component.translatable("folkways.metrics.raze"))
            .setOnServerClick(event -> {
                if (!allowed(player)) {
                    player.displayClientMessage(
                        Component.translatable(CharterRefusal.FOUNDER_ONLY.translationKey()), true);
                    return;
                }
                leaving = null;
                confirming = true;
            })));
    }

    // Ending it is the founder's alone.
    private static boolean allowed(Player player) {
        Optional<Colony> colony = Pages.colonyOf(player);
        return player instanceof ServerPlayer serverPlayer && colony.isPresent()
            && Charters.founder(serverPlayer.getServer(), colony.get(), serverPlayer.getUUID());
    }

    // Shows the asking page in place of the given one while the player is deciding.
    UIElement confirmation(Player player, UIElement instead) {
        Label cost = Rows.text(Component.empty());
        cost.bind(DataBindingBuilder.componentS2C(() -> Component.translatable(
            "folkways.metrics.raze.warning", residents(player))).build());
        Label prompt = Rows.text(Component.empty());
        prompt.bind(DataBindingBuilder.componentS2C(() ->
            Component.translatable("folkways.metrics.raze.prompt", phrase())).build());

        TextField field = new TextField();
        field.layout(layout -> layout.widthPercent(100).flexShrink(1).minWidth(0));
        field.bind(DataBindingBuilder.stringC2S(text -> typed = text).build());
        Tokens.name(field, "folkways.metrics.raze.field");

        UIElement asking = Rows.page().addChildren(cost, prompt, field,
            Rows.actions(
                named("folkways.metrics.raze.back", new Button()
                    .setText(Component.translatable("folkways.action.back"))
                    .setOnServerClick(event -> confirming = false)),
                warning(named("folkways.metrics.raze.confirm", new Button()
                    .setText(Component.translatable("folkways.metrics.raze.confirm"))
                    .setOnServerClick(event -> raze(player))))));
        asking.setDisplay(false);
        asking.addSyncValue(DataBindingBuilder.boolS2C(() -> confirming)
            .onSyncReceived(asked -> {
                instead.setDisplay(!asked);
                asking.setDisplay(asked);
            })
            .build()
            .getSyncValue());
        return asking;
    }

    // Counted once per asking, not on every sync.
    private int residents(Player player) {
        if (!confirming) {
            return 0;
        }
        if (leaving == null) {
            Optional<Colony> colony = Pages.colonyOf(player);
            Optional<ServerLevel> level = Pages.levelOf(player);
            leaving = colony.isEmpty() || level.isEmpty() ? 0
                : ColonyMetrics.of(level.get().getServer(), colony.get()).residents();
        }
        return leaving;
    }

    private static Button named(String token, Button press) {
        Tokens.name(press, token);
        return press;
    }

    private static Button warning(Button press) {
        press.textStyle(style -> style.textColor(WARNING_COLOR));
        return press;
    }

    private static String phrase() {
        return Component.translatable("folkways.metrics.raze.phrase").getString();
    }

    private void raze(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !typed.trim().equals(phrase()) || !allowed(player)) {
            return;
        }
        Optional<Colony> colony = Pages.colonyOf(player);
        ServerLevel level = serverPlayer.serverLevel();
        if (colony.isEmpty() || ColonyGround.razed(level, colony.get().id())) {
            return;
        }
        List<UUID> keepers = Charters.of(level.getServer(), colony.get()).members();
        int sentOff = ColonyGround.raze(level, colony.get());
        // Each keeper's book goes on to another colony they keep, or to none.
        CurrentColony.refresh(level.getServer(), keepers);
        confirming = false;
        serverPlayer.closeContainer();
        serverPlayer.displayClientMessage(
            Component.translatable("folkways.colony.razed", sentOff), false);
    }
}
