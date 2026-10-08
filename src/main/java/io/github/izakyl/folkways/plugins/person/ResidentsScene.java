package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.front.api.ponder.PonderScenery.Body;
import io.github.izakyl.folkways.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.front.api.ponder.Scenes;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.createmod.ponder.api.scene.Selection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@OnlyIn(Dist.CLIENT)
public final class ResidentsScene {

    private static final int PLATE = 9;
    private static final float FACING_NORTH = 180.0F;
    private static final float FACING_SOUTH = 0.0F;
    private static final float FACING_WEST = 90.0F;

    private ResidentsScene() {
    }

    public static void declare(FMLClientSetupEvent event) {
        event.enqueueWork(() -> Scenes.folder(PersonContent.ID, "folkways.page.person",
            ResourceLocation.withDefaultNamespace("bell"),
            Scenes.scene("residents", ResidentsScene::program)));
    }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("residents", "Residents: who comes, what they take on, how they look");
        PonderScenery.openWorld(scene, util, PLATE);
        PonderScenery.grassGround(scene, util, PLATE);

        BlockPos chest = util.grid().at(3, 1, 1);
        BlockPos bedHead = util.grid().at(1, 1, 1);
        BlockPos bedFoot = util.grid().at(1, 1, 2);
        BlockPos harvested = util.grid().at(6, 1, 5);

        scene.world().setBlock(chest,
            Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), false);
        scene.world().setBlock(bedHead, bed(BedPart.HEAD), false);
        scene.world().setBlock(bedFoot, bed(BedPart.FOOT), false);

        Selection bedArea = util.select().fromTo(bedHead, bedFoot);
        Selection field = util.select().fromTo(6, 1, 5, 7, 1, 6);
        scene.world().setBlocks(util.select().fromTo(6, 0, 5, 7, 0, 6),
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7), false);
        scene.world().setBlocks(field, ripeWheat(), false);

        Selection pen = util.select().fromTo(1, 1, 6, 3, 1, 8);
        PonderScenery.fence(scene, Blocks.OAK_FENCE, PonderScenery.ring(util, 1, 6, 3, 8, 1));

        scene.world().showSection(util.select().fromTo(0, 0, 0, PLATE - 1, 1, PLATE - 1), Direction.DOWN);
        scene.idle(20);

        Vec3 pasture = util.vector().topOf(2, 0, 7);
        scene.world().createEntity(level -> {
            Cow cow = EntityType.COW.create(level);
            if (cow == null) {
                return null;
            }
            PonderScenery.stand(cow, pasture, FACING_NORTH);
            return cow;
        });
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.BLUE, "bed", bedArea, 90);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "chest", util.select().position(chest), 90);
        scene.overlay()
            .showText(80)
            .text("A newcomer needs room: a free bed joined with the book, and stored food for every resident plus one more mouth.")
            .pointAt(util.vector().topOf(bedFoot))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(90);

        Vec3 spawnA = util.vector().topOf(4, 0, 2);
        Body residentA = PonderScenery.body(scene, PersonBody.RESIDENT.get(), spawnA, FACING_SOUTH);
        scene.idle(15);

        scene.overlay()
            .showText(100)
            .text("Someone must also be waiting. Newcomers gather over time, more slowly as the colony grows, and only a few wait at once. Let one in on the Residents page brings the next, unless the server has switched arrivals off.")
            .pointAt(spawnA.add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        Vec3 spawnB = util.vector().topOf(3, 0, 3);
        Body residentB = PonderScenery.body(scene, PersonBody.RESIDENT.get(), spawnB, FACING_SOUTH);
        scene.idle(10);

        scene.overlay().showOutline(PonderPalette.GREEN, "field", field, 150);
        scene.overlay().showOutline(PonderPalette.OUTPUT, "pen", pen, 150);
        scene.overlay()
            .showText(110)
            .text("On the Workforce tab each resident has a cell per trade: click it to allow or forbid that trade, scroll it to rank it 1 to 4. You rank trades, never tasks, and a forbidden trade is never handed to him.")
            .pointAt(util.vector().topOf(4, 0, 4).add(0.0, 1.5, 0.0))
            .attachKeyFrame();
        scene.idle(20);

        residentA.walkTo(scene, util.vector().topOf(6, 0, 4));
        residentB.walkTo(scene, util.vector().topOf(2, 0, 5));
        scene.idle(60);

        scene.world().destroyBlock(harvested);
        scene.idle(15);
        scene.world().setBlock(harvested, youngWheat(), false);
        scene.idle(10);

        scene.overlay()
            .showText(100)
            .text("Work in a trade levels a resident up in it, and each level draws a perk for that trade: quicker hands, more ground per trip, a seed put back as he cuts. The same work draws general ones too, like a brisker walk or a bigger pack.")
            .pointAt(util.vector().topOf(harvested))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        scene.overlay()
            .showText(100)
            .text("A resident's look is drawn from a pool that data packs fill, with skins or bedrock models of their own. The Appearances page strikes looks out of the pool for this colony, and the arrows on a resident's card step him through the rest.")
            .pointAt(residentB.at().add(0.0, 1.8, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(110);

        Vec3 lurking = util.vector().topOf(8, 0, 4);
        scene.world().createEntity(level -> {
            Zombie zombie = EntityType.ZOMBIE.create(level);
            if (zombie == null) {
                return null;
            }
            PonderScenery.stand(zombie, lurking, FACING_WEST);
            return zombie;
        });
        scene.idle(20);
        residentA.hold(scene, ItemStack.EMPTY);
        residentA.walkTo(scene, util.vector().topOf(3, 0, 4));
        scene.overlay()
            .showText(90)
            .text("Struck by a mob or a player, or caught by fire, a resident drops what he is at and runs away from it.")
            .pointAt(residentA.at().add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);

        residentA.turnTo(scene, FACING_SOUTH);
        scene.overlay().showOutline(PonderPalette.RED, "dismissed",
            util.select().fromTo(3, 1, 4, 3, 2, 4), 90);
        scene.overlay()
            .showText(90)
            .text("Pointing the book at one of your residents strikes him off the roster. If one dies, everyone carrying the colony's book is told.")
            .pointAt(residentA.at().add(0.0, 1.5, 0.0))
            .placeNearTarget()
            .attachKeyFrame();
        scene.idle(100);
    }

    private static BlockState bed(BedPart part) {
        return Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, part);
    }

    private static BlockState ripeWheat() {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
    }

    private static BlockState youngWheat() {
        return Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0);
    }
}
