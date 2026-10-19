package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import io.github.izakyl.folkways.front.api.ui.ItemDrops;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

public final class FilterTargets {
    private static final int HINT_FILL = 0x8822BB33;

    private FilterTargets() {
    }

    // Where a dragged item can land: a panel element that takes one, as a picker's rules do.
    public sealed interface Target permits Element {
        Rect2i area();

        boolean accepts(ItemStack stack);

        void drop(ItemStack stack);

        default boolean contains(int x, int y) {
            return area().contains(x, y);
        }
    }

    record Element(Rect2i area, ItemDrops.Target target) implements Target {
        @Override
        public boolean accepts(ItemStack stack) {
            return target.drop().accepts(stack);
        }

        @Override
        public void drop(ItemStack stack) {
            target.drop().take(stack.copy());
        }

        // Clipped by whatever it sits in, so a button scrolled out of view takes nothing.
        @Override
        public boolean contains(int x, int y) {
            return ItemDrops.contains(target.element(), x, y);
        }
    }

    public static List<Target> of(AbstractContainerScreen<?> screen) {
        List<Target> targets = new ArrayList<>();
        for (ItemDrops.Target target : ItemDrops.shown(ModularUI.of(screen))) {
            var element = target.element();
            targets.add(new Element(new Rect2i(Math.round(element.getPositionX()), Math.round(element.getPositionY()),
                Math.round(element.getSizeWidth()), Math.round(element.getSizeHeight())), target));
        }
        return List.copyOf(targets);
    }

    public static boolean drop(AbstractContainerScreen<?> screen, ItemStack stack, int mouseX, int mouseY) {
        if (stack.isEmpty()) {
            return false;
        }
        for (Target target : of(screen)) {
            if (target.contains(mouseX, mouseY) && target.accepts(stack)) {
                target.drop(stack);
                return true;
            }
        }
        return false;
    }

    public static void renderHint(AbstractContainerScreen<?> screen, GuiGraphics graphics, ItemStack dragged) {
        if (dragged.isEmpty()) {
            return;
        }
        for (Target target : of(screen)) {
            if (!target.accepts(dragged)) {
                continue;
            }
            Rect2i area = target.area();
            graphics.fill(area.getX(), area.getY(),
                area.getX() + area.getWidth(), area.getY() + area.getHeight(), HINT_FILL);
        }
    }
}
