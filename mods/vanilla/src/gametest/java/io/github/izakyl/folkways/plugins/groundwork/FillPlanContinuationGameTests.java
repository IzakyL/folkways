package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Asking;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Recourse;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FillPlanContinuationGameTests {
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final ItemSpec MATERIAL = ItemSpec.of(ResourceLocation.withDefaultNamespace("dirt"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FillPlanContinuationGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void reducingTargetsKeepsTheOtherAlreadyIssuedRequest(GameTestHelper helper) {
        floor(helper);
        BlockPos a = at(helper, 8, 0, 8);
        BlockPos b = at(helper, 16, 0, 16);
        helper.getLevel().setBlock(a, Blocks.AIR.defaultBlockState(), 3);
        helper.getLevel().setBlock(b, Blocks.AIR.defaultBlockState(), 3);
        try (Fixture fixture = fixture(helper, Set.of(a, b))) {
            FillPlan plan = fixture.plan;
            List<Job> jobs = refresh(plan, helper, Set.of(a, b));
            helper.assertTrue(jobs.size() == 1, "a complete suffix issues only its strictly ordered next placement");
            Job first = jobs.getFirst();
            BlockPos retained = first.cell();
            Node held = node(plan, first);
            held.planned(helper.getLevel());
            plan.refresh(new Survey(helper.getLevel(), Set.of(retained)).fill(), Set.of(Job.keyOf(retained)), DIRT);
            helper.assertTrue(held.refusal(helper.getLevel()).isEmpty(),
                "removing a later target must not terminate the still wanted issued head");
            helper.assertTrue(plan.requests().stream().anyMatch(job -> job.cell().equals(retained)),
                "the issued head identity survives removal of a later, unexecuted target");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void anIssuedRequestSurvivesTerrainRevalidationBeforeTheCoreClaimsIt(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 16, 0, 16);
        helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        try (Fixture fixture = fixture(helper, Set.of(cell))) {
            FillPlan plan = fixture.plan;
            Job issued = refresh(plan, helper, Set.of(cell)).getFirst();
            Node waiting = node(plan, issued);
            helper.getLevel().setBlock(at(helper, 3, 1, 12), Blocks.STONE.defaultBlockState(), 3);
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(!plan.offered(cell), "the old positive geometry is revoked immediately");
            helper.assertTrue(plan.requests().stream().anyMatch(job -> job.cell().equals(cell)),
                "a proved published request cannot disappear in the intake window before planned is called");
            helper.assertTrue(waiting.refusal(helper.getLevel()).isEmpty(),
                "revalidating the terrain does not turn a still executable request into a terminal failure");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void aClaimedRequestSurvivesTinySlicesAndProvesFreshGeometryAtCommit(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 16, 0, 16);
        helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = fixture(helper, Set.of(cell), builder)) {
            FillPlan plan = fixture.plan;
            Job job = refresh(plan, helper, Set.of(cell)).getFirst();
            Node held = node(plan, job);
            held.planned(helper.getLevel());
            helper.getLevel().setBlock(at(helper, 3, 1, 12), Blocks.STONE.defaultBlockState(), 3);
            for (int slice = 0; slice < 31; slice++) {
                Ground.Budget budget = new Ground.Budget(1, 0);
                plan.advance(budget);
                helper.assertTrue(budget.used() <= 1 && held.refusal(helper.getLevel()).isEmpty(),
                    "a one-unit continuation keeps the held request without approving its stale geometry");
            }
            helper.assertTrue(!plan.offered(cell), "the suspended proof has not silently approved this cell");
            builder.setPos(Vec3.atBottomCenterOf(job.footings().cells().iterator().next().block(helper.getLevel())));
            helper.assertTrue(Commit.run(helper.getLevel(), held, worker(builder, pack)) instanceof Outcome.Done,
                "the original node can finish a fresh complete proof even while the planning face is absent");
            helper.assertTrue(helper.getLevel().getBlockState(cell).is(Blocks.DIRT) && pack.countItem(Items.DIRT) == 7,
                "the fresh commit really places exactly one supplied block");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void removingTheRequestedTargetOrChangingMaterialStillRevokesTheOldRequest(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 16, 0, 16);
        helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        try (Fixture fixture = fixture(helper, Set.of(cell))) {
            FillPlan plan = fixture.plan;
            Node old = node(plan, refresh(plan, helper, Set.of(cell)).getFirst());
            plan.refresh(List.of(), Set.of(Job.keyOf(cell)), DIRT);
            helper.assertTrue(old.refusal(helper.getLevel()).isEmpty(),
                "a whole-tail request does not disappear merely because Survey has no instantaneous stance");
            plan.refresh(List.of(), Set.of(), DIRT);
            helper.assertTrue(old.refusal(helper.getLevel()).isPresent() && plan.requests().isEmpty(),
                "an explicitly cancelled target cannot keep its old request or placement permission");
            Node rebuilt = node(plan, refresh(plan, helper, Set.of(cell)).getFirst());
            plan.refresh(new Survey(helper.getLevel(), Set.of(cell)).fill(), Set.of(Job.keyOf(cell)),
                Blocks.STONE.defaultBlockState());
            helper.assertTrue(rebuilt.refusal(helper.getLevel()).isPresent(), "changed material still cancels the old request");
            plan.advance(Ground.Budget.unlimited());
            helper.assertTrue(!plan.jobs().isEmpty() && rebuilt.refusal(helper.getLevel()).isPresent(),
                "publishing the same cell under the new material must not resurrect the old dirt node");
            plan.close();
            helper.assertTrue(rebuilt.refusal(helper.getLevel()).isPresent(), "a closed plan never keeps a request alive");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void aRetainedRequestRechecksARealOccupantAndReturnsAllMaterial(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 16, 0, 16);
        helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        Mob builder = resident(helper, at(helper, 1, 1, 1));
        Mob bystander = null;
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = fixture(helper, Set.of(cell), builder)) {
            FillPlan plan = fixture.plan;
            Job job = refresh(plan, helper, Set.of(cell)).getFirst();
            Node held = node(plan, job);
            held.planned(helper.getLevel());
            builder.setPos(Vec3.atBottomCenterOf(job.footings().cells().iterator().next().block(helper.getLevel())));
            bystander = resident(helper, cell);
            helper.getLevel().setBlock(at(helper, 3, 1, 12), Blocks.STONE.defaultBlockState(), 3);
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(held.refusal(helper.getLevel()).isEmpty(), "the logical request survives the unrelated change");
            long version = plan.requestVersion();
            Outcome outcome = Commit.run(helper.getLevel(), held, worker(builder, pack));
            helper.assertTrue(outcome instanceof Outcome.Failed,
                "the retained request cannot use its former proof to place through a newly arrived body");
            helper.assertTrue(helper.getLevel().getBlockState(cell).isAir() && pack.countItem(Items.DIRT) == 8,
                "the first real attempt refunds every supplied block even if the new person requires recapture");
            boolean blocked = false;
            int slices = 0;
            for (; slices < 4096 && !blocked; slices++) {
                Ground.Budget budget = new Ground.Budget(256, 0);
                plan.advance(budget);
                helper.assertTrue(budget.used() <= 256, "recapture and the full suffix share each bounded slice");
                helper.assertTrue(plan.requestVersion() == version && held.refusal(helper.getLevel()).isEmpty()
                        && plan.requests().stream().anyMatch(current -> current.key().equals(job.key())),
                    "the original held request and version survive every recapture slice");
                Optional<Job> current = plan.jobs().stream().filter(next -> next.cell().equals(cell)).findFirst();
                if (current.isPresent()) {
                    // This case tests a live occupant, not navigation arrival. Re-establish the new
                    // certificate's actual builder stance before retrying the very same held node.
                    builder.setPos(Vec3.atBottomCenterOf(current.get().footings().cells().iterator().next()
                        .block(helper.getLevel())));
                    outcome = Commit.run(helper.getLevel(), held, worker(builder, pack));
                    helper.assertTrue(outcome instanceof Outcome.Failed,
                        "a freshly recaptured certificate still cannot place through the real occupant");
                    Outcome.Failed refused = (Outcome.Failed) outcome;
                    blocked = refused.why() == GroundRefusal.FILL_ACCESS_BLOCKED
                        && !(held.failing(helper.getLevel(), refused.why()) instanceof Recourse.Wait);
                }
                helper.assertTrue(helper.getLevel().getBlockState(cell).isAir() && pack.countItem(Items.DIRT) == 8,
                    "every real retry preserves the target and returns all supplied material; slice=" + slices);
                helper.assertTrue(bystander.isAlive() && !bystander.isInWall()
                        && bystander.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(cell)),
                    "the actual occupant remains in the target throughout the bounded proof");
            }
            helper.assertTrue(blocked,
                "the original real request must reach a definite occupant BLOCKED refusal, not an endless Wait or UNKNOWN; slices="
                    + slices + "; status=" + plan.completionStatus() + "; reason=" + plan.completionReason()
                    + "; cursor=" + plan.completionCursor());
            helper.assertTrue(plan.requestVersion() == version && held.refusal(helper.getLevel()).isEmpty(),
                "a complete safety refusal does not cancel the still wanted original request identity");
            helper.assertTrue(helper.getLevel().getBlockState(cell).isAir() && pack.countItem(Items.DIRT) == 8,
                "the real commit preserves the occupant and refunds every supplied block");
            helper.assertTrue(bystander.isAlive() && !bystander.isInWall(), "the actual occupant remains safe");
        } finally {
            builder.discard();
            if (bystander != null) bystander.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void aRetainedRequestCannotCloseTheOnlyRemainingRealExit(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 6; x <= 16; x++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlock(at(helper, x, y, 9), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at(helper, x, y, 11), Blocks.STONE.defaultBlockState(), 3);
            }
            level.setBlock(at(helper, x, 3, 10), Blocks.STONE.defaultBlockState(), 3);
        }
        BlockPos cell = at(helper, 6, 1, 10);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        Mob inside = resident(helper, at(helper, 11, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = fixture(helper, Set.of(cell), builder)) {
            FillPlan plan = fixture.plan;
            Node held = node(plan, refresh(plan, helper, Set.of(cell)).getFirst());
            held.planned(level);
            for (int y = 1; y <= 2; y++) level.setBlock(at(helper, 16, y, 10), Blocks.STONE.defaultBlockState(), 3);
            plan.advance(new Ground.Budget(1, 0));
            helper.assertTrue(held.refusal(level).isEmpty(), "the request remains while its current geometry is unproved");
            Outcome outcome = Commit.run(level, held, worker(builder, pack));
            helper.assertTrue(outcome instanceof Outcome.Failed,
                "fresh geometry must see that this placement now seals the bystander in the tunnel");
            helper.assertTrue(!(held.failing(level, ((Outcome.Failed) outcome).why()) instanceof Recourse.Wait),
                "a completed last-exit counterexample fails rather than keeping the colony on a budget lease");
            helper.assertTrue(level.getBlockState(cell).isAir() && pack.countItem(Items.DIRT) == 8,
                "the last real exit stays open and all material is returned");
            helper.assertTrue(inside.isAlive() && !inside.isInWall(), "the enclosed resident is not buried");
        } finally {
            builder.discard();
            inside.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation")
    public static void aRetainedEntranceProtectsANewlyRequestedInnerJobBeforePlanningInitializesIt(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 6; x <= 16; x++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlock(at(helper, x, y, 9), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at(helper, x, y, 11), Blocks.STONE.defaultBlockState(), 3);
            }
            level.setBlock(at(helper, x, 3, 10), Blocks.STONE.defaultBlockState(), 3);
        }
        for (int y = 1; y <= 2; y++) level.setBlock(at(helper, 16, y, 10), Blocks.STONE.defaultBlockState(), 3);
        BlockPos entrance = at(helper, 6, 1, 10);
        BlockPos inner = at(helper, 13, 1, 10);
        Set<BlockPos> cells = Set.of(entrance, inner);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = fixture(helper, cells, builder)) {
            FillPlan plan = fixture.plan;
            List<Job> inputs = new Survey(level, cells).fill();
            plan.refresh(inputs, Set.of(Job.keyOf(entrance)), DIRT);
            plan.advance(Ground.Budget.unlimited());
            Node held = node(plan, plan.jobs().getFirst());
            held.planned(level);
            plan.refresh(inputs, Set.of(Job.keyOf(entrance), Job.keyOf(inner)), DIRT);
            helper.assertTrue(held.refusal(level).isEmpty(), "adding another job does not cancel the issued entrance request");
            helper.assertTrue(!plan.offered(entrance), "the expanded work set has not been geometrically re-proved");
            Outcome result = Commit.run(level, held, worker(builder, pack));
            helper.assertTrue(result instanceof Outcome.Failed,
                "a retained node must protect the newly requested inner work even before Planning.step1 runs");
            helper.assertTrue(level.getBlockState(entrance).isAir() && level.getBlockState(inner).isAir()
                && pack.countItem(Items.DIRT) == 8, "the only work entrance stays open and every block is refunded");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_dispatch", timeoutTicks = 1200)
    public static void aRealBodyRunnerKeepsItsRequestThroughTerrainRevalidationAndActuallyFills(GameTestHelper helper) {
        floor(helper);
        BlockPos cell = at(helper, 18, 0, 18);
        helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3);
        new Dispatch(helper, cell).tick(1100);
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_edge_support", timeoutTicks = 1200)
    public static void aRealRunnerLeavesAnAdjacentBlockEdgeWithoutRecenteringByTeleport(GameTestHelper helper) {
        floor(helper);
        // The live 74/400 stall translated up five blocks: the actual AABB is supported by
        // (10,1,4), while its floor-position centre has AIR below at (9,1,4).
        BlockPos target = at(helper, 10, 1, 3);
        helper.getLevel().setBlock(at(helper, 10, 1, 4), DIRT, 3);
        new Dispatch(helper, target, true).tick(1100);
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_background", timeoutTicks = 2000)
    public static void threeRealRunnersUseWindupGapsToContinueTheBoundedGeometryAndSettleTheirFills(GameTestHelper helper) {
        floor(helper);
        Set<BlockPos> cells = Set.of(at(helper, 8, 0, 8), at(helper, 16, 0, 16), at(helper, 8, 0, 16));
        cells.forEach(cell -> helper.getLevel().setBlock(cell, Blocks.AIR.defaultBlockState(), 3));
        new BackgroundDispatch(helper, cells).tick(1900);
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_background")
    public static void aFinishedBackgroundCounterexampleCannotBecomeAnEndlessZeroBudgetWait(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        for (int x = 6; x <= 16; x++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlock(at(helper, x, y, 9), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at(helper, x, y, 11), Blocks.STONE.defaultBlockState(), 3);
            }
            level.setBlock(at(helper, x, 3, 10), Blocks.STONE.defaultBlockState(), 3);
        }
        for (int y = 1; y <= 2; y++) level.setBlock(at(helper, 16, y, 10), Blocks.STONE.defaultBlockState(), 3);
        BlockPos entrance = at(helper, 6, 1, 10);
        BlockPos inner = at(helper, 13, 1, 10);
        Set<BlockPos> cells = Set.of(entrance, inner);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = new Fixture(helper, cells, builder, new FillPlan.Limits(0, 0))) {
            FillPlan plan = fixture.plan;
            List<Job> inputs = new Survey(level, cells).fill();
            plan.refresh(inputs, Set.of(Job.keyOf(entrance)), DIRT);
            plan.advance(Ground.Budget.unlimited());
            Node held = node(plan, plan.jobs().getFirst());
            held.planned(level);
            plan.refresh(inputs, Set.of(Job.keyOf(entrance), Job.keyOf(inner)), DIRT);
            Outcome pending = Commit.run(level, held, worker(builder, pack));
            helper.assertTrue(pending instanceof Outcome.Failed
                && held.failing(level, ((Outcome.Failed) pending).why()) instanceof Recourse.Wait,
                "zero work queues the still unknown geometry rather than granting a placement");
            for (int slice = 0; slice < 1000000 && plan.proving(entrance); slice++) {
                Ground.Budget budget = new Ground.Budget(1, 0);
                plan.advance(budget);
                helper.assertTrue(budget.used() <= 1, "background work also respects an injected one-unit slice");
            }
            helper.assertTrue(!plan.proving(entrance),
                "one-unit continuations must eventually finish the real counterexample rather than consume their own only unit");
            Outcome refused = Commit.run(level, held, worker(builder, pack));
            helper.assertTrue(refused instanceof Outcome.Failed
                && !(held.failing(level, ((Outcome.Failed) refused).why()) instanceof Recourse.Wait),
                "the finished work-route counterexample remains a real refusal even with zero remaining work");
            helper.assertTrue(level.getBlockState(entrance).isAir() && level.getBlockState(inner).isAir()
                && pack.countItem(Items.DIRT) == 8 && builder.isAlive() && !builder.isInWall(),
                "neither the background search nor its terminal refusal places blocks or consumes material");
        } finally {
            builder.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_publication")
    public static void passageWorkWaitsForTheVerifiedWholeTailAndPublishesOnlyItsHead(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        Set<BlockPos> cells = new java.util.LinkedHashSet<>();
        for (int x = 6; x <= 16; x++) {
            for (int y = 1; y <= 4; y++) {
                level.setBlock(at(helper, x, y, 9), Blocks.STONE.defaultBlockState(), 3);
                level.setBlock(at(helper, x, y, 11), Blocks.STONE.defaultBlockState(), 3);
            }
            level.setBlock(at(helper, x, 4, 10), Blocks.STONE.defaultBlockState(), 3);
            if (x < 16) cells.add(at(helper, x, 1, 10));
        }
        for (int y = 1; y <= 3; y++) level.setBlock(at(helper, 17, y, 10), Blocks.STONE.defaultBlockState(), 3);
        Mob builder = resident(helper, at(helper, 5, 1, 10));
        Mob inside = resident(helper, at(helper, 16, 1, 10));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = fixture(helper, cells, builder)) {
            FillPlan plan = fixture.plan;
            plan.refresh(new Survey(level, cells).fill(), cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
            int slices = 0;
            while (plan.jobs().isEmpty() && slices++ < 32768) {
                helper.assertTrue(plan.requests().isEmpty(),
                    "no individual positive candidate is an issued construction request before a complete suffix exists");
                Ground.Budget budget = new Ground.Budget(256, 0);
                plan.advance(budget);
                helper.assertTrue(budget.used() <= 256, "capture, whole-tail search and replay share each bounded slice");
            }
            helper.assertTrue(plan.jobs().size() == 1 && plan.requests().size() == 1 && slices > 1,
                "a complete construction and final-retreat certificate publishes exactly its next placement");
            Job job = plan.jobs().getFirst();
            builder.setPos(Vec3.atBottomCenterOf(job.footings().cells().iterator().next().block(level)));
            Node held = node(plan, job);
            held.planned(level);
            helper.assertTrue(Commit.run(level, held, worker(builder, pack)) instanceof Outcome.Done,
                "the certificate head still passes the complete fresh actual-person and actual-write gate");
            helper.assertTrue(level.getBlockState(job.cell()).is(Blocks.DIRT) && pack.countItem(Items.DIRT) == 7
                    && inside.isAlive() && !inside.isInWall(),
                "one real prefix block is placed with exact material accounting and the other person safe");
            helper.assertTrue(!plan.complete(), "one prefix receipt cannot finish the ten-target project");
        } finally {
            builder.discard();
            inside.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_saved_retreat")
    public static void savedLastPlacementKeepsTheOriginalBuildersActualRetreatObligation(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos target = at(helper, 16, 0, 16);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        Mob body = resident(helper, at(helper, 1, 1, 1));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.DIRT, 8));
        try (Fixture fixture = fixture(helper, Set.of(target), body)) {
            FillPlan original = fixture.plan;
            Job head = refresh(original, helper, Set.of(target)).getFirst();
            body.setPos(Vec3.atBottomCenterOf(head.footings().cells().iterator().next().block(level)));
            Node placement = node(original, head);
            helper.assertTrue(Commit.run(level, placement, worker(body, pack)) instanceof Outcome.Done,
                "the last material receipt is a real successful node write");
            helper.assertTrue(level.getBlockState(target).is(Blocks.DIRT) && pack.countItem(Items.DIRT) == 7,
                "exactly one real block was spent before saving");
            helper.assertTrue(!original.complete(), "last placement is not the original builder's actual retreat");
            net.minecraft.nbt.CompoundTag saved = original.saveCompletion();
            original.close();
            try (FillPlan reloaded = new FillPlan(level, UUID.randomUUID(), fixture.colony.colonyId(), Set.of(target),
                    () -> List.of(at(helper, 1, 1, 1)), () -> { }, false,
                    new FillPlan.Limits(Integer.MAX_VALUE, 0))) {
                reloaded.restoreCompletion(saved);
                reloaded.refresh(List.of(), Set.of(), DIRT);
                reloaded.advance(Ground.Budget.unlimited());
                helper.assertTrue(!reloaded.complete() && reloaded.worker().filter(body.getUUID()::equals).isPresent(),
                    "empty saved targets retain the same obligated builder and do not finish by selecting another actor");
                helper.assertTrue(reloaded.jobs().isEmpty() && !reloaded.steppingAside().isEmpty(),
                    "reload proves an actual final-retreat job rather than inventing another placement");
                Node retreat = reloaded.steppingAside().getFirst().nodes().getFirst();
                BlockPos safe = retreat.spec().stances().cells().iterator().next().block(level);
                Outcome premature = retreat.commit(level, worker(body, pack));
                helper.assertTrue(premature instanceof Outcome.Failed && !reloaded.complete(),
                    "a remote retreat commit cannot settle the task or record an arrival that did not happen");
                body.setPos(Vec3.atBottomCenterOf(safe));
                helper.assertTrue(retreat.commit(level, worker(body, pack)) instanceof Outcome.Done && reloaded.complete(),
                    "only the original builder's actual safe arrival consumes Finish after reload");
                helper.assertTrue(pack.countItem(Items.DIRT) == 7 && body.isAlive() && !body.isInWall(),
                    "retreat and NBT restoration neither consume nor duplicate material and keep the worker safe");
            }
        } finally {
            body.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_saved_retreat")
    public static void unknownSavedFactsCannotTurnAnEmptyTargetSetIntoCompletion(GameTestHelper helper) {
        floor(helper);
        BlockPos target = at(helper, 16, 0, 16);
        helper.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        try (Fixture fixture = fixture(helper, Set.of(target))) {
            FillPlan plan = fixture.plan;
            net.minecraft.nbt.CompoundTag unknown = new net.minecraft.nbt.CompoundTag();
            unknown.putInt("format", Integer.MAX_VALUE);
            plan.restoreCompletion(unknown);
            plan.refresh(List.of(), Set.of(), DIRT);
            plan.advance(Ground.Budget.unlimited());
            helper.assertTrue(!plan.complete() && plan.completionStatus().equals("CONFLICT") && plan.jobs().isEmpty(),
                "an unknown receipt format is a durable conflict, never a no-work completion fact");
            helper.assertTrue(helper.getLevel().getBlockState(target).isAir(),
                "rejecting unknown saved facts does not fill or reconcile a target by invention");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "fill_continuation_saved_retreat")
    public static void aPhysicalWriteWithoutPreparedPrefixRemainsAConflictAfterSaveAndReload(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos target = at(helper, 16, 0, 16);
        level.setBlock(target, Blocks.AIR.defaultBlockState(), 3);
        try (Fixture fixture = fixture(helper, Set.of(target))) {
            FillPlan original = fixture.plan;
            refresh(original, helper, Set.of(target));
            // Deliberately model an unexpected write/callback, not a successful Commit.run permission.
            helper.assertTrue(level.setBlock(target, DIRT, 3), "the unexpected write really exists in the world");
            original.committed(target, DIRT, fixture.builder);
            helper.assertTrue(!original.complete() && original.completionStatus().equals("CONFLICT"),
                "without a same-tick prepared permit, a physical block cannot consume the model prefix");
            net.minecraft.nbt.CompoundTag saved = original.saveCompletion();
            original.close();
            try (FillPlan reloaded = new FillPlan(level, UUID.randomUUID(), fixture.colony.colonyId(), Set.of(target),
                    () -> List.of(at(helper, 1, 1, 1)), () -> { }, false,
                    new FillPlan.Limits(Integer.MAX_VALUE, 0))) {
                reloaded.restoreCompletion(saved);
                reloaded.refresh(List.of(), Set.of(), DIRT);
                reloaded.advance(Ground.Budget.unlimited());
                helper.assertTrue(!reloaded.complete() && reloaded.completionStatus().equals("CONFLICT")
                        && reloaded.jobs().isEmpty(),
                    "empty remaining keys after reload cannot erase the recorded unmatched-write conflict");
                helper.assertTrue(level.getBlockState(target).is(Blocks.DIRT) && fixture.builder.isAlive()
                        && !fixture.builder.isInWall(),
                    "reloading a conflict neither reverses the real write nor endangers the actor");
            }
        }
        helper.succeed();
    }

    private static Object value(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(broken);
        }
    }

    private static Fixture fixture(GameTestHelper helper, Set<BlockPos> cells) {
        return new Fixture(helper, cells, null, new FillPlan.Limits(Integer.MAX_VALUE, 0));
    }

    private static Fixture fixture(GameTestHelper helper, Set<BlockPos> cells, Mob builder) {
        return new Fixture(helper, cells, builder, new FillPlan.Limits(Integer.MAX_VALUE, 0));
    }

    private static final class Fixture implements AutoCloseable {
        final ColonyData colony;
        final ResidentEntity builder;
        final FillPlan plan;
        final boolean owned;
        Fixture(GameTestHelper helper, Set<BlockPos> cells, Mob actor, FillPlan.Limits limits) {
            ServerLevel level = helper.getLevel();
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            owned = actor == null;
            builder = (ResidentEntity) (actor == null ? resident(helper, at(helper, 1, 1, 1)) : actor);
            builder.setNoAi(false);
            builder.pack().setItem(1, new ItemStack(Items.DIAMOND_SHOVEL));
            builder.pack().setItem(2, new ItemStack(Items.DIAMOND_AXE));
            builder.pack().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE));
            colony.works().hold(PersonContent.ID, Held.Entity.of(builder)).orElseThrow();
            builder.joinColony(colony.colonyId());
            colony.works().entered(builder);
            plan = new FillPlan(level, UUID.randomUUID(), colony.colonyId(), cells,
                () -> List.of(at(helper, 1, 1, 1)), () -> { }, false, limits);
        }
        @Override public void close() {
            plan.close();
            if (owned) builder.discard();
            colony.works().raze();
            ColonyWays.forget(colony.colonyId());
        }
    }

    private static List<Job> refresh(FillPlan plan, GameTestHelper helper, Set<BlockPos> cells) {
        plan.refresh(new Survey(helper.getLevel(), cells).fill(), cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
        plan.advance(Ground.Budget.unlimited());
        helper.assertTrue(plan.stage().equals("complete"), "the initial fixture obtains a complete proof");
        return plan.jobs();
    }

    private static Node node(FillPlan plan, Job job) {
        Chores chores = new Chores(UUID.randomUUID(), "fill", (level, chore) -> new FillNode(chore, MATERIAL, plan),
            () -> { }, new PastDay());
        return new FillNode(new Chores.Chore(UUID.randomUUID(), job, chores), MATERIAL, plan);
    }

    private static Worker worker(Mob body, SimpleContainer pack) {
        return (Worker) Proxy.newProxyInstance(FillPlanContinuationGameTests.class.getClassLoader(), new Class<?>[] {Worker.class},
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

    private static Mob resident(GameTestHelper helper, BlockPos feet) {
        Mob body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.setPos(Vec3.atBottomCenterOf(feet));
        body.setOnGround(true);
        io.github.izakyl.folkways.plugins.person.walk.Walking.INSTANCE.fit(body);
        body.setNoAi(true);
        helper.getLevel().addFreshEntity(body);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x <= 23; x++) {
            for (int z = 0; z <= 23; z++) {
                for (int y = 0; y <= 5; y++) helper.getLevel().setBlock(at(helper, x, y, z),
                    y == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static final class BackgroundDispatch {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final Set<BlockPos> cells;
        private final ColonyData colony;
        private final ColonyLabor labor;
        private final List<ResidentEntity> bodies = new ArrayList<>();
        private final Asking asking;
        private final FillPlan plan;
        private final Chores chores;
        private final Method run;
        private final Method closeLabor;
        private final Object inline;
        private int idleProofSlices;
        private boolean changed;
        private boolean closed;

        BackgroundDispatch(GameTestHelper helper, Set<BlockPos> cells) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.cells = cells;
            try {
                run = java.util.Arrays.stream(ColonyLabor.class.getMethods())
                    .filter(method -> method.getName().equals("run") && method.getParameterCount() == 2).findFirst().orElseThrow();
                Field field = run.getParameterTypes()[1].getDeclaredField("INLINE");
                field.setAccessible(true);
                inline = field.get(null);
                closeLabor = ColonyLabor.class.getDeclaredMethod("close");
                closeLabor.setAccessible(true);
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            labor = new ColonyLabor(colony, level.dimension());
            asking = new Asking(colony.works(), GroundworkContent.ID, () -> { });
            plan = new FillPlan(level, UUID.randomUUID(), colony.colonyId(), cells,
                () -> List.of(at(helper, 1, 1, 1)), () -> { }, false, new FillPlan.Limits(2048, 0));
            chores = new Chores(UUID.randomUUID(), "fill", (at, chore) -> new FillNode(chore, MATERIAL, plan),
                () -> { }, new PastDay());
            try {
                for (int i = 0; i < 3; i++) {
                    ResidentEntity body = PersonBody.RESIDENT.get().create(level);
                    bodies.add(body);
                    body.setPos(Vec3.atBottomCenterOf(at(helper, 1 + i, 1, 1)));
                    body.pack().setItem(0, new ItemStack(Items.DIRT, 8));
                    body.pack().setItem(1, new ItemStack(Items.DIAMOND_SHOVEL));
                    body.pack().setItem(2, new ItemStack(Items.DIAMOND_AXE));
                    body.pack().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE));
                    helper.assertTrue(level.addFreshEntity(body), "all three workers are real level entities");
                    colony.works().hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
                    body.joinColony(colony.colonyId());
                    colony.works().entered(body);
                }
                helper.assertTrue(refresh(plan, helper, cells).size() == 1,
                    "the complete three-target suffix exposes exactly one strictly ordered placement");
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void tick(int remaining) {
            try {
                helper.assertTrue(bodies.stream().allMatch(body -> body.isAlive() && !body.isInWall()),
                    "all actual workers stay alive and clear of solid blocks");
                if (cells.stream().allMatch(cell -> level.getBlockState(cell).is(Blocks.DIRT)) && plan.complete()) {
                    Dispatch.invoke(run, labor, level, inline);
                    helper.assertTrue(cells.stream().allMatch(cell -> chores.spent().contains(Job.keyOf(cell))),
                        "the actual BodyRunners settle every physically completed request as Done");
                    helper.assertTrue(changed && metric("commitBudgetYields") > 0 && idleProofSlices >= 3,
                        "pure geometry really progresses in multiple windup gaps without another commit attempt; changed="
                            + changed + "; yields=" + metric("commitBudgetYields") + "; idle=" + idleProofSlices);
                    helper.assertTrue(bodies.stream().mapToInt(body -> body.pack().count(Items.DIRT)).sum() == 21,
                        "the full native pipeline spends exactly three of its original twenty-four dirt");
                    close();
                    helper.succeed();
                    return;
                }
                helper.assertTrue(remaining > 0, "bounded native fills must finish; phase=" + plan.phase()
                    + "; stage=" + plan.stage() + "; attempts=" + metric("commitAttempts") + "; idle=" + idleProofSlices
                    + "; completion=" + plan.completionStatus() + "; reason=" + plan.completionReason());
                plan.refresh(new Survey(level, cells).fill(), cells.stream().map(Job::keyOf).collect(Collectors.toSet()), DIRT);
                long attempts = metric("commitAttempts");
                long work = metric("totalWork");
                boolean queued = cells.stream().anyMatch(plan::proving)
                    || changed && cells.stream().anyMatch(cell -> !level.getBlockState(cell).is(Blocks.DIRT));
                plan.advance();
                long advanced = metric("totalWork") - work;
                chores.settle(plan.requests());
                List<Grown> goals = new ArrayList<>();
                chores.goals(level, goals);
                goals.addAll(plan.steppingAside());
                asking.offer(Map.of(level.dimension(), goals));
                ColonyWays.advance(colony, level);
                if (!changed && metric("commitAttempts") == 0 && bodies.stream().anyMatch(body -> body.doing()
                        .filter(doing -> doing.what().equals(GroundworkContent.FILLING))
                        .flatMap(Doing::progress).filter(progress -> progress instanceof Doing.Progress.Span span
                            && span.until() <= level.getGameTime() + 1L).isPresent())) {
                    // Revoke the geometry at the real work gesture's completion boundary, after walking
                    // and this tick's planning. The imminent native commit cannot reuse the initial proof.
                    level.setBlock(at(helper, 3, 1, 12), Blocks.STONE.defaultBlockState(), 3);
                    changed = true;
                }
                Dispatch.invoke(run, labor, level, inline);
                if (queued && advanced > 0 && attempts == metric("commitAttempts")) {
                    idleProofSlices++;
                }
                helper.runAfterDelay(1, () -> tick(remaining - 1));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private long metric(String name) {
            try {
                Field field = FillPlan.class.getDeclaredField(name);
                field.setAccessible(true);
                return field.getLong(plan);
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
        }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                Dispatch.invoke(closeLabor, labor);
            } finally {
                plan.close();
                bodies.forEach(ResidentEntity::discard);
                colony.works().raze();
                ColonyWays.forget(colony.colonyId());
            }
        }
    }

    private static final class Dispatch {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos cell;
        private final ColonyData colony;
        private final ColonyLabor labor;
        private final ResidentEntity body;
        private final Asking asking;
        private final FillPlan plan;
        private final Chores chores;
        private final Method run;
        private final Method closeLabor;
        private final Object inline;
        private UUID held;
        private int retainedTicks;
        private boolean changed;
        private boolean closed;
        private ConstructionFixtureUrges fixtureUrges;
        private final boolean edgeSupport;
        private Vec3 edgeStart;
        private boolean edgeAttempt;

        Dispatch(GameTestHelper helper, BlockPos cell) {
            this(helper, cell, false);
        }

        Dispatch(GameTestHelper helper, BlockPos cell, boolean edgeSupport) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.cell = cell;
            this.edgeSupport = edgeSupport;
            try {
                run = java.util.Arrays.stream(ColonyLabor.class.getMethods())
                    .filter(method -> method.getName().equals("run") && method.getParameterCount() == 2).findFirst().orElseThrow();
                Field field = run.getParameterTypes()[1].getDeclaredField("INLINE");
                field.setAccessible(true);
                inline = field.get(null);
                closeLabor = ColonyLabor.class.getDeclaredMethod("close");
                closeLabor.setAccessible(true);
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
            colony = ColonyRegistry.get(level.getServer()).mint(level.getServer());
            labor = new ColonyLabor(colony, level.dimension());
            body = PersonBody.RESIDENT.get().create(level);
            asking = new Asking(colony.works(), GroundworkContent.ID, () -> { });
            plan = new FillPlan(level, UUID.randomUUID(), colony.colonyId(), Set.of(cell),
                () -> List.of(at(helper, 1, 1, 1)), () -> { });
            chores = new Chores(UUID.randomUUID(), "fill", (at, chore) -> new FillNode(chore, MATERIAL, plan),
                () -> { }, new PastDay());
            try {
                fixtureUrges = ConstructionFixtureUrges.isolate(helper, colony);
                body.setPos(Vec3.atBottomCenterOf(at(helper, edgeSupport ? 9 : 1, 1, edgeSupport ? 3 : 1)));
                body.pack().setItem(0, new ItemStack(Items.DIRT, 8));
                body.pack().setItem(1, new ItemStack(Items.DIAMOND_SHOVEL));
                body.pack().setItem(2, new ItemStack(Items.DIAMOND_AXE));
                body.pack().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE));
                helper.assertTrue(level.addFreshEntity(body), "the builder exists in the real level");
                colony.works().hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
                body.joinColony(colony.colonyId());
                colony.works().entered(body);
                List<Job> heads = refresh(plan, helper, Set.of(cell));
                if (edgeSupport) {
                    BlockPos stance = at(helper, 9, 1, 3);
                    helper.assertTrue(heads.size() == 1 && heads.getFirst().footings().cells().size() == 1
                            && heads.getFirst().footings().cells().iterator().next().block(level).equals(stance),
                        "the complete certificate must reproduce the stalled adjacent native stance");
                    edgeStart = Vec3.atLowerCornerOf(at(helper, 0, 0, 0)).add(9.87868866268982D, 2.0D, 4.156253236743995D);
                    // Fixture placement only. Every subsequent move must be the actual native runner.
                    body.setPos(edgeStart);
                    body.setOnGround(true);
                    helper.assertTrue(body.blockPosition().below().equals(at(helper, 9, 1, 4))
                            && level.getBlockState(body.blockPosition().below()).isAir(),
                        "the actual floor-cell centre deliberately has no supporting block");
                    helper.assertTrue(level.noCollision(body, body.getBoundingBox())
                            && level.getBlockCollisions(body, body.getBoundingBox().move(0.0D, -0.01D, 0.0D))
                                .iterator().hasNext(),
                        "the real body is collision-free and supported by the neighbouring full-block edge");
                    helper.assertTrue(Reach.standingAt(body.position(), stance),
                        "Core's normal arrival tolerance must accept this exact unsupported-centre edge pose");
                }
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        void tick(int remaining) {
            try {
                helper.assertTrue(body.isAlive() && !body.isInWall(), "the real worker stays alive and clear of solid blocks");
                if (edgeSupport) helper.assertTrue(body.getHealth() == body.getMaxHealth()
                        && level.noCollision(body, body.getBoundingBox()),
                    "the continuous native correction never hurts or embeds the edge-supported worker");
                if (level.getBlockState(cell).is(Blocks.DIRT) && plan.complete()) {
                    fixtureUrges.run(body, () -> invoke(run, labor, level, inline));
                    helper.assertTrue(chores.spent().contains(Job.keyOf(cell)),
                        "the actual core also settles this original request as Done after its physical placement");
                    if (edgeSupport) {
                        helper.assertTrue(edgeAttempt,
                            "a real native commit must have encountered the initial supported edge pose");
                        helper.assertTrue(body.position().distanceToSqr(edgeStart) > 0.25D
                                && Reach.standingAt(body.position(), at(helper, 1, 1, 1)),
                            "the original worker actually leaves the edge and executes its final retreat to the trusted anchor");
                    } else {
                        helper.assertTrue(changed && retainedTicks > 0, "the actual runner held the same request across a terrain revision");
                    }
                    helper.assertTrue(body.pack().count(Items.DIRT) == 7, "the real pipeline spends exactly one dirt");
                    close();
                    helper.succeed();
                    return;
                }
                helper.assertTrue(remaining > 0, "the retained native request must eventually execute; body=" + body.position()
                    + "; stage=" + plan.stage() + "; changed=" + changed
                    + "; completion=" + plan.completionStatus() + "; reason=" + plan.completionReason());
                plan.refresh(new Survey(level, Set.of(cell)).fill(), Set.of(Job.keyOf(cell)), DIRT);
                plan.advance();
                chores.settle(plan.requests());
                List<Grown> goals = new ArrayList<>();
                chores.goals(level, goals);
                goals.addAll(plan.steppingAside());
                asking.offer(Map.of(level.dimension(), goals));
                ColonyWays.advance(colony, level);
                fixtureUrges.run(body, () -> invoke(run, labor, level, inline));
                Node current = current();
                if (edgeSupport) {
                    CompletionRuntime completion = (CompletionRuntime) value(plan, "completion");
                    Object raw = completion.diagnostics().get("lastAttempt");
                    if (raw instanceof Map<?, ?> attempt
                            && at(helper, 9, 2, 4).toShortString().equals(attempt.get("actualFeet"))
                            && Boolean.TRUE.equals(attempt.get("standingAtStance"))) {
                        if (!edgeAttempt) System.out.println("CONSTRUCTION_EDGE_INITIAL actor=" + body.getUUID()
                            + " position=" + body.position() + " attempt=" + attempt);
                        edgeAttempt = true;
                    }
                }
                if (!edgeSupport && current instanceof FillNode && !changed
                        && body.position().distanceToSqr(Vec3.atCenterOf(cell)) > 36.0D) {
                    held = current.id();
                    level.setBlock(at(helper, 3, 1, 12), Blocks.STONE.defaultBlockState(), 3);
                    plan.advance(new Ground.Budget(1, 0));
                    helper.assertTrue(current.refusal(level).isEmpty(), "the active BodyRunner request is not failed by an unrelated change");
                    changed = true;
                } else if (changed && current != null && current.id().equals(held)) {
                    retainedTicks++;
                }
                helper.runAfterDelay(1, () -> tick(remaining - 1));
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private Node current() {
            try {
                Field field = ColonyLabor.class.getDeclaredField("runners");
                field.setAccessible(true);
                Object runners = field.get(labor);
                Method of = runners.getClass().getDeclaredMethod("of", UUID.class);
                of.setAccessible(true);
                Object runner = of.invoke(runners, body.getUUID());
                field = runner.getClass().getDeclaredField("current");
                field.setAccessible(true);
                return (Node) field.get(runner);
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
        }

        private void close() {
            if (closed) return;
            closed = true;
            try {
                invoke(closeLabor, labor);
            } finally {
                try {
                    if (fixtureUrges != null) fixtureUrges.close();
                } finally {
                    plan.close();
                    body.discard();
                    colony.works().raze();
                    ColonyWays.forget(colony.colonyId());
                }
            }
        }

        private static void invoke(Method method, Object target, Object... arguments) {
            try {
                method.invoke(target, arguments);
            } catch (InvocationTargetException broken) {
                if (broken.getCause() instanceof RuntimeException failure) throw failure;
                if (broken.getCause() instanceof Error failure) throw failure;
                throw new IllegalStateException(broken.getCause());
            } catch (ReflectiveOperationException broken) {
                throw new IllegalStateException(broken);
            }
        }
    }
}
