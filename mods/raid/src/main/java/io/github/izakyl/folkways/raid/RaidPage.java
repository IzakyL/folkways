package io.github.izakyl.folkways.raid;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class RaidPage implements Draw {

    private UUID context;
    private final Label status = Rows.text(Component.empty());
    private final Label direction = Rows.text(Component.empty());
    private final Label damage = Rows.text(Component.empty());
    private final Label loot = Rows.text(Component.empty());
    private Button toggle;

    @Override
    public UIElement build(Player player) {
        context = Pages.colonyOf(player).map(colony -> colony.id()).orElse(null);
        toggle =
            new Button()
            .setText(Component.empty())
            .setOnServerClick(
            event -> {
                if (player instanceof ServerPlayer server) {
                    director(player).ifPresent(director -> director.setEnabled(server, !director.enabled()));
                }
            });
        toggle.layout(layout -> layout.widthAuto().flexShrink(0));
        Tokens.name(toggle, "folkways.raids.toggle");
        Tokens.name(status, "folkways.raids.status");
        Tokens.name(damage, "folkways.raids.damage");
        Tokens.name(loot, "folkways.raids.loot");
        UIElement content =
            Rows.page()
            .addChildren(
            Rows.note(Component.translatable("folkways.raid.help")),
            status,
            direction,
            Rows.heading(Component.translatable("folkways.raid.report")),
            damage,
            loot,
            toggle);
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(content);
        UIElement page = Rows.fills(Rows.page().addChildren(scroll));
        page.addSyncValue(
            DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(tag -> fill(player, tag))
            .build()
            .getSyncValue());
        return page;
    }

    private Optional<RaidDirector> director(Player player) {
        return Pages.colonyOf(player).filter(colony -> colony.id().equals(context)).flatMap(RaidDirector::of);
    }

    private Tag reading(Player player) {
        return player instanceof ServerPlayer server
            ? director(player).map(director -> director.reading(server)).orElseGet(CompoundTag::new)
            : new CompoundTag();
    }

    private void fill(Player player, Tag value) {
        CompoundTag tag = value instanceof CompoundTag compound ? compound : new CompoundTag();
        status.setText(Pages.readText(player.registryAccess(), tag.get("status")));
        direction.setText(Pages.readText(player.registryAccess(), tag.get("direction")));
        damage.setText(Pages.readText(player.registryAccess(), tag.get("damage")));
        loot.setText(Pages.readText(player.registryAccess(), tag.get("loot")));
        toggle.setText(
            Component.translatable(tag.getBoolean("enabled") ? "folkways.raid.disable" : "folkways.raid.enable"));
    }
}
