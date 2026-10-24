package io.github.izakyl.folkways.plugins.combat.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** The colony book's combat page: people, an actual squad, point configuration, and explicit orders. */
public final class CombatPage implements Draw {
    private UUID context;
    private int residentOffset;
    private int groupOffset;
    private int loadoutOffset;
    private final List<Button> loadoutButtons = new ArrayList<>();
    private List<UUID> residentWindow = List.of();
    private List<UUID> groupWindow = List.of();
    private final List<UIElement> residentRows = new ArrayList<>();
    private final List<Label> residentNames = new ArrayList<>();
    private final List<Label> residentDetails = new ArrayList<>();
    private final List<Button> residentButtons = new ArrayList<>();
    private final List<UIElement> groupRows = new ArrayList<>();
    private final List<Label> groupNames = new ArrayList<>();
    private final List<Button> groupButtons = new ArrayList<>();
    private final Label summary = named("summary", Rows.text(Component.empty()));
    private final Label points = named("points", Rows.text(Component.empty()));
    private final Label message = named("message", Rows.note(Component.empty()));
    private final Label locations = Rows.note(Component.empty());
    private final Label preference = named("preference", Rows.text(Component.empty()));
    private final Label selection = named("selection", Rows.value(Component.empty()));

    @Override
    public UIElement build(Player player) {
        context = Pages.colonyOf(player).map(colony -> colony.id()).orElse(null);
        UIElement residents = Rows.table();
        for (int slot = 0; slot < 8; slot++) {
            int index = slot;
            Label name = Rows.name(Component.empty());
            Label detail = Rows.name(Component.empty());
            name.layout(layout -> layout.height(14).flexGrow(0));
            detail.layout(layout -> layout.height(14).flexGrow(0));
            Button pick = button("resident." + slot, "folkways.combat.select", player, () -> {
                if (index < residentWindow.size()) { act(player, "resident", residentWindow.get(index).toString()); }
            });
            UIElement descriptions = Rows.page().addChildren(name, detail);
            descriptions.layout(layout -> layout.widthAuto().flexGrow(1).flexShrink(1).minWidth(0).gapAll(0));
            UIElement row = Rows.row().addChildren(descriptions, pick);
            row.layout(layout -> layout.height(34));
            Tokens.name(row, "folkways.combat.resident_row." + slot);
            residents.addChildren(row); residentRows.add(row); residentNames.add(name); residentDetails.add(detail); residentButtons.add(pick);
        }
        UIElement groups = Rows.table();
        for (int slot = 0; slot < 4; slot++) {
            int index = slot;
            Label name = Rows.name(Component.empty());
            Button pick = button("group." + slot, "folkways.combat.select", player, () -> {
                if (index < groupWindow.size()) { act(player, "group", groupWindow.get(index).toString()); }
            });
            UIElement row = Rows.row().addChildren(name, pick);
            Tokens.name(row, "folkways.combat.group_row." + slot);
            groups.addChildren(row); groupRows.add(row); groupNames.add(name); groupButtons.add(pick);
        }
        UIElement loadouts = Rows.page();
        loadouts.addChildren(Rows.strip().addChildren(
            button("loadout_previous", "folkways.combat.previous", player, () -> loadoutOffset -= 6),
            button("loadout_next", "folkways.combat.next", player, () -> loadoutOffset += 6)));
        for (int slot = 0; slot < 6; slot++) {
            int index = slot;
            Button choice = button("loadout." + slot, "folkways.combat.select", player,
                () -> act(player, "loadout", Integer.toString(loadoutOffset + index)));
            loadouts.addChildren(choice); loadoutButtons.add(choice);
        }
        UIElement content = Rows.page().addChildren(
            Rows.note(Component.translatable("folkways.combat.help")),
            Rows.heading(Component.translatable("folkways.combat.people")),
            Rows.strip().addChildren(pager(player, true, -1), pager(player, true, 1),
                selection, action(player, "create_group", "folkways.combat.create_group")), residents,
            Rows.heading(Component.translatable("folkways.combat.groups")),
            Rows.strip().addChildren(pager(player, false, -1), pager(player, false, 1)), groups,
            Rows.heading(Component.translatable("folkways.combat.positions")), points, locations,
            Rows.strip().addChildren(action(player, "add_point", "folkways.combat.add_point"),
                action(player, "add_aim", "folkways.combat.add_aim"), action(player, "clear_points", "folkways.combat.clear_points")),
            preference, Rows.note(Component.translatable("folkways.combat.supply_help")),
            loadouts);
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(content);
        Tokens.name(scroll, "folkways.combat.scroll");
        UIElement page = Rows.page().addChildren(summary, scroll, message,
            Rows.strip().addChildren(action(player, "execute", "folkways.combat.execute"),
                action(player, "retreat", "folkways.combat.retreat"), action(player, "release", "folkways.combat.release")));
        Rows.fills(page);
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player)).onSyncReceived(value -> fill(player, value)).build().getSyncValue());
        return page;
    }

    private Optional<CombatDesk> desk(Player player) {
        return Pages.colonyOf(player).filter(colony -> colony.id().equals(context)).map(CombatDesk::of);
    }
    private void act(Player player, String action, String value) {
        if (player instanceof ServerPlayer server) { desk(player).ifPresent(desk -> desk.act(server, action, value)); }
    }
    private Button action(Player player, String action, String label) { return button(action, label, player, () -> act(player, action, "")); }
    private Button button(String token, String label, Player player, Runnable run) {
        Button button = new Button().setText(Component.translatable(label)).setOnServerClick(event -> {
            if (desk(player).isPresent()) { run.run(); }
        });
        button.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(button, "folkways.combat." + token);
        return button;
    }
    private Button pager(Player player, boolean residents, int direction) {
        String token = (residents ? "resident_" : "group_") + (direction < 0 ? "previous" : "next");
        return button(token, direction < 0 ? "folkways.combat.previous" : "folkways.combat.next", player, () -> {
            if (residents) { residentOffset += direction * 8; } else { groupOffset += direction * 4; }
        });
    }
    private Tag reading(Player player) {
        CompoundTag tag = player instanceof ServerPlayer server ? desk(player).map(desk -> desk.reading(server)).orElseGet(CompoundTag::new)
            : new CompoundTag();
        ListTag loadouts = tag.getList("loadouts", Tag.TAG_COMPOUND);
        loadoutOffset = Math.clamp(loadoutOffset, 0, Math.max(0, loadouts.size() - 1) / 6 * 6);
        ListTag shownLoadouts = new ListTag();
        for (int n = loadoutOffset; n < Math.min(loadouts.size(), loadoutOffset + 6); n++) { shownLoadouts.add(loadouts.getCompound(n).copy()); }
        tag.put("loadouts", shownLoadouts);
        ListTag residents = tag.getList("residents", Tag.TAG_COMPOUND);
        ListTag groups = tag.getList("groups", Tag.TAG_COMPOUND);
        residentOffset = Math.clamp(residentOffset, 0, Math.max(0, residents.size() - 1) / 8 * 8);
        groupOffset = Math.clamp(groupOffset, 0, Math.max(0, groups.size() - 1) / 4 * 4);
        ListTag shownResidents = new ListTag();
        List<UUID> residentIds = new ArrayList<>();
        for (int n = residentOffset; n < Math.min(residents.size(), residentOffset + 8); n++) {
            CompoundTag row = residents.getCompound(n); residentIds.add(row.getUUID("id")); shownResidents.add(row.copy());
        }
        ListTag shownGroups = new ListTag();
        List<UUID> groupIds = new ArrayList<>();
        for (int n = groupOffset; n < Math.min(groups.size(), groupOffset + 4); n++) {
            CompoundTag row = groups.getCompound(n); groupIds.add(row.getUUID("id")); shownGroups.add(row.copy());
        }
        residentWindow = List.copyOf(residentIds); groupWindow = List.copyOf(groupIds);
        tag.put("residents", shownResidents); tag.put("groups", shownGroups);
        return tag;
    }
    private void fill(Player player, Tag value) {
        CompoundTag tag = value instanceof CompoundTag compound ? compound : new CompoundTag();
        summary.setText(Pages.readText(player.registryAccess(), tag.get("summary")));
        points.setText(Pages.readText(player.registryAccess(), tag.get("points")));
        message.setText(Pages.readText(player.registryAccess(), tag.get("message")));
        preference.setText(Pages.readText(player.registryAccess(), tag.get("loadout")));
        selection.setText(Component.translatable("folkways.combat.selection", tag.getInt("selected")));
        locations.setText(Component.literal(tag.getString("locations")));
        ListTag loadouts = tag.getList("loadouts", Tag.TAG_COMPOUND);
        for (int n = 0; n < loadoutButtons.size(); n++) {
            loadoutButtons.get(n).setDisplay(n < loadouts.size());
            if (n < loadouts.size()) { loadoutButtons.get(n).setText(Pages.readText(player.registryAccess(), loadouts.getCompound(n).get("label"))); }
        }
        ListTag residents = tag.getList("residents", Tag.TAG_COMPOUND);
        for (int n = 0; n < 8; n++) {
            residentRows.get(n).setDisplay(n < residents.size());
            if (n >= residents.size()) { continue; }
            CompoundTag row = residents.getCompound(n);
            residentNames.get(n).setText(Pages.readText(player.registryAccess(), row.get("name")));
            residentDetails.get(n).setText(Pages.readText(player.registryAccess(), row.get("detail")));
            residentButtons.get(n).setText(Component.translatable(row.getBoolean("selected") ? "folkways.combat.selected" : "folkways.combat.select"));
        }
        ListTag groups = tag.getList("groups", Tag.TAG_COMPOUND);
        for (int n = 0; n < 4; n++) {
            groupRows.get(n).setDisplay(n < groups.size());
            if (n >= groups.size()) { continue; }
            CompoundTag row = groups.getCompound(n);
            groupNames.get(n).setText(Component.translatable("folkways.combat.group_row", row.getInt("number"), row.getInt("members")));
            groupButtons.get(n).setText(Component.translatable(row.getBoolean("selected") ? "folkways.combat.selected" : "folkways.combat.select"));
        }
    }
    private static <T extends UIElement> T named(String token, T element) { Tokens.name(element, "folkways.combat." + token); return element; }
}
