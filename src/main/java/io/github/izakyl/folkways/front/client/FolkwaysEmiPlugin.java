package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import io.github.izakyl.folkways.front.ui.screen.Desk;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;

@EmiEntrypoint
public final class FolkwaysEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addGenericDragDropHandler(new DemandSlotDragHandler());
        registry.addScreenBoundsProvider(AbstractContainerScreen.class, FolkwaysEmiPlugin::deskBounds);
    }

    private static Bounds deskBounds(AbstractContainerScreen<?> screen) {
        List<Desk.Box> occupied = Desk.occupied(ModularUI.of(screen));
        if (occupied.isEmpty()) {
            return new Bounds(screen.getGuiLeft(), screen.getGuiTop(), screen.getXSize(), screen.getYSize());
        }
        int left = occupied.stream().mapToInt(Desk.Box::x).min().orElseThrow();
        int top = occupied.stream().mapToInt(Desk.Box::y).min().orElseThrow();
        int right = occupied.stream().mapToInt(box -> box.x() + box.width()).max().orElseThrow();
        int bottom = occupied.stream().mapToInt(box -> box.y() + box.height()).max().orElseThrow();
        if (!(screen instanceof ModularUIContainerScreen)) {
            left = Math.min(left, screen.getGuiLeft());
            top = Math.min(top, screen.getGuiTop());
            right = Math.max(right, screen.getGuiLeft() + screen.getXSize());
            bottom = Math.max(bottom, screen.getGuiTop() + screen.getYSize());
        }
        return new Bounds(left, top, right - left, bottom - top);
    }

    private static final class DemandSlotDragHandler implements EmiDragDropHandler<Screen> {
        @Override
        public boolean dropStack(Screen screen, EmiIngredient dragged, int x, int y) {
            if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
                return false;
            }
            List<EmiStack> stacks = dragged.getEmiStacks();
            if (stacks.isEmpty()) {
                return false;
            }
            ItemStack stack = stacks.get(0).getItemStack();
            if (stack.isEmpty()) {
                return false;
            }
            return FilterTargets.drop(containerScreen, stack, x, y);
        }

        @Override
        public void render(Screen screen, EmiIngredient dragged, GuiGraphics graphics, int x, int y, float delta) {
            if (screen instanceof AbstractContainerScreen<?> containerScreen) {
                List<EmiStack> stacks = dragged.getEmiStacks();
                FilterTargets.renderHint(containerScreen, graphics,
                    stacks.isEmpty() ? ItemStack.EMPTY : stacks.get(0).getItemStack());
            }
        }
    }
}
