package io.github.izakyl.folkways.vanilla.person.walk;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Diagnosis;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Keepouts;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class BreathingReviewGameTests {
    private static final ResourceLocation COMPETITORS =
        ResourceLocation.fromNamespaceAndPath("folkways_test", "breathing_competitors");
    private static final Map<UUID, List<Urge>> OFFERS = new HashMap<>();

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.urges(COMPETITORS, (colony, who, view) -> OFFERS.getOrDefault(who.resident().id(), List.of()));
        Participations.register(PersonBody.ID, Stake.urge(COMPETITORS), Participation.ALWAYS);
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BreathingReviewGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void aResidentEscapesFloodwaterThroughAClosedDoor(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        room(helper, base);
        BlockPos door = base.offset(4, 1, 2);
        var state = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST);
        helper.getLevel().setBlock(door, state, 2);
        helper.getLevel().setBlock(door.above(), state.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        for (int x = 5; x <= 7; x++) {
            helper.getLevel().setBlock(base.offset(x, 0, 2), Blocks.STONE.defaultBlockState(), 2);
        }
        ResidentEntity body = resident(helper, base.offset(2, 1, 2));
        survives(helper, body, "closed door");
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void aResidentEscapesFloodwaterThroughAClosedTrapdoor(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        room(helper, base);
        helper.getLevel().setBlock(base.offset(2, 3, 2), Blocks.OAK_TRAPDOOR.defaultBlockState(), 2);
        ResidentEntity body = resident(helper, base.offset(2, 1, 2));
        survives(helper, body, "closed trapdoor");
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void aResidentEscapesADeepFloodedShaft(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(7, 1, 7));
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = 0; y <= 8; y++) {
                    helper.getLevel().setBlock(base.offset(x, y, z),
                        (y == 0 || x != 0 || z != 0 ? Blocks.STONE : Blocks.WATER).defaultBlockState(), 2);
                }
            }
        }
        ResidentEntity body = resident(helper, base.above());
        survives(helper, body, "deep shaft");
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void aResidentEscapesADeepFloodedStairwell(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(3, 1, 4));
        for (int x = 0; x <= 9; x++) {
            for (int z = 0; z <= 4; z++) {
                for (int y = 0; y <= 13; y++) {
                    boolean wall = x == 0 || x == 9 || z == 0 || z == 4 || y <= x
                        || x < 8 && y == x + 4;
                    boolean flooded = y > x && y <= x + 3;
                    helper.getLevel().setBlock(base.offset(x, y, z),
                        (wall ? Blocks.STONE : flooded ? Blocks.WATER : Blocks.AIR).defaultBlockState(), 2);
                }
            }
        }
        ResidentEntity body = resident(helper, base.offset(1, 2, 2));
        survives(helper, body, "deep flooded stairwell");
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void aProtectedResidentWithLowAirDoesNotInterruptWork(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        room(helper, base);
        ResidentEntity body = resident(helper, base.offset(2, 1, 2));
        body.setAirSupply(100);
        body.addEffect(new net.minecraft.world.effect.MobEffectInstance(
            net.minecraft.world.effect.MobEffects.WATER_BREATHING, 200));
        boolean[] interrupted = {false};
        helper.onEachTick(() -> interrupted[0] |= WaterLabor.escaping(body));
        helper.startSequence().thenExecuteAfter(40, () -> {
            helper.assertFalse(interrupted[0], "water breathing never reserves a body with an existing air debt");
            helper.assertTrue(body.getHealth() == body.getMaxHealth(), "the protected resident is unharmed");
            body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void escapeAvoidsAnUnrelatedKeepout(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        room(helper, base);
        BlockPos forbidden = base.offset(2, 2, 2);
        helper.getLevel().setBlock(base.offset(2, 3, 2), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(base.offset(3, 3, 2), Blocks.AIR.defaultBlockState(), 2);
        Runnable remove = Keepouts.install((level, x, y, z) -> level == helper.getLevel()
            && forbidden.equals(new BlockPos(x, y, z)));
        helper.runAfterDelay(450, remove);
        ResidentEntity body = resident(helper, base.offset(1, 1, 2));
        boolean[] crossed = {false};
        helper.onEachTick(() -> crossed[0] |= WaterLabor.escaping(body) && body.blockPosition().equals(forbidden));
        helper.startSequence().thenExecuteAfter(420, () -> {
            remove.run();
            helper.assertFalse(crossed[0], "the escape does not enter a separate keepout");
            helper.assertTrue(!body.isUnderWater() && body.getHealth() == body.getMaxHealth(),
                "the resident finds the other opening");
            body.discard();
        }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 4000)
    public static void anUnreachableUnderwaterRouteEndsWithoutRepeatedWork(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 4));
        WaterLabor.tunnel(level, base, 19);
        ResidentEntity body = resident(helper, base.offset(0, 1, 1), false);
        for (var trade : Vocations.all()) {
            body.licences().trade(trade).ifPresent(licence -> body.licences().allow(licence, false));
        }
        BlockPos goal = base.offset(21, 1, 1);
        boolean[] committed = {false};
        boolean[] observing = {false};
        int[] movementTicks = {0};
        java.util.Set<String> movements = new java.util.LinkedHashSet<>();
        List<Ending> ended = new ArrayList<>();
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_test", "water_route");
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, WorkSite.at(level, goal),
            Stances.at(level, goal), Workload.Once.of(1)).doing(owner).done();
        Node node = new Node() {
            public NodeSpec spec() { return spec; }

            public Outcome commit(ServerLevel at, Worker who) {
                committed[0] = true;
                return Outcome.done();
            }
        };
        helper.onEachTick(() -> {
            if (observing[0] && !body.getNavigation().isDone() && body.doing()
                    .filter(doing -> doing.what().equals(owner) || doing.toward().filter(owner::equals).isPresent())
                    .isPresent()) {
                movementTicks[0]++;
                movements.add("doing=" + body.doing() + ", target=" + body.getNavigation().getTargetPos());
            }
        });
        var enlisted = WaterLabor.enlist(helper, body);
        LaborFixture labor = enlisted.labor();
        enlisted.colony().submit(owner, level.dimension(), Grown.of(node), (id, how) -> ended.add(how));
        helper.startSequence().thenWaitUntil(() -> {
                Diagnosis diagnosis = labor.diagnosis();
                helper.assertTrue(diagnosis.unassigned().stream().anyMatch(item -> item.node().equals(spec.id())
                        && item.why() == Unassigned.Reason.NO_WAY),
                    "the unreachable job reports no way: " + diagnosis.unassigned() + ", endings=" + ended);
                observing[0] = true;
            }).thenExecuteAfter(200, () -> {
                helper.assertFalse(committed[0], "the unreachable work does not complete: " + ended);
                helper.assertTrue(movementTicks[0] == 0,
                    "navigation ticks=" + movementTicks[0] + ", routes=" + movements);
                helper.assertTrue(body.getHealth() == body.getMaxHealth(), "the resident does not drown");
                body.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 240)
    public static void noExitRetriesAfterFortyTicksRatherThanTheRefusalCooldown(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        room(helper, base);
        ResidentEntity body = resident(helper, base.offset(2, 1, 2));
        ResidentEntity[] restored = {null};
        int[] recovered = {-1};
        helper.onEachTick(() -> {
            if (body.tickCount > 15 && !body.isUnderWater() && recovered[0] < 0) {
                recovered[0] = body.tickCount;
            }
        });
        helper.startSequence().thenExecuteAfter(5, () -> body.setAirSupply(100))
            .thenExecuteAfter(10, () -> {
                helper.assertFalse(WaterLabor.escaping(body), "no exit ends normally and returns control");
                helper.getLevel().setBlock(base.offset(4, 1, 2), Blocks.AIR.defaultBlockState(), 2);
                helper.getLevel().setBlock(base.offset(4, 2, 2), Blocks.AIR.defaultBlockState(), 2);
                helper.getLevel().setBlock(base.offset(5, 0, 2), Blocks.STONE.defaultBlockState(), 2);
            }).thenExecuteAfter(100, () -> {
                helper.assertTrue(recovered[0] >= 40 && recovered[0] < 95,
                    "retry waits about 40 ticks, without the 100 tick refusal backoff: " + recovered[0]);
                helper.assertTrue(body.getHealth() == body.getMaxHealth() && !WaterLabor.escaping(body),
                    "the retry preserves the resident and releases control");
            }).thenExecuteAfter(60, () -> {
                var saved = body.saveWithoutId(new net.minecraft.nbt.CompoundTag());
                body.discard();
                restored[0] = PersonBody.RESIDENT.get().create(helper.getLevel());
                restored[0].load(saved);
                helper.getLevel().addFreshEntity(restored[0]);
                WaterLabor.enlist(helper, restored[0]);
            }).thenExecuteAfter(20, () -> {
                helper.assertFalse(WaterLabor.escaping(restored[0]), "a healthy reloaded resident keeps control free");
                helper.assertTrue(restored[0].getAirSupply() == restored[0].getMaxAirSupply(),
                    "the reloaded resident is still breathing");
                restored[0].discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void escapeOutranksDireUrgesAndSurvivesAPassiveReservation(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(4, 1, 4));
        room(helper, base);
        helper.getLevel().setBlock(base.offset(4, 1, 2), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(base.offset(4, 2, 2), Blocks.AIR.defaultBlockState(), 2);
        helper.getLevel().setBlock(base.offset(5, 0, 2), Blocks.STONE.defaultBlockState(), 2);
        ResidentEntity body = resident(helper, base.offset(2, 1, 2));
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), COMPETITORS,
            WorkSite.on(helper.getLevel(), body.id(), body.blockPosition()), Stances.WHEREVER,
            (Workload.Continuous) (level, who) -> true).doing(COMPETITORS).done();
        Node competing = new Node() {
            public NodeSpec spec() { return spec; }

            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        OFFERS.put(body.id(), List.of(
            Urge.reserve(COMPETITORS),
            new Urge(ResourceLocation.withDefaultNamespace("flee"), Urge.DIRE, true, () -> competing),
            new Urge(ResourceLocation.withDefaultNamespace("leaving"), Urge.DIRE + 0.1, true, () -> competing)));
        boolean[] escaped = {false};
        helper.onEachTick(() -> escaped[0] |= WaterLabor.escaping(body));
        helper.startSequence().thenExecuteAfter(5, () -> body.setAirSupply(100))
            .thenExecuteAfter(200, () -> {
                helper.assertTrue(escaped[0], "escape wins over both DIRE weights and a passive reservation");
                helper.assertTrue(!body.isUnderWater() && body.getHealth() == body.getMaxHealth(),
                    "reservation does not repeatedly cancel the escape node");
                OFFERS.remove(body.id());
                body.discard();
            }).thenSucceed();
    }

    private static void room(GameTestHelper helper, BlockPos base) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                for (int y = 0; y <= 3; y++) {
                    boolean wall = y == 0 || y == 3 || x == 0 || x == 4 || z == 0 || z == 4;
                    helper.getLevel().setBlock(base.offset(x, y, z),
                        (wall ? Blocks.STONE : Blocks.WATER).defaultBlockState(), 2);
                }
            }
        }
    }

    private static ResidentEntity resident(GameTestHelper helper, BlockPos at) {
        return resident(helper, at, true);
    }

    private static ResidentEntity resident(GameTestHelper helper, BlockPos at, boolean enroll) {
        ResidentEntity body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(Vec3.atBottomCenterOf(at));
        helper.getLevel().addFreshEntity(body);
        if (enroll) {
            WaterLabor.enlist(helper, body);
        }
        return body;
    }

    private static void survives(GameTestHelper helper, ResidentEntity body, String exit) {
        int[] damage = {-1};
        helper.onEachTick(() -> {
            if (damage[0] < 0 && body.getHealth() < body.getMaxHealth()) {
                damage[0] = body.tickCount;
            }
        });
        helper.startSequence().thenExecuteAfter(420, () -> {
            helper.assertTrue(damage[0] < 0 && !body.isUnderWater(), exit + ": damage=" + damage[0]
                + ", air=" + body.getAirSupply() + ", at=" + body.position());
            body.discard();
        }).thenSucceed();
    }
}
