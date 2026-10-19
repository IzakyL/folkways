package io.github.izakyl.folkways.front.client;

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
        // Asked of every screen: EMI looks a provider up by a screen's exact class, never by what it extends.
        registry.addGenericScreenBoundsProvider(FolkwaysEmiPlugin::deskBounds);
    }

    // The windows on the desk, so EMI's lists stand beside them; empty for any screen that is not the colony's.
    private static Bounds deskBounds(Screen screen) {
        List<Desk.Box> occupied = Desk.occupied(ModularUI.of(screen));
        if (occupied.isEmpty()) {
            return Bounds.EMPTY;
        }
        int left = occupied.stream().mapToInt(Desk.Box::x).min().orElseThrow();
        int top = occupied.stream().mapToInt(Desk.Box::y).min().orElseThrow();
        int right = occupied.stream().mapToInt(box -> box.x() + box.width()).max().orElseThrow();
        int bottom = occupied.stream().mapToInt(box -> box.y() + box.height()).max().orElseThrow();
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
