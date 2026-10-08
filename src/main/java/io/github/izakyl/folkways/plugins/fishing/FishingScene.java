package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class FishingScene {

    private static final int PLATE = 9;
    private static final float FACING_EAST = 270.0F;

    private static final PonderPalette FISHERY = PonderPalette.INPUT;

    private FishingScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(FishingContent.ID, "folkways.lessons.fishing",
            ResourceLocation.withDefaultNamespace("fishing_rod"),
            Scenes.scene("fishing", FishingScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("fishing", "Fishing: frame the bank, not the water");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);
        scene.world().setBlocks(util.select().fromTo(5, 0, 0, 8, 0, 8), Blocks.WATER.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(4, 0, 2, 4, 0, 4), Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.world().setBlocks(util.select().fromTo(4, 0, 6, 4, 0, 7), Blocks.OAK_PLANKS.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 1, 0, PLATE - 1, 2, PLATE - 1), Direction.DOWN);
        scene.idle(15);

        scene.overlay().showOutline(FISHERY, "fishery", util.select().fromTo(4, 0, 2, 4, 1, 4), 190);
        scene.overlay().showText(80)
            .text("A fishery frames the bank the residents stand on.")
            .colored(FISHERY)
            .pointAt(util.vector().topOf(4, 0, 3))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        scene.overlay().showOutline(PonderPalette.RED, "water", util.select().fromTo(5, 0, 0, 8, 0, 8), 80);
        scene.overlay().showText(80)
            .text("Never the water itself. Frame the bank, and the water within reach of it is what gets fished.")
            .colored(PonderPalette.RED)
            .pointAt(util.vector().topOf(6, 0, 3))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        BlockPos spot = util.grid().at(5, 0, 3);
        BlockPos otherSpot = util.grid().at(5, 0, 6);
        scene.overlay().showOutline(FISHERY, "spot", util.select().position(spot), 100);
        scene.overlay().showOutline(PonderPalette.GREEN, "second", util.select().fromTo(4, 0, 6, 4, 1, 7), 100);
        scene.overlay().showOutline(PonderPalette.GREEN, "second_spot", util.select().position(otherSpot), 100);
        scene.overlay().showText(90)
            .text("Each fishery fishes the one block of water nearest its bank, and two fisheries never share one. If that block is already taken, the second fishery sits idle.")
            .pointAt(util.vector().topOf(spot))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        Body fisher = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(1, 0, 3), FACING_EAST);
        scene.idle(10);
        fisher.walkTo(scene, util.vector().topOf(4, 0, 3));
        fisher.turnTo(scene, FACING_EAST);
        fisher.hold(scene, new ItemStack(Items.FISHING_ROD));
        scene.idle(10);
        scene.overlay().showText(90)
            .text("A rod is the one tool a cast asks for, and it has to be on the Tools list under Settings.")
            .pointAt(util.vector().topOf(4, 0, 3).add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        Vec3 hand = util.vector().topOf(4, 0, 3).add(0.6, 1.3, 0.0);
        Vec3 bob = util.vector().topOf(spot).add(0.0, -0.1, 0.0);
        scene.overlay().showLine(FISHERY, hand, bob, 110);
        scene.overlay().showText(100)
            .text("Then they stand there and wait out the bite. The line running to the float is how you tell fishing apart from idling.")
            .pointAt(bob)
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        fisher.hold(scene, new ItemStack(Items.COD));
        scene.overlay().showText(80)
            .text("Practice shortens the wait: the Quick hands perk brings the bite on sooner.")
            .pointAt(util.vector().topOf(4, 0, 3).add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);
    }
}
