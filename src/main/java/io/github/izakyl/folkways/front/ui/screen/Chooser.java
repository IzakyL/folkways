package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.CharterRefusal;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

// The colonies a player keeps, under the panel's title: one to act for, or a new one to found, named from the field
// that also renames the one open, under any invitations the player has to answer. With none kept yet, it is all the
// panel shows.
final class Chooser {

    // Far more colonies than one player keeps; past this they scroll off unlisted.
    private static final int ROW_SLOTS = 16;

    private static final String COLONIES = "colonies";
    private static final String TITLE = "title";
    private static final String CURRENT = "current";
    private static final String SETTLED = "settled";

    private final List<UUID> window = new ArrayList<>();
    private final List<UIElement> slots = new ArrayList<>();
    private final List<Label> names = new ArrayList<>();
    private final List<Button> choosers = new ArrayList<>();
    private String typed = "";

    UIElement build(Player player, Runnable back) {
        UIElement table = Rows.table();
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            int index = slot;
            Label name = Rows.name(Component.empty());
            Button choose = new Button()
                .setText(Component.translatable("folkways.charter.switch"))
                .setOnServerClick(event -> choose(player, index));
            choose.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            UIElement row = Rows.row().addChildren(name, choose);
            row.setDisplay(false);
            names.add(name);
            choosers.add(choose);
            slots.add(row);
            table.addChildren(row);
        }

        TextField field = new TextField();
        field.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        field.bind(DataBindingBuilder.stringC2S(text -> typed = text).build());
        Tokens.name(field, "folkways.charter.name.field");
        Button rename = named("folkways.charter.rename", new Button()
            .setText(Component.translatable("folkways.charter.rename"))
            .setOnServerClick(event -> rename(player)));
        rename.layout(layout -> layout.flexShrink(0));
        UIElement naming = Rows.strip().addChildren(field, rename);

        Button found = named("folkways.colony.create", new Button()
            .setText(Component.translatable("folkways.action.new_colony"))
            .setOnServerClick(event -> found(player)));
        Button leave = named("folkways.charter.back", new Button()
            .setText(Component.translatable("folkways.action.back"))
            .setOnClick(event -> back.run()));

        Label lead = Rows.text(Component.translatable("folkways.colony.create"));
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(table);
        UIElement page = Rows.page().addChildren(new InvitationBanner("chooser").build(player), lead, naming, scroller,
            Rows.actions(found, leave));
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        Tokens.name(page, "folkways.charter.chooser");

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(tag -> {
                CompoundTag read = tag instanceof CompoundTag compound ? compound : new CompoundTag();
                boolean settled = read.getBoolean(SETTLED);
                lead.setText(Component.translatable(settled ? "folkways.charter.yours" : "folkways.colony.create"));
                rename.setDisplay(settled);
                leave.setDisplay(settled);
                fill(player, read.getList(COLONIES, Tag.TAG_COMPOUND));
            })
            .build()
            .getSyncValue());
        return page;
    }

    private static Button named(String token, Button press) {
        Tokens.name(press, token);
        return press;
    }

    private Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        window.clear();
        if (player instanceof ServerPlayer serverPlayer) {
            MinecraftServer server = serverPlayer.getServer();
            Optional<UUID> current = Pages.colonyOf(player).map(Colony::id);
            for (Colony colony : Charters.joined(server, serverPlayer.getUUID())) {
                if (window.size() >= ROW_SLOTS) {
                    break;
                }
                window.add(colony.id());
                CompoundTag row = new CompoundTag();
                row.put(TITLE, Pages.text(serverPlayer.registryAccess(),
                    Charters.title(server, Charters.of(server, colony))));
                row.putBoolean(CURRENT, current.filter(colony.id()::equals).isPresent());
                rows.add(row);
            }
            tag.putBoolean(SETTLED, current.isPresent());
        }
        tag.put(COLONIES, rows);
        return tag;
    }

    private void fill(Player player, ListTag rows) {
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            slots.get(slot).setDisplay(shown);
            if (!shown) {
                Tokens.name(choosers.get(slot), Tokens.NONE);
                continue;
            }
            CompoundTag row = rows.getCompound(slot);
            boolean current = row.getBoolean(CURRENT);
            names.get(slot).setText(Pages.readText(player.registryAccess(), row.get(TITLE)));
            Button choose = choosers.get(slot);
            choose.setText(Component.translatable(current ? "folkways.charter.current" : "folkways.charter.switch"));
            choose.setActive(!current);
            Tokens.name(choose, "folkways.charter.switch." + slot);
        }
    }

    private void choose(Player player, int slot) {
        if (!(player instanceof ServerPlayer serverPlayer) || slot < 0 || slot >= window.size()) {
            return;
        }
        MinecraftServer server = serverPlayer.getServer();
        UUID picked = window.get(slot);
        Charters.joined(server, serverPlayer.getUUID()).stream()
            .filter(colony -> colony.id().equals(picked))
            .findFirst()
            .ifPresent(colony -> {
                CurrentColony.choose(serverPlayer, colony);
                ColonyShell.reopen(serverPlayer);
            });
    }

    private void found(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        String name = typed.trim();
        if (name.length() > Charter.MAX_NAME) {
            serverPlayer.displayClientMessage(Component.translatable(CharterRefusal.BAD_NAME.translationKey()), true);
            return;
        }
        Colony colony = Charters.found(serverPlayer, name);
        CurrentColony.choose(serverPlayer, colony);
        ColonyShell.reopen(serverPlayer);
    }

    private void rename(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Pages.colonyOf(player)
            .flatMap(colony -> Charters.rename(serverPlayer.getServer(), colony, serverPlayer.getUUID(), typed))
            .map(CharterRefusal::translationKey)
            .ifPresent(why -> serverPlayer.displayClientMessage(Component.translatable(why), true));
    }
}
