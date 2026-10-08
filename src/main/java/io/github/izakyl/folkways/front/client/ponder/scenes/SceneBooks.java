package io.github.izakyl.folkways.front.client.ponder.scenes;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

// Stacks and bodies the basics scenes share. The colony a scene's book is bound to never exists;
// any id makes the book look and read as bound.
@OnlyIn(Dist.CLIENT)
final class SceneBooks {

    private static final UUID SCENE_COLONY = new UUID(0L, 1L);
    private static final ResourceLocation RESIDENT = ResourceLocation.fromNamespaceAndPath("folkways", "resident");

    private SceneBooks() {
    }

    static ItemStack blank() {
        return new ItemStack(FolkwaysItems.COLONY_BOOK.get());
    }

    static ItemStack bound() {
        ItemStack book = blank();
        ColonyBookItem.linkExistingColony(book, SCENE_COLONY);
        return book;
    }

    static ItemStack bound(Shape.Gesture gesture) {
        ItemStack book = bound();
        ColonyBookItem.setGesture(book, gesture);
        return book;
    }

    // The resident body belongs to the person plugin; front looks it up by id instead of importing it.
    // Every text beat must run whether or not it is there (the lang keys count them), so a missing
    // resident falls back to the registry's default body rather than dropping a beat.
    static EntityType<?> resident() {
        return BuiltInRegistries.ENTITY_TYPE.get(RESIDENT);
    }
}
