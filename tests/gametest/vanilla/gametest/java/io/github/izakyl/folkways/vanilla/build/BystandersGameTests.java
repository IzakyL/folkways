package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BystandersGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BystandersGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void aResidentFullyInsideTheLastCellIsPushedOut(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        Mob resident = resident(helper, Vec3.atBottomCenterOf(at));
        push(helper, at, resident);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void aResidentAtTheRailwayCellEdgeIsPushedPastItsFullWidth(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        Mob resident = resident(helper, new Vec3(at.getX() + 0.3D, at.getY(), at.getZ() + 0.7D));
        push(helper, at, resident);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void pushingChoosesTheOpenSideInsteadOfPassingThroughAWall(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        wall(helper, at.west());
        wall(helper, at.north());
        wall(helper, at.south());
        Mob resident = resident(helper, Vec3.atBottomCenterOf(at));
        helper.assertTrue(Bystanders.clear(helper.getLevel(), Map.of(at, Blocks.TUFF.defaultBlockState()), List.of()),
            "an ordinary bystander is pushed out without requiring a worker stance");
        helper.assertTrue(resident.getX() > at.getX() + 1.0D, "the resident leaves through the open east side");
        helper.assertTrue(helper.getLevel().noCollision(resident), "the resident is clear of the existing walls");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void aBlockCanLiftABystanderWhenEverySideIsClosed(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        for (BlockPos side : List.of(at.west(), at.east(), at.north(), at.south())) {
            wall(helper, side);
        }
        Mob resident = resident(helper, Vec3.atBottomCenterOf(at));
        helper.assertTrue(Bystanders.clear(helper.getLevel(), Map.of(at, Blocks.TUFF.defaultBlockState()), List.of()),
            "a full block pushes the resident up into clear air");
        helper.assertTrue(resident.getY() >= at.getY() + 1.0D, "the resident stands above the new block");
        helper.assertTrue(!resident.getBoundingBox().intersects(new AABB(at)), "the new block cannot suffocate it");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void anEnclosedResidentIsNeverPushedIntoWallsOrACeiling(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        for (BlockPos side : List.of(at.west(), at.east(), at.north(), at.south())) {
            wall(helper, side);
        }
        helper.getLevel().setBlock(at.above(2), Blocks.STONE.defaultBlockState(), 3);
        Vec3 start = Vec3.atBottomCenterOf(at);
        Mob resident = resident(helper, start);
        helper.assertTrue(!Bystanders.clear(helper.getLevel(), Map.of(at, Blocks.TUFF.defaultBlockState()), List.of()),
            "placement waits when there is no collision-free exit");
        helper.assertValueEqual(resident.position(), start, "an unsuccessful push moves nobody");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void placingABlockDoesNotPushAResidentIntoDeepWater(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        BlockPos water = at.west();
        helper.getLevel().setBlock(water.below(), Blocks.STONE.defaultBlockState(), 2);
        helper.getLevel().setBlock(at.below(), Blocks.STONE.defaultBlockState(), 2);
        for (BlockPos side : List.of(at.east(), at.north(), at.south())) {
            helper.getLevel().setBlock(side.below(), Blocks.STONE.defaultBlockState(), 2);
        }
        helper.getLevel().setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        helper.getLevel().setBlock(water.above(), Blocks.WATER.defaultBlockState(), 2);
        helper.getLevel().setBlock(water.above(2), Blocks.STONE.defaultBlockState(), 2);
        Mob resident = resident(helper, Vec3.atBottomCenterOf(at));
        helper.assertTrue(Bystanders.clear(helper.getLevel(), Map.of(at, Blocks.TUFF.defaultBlockState()), List.of()),
            "the block can still be placed");
        BlockPos eyes = BlockPos.containing(resident.getX(), resident.getEyeY(), resident.getZ());
        helper.assertTrue(helper.getLevel().getFluidState(eyes).isEmpty(), "placement does not push the resident underwater");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 40)
    public static void aBystanderWithWaterBreathingCanBePushedIntoWater(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(5, 1, 5));
        for (BlockPos side : List.of(at.east(), at.north(), at.south())) {
            wall(helper, side);
        }
        BlockPos water = at.west();
        helper.getLevel().setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        helper.getLevel().setBlock(water.above(), Blocks.WATER.defaultBlockState(), 2);
        Mob resident = resident(helper, Vec3.atBottomCenterOf(at));
        resident.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.WATER_BREATHING, 600));
        helper.assertTrue(Bystanders.clear(helper.getLevel(), Map.of(at, Blocks.TUFF.defaultBlockState()), List.of()),
            "water breathing permits the open side");
        helper.assertTrue(resident.getX() < at.getX(), "the protected resident steps into the water");
        resident.discard();
        helper.succeed();
    }

    private static void push(GameTestHelper helper, BlockPos at, Mob resident) {
        helper.assertTrue(Bystanders.clear(helper.getLevel(), Map.of(at, Blocks.TUFF.defaultBlockState()), List.of()),
            "a resident occupying the cell does not block placement");
        helper.getLevel().setBlock(at, Blocks.TUFF.defaultBlockState(), 3);
        helper.assertTrue(!resident.getBoundingBox().intersects(new AABB(at)), "the resident is outside the new block");
        helper.assertTrue(helper.getLevel().noCollision(resident), "the resident is not pushed into another block");
        resident.discard();
        helper.succeed();
    }

    private static Mob resident(GameTestHelper helper, Vec3 feet) {
        Mob resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        resident.setPos(feet);
        resident.setNoAi(true);
        helper.getLevel().addFreshEntity(resident);
        return resident;
    }

    private static void wall(GameTestHelper helper, BlockPos base) {
        helper.getLevel().setBlock(base, Blocks.STONE.defaultBlockState(), 3);
        helper.getLevel().setBlock(base.above(), Blocks.STONE.defaultBlockState(), 3);
    }
}
