package io.github.izakyl.folkways.core.equipment.internal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.front.api.ui.Inlay;
import io.github.izakyl.folkways.core.front.api.ui.Nook;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.resident.api.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

/** The resident card's equipment lines: the profile, worn equipment, accessories and combined inventory. */
public final class EquipmentInlay implements Inlay {

    @Override
    public UIElement build(Nook nook) {
        UIElement page = Rows.page();
        Tokens.name(page, "folkways.resident.equipment");
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> nook.subject()
                .map(EquipmentInlay::reading).orElseGet(CompoundTag::new))
            .onSyncReceived(value -> {
                page.clearAllChildren();
                CompoundTag read = value instanceof CompoundTag tag ? tag : new CompoundTag();
                ListTag lines = read.getList("lines", Tag.TAG_COMPOUND);
                page.setDisplay(!lines.isEmpty());
                if (lines.isEmpty()) {
                    return;
                }
                page.addChildren(Rows.heading(Component.translatable("folkways.equipment.title")));
                var registries = Minecraft.getInstance().level.registryAccess();
                for (Tag line : lines) {
                    page.addChildren(Rows.note(Pages.readText(registries, ((CompoundTag) line).get("text"))));
                }
            }).build().getSyncValue());
        return page;
    }

    private static CompoundTag reading(Body body) {
        CompoundTag tag = new CompoundTag();
        ListTag lines = new ListTag();
        body.colonyId().flatMap(id -> Colonies.of(body.mob().getServer(), id))
            .flatMap(colony -> colony.service(EquipmentProfiles.ID, ProfileLedger.class)).ifPresent(ledger -> {
                for (Component text : ledger.carried().details(body)) {
                    CompoundTag row = new CompoundTag();
                    row.put("text", Pages.text(body.mob().level().registryAccess(), text));
                    lines.add(row);
                }
            });
        tag.put("lines", lines);
        return tag;
    }
}
