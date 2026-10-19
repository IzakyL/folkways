package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentGateGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentGateGameTests.class);
    }

    // The body can enter a gate's cell before its collision makes the doorway goal open it (issue #20).
    @GameTest(template = "empty")
    public static void aShutGateDoesNotAskForAJump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos gate = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(gate.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(gate, Blocks.OAK_FENCE_GATE.defaultBlockState());
        var resident = new JumpProbe(level);
        resident.setPos(Vec3.atBottomCenterOf(gate));
        resident.getMoveControl().setWantedPosition(gate.getX() + 0.5D, gate.getY(), gate.getZ() + 1.5D, 1.0D);
        resident.getMoveControl().tick();
        resident.getJumpControl().tick();
        helper.assertFalse(resident.askedToJump, "a resident waits for a shut gate to open without jumping");
        helper.assertTrue(resident.getSpeed() > 0.0F, "the resident still walks toward the gate");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStepUpBeyondAGateStillAsksForAJump(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos gate = helper.absolutePos(new BlockPos(2, 1, 2));
        level.setBlockAndUpdate(gate.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(gate, Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.OPEN, true));
        level.setBlockAndUpdate(gate.south(), Blocks.STONE.defaultBlockState());
        var resident = new JumpProbe(level);
        resident.setPos(gate.getX() + 0.5D, gate.getY(), gate.getZ() + 0.75D);
        resident.getMoveControl().setWantedPosition(gate.getX() + 0.5D, gate.getY() + 1.0D, gate.getZ() + 1.5D, 1.0D);
        resident.getMoveControl().tick();
        resident.getJumpControl().tick();
        helper.assertTrue(resident.askedToJump, "a real step up beyond an open gate still needs a jump");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void aResidentWalksThroughAnEastWestGateAndBackWithoutJumping(GameTestHelper helper) {
        walkThroughAndBack(helper, Direction.EAST);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void aResidentWalksThroughANorthSouthGateAndBackWithoutJumping(GameTestHelper helper) {
        walkThroughAndBack(helper, Direction.NORTH);
    }

    @GameTest(template = "empty")
    public static void aGateWaitsUntilTheOpenersWholeBodyIsClear(GameTestHelper helper) {
        BlockPos gate = gateRoom(helper, Direction.EAST);
        ResidentEntity resident = residentAt(helper, Vec3.atBottomCenterOf(gate).add(-0.9D, 0.0D, 0.0D));
        OpenDoorwayGoal goal = openGate(helper, resident, gate);
        resident.setPos(Vec3.atBottomCenterOf(gate).add(0.1D, 0.0D, 0.0D));
        goal.tick();
        helper.assertTrue(goal.canContinueToUse(), "crossing the gate's centre has not cleared the body");
        resident.setPos(Vec3.atBottomCenterOf(gate).add(1.2D, 0.0D, 0.0D));
        helper.assertFalse(goal.canContinueToUse(), "the whole body is clear of the doorway");
        goal.stop();
        helper.assertFalse(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN),
            "the empty gate closes");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGateWaitsForAFollowingResidentThatJoinedIt(GameTestHelper helper) {
        waitForAnotherResident(helper, false);
    }

    @GameTest(template = "empty")
    public static void aGateWaitsForAnOncomingResidentThatJoinedIt(GameTestHelper helper) {
        waitForAnotherResident(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void twoResidentsCanFollowOneAnotherThroughAGate(GameTestHelper helper) {
        twoResidentsThrough(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void twoResidentsCanPassAGateInOppositeDirections(GameTestHelper helper) {
        twoResidentsThrough(helper, true);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void twoResidentsCanFollowOneAnotherThroughAWoodenDoor(GameTestHelper helper) {
        twoResidentsThrough(helper, false, true);
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void twoResidentsCanPassAWoodenDoorInOppositeDirections(GameTestHelper helper) {
        twoResidentsThrough(helper, true, true);
    }

    private static void waitForAnotherResident(GameTestHelper helper, boolean oncoming) {
        BlockPos gate = gateRoom(helper, Direction.EAST);
        Vec3 centre = Vec3.atBottomCenterOf(gate);
        ResidentEntity opener = residentAt(helper, centre.add(-0.9D, 0.0D, 0.0D));
        OpenDoorwayGoal openerGoal = openGate(helper, opener, gate);
        ResidentEntity other = residentAt(helper, centre.add(oncoming ? 0.9D : -1.2D, 0.0D, 0.0D));
        Vec3 destination = centre.add(oncoming ? -2.0D : 2.0D, 0.0D, 0.0D);
        walkTo(helper, other, destination);
        var otherGoal = new OpenDoorwayGoal(other);
        helper.assertTrue(otherGoal.canUse(), "the other resident joins the gate held open on its path");
        otherGoal.start();
        opener.setPos(centre.add(1.3D, 0.0D, 0.0D));
        helper.assertFalse(openerGoal.canContinueToUse(), "the opener is through");
        openerGoal.stop();
        helper.assertTrue(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN),
            "the gate stays open while the other resident holds it");
        other.setPos(centre);
        for (int tick = 0; tick < 25; tick++) {
            otherGoal.tick();
        }
        helper.assertTrue(otherGoal.canContinueToUse(), "the gate stays open beyond its hold time for a body in it");
        other.setPos(destination);
        helper.assertFalse(otherGoal.canContinueToUse(), "the other resident is through");
        otherGoal.stop();
        helper.assertFalse(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN),
            "the last resident through closes the gate");
        opener.discard();
        other.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anAnimalAtTheGateDoesNotHoldItOpen(GameTestHelper helper) {
        BlockPos gate = gateRoom(helper, Direction.EAST);
        Vec3 centre = Vec3.atBottomCenterOf(gate);
        ResidentEntity resident = residentAt(helper, centre.add(-0.9D, 0.0D, 0.0D));
        OpenDoorwayGoal goal = openGate(helper, resident, gate);
        var sheep = EntityType.SHEEP.create(helper.getLevel());
        sheep.setPos(centre.add(-0.9D, 0.0D, 0.0D));
        helper.getLevel().addFreshEntity(sheep);
        sheep.getNavigation().moveTo(centre.x + 2.0D, centre.y, centre.z, 1.0D);
        resident.setPos(centre.add(1.3D, 0.0D, 0.0D));
        goal.tick();
        helper.assertFalse(goal.canContinueToUse(), "the resident is through");
        goal.stop();
        helper.assertFalse(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN),
            "a sheep about to walk through does not keep the gate open");
        resident.discard();
        sheep.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGateLeftOpenByAPlayerIsLeftAlone(GameTestHelper helper) {
        BlockPos gate = gateRoom(helper, Direction.EAST);
        helper.getLevel().setBlockAndUpdate(gate, helper.getLevel().getBlockState(gate)
            .setValue(FenceGateBlock.OPEN, true));
        ResidentEntity resident = residentAt(helper, Vec3.atBottomCenterOf(gate).add(-0.9D, 0.0D, 0.0D));
        walkTo(helper, resident, Vec3.atBottomCenterOf(gate.east(2)));
        resident.horizontalCollision = true;
        helper.assertFalse(new OpenDoorwayGoal(resident).canUse(), "nobody holds the open gate, so there is none to join");
        resident.discard();
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aGateHeldByAResidentThatIsGoneClosesAfterTheNext(GameTestHelper helper) {
        BlockPos gate = gateRoom(helper, Direction.EAST);
        Vec3 centre = Vec3.atBottomCenterOf(gate);
        ResidentEntity gone = residentAt(helper, centre.add(-0.9D, 0.0D, 0.0D));
        openGate(helper, gone, gate);
        gone.discard();
        ResidentEntity next = residentAt(helper, centre.add(-1.2D, 0.0D, 0.0D));
        walkTo(helper, next, centre.add(2.0D, 0.0D, 0.0D));
        var goal = new OpenDoorwayGoal(next);
        helper.assertTrue(goal.canUse(), "the next resident joins the gate left held");
        goal.start();
        next.setPos(centre.add(1.3D, 0.0D, 0.0D));
        goal.tick();
        helper.assertFalse(goal.canContinueToUse(), "the next resident is through");
        goal.stop();
        helper.assertFalse(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN),
            "the holder that never let go no longer keeps the gate open");
        next.discard();
        helper.succeed();
    }

    private static OpenDoorwayGoal openGate(GameTestHelper helper, ResidentEntity resident, BlockPos gate) {
        walkTo(helper, resident, Vec3.atBottomCenterOf(gate.east(2)));
        resident.horizontalCollision = true;
        var goal = new OpenDoorwayGoal(resident);
        helper.assertTrue(goal.canUse(), "the resident finds the shut gate on its path");
        goal.start();
        helper.assertTrue(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN), "the gate opens");
        return goal;
    }

    private static void twoResidentsThrough(GameTestHelper helper, boolean oncoming) {
        twoResidentsThrough(helper, oncoming, false);
    }

    private static void twoResidentsThrough(GameTestHelper helper, boolean oncoming, boolean woodenDoor) {
        BlockPos gate = gateRoom(helper, Direction.EAST);
        if (woodenDoor) {
            var door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
            helper.getLevel().setBlockAndUpdate(gate, door);
            helper.getLevel().setBlockAndUpdate(gate.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        Vec3 centre = Vec3.atBottomCenterOf(gate);
        ResidentEntity first = residentAt(helper, centre.add(oncoming ? -2.0D : -1.0D, 0.0D, oncoming ? -1.0D : 0.0D));
        ResidentEntity second = residentAt(helper, centre.add(oncoming ? 2.0D : -2.0D, 0.0D, oncoming ? 1.0D : 0.0D));
        Vec3 firstEnd = centre.add(2.0D, 0.0D, oncoming ? -1.0D : 0.0D);
        Vec3 secondEnd = centre.add(oncoming ? -2.0D : 2.0D, 0.0D, oncoming ? 1.0D : 0.0D);
        AABB opening = new AABB(gate).expandTowards(0.0D, 1.0D, 0.0D);
        boolean[] opened = { false };
        helper.onEachTick(() -> {
            boolean open = helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN);
            if (opened[0] && (opening.intersects(first.getBoundingBox()) || opening.intersects(second.getBoundingBox()))) {
                helper.assertTrue(open, "the gate must not close on either resident");
            }
            opened[0] |= open;
        });
        helper.startSequence()
            .thenExecuteAfter(2, () -> {
                walkTo(helper, first, firstEnd);
                walkTo(helper, second, secondEnd);
            })
            .thenWaitUntil(() -> helper.assertTrue(first.getX() > centre.x + 1.0D
                    && (oncoming ? second.getX() < centre.x - 1.0D : second.getX() > centre.x + 1.0D),
                "both residents get through; first " + describe(first, gate) + "; second " + describe(second, gate)))
            .thenWaitUntil(() -> helper.assertFalse(helper.getLevel().getBlockState(gate).getValue(FenceGateBlock.OPEN),
                "the gate closes after both residents pass"))
            .thenExecute(() -> {
                helper.assertTrue(opened[0], "the residents opened the gate");
                first.discard();
                second.discard();
            })
            .thenSucceed();
    }

    private static ResidentEntity residentAt(GameTestHelper helper, Vec3 at) {
        ResidentEntity resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        resident.setPos(at);
        resident.setOnGround(true);
        helper.getLevel().addFreshEntity(resident);
        return resident;
    }

    private static BlockPos gateRoom(GameTestHelper helper, Direction facing) {
        ServerLevel level = helper.getLevel();
        BlockPos gate = helper.absolutePos(new BlockPos(3, 1, 3));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                level.setBlockAndUpdate(gate.offset(x, -1, z), Blocks.STONE.defaultBlockState());
                for (int y = 0; y <= 2; y++) {
                    boolean wall = Math.abs(x) == 3 || Math.abs(z) == 3
                        || (facing.getAxis() == Direction.Axis.X ? x == 0 : z == 0);
                    level.setBlockAndUpdate(gate.offset(x, y, z),
                        (wall ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(gate.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(gate, Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.FACING, facing));
        return gate;
    }

    private static void walkThroughAndBack(GameTestHelper helper, Direction facing) {
        ServerLevel level = helper.getLevel();
        BlockPos gate = gateRoom(helper, facing);
        Vec3 start = Vec3.atBottomCenterOf(gate.relative(facing, 2));
        Vec3 end = Vec3.atBottomCenterOf(gate.relative(facing.getOpposite(), 2));
        ResidentEntity resident = PersonBody.RESIDENT.get().create(level);
        resident.setPos(start);
        level.addFreshEntity(resident);
        boolean[] opened = { false };
        helper.onEachTick(() -> {
            helper.assertTrue(resident.getY() <= gate.getY() + 0.05D,
                "the resident jumped at a gate: feet at " + resident.getY() + ", floor at " + gate.getY());
            opened[0] |= level.getBlockState(gate).getValue(FenceGateBlock.OPEN);
        });
        helper.startSequence()
            .thenExecuteAfter(2, () -> walkTo(helper, resident, end))
            .thenWaitUntil(() -> helper.assertTrue(resident.position().distanceToSqr(end) < 0.25D,
                "the resident reaches the other side of the gate; " + describe(resident, gate)))
            .thenWaitUntil(() -> helper.assertFalse(level.getBlockState(gate).getValue(FenceGateBlock.OPEN),
                "the gate closes after the outward trip"))
            .thenExecute(() -> {
                helper.assertTrue(opened[0], "the resident opened the gate on the outward trip");
                opened[0] = false;
                walkTo(helper, resident, start);
            })
            .thenWaitUntil(() -> helper.assertTrue(resident.position().distanceToSqr(start) < 0.25D,
                "the resident returns through the gate; " + describe(resident, gate)))
            .thenWaitUntil(() -> helper.assertFalse(level.getBlockState(gate).getValue(FenceGateBlock.OPEN),
                "the gate closes after the return trip"))
            .thenExecute(() -> {
                helper.assertTrue(opened[0], "the resident opened the gate on the return trip");
                resident.discard();
            })
            .thenSucceed();
    }

    private static void walkTo(GameTestHelper helper, ResidentEntity resident, Vec3 target) {
        var path = resident.getNavigation().createPath(BlockPos.containing(target), 0);
        helper.assertTrue(path != null && path.canReach() && resident.getNavigation().moveTo(path, 1.0D),
            "there is a path through the shut gate");
    }

    private static String describe(ResidentEntity resident, BlockPos gate) {
        return "at=" + resident.position() + " gate=" + resident.level().getBlockState(gate)
            + " path=" + resident.getNavigation().getPath() + " done=" + resident.getNavigation().isDone()
            + " collision=" + resident.horizontalCollision + " removed=" + resident.isRemoved();
    }

    private static final class JumpProbe extends ResidentEntity {
        private boolean askedToJump;

        private JumpProbe(ServerLevel level) {
            super(PersonBody.RESIDENT.get(), level);
        }

        @Override
        public void setJumping(boolean jumping) {
            super.setJumping(jumping);
            askedToJump |= jumping;
        }
    }
}
