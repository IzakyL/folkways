package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCartsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_carts")
@PrefixGameTestTemplate(false)
public final class HorseLineGameTests {
    private static final double MIN_SPEED = 0.03;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HorseLineGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "horse_line_straight", timeoutTicks = 3600)
    public static void horseHaulsWithoutALeaderAndWaitsForHandlersOnBothLegs(GameTestHelper helper) {
        haul(helper, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "horse_line_corner", timeoutTicks = 4000)
    public static void steeringMarksAndACornerDoNotStopTheHorse(GameTestHelper helper) {
        haul(helper, true);
    }

    private static void haul(GameTestHelper helper, boolean corner) {
        var level = helper.getLevel();
        BlockPos start = helper.absolutePos(new BlockPos(2, 1, 2)).above(80);
        int length = 72;
        int bend = corner ? 24 : 0;
        for (BlockPos at : BlockPos.betweenClosed(start.offset(-16, -1, -12),
                start.offset(length + 16, 4, bend + 16))) {
            level.setBlockAndUpdate(at, at.getY() == start.getY() - 1
                ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        // Roadside furniture in both station bays: the team must first draw forward before circling.
        level.setBlockAndUpdate(start.offset(-5, 0, -4), Blocks.LANTERN.defaultBlockState());
        level.setBlockAndUpdate(corner ? start.offset(length - 4, 0, bend + 5) : start.offset(length + 5, 0, 4),
            Blocks.LANTERN.defaultBlockState());
        var forced = new java.util.HashSet<net.minecraft.world.level.ChunkPos>();
        for (int x = (start.getX() - 16) >> 4; x <= (start.getX() + length + 16) >> 4; x++) {
            for (int z = (start.getZ() - 12) >> 4; z <= (start.getZ() + bend + 16) >> 4; z++) {
                var chunk = new net.minecraft.world.level.ChunkPos(x, z);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    level.setChunkForced(x, z, true);
                    forced.add(chunk);
                }
            }
        }
        var colony = Colonies.mint(level.getServer());
        List<Line.Mark> course = new ArrayList<>();
        for (int x = 0; x <= length; x += 6) {
            WorldPos at = WorldPos.of(level, start.east(x));
            course.add(new Line.Mark(at, Stances.at(at), x == 0 ? 0 : !corner && x == length ? 1 : -1));
        }
        for (int z = 6; z <= bend; z += 6) {
            WorldPos at = WorldPos.of(level, start.offset(length, 0, z));
            course.add(new Line.Mark(at, Stances.at(at), z == bend ? 1 : -1));
        }
        var line = new Line(UUID.randomUUID(), level.dimension(), Line.Kind.SUPPLY, false, course, 2);
        var state = new LineState(line.id());
        var cart = (AbstractCargoCart) BuiltInRegistries.ENTITY_TYPE.get(Line.Kind.SUPPLY.entity()).create(level);
        cart.moveTo(start.getBottomCenter());
        cart.setYRot(-90);
        level.addFreshEntity(cart);
        colony.hold(FolkwaysCartsMod.OWNER, Claimed.Entity.of(cart)).orElseThrow();
        state.cart = Optional.of(cart.getUUID());
        var horse = EntityType.HORSE.create(level);
        horse.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.27);
        horse.moveTo((corner ? start.east(5) : start.offset(11, 0, -7)).getBottomCenter());
        if (!corner) {
            for (BlockPos at : BlockPos.betweenClosed(start.offset(9, 0, -9), start.offset(13, 0, -5))) {
                boolean fence = at.getX() == start.getX() + 9 || at.getX() == start.getX() + 13
                    || at.getZ() == start.getZ() - 9;
                if (fence) {
                    level.setBlockAndUpdate(at, Blocks.OAK_FENCE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(start.offset(10, 0, -8), Blocks.HAY_BLOCK.defaultBlockState());
        }
        horse.setTamed(true);
        horse.setOnGround(true);
        level.addFreshEntity(horse);
        state.horse = Optional.of(horse.getUUID());
        var navigation = horse.getNavigation();
        var team = new HorseLine(level, line, state, horse);
        state.team = team;
        var handler = PersonBody.RESIDENT.get().create(level);
        handler.moveTo(start.south(6).getBottomCenter());
        handler.setNoAi(true);
        level.addFreshEntity(handler);
        var worker = TestWorker.of(handler, handler.resident(), handler.pack());
        var sent = new LineState.Consignment(UUID.randomUUID(), ItemSpec.of(Items.WHEAT), 64, 0, 1);
        state.aboard.put(sent.id, sent);
        var load = new LineWork.Load(state, line, course.getFirst(), sent);
        var unload = new LineWork.Unload(state, line, course.getLast(), sent);
        Vec3[] previous = {horse.position(), cart.position()};
        int[] parked = {0, 0};
        int[] samples = {0};
        double[] minimum = {Double.MAX_VALUE};
        long[] lastTick = {-1};
        int[] hitchedTicks = {0};
        int[] turnSamples = {0, 0};
        double[] minimumAngle = {180};
        boolean[] loaded = {false};
        boolean[] unloaded = {false};
        helper.onEachTick(() -> {
            if (lastTick[0] == level.getGameTime()) {
                return;
            }
            lastTick[0] = level.getGameTime();
            double speed = horse.position().subtract(previous[0]).horizontalDistance();
            double cartSpeed = cart.position().subtract(previous[1]).horizontalDistance();
            previous[0] = horse.position();
            previous[1] = cart.position();
            helper.assertTrue(!horse.isLeashed() && horse.getLeashHolder() == null,
                "no resident leads the horse, including when it turns back");
            if (cart.getPulling() != horse) {
                return;
            }
            hitchedTicks[0]++;
            if (hitchedTicks[0] > 20) {
                Vec3 trailing = cart.position().subtract(horse.position()).multiply(1, 0, 1).normalize();
                Vec3 facing = Vec3.directionFromRotation(0, horse.getYRot());
                double angle = Math.toDegrees(Math.acos(Math.clamp(facing.dot(trailing), -1, 1)));
                minimumAngle[0] = Math.min(minimumAngle[0], angle);
                helper.assertTrue(angle > 100,
                    "cart must trail the horse through both turnarounds: angle=" + angle + " lap=" + state.lapNumber
                        + " horse=" + horse.position() + " cart=" + cart.position() + " yaw=" + horse.getYRot()
                        + " next=" + (horse.getNavigation().isDone() ? "done"
                            : horse.getNavigation().getPath().getNextNodeIndex() + ":"
                                + horse.getNavigation().getPath().getNextEntityPos(horse))
                        + " path=" + horse.getNavigation().getPath()
                        + " wanted=" + horse.getMoveControl().getWantedX() + ","
                            + horse.getMoveControl().getWantedZ());
                if (state.lapNumber == 1 || state.lapNumber == 2) {
                    turnSamples[state.lapNumber - 1]++;
                }
            }
            // Exclude the first/last 10 blocks of each leg; every other tick must maintain real motion.
            double from = horse.position().distanceTo(course.getFirst().at().cell().getBottomCenter());
            double to = horse.position().distanceTo(course.getLast().at().cell().getBottomCenter());
            if (from > 10 && to > 10) {
                samples[0]++;
                minimum[0] = Math.min(minimum[0], speed);
                if (samples[0] == 100) {
                    horse.setEating(true);
                }
                if (samples[0] == 200) {
                    horse.setStanding(true);
                }
                helper.assertTrue(speed >= MIN_SPEED,
                    "mid-leg stop: tick=" + level.getGameTime() + " horseV=" + speed + " cartV=" + cartSpeed
                        + " horse=" + horse.position() + " mark=" + state.nextMark
                        + " path=" + horse.getNavigation().getPath());
            }
            if (!loaded[0] && state.parkedAt(level, line, 0)) {
                parked[0]++;
                if (parked[0] == Line.DWELL_TICKS + 60) {
                    // Supply is furnished to the worker; the actual production Load node moves it aboard.
                    var supplied = TestWorker.of(handler, handler.resident(), handler.pack())
                        .supplied(() -> List.of(new ItemStack(Items.WHEAT, 64)));
                    helper.assertTrue(load.commit(level, supplied) instanceof Outcome.Done,
                        "loading trusts the handler's work-cell arrival without a second distance check");
                    loaded[0] = true;
                }
            }
            if (sent.loaded && state.parkedAt(level, line, 1)) {
                parked[1]++;
                if (!unloaded[0] && parked[1] == Line.DWELL_TICKS + 60) {
                    helper.assertTrue(unload.commit(level, worker) instanceof Outcome.Done done
                            && done.made().stream().mapToInt(ItemStack::getCount).sum() == 64,
                        "the team waits past its minimum dwell for unloading");
                    unloaded[0] = true;
                }
            }
            if (unloaded[0] && state.lapNumber >= 2 && from > 12 && to > 12 && horse.getX() > start.getX() + 12) {
                helper.assertTrue(turnSamples[0] > 60 && turnSamples[1] > 60,
                    "both endpoints have completed a turn and departed under the trailing-angle assertion");
                helper.assertTrue(samples[0] > 100 && loaded[0] && parked[1] >= Line.DWELL_TICKS + 60,
                    "a full out-and-back trip and both handling waits were measured");
                LogUtils.getLogger().info("horse-line turnarounds: corner={} minimumAngle={} samples={}/{}",
                    corner, minimumAngle[0], turnSamples[0], turnSamples[1]);
                LogUtils.getLogger().info("horse-line motion: corner={} ticks={} minimumHorseSpeed={} threshold={}",
                    corner, samples[0], minimum[0], MIN_SPEED);
                team.release();
                helper.assertTrue(horse.getNavigation() == navigation && !horse.isNoAi(),
                    "releasing a line restores ordinary horse navigation and AI");
                cart.setPulling(null);
                cart.discard();
                horse.discard();
                handler.discard();
                colony.raze();
                for (BlockPos at : BlockPos.betweenClosed(start.offset(-16, -1, -12),
                        start.offset(length + 16, 4, bend + 16))) {
                    level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
                }
                forced.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
                helper.succeed();
            }
        });
    }
}
