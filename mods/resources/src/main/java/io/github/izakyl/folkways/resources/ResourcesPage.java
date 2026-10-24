package io.github.izakyl.folkways.resources;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.InputSubstitutions;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import java.util.HashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public final class ResourcesPage implements Draw {
    @Override
    public UIElement build(Player player) {
        UIElement table = Rows.page();
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(table);
        scroll.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player)).onSyncReceived(value -> {
            table.clearAllChildren();
            table.addChildren(Rows.note(Component.translatable("folkways.resources.explanation")));
            if (!(value instanceof CompoundTag tag)) return;
            ListTag rows = tag.getList("rows", Tag.TAG_COMPOUND);
            for (int index = 0; index < rows.size(); index++) {
                CompoundTag row = rows.getCompound(index);
                table.addChildren(Rows.row().addChildren(
                    Rows.name(Pages.readText(player.registryAccess(), row.get("name"))),
                    Rows.value(Component.translatable("folkways.resources.stock", row.getLong("stock")))),
                    Rows.note(Pages.readText(player.registryAccess(), row.get("members"))),
                    Rows.note(Component.translatable("folkways.resources.cost", row.getInt("cost"))));
            }
        }).build().getSyncValue());
        return scroll;
    }

    private static Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        Pages.levelOf(player).ifPresent(level -> Pages.colonyOf(player).ifPresent(colony -> {
            for (InputSubstitutions.Rule rule : ResourceCatalog.current()) {
                CompoundTag row = new CompoundTag();
                row.put("name", Pages.text(level.registryAccess(), Goods.display(rule.payment()).getHoverName()));
                long held = 0;
                for (var at : new HashSet<>(colony.storesIn(level.dimension()))) {
                    var store = colony.store(level, at);
                    if (store.isPresent()) held += Goods.countIn(store.get(), rule.payment());
                }
                row.putLong("stock", held);
                row.putInt("cost", rule.cost());
                var members = Component.empty();
                Goods.members(rule.targets()).stream().sorted(java.util.Comparator.comparing(item ->
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item))).forEach(item -> {
                        if (!members.getSiblings().isEmpty()) members.append(", ");
                        members.append(item.getDescription());
                    });
                row.put("members", Pages.text(level.registryAccess(),
                    Component.translatable("folkways.resources.substitutes", members)));
                rows.add(row);
            }
        }));
        tag.put("rows", rows);
        return tag;
    }
}
