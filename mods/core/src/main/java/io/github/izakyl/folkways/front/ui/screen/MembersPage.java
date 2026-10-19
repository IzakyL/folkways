package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.CharterRefusal;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.charter.Teams;
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

// Who keeps the colony: every keeper may ask another in, take back an unanswered invitation, or leave; the founder
// alone turns one out, hands the founding on, or gives the whole list over to a team, after which the team says who
// keeps it. Above it stand the invitations the viewer has from other colonies.
public final class MembersPage implements Draw {

    private static final int ROW_SLOTS = 24;

    private static final String MEMBERS = "members";
    private static final String NAME = "name";
    private static final String FOUNDER = "founder";
    private static final String INVITED = "invited";
    private static final String MODE = "mode";
    private static final String TEAM = "team";
    private static final String FOUNDING = "founding";
    private static final String TEAMS = "teams";
    private static final String ASKING = "asking";

    private final List<UUID> window = new ArrayList<>();
    private final List<UIElement> slots = new ArrayList<>();
    private final List<Label> names = new ArrayList<>();
    private final List<Button> dismissers = new ArrayList<>();
    private final List<Button> handers = new ArrayList<>();
    private final List<Button> withdrawers = new ArrayList<>();
    private String typed = "";
    private boolean asking;

    @Override
    public UIElement build(Player player) {
        UIElement table = Rows.table();
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            int index = slot;
            Label name = Rows.name(Component.empty());
            Button handOver = new Button()
                .setText(Component.translatable("folkways.charter.hand_over"))
                .setOnServerClick(event -> act(player, index, Charters::handOver));
            Button dismiss = new Button()
                .setText(Component.translatable("folkways.charter.dismiss"))
                .setOnServerClick(event -> act(player, index, Charters::dismiss));
            Button withdraw = new Button()
                .setText(Component.translatable("folkways.charter.withdraw"))
                .setOnServerClick(event -> act(player, index, Charters::withdraw));
            handOver.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            dismiss.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            withdraw.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            UIElement row = Rows.row().addChildren(name, handOver, dismiss, withdraw);
            row.setDisplay(false);
            names.add(name);
            handers.add(handOver);
            dismissers.add(dismiss);
            withdrawers.add(withdraw);
            slots.add(row);
            table.addChildren(row);
        }

        Label mode = Rows.note(Component.empty());

        TextField field = new TextField();
        field.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        field.bind(DataBindingBuilder.stringC2S(text -> typed = text).build());
        Tokens.name(field, "folkways.charter.admit.field");
        Button admit = named("folkways.charter.admit", new Button()
            .setText(Component.translatable("folkways.charter.admit"))
            .setOnServerClick(event -> answer(player, (server, colony) ->
                Charters.invite(server, colony, player.getUUID(), typed), "folkways.charter.invitation.sent")));
        admit.layout(layout -> layout.flexShrink(0));
        UIElement admitting = Rows.strip().addChildren(field, admit);

        Button leave = named("folkways.charter.leave", new Button()
            .setText(Component.translatable("folkways.charter.leave"))
            .setOnServerClick(event -> answer(player, (server, colony) ->
                Charters.leave(server, colony, player.getUUID()))));
        Button entrust = named("folkways.charter.entrust", new Button()
            .setText(Component.translatable("folkways.charter.entrust"))
            .setOnServerClick(event -> asking = true));
        Button release = named("folkways.charter.release", new Button()
            .setText(Component.translatable("folkways.charter.release"))
            .setOnServerClick(event -> answer(player, (server, colony) ->
                Charters.release(server, colony, player.getUUID()))));
        UIElement acts = Rows.actions(leave, entrust, release);

