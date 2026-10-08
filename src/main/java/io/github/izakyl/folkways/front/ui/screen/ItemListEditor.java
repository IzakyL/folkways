package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ItemSlot;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.colony.ColonySchemas;
import io.github.izakyl.folkways.front.engine.net.FilterAction;
import io.github.izakyl.folkways.front.engine.net.FilterActionPacket;
import io.github.izakyl.folkways.front.ui.menu.ColonyItemGrid;
import io.github.izakyl.folkways.front.ui.menu.FilterInventory;
import io.github.izakyl.folkways.front.ui.menu.FilterSlot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ItemListEditor {

    public static final int COLS = 12;
    public static final int ROWS = 4;
    private static final int CELL = 18;

    private final FilterInventory entries = new FilterInventory(COLS * ROWS);
    private final ColonyItemGrid bound;
    private final List<FilterSlot> cells = new ArrayList<>();
    private Pane pane;

    private boolean showing;

    private ItemListEditor(Player player) {
        bound = new ColonyItemGrid(() -> Pages.colonyOf(player));
        entries.addListener(changed -> bound.itemsChanged(entries));
        for (int index = 0; index < COLS * ROWS; index++) {
            cells.add(new FilterSlot(entries, index, 0, 0).activeWhen(() -> showing));
        }
    }

    public static ItemListEditor create(Player player) {
        return new ItemListEditor(player);
    }

    public UIElement build() {
        UIElement grid = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).gapAll(0));
        UIElement row = null;
        for (int index = 0; index < cells.size(); index++) {
            if (index % COLS == 0) {
                row = new UIElement()
                    .layout(layout -> layout.flexDirection(FlexDirection.ROW).gapAll(0));
                grid.addChildren(row);
            }
            row.addChildren(cell(cells.get(index)));
        }
        Button back = new Button()
            .setText(Component.translatable("folkways.action.back"))
            .setOnClick(event -> put(Optional.empty(), ""))
            .setOnServerClick(event -> put(Optional.empty(), ""));
        back.layout(layout -> layout.flexShrink(0));
        Tokens.name(back, "folkways.settings.back");
        return Rows.page().addChildren(grid, Rows.strip().addChildren(back));
    }

    void livesIn(Pane window) {
        pane = window;
        window.onDismiss(() -> put(Optional.empty(), ""));
    }

    public static void open(Player player, ResourceLocation scope, String key) {
        of(player).ifPresent(editor -> editor.put(Optional.of(scope), key));
    }

    private static Optional<ItemListEditor> of(Player player) {
        return ColonyShell.of(player).flatMap(ColonyShell::itemLists);
    }

    private void put(Optional<ResourceLocation> scope, String key) {
        showing = scope.isPresent();
        if (LDLib2.isRemote()) {
            Component named = scope.flatMap(present -> ColonySchemas.of(present).find(key))
                .map(Schema.Setting::name)
                .orElse(Component.empty());
            if (pane != null) {
                pane.title(named);
                if (showing) {
                    pane.open();
                } else {
                    pane.close();
                }
            }
        } else {
            bound.show(entries, scope, key);
        }
    }

    private static ItemSlot cell(FilterSlot slot) {
        ItemSlot element = new ItemSlot(slot);
        element.layout(layout -> layout.width(CELL).height(CELL));
        element.addEventListener(UIEvents.MOUSE_DOWN, event -> onPress(event, element, slot));
        return element;
    }

    private static void onPress(UIEvent event, ItemSlot element, FilterSlot slot) {
        event.hasHandler = true;
        event.stopPropagation();
        if (!LDLib2.isRemote()) {
            return;
        }
        if (event.button == 0) {
            if (carried(element).isEmpty() && slot.getItem().isEmpty()) {
                SelectorDialog.open(element, stack -> send(FilterAction.DROP, slot.index, stack));
                return;
            }
            send(FilterAction.STEP_UP, slot.index, ItemStack.EMPTY);
        } else if (event.button == 1) {
            send(FilterAction.STEP_DOWN, slot.index, ItemStack.EMPTY);
        }
    }

    private static ItemStack carried(ItemSlot element) {
        var modularUI = element.getModularUI();
        if (modularUI == null) {
            return ItemStack.EMPTY;
        }
        AbstractContainerMenu menu = modularUI.getMenu();
        return menu == null ? ItemStack.EMPTY : menu.getCarried();
    }

    private static void send(FilterAction action, int slotIndex, ItemStack stack) {
        PacketDistributor.sendToServer(new FilterActionPacket(action, slotIndex, stack));
    }
}
