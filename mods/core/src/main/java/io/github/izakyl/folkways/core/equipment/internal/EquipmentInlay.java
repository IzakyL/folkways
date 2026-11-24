package io.github.izakyl.folkways.core.equipment.internal;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.front.api.ui.Inlay;
import io.github.izakyl.folkways.core.front.api.ui.Nook;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Slot;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.resident.api.Body;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The resident card's equipment lines: the profile, worn equipment, accessories and combined inventory, the items
 * each in a slot with its count.
 */
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
                    CompoundTag row = (CompoundTag) line;
                    page.addChildren(Rows.note(Pages.readText(registries, row.get("text"))));
                    ListTag items = row.getList("items", Tag.TAG_COMPOUND);
                    if (!items.isEmpty()) {
                        page.addChildren(grid(registries, items));
                    }
                }
            }).build().getSyncValue());
        return page;
    }

    // The items in slots as a container shows them, wrapping onto further lines when the card is narrow.
    private static UIElement grid(HolderLookup.Provider registries, ListTag items) {
        UIElement grid = Rows.strip();
        grid.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(1).flexShrink(0));
        for (Tag item : items) {
            CompoundTag held = (CompoundTag) item;
            ItemStack stack = ItemStack.parseOptional(registries, held.getCompound("stack"));
            if (!stack.isEmpty()) {
                grid.addChildren(new Slot(stack.copyWithCount(Math.max(1, held.getInt("count")))));
            }
        }
        return grid;
    }

    private static CompoundTag reading(Body body) {
        CompoundTag tag = new CompoundTag();
        ListTag lines = new ListTag();
        body.colonyId().flatMap(id -> Colonies.of(body.mob().getServer(), id))
            .flatMap(colony -> colony.service(EquipmentProfiles.ID, ProfileLedger.class)).ifPresent(ledger -> {
                var registries = body.mob().level().registryAccess();
                for (CarriedKit.Detail detail : ledger.carried().details(body)) {
                    CompoundTag row = new CompoundTag();
                    row.put("text", Pages.text(registries, detail.text()));
                    ListTag items = new ListTag();
                    for (ItemStack stack : detail.items()) {
                        // The count rides beside a single item, so a pack cell holding more than a stack still reads.
                        CompoundTag held = new CompoundTag();
                        held.put("stack", stack.copyWithCount(1).save(registries));
                        held.putInt("count", stack.getCount());
                        items.add(held);
                    }
                    row.put("items", items);
                    lines.add(row);
                }
            });
        tag.put("lines", lines);
        return tag;
    }
}
