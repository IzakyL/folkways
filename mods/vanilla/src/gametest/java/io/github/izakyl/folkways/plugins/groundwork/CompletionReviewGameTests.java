package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.build.BuildContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Execution-permission and actor-selection regressions. Fixture arrival is explicit, not a native-walking claim. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompletionReviewGameTests {
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final ItemSpec MATERIAL = ItemSpec.of(ResourceLocation.withDefaultNamespace("dirt"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CompletionReviewGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "completion_review_reselect", timeoutTicks = 1200)
    public static void anUnexecutedReadyProjectReplacesItsIneligibleBuilderWithoutACommit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        floor(level, origin, 23, 23);
        BlockPos target = origin.offset(11, 1, 11), safe = origin.offset(1, 1, 1);
        UUID colony = UUID.randomUUID();
        Mob original = resident(helper, safe, colony);
        Mob replacement = resident(helper, origin.offset(2, 1, 1), colony);
        FillPlan plan = new FillPlan(level, UUID.randomUUID(), colony, Set.of(target), () -> List.of(safe),
            () -> { }, false, new FillPlan.Limits(Integer.MAX_VALUE, 0));
        SimpleContainer firstPack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        SimpleContainer nextPack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        boolean[] ended = {false};
        int[] remainingTicks = {1000};
        Runnable close = () -> { ended[0] = true; plan.close(); original.discard(); replacement.discard(); };
        try {
            refresh(plan, target);
            Job first = head(helper, plan);
            Node originalRequest = node(plan, first);
            originalRequest.planned(level);
            helper.assertTrue(plan.worker().filter(original.getUUID()::equals).isPresent(),
                "the initial complete certificate is bound to the nearest real eligible builder");
            helper.assertTrue(plan.saveCompletion().getList("receipts", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty(),
                "this handover has no executed construction fact or retreat obligation");
            ((Body) original).joinColony(UUID.randomUUID());
            // Do not call Commit here. A READY project must notice the loss of eligibility while
            // advancing normally, even when the labor planner can no longer dispatch the old actor.
            helper.onEachTick(() -> {
                if (ended[0]) return;
                try {
                    Ground.Budget slice = new Ground.Budget(256, 0);
                    plan.advance(slice);
                    helper.assertTrue(slice.used() <= 256, "reselection, recapture and reproof share the bounded slice");
                    helper.assertTrue(level.getBlockState(target).isAir() && firstPack.countItem(Items.DIRT) == 8
                            && nextPack.countItem(Items.DIRT) == 8,
                        "eligibility changes and replanning neither place nor consume material");
                    helper.assertTrue(--remainingTicks[0] > 0,
                        "a healthy replacement must receive the complete head without any triggering Commit: "
                            + plan.completionStatus() + ": " + plan.completionReason());
                    if (plan.worker().filter(replacement.getUUID()::equals).isEmpty() || plan.jobs().isEmpty()) return;
                    Job fresh = head(helper, plan);
                    stand(original, fresh, level);
                    helper.assertTrue(Commit.run(level, originalRequest, worker(original, firstPack)) instanceof Outcome.Failed,
                        "the old actor cannot write with its old request even from the correct current stance");
                    helper.assertTrue(level.getBlockState(target).isAir() && firstPack.countItem(Items.DIRT) == 8,
                        "refusing the previous actor returns all material and creates no own receipt");
                    original.setPos(Vec3.atBottomCenterOf(safe));
                    stand(replacement, fresh, level);
                    helper.assertTrue(Commit.run(level, node(plan, fresh), worker(replacement, nextPack)) instanceof Outcome.Done,
                        "the newly bound actual actor executes a real placement after the complete replacement proof");
                    helper.assertTrue(level.getBlockState(target).is(Blocks.DIRT) && nextPack.countItem(Items.DIRT) == 7,
                        "the replacement spends exactly one real block");
                    finishRetreat(helper, plan, nextPack);
                    close.run();
                    helper.succeed();
                } catch (RuntimeException | Error failure) { close.run(); throw failure; }
            });
        } catch (RuntimeException | Error failure) { close.run(); throw failure; }
    }

    @GameTest(template = "empty_large", batch = "completion_review_far_person", timeoutTicks = 200)
    public static void aLoadedResidentBeyondSixtyFourBlocksStillPreventsSealingItsOnlyExit(GameTestHelper helper) {
        new FarPersonVisibility(helper).start();
    }

    @GameTest(template = "empty_large", batch = "completion_review_one_work_execution", timeoutTicks = 1400)
    public static void oneWorkPermitsActuallyPlaceAndOneWorkRetreatsActuallySettleAcrossTicks(GameTestHelper helper) {
        new OneWorkExecution(helper).start();
    }

    @GameTest(template = "empty_large", batch = "completion_review_census_saturation", timeoutTicks = 200)
    public static void aSaturatedCensusCannotReuseAnAlreadyCompletePlacementCertificate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        floor(level, origin, 23, 23);
        BlockPos target = origin.offset(11, 1, 11), safe = origin.offset(1, 1, 1);
        UUID colony = UUID.randomUUID();
        Mob actor = resident(helper, safe, colony);
        List<ItemEntity> owned = new ArrayList<>();
        try (FillPlan plan = new FillPlan(level, UUID.randomUUID(), colony, Set.of(target), () -> List.of(safe),
                () -> { }, false, new FillPlan.Limits(Integer.MAX_VALUE, 0))) {
            refresh(plan, target);
            Job job = head(helper, plan);
            Node held = node(plan, job);
            held.planned(level);
            stand(actor, job, level);
            CompletionRuntime runtime = runtime(plan);
            helper.assertTrue(value(runtime, "nativeBefore") == null,
                "the complete logical head has not precomputed a native write permit");
            // All entities are made and checked synchronously, before a world tick can merge items.
            // They are non-Living entities: saturation must not silently become a partial people list.
            for (int index = 0; index < 2049; index++) {
                ItemEntity item = new ItemEntity(level, safe.getX() + 0.5D, safe.getY() + 1.0D,
                    safe.getZ() + 0.5D, new ItemStack(Items.STONE));
                item.setNoGravity(true);
                helper.assertTrue(level.addFreshEntity(item), "every census-saturation item is a real loaded entity");
                owned.add(item);
            }
            SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
            for (int attempt = 0; attempt < 8; attempt++) {
                CompletionRuntime.Permit answer = runtime.permits(target, DIRT, actor, Ground.Budget.unlimited());
                helper.assertTrue(answer != CompletionRuntime.Permit.READY,
                    "no truncated loaded-entity census may grant a write, including an earlier complete certificate");
                helper.assertTrue(level.getBlockState(target).isAir() && pack.countItem(Items.DIRT) == 8,
                    "saturation and retries preserve every physical block and material");
            }
            helper.assertTrue(Boolean.TRUE.equals(runtime.diagnostics().get("lastCensusLimited")),
                "the test really exercised the bounded-census saturation path");
            helper.assertTrue(Commit.run(level, held, worker(actor, pack)) instanceof Outcome.Failed,
                "the normal actual Commit also cannot bypass census saturation with the retained node");
            helper.assertTrue(level.getBlockState(target).isAir() && pack.countItem(Items.DIRT) == 8,
                "the refused normal Commit restores all eight real blocks");
            helper.succeed();
        } finally {
            owned.forEach(ItemEntity::discard);
            actor.discard();
        }
    }

    private static final class FarPersonVisibility {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos origin;
        private final BlockPos target;
        private final BlockPos safe;
        private final UUID colony = UUID.randomUUID();
        private List<Long> forcedByThisTest = List.of();
        private Mob builder;
        private Mob far;
        private FillPlan plan;
        private int visibilityTicks = 160;
        private boolean closed;

        FarPersonVisibility(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            // Separate the long fixture from neighboring templates. FULL terrain loading and
            // visible entity tracking are different conditions; do not guess a fixed tick delay.
            origin = helper.absolutePos(new BlockPos(4096, 0, 4096));
            target = origin.offset(8, 1, 9);
            safe = origin.offset(3, 1, 9);
            try {
                forcedByThisTest = force(level, origin.offset(-4, -2, 3), origin.offset(93, 6, 15));
                floor(level, origin, 6, 14);
                for (int x = 7; x <= 88; x++) for (int y = 0; y <= 3; y++) for (int z = 8; z <= 10; z++) {
                    boolean wall = y == 0 || y == 3 || z != 9 || x == 88;
                    level.setBlock(origin.offset(x, y, z), wall ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
                }
                builder = resident(helper, origin.offset(4, 1, 9), colony);
                far = resident(helper, origin.offset(80, 1, 9), UUID.randomUUID());
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        void start() {
            helper.onEachTick(() -> {
                if (closed) return;
                try {
                    boolean visible = level.getEntities().get(builder.getUUID()) == builder
                        && level.getEntities().get(far.getUUID()) == far;
                    helper.assertTrue(builder.isAlive() && far.isAlive(),
                        "both real fixture residents stay alive while their native tracking becomes visible");
                    helper.assertTrue(visibilityTicks-- > 0,
                        "bounded native entity visibility wait expired: " + visibility("builder", builder) + "; " + visibility("far", far));
                    if (!visible) return;
                    checks();
                    close();
                    helper.succeed();
                } catch (RuntimeException | Error failure) { close(); throw failure; }
            });
        }

        private void checks() {
            helper.assertTrue(level.getEntities().get(builder.getUUID()) == builder
                    && level.getEntities().get(far.getUUID()) == far,
                "both actual residents must be visible to the native loaded-person lookup before capturing the proof");
            helper.assertTrue(far.position().distanceTo(Vec3.atCenterOf(target)) > 65.0D
                    && !((Body) far).colonyId().filter(colony::equals).isPresent(),
                "the loaded declared walker is outside the old 64-block query and belongs to another colony");
            plan = new FillPlan(level, UUID.randomUUID(), colony, Set.of(target), () -> List.of(safe),
                () -> { }, false, new FillPlan.Limits(Integer.MAX_VALUE, 0));
            refresh(plan, target);
            Job job = head(helper, plan);
            Node retained = node(plan, job);
            retained.planned(level);
            stand(builder, job, level);
            SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
            Outcome blocked = Commit.run(level, retained, worker(builder, pack));
            for (int tries = 0; tries < 32 && blocked instanceof Outcome.Failed failed
                    && retained.failing(level, failed.why()) instanceof Recourse.Wait; tries++) {
                plan.advance(Ground.Budget.unlimited());
                helper.assertTrue(level.getBlockState(target).isAir() && pack.countItem(Items.DIRT) == 8,
                    "continuing a fresh person proof never seals the mouth or consumes material");
                blocked = Commit.run(level, retained, worker(builder, pack));
            }
            helper.assertTrue(blocked instanceof Outcome.Failed failed
                    && failed.why() == GroundRefusal.FILL_ACCESS_BLOCKED
                    && !(retained.failing(level, failed.why()) instanceof Recourse.Wait),
                "a complete builder suffix is insufficient: the real remote person must produce a finished refusal");
            helper.assertTrue(level.getBlockState(target).isAir() && pack.countItem(Items.DIRT) == 8
                    && far.isAlive() && !far.isInWall(),
                "the outside-range person keeps its only real exit and all eight blocks remain");
            // Explicit occupancy change, not an autonomous evacuation claim. Keep both real actors.
            far.setPos(Vec3.atBottomCenterOf(origin.offset(4, 1, 11)));
            helper.assertTrue(Commit.run(level, retained, worker(builder, pack)) instanceof Outcome.Done,
                "the same retained request can seal the entrance once the far resident really leaves");
            helper.assertTrue(pack.countItem(Items.DIRT) == 7 && level.getBlockState(target).is(Blocks.DIRT)
                    && far.isAlive() && !far.isInWall(),
                "one later successful write spends one block while both real residents remain safe");
            finishRetreat(helper, plan, pack);
        }

        private String visibility(String name, Mob body) {
            BlockPos at = body.blockPosition();
            long key = ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4);
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            return name + " pos=" + at.toShortString() + " loaded=" + level.isLoaded(at)
                + " forced=" + level.getForcedChunks().contains(key) + " full=" + (chunk == null ? "none" : chunk.getFullStatus())
                + " entitiesLoaded=" + level.areEntitiesLoaded(key) + " ticking=" + level.isPositionEntityTicking(at)
                + " visible=" + (level.getEntities().get(body.getUUID()) == body) + " entityTicks=" + body.tickCount;
        }

        private void close() {
            if (closed) return;
            closed = true;
            if (plan != null) plan.close();
            if (builder != null) builder.discard();
            if (far != null) far.discard();
            for (long key : forcedByThisTest) level.setChunkForced(ChunkPos.getX(key), ChunkPos.getZ(key), false);
        }
    }

    private static final class OneWorkExecution {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos target;
        private final Mob actor;
        private final FillPlan plan;
        private final CompletionRuntime runtime;
        private final SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        private final Node fill;
        private final Vec3 overlapPose;
        private Node retreat;
        private BlockPos retreatTarget;
        private int phase;
        private int remainingTicks = 1200;
        private int permitCalls;
        private int retreatCalls;
        private int permitPending;
        private int retreatPending;
        private long firstPermitTick = Long.MIN_VALUE;
        private long firstRetreatTick = Long.MIN_VALUE;
        private boolean retreatArrivalSet;
        private boolean closed;

        OneWorkExecution(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            floor(level, origin, 23, 23);
            target = origin.offset(11, 1, 11);
            BlockPos safe = origin.offset(1, 1, 1);
            UUID colony = UUID.randomUUID();
            actor = resident(helper, safe, colony);
            // The normal Commit and StepAside Commit get one work too. Direct Runtime calls do not
            // replenish this shared colony allowance; a normal commit is attempted at most once/tick.
            plan = new FillPlan(level, UUID.randomUUID(), colony, Set.of(target), () -> List.of(safe),
                () -> { }, false, new FillPlan.Limits(1, 0));
            try {
                // Initial whole-tail construction is fixture setup. The execution native gate is cold.
                // All later execution, approach and retreat calls below have exactly one work unit.
                refresh(plan, target);
                Job job = head(helper, plan);
                runtime = runtime(plan);
                helper.assertTrue(value(runtime, "nativeBefore") == null,
                    "the complete head starts with a cold native execution gate");
                fill = node(plan, job);
                fill.planned(level);
                Vec3 centre = Vec3.atBottomCenterOf(job.footings().cells().iterator().next().block(level));
                Vec3 toward = Vec3.atBottomCenterOf(target).subtract(centre).multiply(1.0D, 0.0D, 1.0D).normalize();
                overlapPose = centre.add(toward.scale(0.85D));
                actor.setPos(overlapPose); actor.setOnGround(true);
                helper.assertTrue(io.github.izakyl.folkways.core.api.terms.Reach.standingAt(actor.position(),
                        job.footings().cells().iterator().next().block(level)) && !plan.clearOfFill(actor),
                    "the real initial arrival is within Core tolerance but its body still overhangs the future cube");
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        void start() {
            helper.onEachTick(() -> {
                if (closed) return;
                try {
                    helper.assertTrue(--remainingTicks > 0,
                        "one-work execution and retreat must terminate; phase=" + phase + "; permits=" + permitCalls
                            + "; retreats=" + retreatCalls + "; " + shortReason());
                    helper.assertTrue(actor.isAlive() && !actor.isInWall()
                            && level.noCollision(actor, actor.getBoundingBox()),
                        "every suspended execution/retreat slice keeps the real actor healthy and outside solid blocks");
                    if (phase == 0) permitTick();
                    else if (phase == 1) prepareRetreatTick();
                    else retreatTick();
                } catch (RuntimeException | Error failure) { close(); throw failure; }
            });
        }

        private void permitTick() {
            long now = level.getGameTime();
            if (firstPermitTick == Long.MIN_VALUE) firstPermitTick = now;
            for (int slice = 0; slice < 8; slice++) {
                assertLedger(false);
                Ground.Budget one = new Ground.Budget(1, 0);
                CompletionRuntime.Permit answer = runtime.permits(target, DIRT, actor, one);
                permitCalls++;
                helper.assertTrue(one.used() <= 1, "each native permit/approach call spends at most its only work unit");
                assertLedger(false);
                helper.assertTrue(answer != CompletionRuntime.Permit.CHANGED,
                    "suspension does not revoke an otherwise unchanged logical request");
                if (answer != CompletionRuntime.Permit.READY) { permitPending++; continue; }
                helper.assertTrue(now > firstPermitTick && permitPending > 0,
                    "the cold execution proof and same-tick leases really continued across actual world ticks");
                helper.assertTrue(plan.clearOfFill(actor) && actor.position().distanceToSqr(overlapPose) > 0.04D,
                    "one-work local approach physically moved the body clear without any fixture teleport after setup");
                Outcome result = Commit.run(level, fill, worker(actor, pack));
                helper.assertTrue(((Number)value(plan, "lastWork")).intValue() <= 1,
                    "the normal material Commit receives the same one-work colony allowance");
                if (result instanceof Outcome.Failed) {
                    assertLedger(false);
                    return;
                }
                helper.assertTrue(result instanceof Outcome.Done, "the actual normal node eventually commits with one work");
                assertLedger(true);
                helper.assertTrue(!plan.complete(), "a successful last placement still owes the actual original actor's retreat");
                phase = 1;
                return;
            }
        }

        private void prepareRetreatTick() {
            assertLedger(true);
            for (int slice = 0; slice < 32 && plan.steppingAside().isEmpty(); slice++) {
                Ground.Budget one = new Ground.Budget(1, 0);
                plan.advance(one);
                helper.assertTrue(one.used() <= 1, "post-placement validation also continues within each one-work slice");
                assertLedger(true);
            }
            if (plan.steppingAside().isEmpty()) return;
            retreat = plan.steppingAside().getFirst().nodes().getFirst();
            helper.assertTrue(retreat.worker().filter(actor.getUUID()::equals).isPresent(),
                "the actual placed block retains the original actor's retreat obligation");
            retreatTarget = retreat.spec().stances().cells().iterator().next().block(level);
            helper.assertTrue(!io.github.izakyl.folkways.core.api.terms.Reach.standingAt(actor.position(), retreatTarget),
                "the cold destination proof starts away from its safe root and must actually search a return path");
            phase = 2;
        }

        private void retreatTick() {
            assertLedger(true);
            long now = level.getGameTime();
            if (firstRetreatTick == Long.MIN_VALUE) firstRetreatTick = now;
            for (int slice = 0; slice < 32; slice++) {
                Ground.Budget one = new Ground.Budget(1, 0);
                boolean ready = runtime.safeDestination(actor, retreatTarget, one);
                retreatCalls++;
                helper.assertTrue(one.used() <= 1, "each actual destination proof spends at most one work unit");
                assertLedger(true);
                if (!ready) { retreatPending++; continue; }
                if (now == firstRetreatTick) return;
                helper.assertTrue(retreatPending > 0,
                    "the destination gate really suspended rather than inheriting an omitted positive proof");
                if (!retreatArrivalSet) {
                    helper.assertTrue(Commit.run(level, retreat, worker(actor, pack)) instanceof Outcome.Failed
                            && !plan.complete(),
                        "a remote positive destination proof still cannot fake the actual StepAside arrival receipt");
                    assertLedger(true);
                    // Only the retreat-arrival fixture is positioned explicitly. The preceding
                    // overlap correction used real movement. This phase tests permission continuation.
                    actor.setPos(Vec3.atBottomCenterOf(retreatTarget)); actor.setOnGround(true);
                    retreatArrivalSet = true;
                    return;
                }
                Outcome result = Commit.run(level, retreat, worker(actor, pack));
                helper.assertTrue(((Number)value(plan, "lastWork")).intValue() <= 1,
                    "the actual StepAside Commit also receives only one colony work unit");
                assertLedger(true);
                if (result instanceof Outcome.Failed) return;
                helper.assertTrue(result instanceof Outcome.Done && plan.complete(),
                    "one-work fresh destination permission and the real StepAside Commit settle actual completion");
                helper.assertTrue(permitCalls > 1 && retreatCalls > 1 && level.getGameTime() > firstPermitTick,
                    "both permission pipelines progressed across suspended work and real ticks");
                close();
                helper.succeed();
                return;
            }
        }

        private void assertLedger(boolean placed) {
            helper.assertTrue(level.getBlockState(target).is(placed ? Blocks.DIRT : Blocks.AIR)
                    && pack.countItem(Items.DIRT) == (placed ? 7 : 8),
                "only the one actual successful placement changes terrain and consumes exactly one material");
        }

        private String shortReason() {
            String reason = runtime.phase() + ": " + runtime.diagnostics().get("lastPermitReason");
            return reason.length() <= 240 ? reason : reason.substring(0, 240);
        }

        private void close() {
            if (closed) return;
            closed = true;
            plan.close();
            actor.discard();
        }
    }

    private static CompletionRuntime runtime(FillPlan plan) {
        return (CompletionRuntime)value(plan, "completion");
    }

    private static Object value(Object owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
    }

    private static List<Long> force(ServerLevel level, BlockPos from, BlockPos to) {
        List<Long> newlyForced = new ArrayList<>();
        for (int x = from.getX() >> 4; x <= to.getX() >> 4; x++) for (int z = from.getZ() >> 4; z <= to.getZ() >> 4; z++) {
            long key = ChunkPos.asLong(x, z);
            if (!level.getForcedChunks().contains(key)) { level.setChunkForced(x, z, true); newlyForced.add(key); }
            level.getChunk(x, z);
        }
        return newlyForced;
    }

    private static void floor(ServerLevel level, BlockPos origin, int maxX, int maxZ) {
        for (int x = 0; x <= maxX; x++) for (int z = 0; z <= maxZ; z++) for (int y = 0; y <= 5; y++) {
            level.setBlock(origin.offset(x, y, z), y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static Mob resident(GameTestHelper helper, BlockPos at, UUID colony) {
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(at)); body.setOnGround(true);
        io.github.izakyl.folkways.plugins.person.walk.Walking.INSTANCE.fit(body);
        body.setNoAi(false);
        ((Body) body).joinColony(colony);
        helper.assertTrue(((Body) body).licences().of(BuildContent.trade()).isPresent(), "the fixture actor has real building capability");
        helper.assertTrue(helper.getLevel().addFreshEntity(body), "the fixture actor is a real loaded level entity");
        return body;
    }

    private static void refresh(FillPlan plan, BlockPos target) {
        plan.refresh(List.of(), Set.of(Job.keyOf(target)), DIRT);
        for (int attempt = 0; attempt < 16 && plan.jobs().isEmpty(); attempt++) plan.advance(Ground.Budget.unlimited());
    }

    private static Job head(GameTestHelper helper, FillPlan plan) {
        helper.assertTrue(plan.jobs().size() == 1, "a verified complete suffix must publish its one head: "
            + plan.completionStatus() + ": " + plan.completionReason());
        return plan.jobs().getFirst();
    }

    private static void stand(Mob body, Job job, ServerLevel level) {
        body.setPos(Vec3.atBottomCenterOf(job.footings().cells().iterator().next().block(level))); body.setOnGround(true);
    }

    private static void finishRetreat(GameTestHelper helper, FillPlan plan, SimpleContainer pack) {
        plan.advance(Ground.Budget.unlimited());
        helper.assertTrue(!plan.complete() && !plan.steppingAside().isEmpty(), "the material receipt alone does not settle final retreat");
        for (Grown grown : List.copyOf(plan.steppingAside())) {
            Node move = grown.nodes().getFirst();
            Mob actor = (Mob) helper.getLevel().getEntity(move.worker().orElseThrow());
            helper.assertTrue(actor != null, "retreat still names the actual obligated builder");
            actor.setPos(Vec3.atBottomCenterOf(move.spec().stances().cells().iterator().next().block(helper.getLevel())));
            actor.setOnGround(true);
            helper.assertTrue(Commit.run(helper.getLevel(), move, worker(actor, pack)) instanceof Outcome.Done,
                "only an actual safe arrival and its fresh permission settle retreat");
        }
        helper.assertTrue(plan.complete(), "all real target and actual-retreat facts complete the project");
    }

    private static Node node(FillPlan plan, Job job) {
        Chores chores = new Chores(UUID.randomUUID(), "fill", (at, chore) -> new FillNode(chore, MATERIAL, plan),
            () -> { }, new PastDay());
        return new FillNode(new Chores.Chore(UUID.randomUUID(), job, chores), MATERIAL, plan);
    }

    private static Worker worker(Mob body, SimpleContainer pack) {
        return (Worker) Proxy.newProxyInstance(CompletionReviewGameTests.class.getClassLoader(), new Class<?>[] { Worker.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "body" -> body;
                case "pack" -> pack;
                case "resident" -> new Resident(body.getUUID(), PersonBody.ID);
                case "effects" -> Effects.NONE;
                case "placement" -> Placement.NOWHERE;
                case "held" -> ItemStack.EMPTY;
                case "within", "route", "supplied" -> List.of();
                default -> null;
            });
    }
}