        // Handing the colony to a team is asked twice: past it, the team decides who keeps it, the founder included.
        Label warning = Rows.text(Component.translatable("folkways.charter.entrust.warning"));
        UIElement confirming = Rows.page().addChildren(warning, Rows.actions(
            named("folkways.charter.entrust.back", new Button()
                .setText(Component.translatable("folkways.action.back"))
                .setOnServerClick(event -> asking = false)),
            named("folkways.charter.entrust.confirm", new Button()
                .setText(Component.translatable("folkways.charter.entrust.confirm"))
                .setOnServerClick(event -> {
                    asking = false;
                    if (player instanceof ServerPlayer serverPlayer) {
                        answer(player, (server, colony) -> Charters.entrust(server, colony, serverPlayer));
                    }
                }))));
        confirming.setDisplay(false);

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(table);
        UIElement listing = Rows.page().addChildren(mode, admitting, scroller, acts);
        listing.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        UIElement banner = new InvitationBanner("members").build(player);
        UIElement page = Rows.page().addChildren(banner, listing, confirming);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(tag -> {
                CompoundTag read = tag instanceof CompoundTag compound ? compound : new CompoundTag();
                boolean founding = read.getBoolean(FOUNDING);
                boolean kept = read.contains(TEAM);
                mode.setText(kept
                    ? Component.translatable("folkways.charter.mode.team",
                        Pages.readText(player.registryAccess(), read.get(TEAM)))
                    : Component.translatable("folkways.charter.mode.own"));
                admitting.setDisplay(!kept);
                leave.setDisplay(!kept);
                entrust.setDisplay(!kept && founding && read.getBoolean(TEAMS));
                release.setDisplay(kept && founding);
                boolean asked = read.getBoolean(ASKING);
                listing.setDisplay(!asked);
                confirming.setDisplay(asked);
                fill(player, read.getList(MEMBERS, Tag.TAG_COMPOUND), founding && !kept);
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
        Optional<Colony> colony = Pages.colonyOf(player);
        if (colony.isPresent() && player instanceof ServerPlayer serverPlayer) {
            MinecraftServer server = serverPlayer.getServer();
            Charter charter = Charters.of(server, colony.get());
            for (UUID member : charter.members()) {
                if (window.size() >= ROW_SLOTS) {
                    break;
                }
                window.add(member);
                CompoundTag row = new CompoundTag();
                row.putString(NAME, Charters.nameOf(server, member));
                row.putBoolean(FOUNDER, charter.founder(member));
                rows.add(row);
            }
            for (Charter.Invitation invitation : charter.invited()) {
                if (window.size() >= ROW_SLOTS) {
                    break;
                }
                window.add(invitation.player());
                CompoundTag row = new CompoundTag();
                row.putString(NAME, Charters.nameOf(server, invitation.player()));
                row.putBoolean(INVITED, true);
                rows.add(row);
            }
            tag.putBoolean(FOUNDING, charter.founder(serverPlayer.getUUID()));
            tag.putBoolean(TEAMS, Teams.present());
            Charters.team(server, colony.get())
                .ifPresent(team -> tag.put(TEAM, Pages.text(serverPlayer.registryAccess(), team.name())));
            if (charter.team().isPresent() && !tag.contains(TEAM)) {
                tag.put(TEAM, Pages.text(serverPlayer.registryAccess(),
                    Component.translatable("folkways.charter.team.gone")));
            }
        }
        tag.putBoolean(ASKING, asking);
        tag.put(MEMBERS, rows);
        return tag;
    }

    private void fill(Player player, ListTag rows, boolean managing) {
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            slots.get(slot).setDisplay(shown);
            if (!shown) {
                Tokens.name(dismissers.get(slot), Tokens.NONE);
                Tokens.name(handers.get(slot), Tokens.NONE);
                Tokens.name(withdrawers.get(slot), Tokens.NONE);
                continue;
            }
            CompoundTag row = rows.getCompound(slot);
            boolean founder = row.getBoolean(FOUNDER);
            boolean invited = row.getBoolean(INVITED);
            String name = row.getString(NAME);
            names.get(slot).setText(founder ? Component.translatable("folkways.charter.founder", name)
                : invited ? Component.translatable("folkways.charter.invited", name)
                : Component.literal(name));
            withdrawers.get(slot).setDisplay(invited);
            Tokens.name(withdrawers.get(slot), invited ? "folkways.charter.withdraw." + slot : Tokens.NONE);
            boolean others = managing && !founder && !invited;
            dismissers.get(slot).setDisplay(others);
            handers.get(slot).setDisplay(others);
            Tokens.name(dismissers.get(slot), others ? "folkways.charter.dismiss." + slot : Tokens.NONE);
            Tokens.name(handers.get(slot), others ? "folkways.charter.hand_over." + slot : Tokens.NONE);
        }
    }

    private interface Change {

        Optional<CharterRefusal> apply(MinecraftServer server, Colony colony, UUID by, UUID whom);
    }

    private void act(Player player, int slot, Change change) {
        if (slot < 0 || slot >= window.size()) {
            return;
        }
        UUID whom = window.get(slot);
        answer(player, (server, colony) -> change.apply(server, colony, player.getUUID(), whom));
    }

    private interface Asked {

        Optional<CharterRefusal> apply(MinecraftServer server, Colony colony);
    }

    private void answer(Player player, Asked asked) {
        answer(player, asked, null);
    }

    // Tells the player why it was refused, or, given one, what came of it.
    private void answer(Player player, Asked asked, String done) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Optional<Colony> colony = Pages.colonyOf(player);
        if (colony.isEmpty()) {
            return;
        }
        asked.apply(serverPlayer.getServer(), colony.get()).ifPresentOrElse(
            why -> serverPlayer.displayClientMessage(Component.translatable(why.translationKey()), true),
            () -> {
                if (done != null) {
                    serverPlayer.displayClientMessage(Component.translatable(done, typed.trim()), true);
                }
            });
    }
}
