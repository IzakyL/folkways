package io.github.izakyl.folkways.core.api.ground;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GroundGameTests {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GroundGameTests.class);
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void reachAcrossSectionsFollowsTheWorldAndTrialsLeaveItAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        room(helper, 6, 8);
        room(helper, 13, 15);
        Mob walker = walker(helper, at(helper, 3, 1, 7));
        BlockPos far = at(helper, 20, 1, 7);
        BlockPos sealed = at(helper, 10, 1, 14);
        try (Grounds.Watch watch = Grounds.watch(level, BoundingBox.fromCorners(at(helper, 2, 0, 5), at(helper, 21, 3, 16)))) {
            Ground ground = watch.now(List.of(walker));
            helper.assertTrue(ground.standable(far), "the far end of the hall is somewhere to stand");
            helper.assertTrue(ground.reachable(far), "reach carries across several 8x8x8 sections");
            helper.assertTrue(ground.standable(sealed) && !ground.reachable(sealed), "a sealed room is not reached");
            helper.assertTrue(!ground.stancesFor(at(helper, 20, 2, 7)).isEmpty(), "a cell in the hall can be worked");
            helper.assertTrue(ground.stancesFor(at(helper, 10, 2, 14)).isEmpty(), "a cell in the sealed room cannot");

            Map<BlockPos, BlockState> wall = new HashMap<>();
            for (int z = 6; z <= 8; z++) {
                wall.put(at(helper, 12, 1, z), STONE);
                wall.put(at(helper, 12, 2, z), STONE);
            }
            Ground walled = ground.with(wall);
            helper.assertTrue(!walled.reachable(far), "a trial wall cuts the hall");
            helper.assertTrue(walled.lostSince(ground).contains(far), "the cut-off end is reported lost");
            helper.assertTrue(watch.now(List.of(walker)).reachable(far), "a trial leaves the watched ground alone");

            wall.forEach((cell, state) -> level.setBlock(cell, state, 3));
            helper.assertTrue(!watch.now(List.of(walker)).reachable(far), "a real wall is seen at once");
            wall.keySet().forEach(cell -> level.setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
            helper.assertTrue(watch.now(List.of(walker)).reachable(far), "taking it down opens the hall again");
            helper.assertTrue(!ground.known(at(helper, 2, 0, 5).offset(-40, 0, 0)), "outside the box is not known");
        }
        helper.succeed();
    }

    private static void room(GameTestHelper helper, int nearZ, int farZ) {
        for (int x = 2; x <= 21; x++) {
            for (int z = nearZ - 1; z <= farZ + 1; z++) {
                for (int y = 0; y <= 3; y++) {
                    boolean inside = x > 2 && x < 21 && z >= nearZ && z <= farZ && y >= 1 && y <= 2;
                    helper.getLevel().setBlock(at(helper, x, y, z), inside ? Blocks.AIR.defaultBlockState() : STONE, 3);
                }
            }
        }
    }

    private static Mob walker(GameTestHelper helper, BlockPos feet) {
        Mob body = EntityType.PIG.create(helper.getLevel());
        body.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        return body;
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }
}
