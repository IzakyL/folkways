package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.front.api.ponder.PonderScenery;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import net.createmod.ponder.api.PonderPalette;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class HousingScene {
    private HousingScene() { }

    public static void program(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("housing", "Housing: a room worth coming home to");
        PonderScenery.openWorld(scene, util, 8);
        PonderScenery.ground(scene, util, 8, Blocks.OAK_PLANKS);
        for (int y = 1; y <= 3; y++) {
            for (BlockPos wall : PonderScenery.ring(util, 1, 1, 6, 6, y)) {
                scene.world().setBlock(wall, Blocks.OAK_PLANKS.defaultBlockState(), false);
            }
        }
        scene.world().setBlocks(util.select().fromTo(1, 4, 1, 6, 4, 6), Blocks.OAK_PLANKS.defaultBlockState(), false);
        for (int x = 2; x <= 5; x++) {
            scene.world().setBlock(util.grid().at(x, 1, 4), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.HEAD), false);
            scene.world().setBlock(util.grid().at(x, 1, 5), Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.NORTH).setValue(BedBlock.PART, BedPart.FOOT), false);
        }
        BlockPos door = util.grid().at(4, 1, 1);
        scene.world().setBlock(door, Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH), false);
        scene.world().setBlock(door.above(), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.NORTH).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), false);
        scene.world().setBlock(util.grid().at(3, 3, 3), Blocks.GLOWSTONE.defaultBlockState(), false);
        scene.world().showSection(util.select().fromTo(0, 0, 0, 7, 4, 7), Direction.DOWN);
        scene.idle(15);
        scene.overlay().showText(125)
            .text("Put beds in an enclosed room with a roof and a door residents can walk through. Every floor block needs light level 8. Otherwise, or with under 4 floor blocks per bed, it is a Basic bed.")
            .pointAt(util.vector().centerOf(door)).placeNearTarget().attachKeyFrame();
        scene.idle(135);
        // Open a cutaway after showing the enclosure.
        scene.world().hideSection(util.select().fromTo(1, 4, 1, 6, 4, 6), Direction.UP);
        scene.world().hideSection(util.select().fromTo(1, 1, 1, 6, 3, 1), Direction.NORTH);
        scene.world().hideSection(util.select().fromTo(1, 1, 2, 1, 3, 6), Direction.WEST);
        scene.idle(20);
        scene.world().setBlocks(util.select().fromTo(4, 1, 4, 5, 1, 5), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(2, 1, 2), Blocks.CHEST.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(4, 1, 3), Blocks.RED_CARPET.defaultBlockState(), false);
        scene.overlay().showText(115)
            .text("A Suitable dormitory has 4 floor blocks per bed. Comfortable housing needs 6 per bed, at most 4 beds and 2 kinds of furnishing.")
            .pointAt(util.vector().topOf(3, 0, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(125);
        scene.world().setBlocks(util.select().fromTo(3, 1, 4, 3, 1, 5), Blocks.AIR.defaultBlockState(), false);
        scene.world().setBlock(util.grid().at(5, 1, 2), Blocks.BOOKSHELF.defaultBlockState(), false);
        scene.overlay().showOutline(PonderPalette.GREEN, "room", util.select().fromTo(2, 0, 2, 5, 0, 5), 140);
        scene.overlay().showText(140)
            .text("An Excellent bedroom needs 9 per bed, at most 2 beds and 3 kinds of furnishing, one of them decoration. The kinds are storage, carpets, decoration, tables and lighting; copies of one kind count once.")
            .pointAt(util.vector().topOf(3, 0, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(150);
        scene.world().setBlock(util.grid().at(5, 1, 3), Blocks.FURNACE.defaultBlockState(), false);
        scene.overlay().showOutline(PonderPalette.RED, "furnace", util.select().position(5, 1, 3), 110);
        scene.overlay().showText(110)
            .text("Keep work blocks such as crafting tables and furnaces out of an Excellent bedroom. Hold the book to read each bed's grade and advice.")
            .pointAt(util.vector().topOf(5, 1, 3)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
        scene.world().setBlock(util.grid().at(5, 1, 3), Blocks.AIR.defaultBlockState(), false);
        var resident = PonderScenery.body(scene, PersonBody.RESIDENT.get(), util.vector().topOf(4, 0, 5), 0);
        scene.effects().emitParticles(resident.at().add(0, 2.1, 0),
            scene.effects().simpleParticleEmitter(ParticleTypes.HAPPY_VILLAGER, Vec3.ZERO), 0.3F, 10);
        scene.overlay().showText(125)
            .text("The icon after a resident's name shows mood: sad below 40, neutral from 40, happy from 70. With the book held, sparks show when something raises or lowers it.")
            .pointAt(resident.at().add(0, 1.5, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(45);
        scene.effects().emitParticles(resident.at().add(0, 2.1, 0),
            scene.effects().simpleParticleEmitter(ParticleTypes.ANGRY_VILLAGER, Vec3.ZERO), 0.2F, 10);
        scene.idle(90);
        scene.overlay().showText(110)
            .text("Mood sets work speed: ×0.75 at 0, normal at 60, ×1.15 at 100. Food, rest, housing, company and recreation all help.")
            .pointAt(resident.at().add(0, 1.5, 0)).placeNearTarget().attachKeyFrame();
        scene.idle(120);
    }
}
