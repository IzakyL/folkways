package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.ArrayList;
import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

@JeiPlugin
public final class FolkwaysJeiPlugin implements IModPlugin {

    private static final ResourceLocation UID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "jei_plugin");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGhostIngredientHandler(AbstractContainerScreen.class, new DemandSlotGhostHandler());
    }

    private static final class DemandSlotGhostHandler
            implements IGhostIngredientHandler<AbstractContainerScreen> {

        @SuppressWarnings("unchecked")
        @Override
        public <I> List<Target<I>> getTargetsTyped(AbstractContainerScreen screen,
                ITypedIngredient<I> ingredient, boolean doStart) {
            ItemStack stack = ingredient.getItemStack().orElse(ItemStack.EMPTY);
            if (stack.isEmpty()) {
                return List.of();
            }
            List<Target<I>> targets = new ArrayList<>();
            for (Rect2i area : areasOn(screen)) {
                targets.add((Target<I>) new SlotTarget(screen, area, stack));
            }
            return targets;
        }

        @Override
        public void onComplete() {
        }
    }

    private static List<Rect2i> areasOn(AbstractContainerScreen<?> screen) {
        return FilterTargets.of(screen).stream().map(FilterTargets.Target::area).toList();
    }

    private record SlotTarget(AbstractContainerScreen<?> screen, Rect2i area, ItemStack stack)
            implements IGhostIngredientHandler.Target<Object> {

        @Override
        public Rect2i getArea() {
            return area;
        }

        @Override
        public void accept(Object ingredient) {
            FilterTargets.drop(screen, stack,
                area.getX() + area.getWidth() / 2,
                area.getY() + area.getHeight() / 2);
        }
    }
}
