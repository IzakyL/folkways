package io.github.izakyl.folkways.vanilla.carts;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class CartsScene {
    private CartsScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.LOGISTICS, CartsPlugin.OWNER, "folkways.lessons.carts",
            ResourceLocation.fromNamespaceAndPath("astikorcartsredux", "oak_hand_cart"),
            Scenes.scene("folkways_vanilla", "carts", CartsScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("carts", "Cart lines: stations and laps");
        PonderScenery.openWorld(scene, util, 9);
        PonderScenery.grassGround(scene, util, 9);
        scene.world().setBlock(util.grid().at(1, 1, 4), Blocks.BARREL.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(7, 1, 4), Blocks.BARREL.defaultBlockState(), false);
        scene.world().createEntity(level -> {
            var entity = BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("astikorcartsredux", "hand_cart")).create(level);
            if (entity != null) {
                entity.setPos(util.vector().topOf(3, 0, 4));
            }
            return entity;
        });
        scene.world().showSection(util.select().fromTo(0, 0, 0, 8, 2, 8), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(100)
            .text("Draw a line through each station in turn, then right-click and choose Cart line. On its card, choose the Cart and the Route, Out and back or Loop. The colony makes the cart and sets it at the first station.")
            .pointAt(util.vector().topOf(3, 0, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(110);
        scene.overlay().showText(110)
            .text("A hauler pulls the cart from station to station, stopping at each for loading. A supply cart takes a tame adult horse from a pasture, which then stops counting there. Leave wide gates and room at corners.")
            .pointAt(util.vector().topOf(3, 0, 4)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
    }
}
