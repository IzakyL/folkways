package io.github.izakyl.folkways.plugins.golem;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.plugins.golem.domain.GolemContent;
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

    private static final ResourceLocation METAL_GOLEM =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "metal_golem");

    private GolemScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(GolemContent.ID, "folkways.lessons.golem",
            ResourceLocation.withDefaultNamespace("iron_ingot"),
            Scenes.scene("golems", GolemScene::program)));
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
        Optional<Body> golem = BuiltInRegistries.ENTITY_TYPE.getOptional(METAL_GOLEM)
            .map(type -> PonderScenery.body(scene, (EntityType<?>) type, stand, FACING_SOUTH));
        scene.idle(15);

        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        scene.overlay().showControls(head, Pointing.DOWN, 40).leftClick().withItem(book);
        scene.idle(10);
        scene.overlay().showText(90)
            .text("With the book on pointing, left click a Modular Golem you own. It joins the colony as a resident; click it again and it leaves.")
            .pointAt(head)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        scene.overlay().showText(70)
            .text("Only the golem's owner can hand it over, and one that already belongs to another colony stays there.")
            .pointAt(head)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(80);

        scene.overlay().showText(90)
            .text("In the colony it drops its own goals and picks no fights, and golem command items do nothing to it. When it leaves, it gets its own ways back.")
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
            .text("It works in the trades its body allows: a metal golem hauls and builds, a dog golem only hauls, and a humanoid golem can also craft, drive and dispatch.")
            .pointAt(util.vector().topOf(chest))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);
        golem.ifPresent(body -> body.hold(scene, ItemStack.EMPTY));

        scene.overlay().showText(80)
            .text("It never eats or sleeps. Its knacks are built in, so it does not get better with practice.")
            .pointAt(util.vector().topOf(2, 2, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        golem.ifPresent(body -> body.hold(scene, new ItemStack(Items.IRON_INGOT)));
        scene.overlay().showText(90)
            .text("Below 60% of its health, a golem carrying something golems are repaired with, such as iron ingots, spends one from its pack to mend itself.")
            .pointAt(util.vector().topOf(2, 2, 5))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
        golem.ifPresent(body -> body.hold(scene, ItemStack.EMPTY));
        scene.idle(10);
    }
}
