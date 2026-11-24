package io.github.izakyl.folkways.core.shell.golems;

import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.core.front.api.ponder.Scenes;
import io.github.izakyl.folkways.core.shell.api.GolemKindIds;
import io.github.izakyl.folkways.core.shell.golems.domain.GolemContent;
import java.util.Optional;
import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// Registered only with Modular Golems loaded; the golem is looked up by id so this class never loads that mod's own.
@OnlyIn(Dist.CLIENT)
public final class GolemScene {

    private static final int PLATE = 7;
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_WEST = 90.0F;

    private GolemScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(Scenes.Category.PRODUCTION, GolemContent.ID, "folkways.lessons.golem",
            ResourceLocation.withDefaultNamespace("iron_ingot"),
            Scenes.scene("folkways_core", "golems", GolemScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("golems", "Golems: put your Modular Golems to work");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(1, 1, 5);
        scene.world().setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST),
            false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(10);

        Vec3 stand = util.vector().topOf(3, 0, 3);
        Vec3 head = stand.add(0, 2.5D, 0);
        Optional<Body> golem = BuiltInRegistries.ENTITY_TYPE.getOptional(GolemKindIds.METAL)
            .map(type -> PonderScenery.body(scene, (EntityType<?>) type, stand, FACING_SOUTH));
        scene.idle(15);

        ItemStack book = PonderScenery.book(Shape.Gesture.POINT);
        scene.overlay().showControls(head, Pointing.DOWN, 40).leftClick().withItem(book);
        scene.idle(10);
        scene.overlay().showText(90)
            .text("With Take in picked, left-click a Modular Golem you own. It joins as a resident; click again to let it go.")
            .pointAt(head)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(70)
            .text("Only the golem's owner can hand it over. A golem already in another colony stays there.")
            .pointAt(head)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showText(90)
            .text("In the colony it drops its own goals, starts no fights and ignores golem command items. It returns to normal when it leaves.")
            .pointAt(head)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        golem.ifPresent(body -> {
            body.walkTo(scene, util.vector().topOf(2, 0, 5));
            body.turnTo(scene, FACING_WEST);
            body.hold(scene, new ItemStack(Items.OAK_LOG, 16));
        });
        scene.idle(10);
        scene.overlay().showOutline(PonderPalette.GREEN, "storage", util.select().position(chest), 90);
        scene.overlay().showText(100)
            .text("Golems can haul. Which other trades they take depends on their body.")
            .pointAt(util.vector().topOf(chest))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);
        golem.ifPresent(body -> body.hold(scene, ItemStack.EMPTY));

        scene.overlay().showText(80)
            .text("It never eats or sleeps, and does not improve with practice.")
            .pointAt(util.vector().topOf(2, 2, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        golem.ifPresent(body -> body.hold(scene, new ItemStack(Items.IRON_INGOT)));
        scene.overlay().showText(90)
            .text("Below 60% health, it uses one repair item it carries, such as an iron ingot, to mend itself.")
            .pointAt(util.vector().topOf(2, 2, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        golem.ifPresent(body -> body.hold(scene, ItemStack.EMPTY));
        scene.idle(10);
    }
}
