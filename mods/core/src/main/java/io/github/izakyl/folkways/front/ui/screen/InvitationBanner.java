package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.charter.Invitations;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

// The colonies that have asked the player in, each with its answer, above whatever page shows it. Hidden while none
// has.
final class InvitationBanner {

    private static final int ROW_SLOTS = 4;
    private static final int BACKGROUND = 0x409A3324;

    private static final String ROWS = "invitations";
    private static final String TEXT = "text";

    private final List<UUID> window = new ArrayList<>();
    private final List<UIElement> slots = new ArrayList<>();
    private final List<Label> texts = new ArrayList<>();
    private final List<Button> accepts = new ArrayList<>();
    private final List<Button> declines = new ArrayList<>();
    // Names its presses apart from another banner's in the same panel.
    private final String where;

    InvitationBanner(String where) {
        this.where = where;
    }

    UIElement build(Player player) {
        UIElement banner = Rows.table();
        banner.style(style -> style.background(new ColorRectTexture(BACKGROUND)));
        Tokens.name(banner, "folkways.charter." + where + ".invitations");
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            int index = slot;
            Label text = Rows.name(Component.empty());
            Button accept = new Button()
                .setText(Component.translatable("folkways.charter.accept"))
                .setOnServerClick(event -> answer(player, index, Invitations::accept));
            Button decline = new Button()
                .setText(Component.translatable("folkways.charter.decline"))
                .setOnServerClick(event -> answer(player, index, Invitations::decline));
            accept.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            decline.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            UIElement row = Rows.row().addChildren(text, accept, decline);
            row.setDisplay(false);
            texts.add(text);
            accepts.add(accept);
            declines.add(decline);
            slots.add(row);
            banner.addChildren(row);
        }
        banner.setDisplay(false);
        banner.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(tag -> {
                CompoundTag read = tag instanceof CompoundTag compound ? compound : new CompoundTag();
                ListTag rows = read.getList(ROWS, Tag.TAG_COMPOUND);
                banner.setDisplay(!rows.isEmpty());
                fill(player, rows);
            })
            .build()
            .getSyncValue());
        return banner;
    }

    private Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        window.clear();
        if (player instanceof ServerPlayer serverPlayer) {
            MinecraftServer server = serverPlayer.getServer();
            for (Colony colony : Charters.invitations(server, serverPlayer.getUUID())) {
                if (window.size() >= ROW_SLOTS) {
                    break;
                }
                Charter charter = Charters.written(colony);
                charter.invitation(serverPlayer.getUUID()).ifPresent(invitation -> {
                    window.add(colony.id());
                    CompoundTag row = new CompoundTag();
                    row.put(TEXT, Pages.text(serverPlayer.registryAccess(),
                        Component.translatable("folkways.charter.invitation",
                            Charters.nameOf(server, invitation.by()), Charters.title(server, charter))));
                    rows.add(row);
                });
            }
        }
        tag.put(ROWS, rows);
        return tag;
    }

    private void fill(Player player, ListTag rows) {
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            slots.get(slot).setDisplay(shown);
            Tokens.name(accepts.get(slot), shown ? "folkways.charter." + where + ".accept." + slot : Tokens.NONE);
            Tokens.name(declines.get(slot), shown ? "folkways.charter." + where + ".decline." + slot : Tokens.NONE);
            if (shown) {
                texts.get(slot).setText(
                    Pages.readText(player.registryAccess(), rows.getCompound(slot).get(TEXT)));
            }
        }
    }

    private void answer(Player player, int slot, BiPredicate<ServerPlayer, Colony> answer) {
        if (!(player instanceof ServerPlayer serverPlayer) || slot < 0 || slot >= window.size()) {
            return;
        }
        UUID picked = window.get(slot);
        Charters.invitations(serverPlayer.getServer(), serverPlayer.getUUID()).stream()
            .filter(colony -> colony.id().equals(picked))
            .findFirst()
            .filter(colony -> answer.test(serverPlayer, colony))
            // The panel opens again on whichever colony the player now acts for.
            .ifPresent(colony -> ColonyShell.reopen(serverPlayer));
    }
}
