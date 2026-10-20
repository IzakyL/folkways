package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompostGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CompostGameTests.class); }

    @GameTest(template = "empty")
    public static void nativeChanceFailureStillConsumesExactlyOneSurplusSeed(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.step(fixture.node());
            helper.assertValueEqual(fixture.fill(), 1, "the native composter always accepts its first valid input");
            long before = fixture.seeds();
            // Choose a reproducible roll through the real ServerLevel random source, without replacing
            // either the native composter or its probability/data-map implementation.
            fixture.level.random.setSeed(0L);
            fixture.step(fixture.node());
            helper.assertValueEqual(fixture.fill(), 1, "the actual 30-percent wheat-seed roll fails");
            helper.assertValueEqual(fixture.seeds(), before - 1, "native chance failure consumes rather than refunds one seed");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 0L,
                "neither a successful fill layer nor a failed roll invents bone meal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void sevenWaitsForNativeMaturityThenCollectsOneActualBoneMeal(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.fillNative();
        helper.assertValueEqual(fixture.fill(), 7, "only native insertItem advances the composter to seven");
        Node waiting = fixture.node();
        helper.assertTrue(waiting.planned(fixture.level).isEmpty(), "maturation keeps one native session");
        helper.assertTrue(((io.github.izakyl.folkways.core.api.work.batch.BatchSession) waiting).advance(fixture.level, fixture)
            instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Wait, "maturation waits on game ticks, with no premature action");
        waiting.ended(fixture.level, Ending.REVOKED);
        helper.runAfterDelay(25, () -> {
            try (fixture) {
                helper.assertValueEqual(fixture.fill(), 8, "the original twenty-tick scheduled block tick matures compost");
                fixture.step(fixture.node());
                helper.assertValueEqual(fixture.fill(), 0, "the original native extraction empties the composter");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "one actual captured ItemEntity enters logistics");
                helper.assertTrue(fixture.level.getEntitiesOfClass(ItemEntity.class, new AABB(fixture.pos).inflate(2)).isEmpty(),
                    "no duplicate bone meal remains on the ground");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void aNewSowingClaimAfterPlanningReturnsTheEscrowWithoutFeeding(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.pack.clearContent();
            fixture.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS));
            Node feed = fixture.node();
            fixture.claims = List.of(new SeedBudget.Claim(UUID.randomUUID(), SeedBudget.Location.pack(fixture.body.id()),
                Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance()), 1));
            fixture.step(feed);
            helper.assertValueEqual(fixture.fill(), 0, "fresh sowing priority blocks the old planned compost input");
            helper.assertValueEqual(fixture.seeds(), 65L, "the real escrow is returned once, not consumed or duplicated");
            helper.assertTrue(fixture.goals().isEmpty(), "the reserved final seed does not produce another destructive goal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawalAndRemarkNeverReviveAnOldCompostTask(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Node old = fixture.node();
            helper.assertTrue(old.planned(fixture.level).isEmpty(), "the real station can reserve one task");
            fixture.bookings.withdraw(fixture.at);
            fixture.bookings.admit(fixture.at);
            long before = fixture.seeds();
            helper.assertTrue(Commit.run(fixture.level, old, fixture) instanceof Outcome.Failed,
                "a changed station generation rejects old work after remarking");
            helper.assertValueEqual(fixture.seeds(), before, "core returns the rejected physical input exactly once");
            helper.assertValueEqual(fixture.fill(), 0, "old work never feeds the station");
            old.ended(fixture.level, Ending.REVOKED);
            fixture.step(fixture.node());
            helper.assertValueEqual(fixture.fill(), 1, "a genuinely new station task can feed normally");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void notificationFailureAfterNativeLayerMutationDoesNotRefundAFreeLayer(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Consumer<VanillaGameEvent> listener = event -> {
                if (event.getVanillaEvent().equals(GameEvent.BLOCK_CHANGE)) throw new IllegalStateException("test: native composter layer already changed");
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, VanillaGameEvent.class, listener);
            long before = fixture.seeds();
            try { fixture.step(fixture.node()); } finally { NeoForge.EVENT_BUS.unregister(listener); }
            helper.assertValueEqual(fixture.fill(), 1, "the real composter layer remains committed");
            helper.assertValueEqual(fixture.seeds(), before - 1, "its seed is spent despite the intervening event exception");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void notificationFailureAfterNativeEmptyStillDeliversBoneMealExactlyOnce(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.fillNative();
        helper.runAfterDelay(25, () -> {
            Consumer<VanillaGameEvent> listener = event -> {
                if (event.getVanillaEvent().equals(GameEvent.BLOCK_CHANGE)) throw new IllegalStateException("test: native composter already emptied");
            };
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, VanillaGameEvent.class, listener);
            try (fixture) {
                try { fixture.step(fixture.node()); } finally { NeoForge.EVENT_BUS.unregister(listener); }
                helper.assertValueEqual(fixture.fill(), 0, "the native empty mutation remains committed");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "the captured native bone meal is still delivered once");
                helper.assertTrue(fixture.level.getEntitiesOfClass(ItemEntity.class, new AABB(fixture.pos).inflate(2)).isEmpty(),
                    "notification failure leaves no duplicate floor output");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void anAdoptedCompostFeedStaysWantedUntilTheFarResidentActuallyArrives(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            long before = fixture.seeds();
            commitAfterRepeatedOffers(helper, fixture);
            helper.assertValueEqual(fixture.fill(), 1, "the retained original feed performs exactly one native compost insertion");
            helper.assertValueEqual(fixture.seeds(), before - 1, "only one actual seed is consumed after travel");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void aNativeMatureCompostCollectionStaysWantedUntilTheFarResidentArrives(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper); fixture.fillNative();
        helper.assertValueEqual(fixture.fill(), 7, "native insertion reaches seven without a synthetic mature state");
        helper.runAfterDelay(25, () -> {
            try (fixture) {
                helper.assertValueEqual(fixture.fill(), 8, "the actual native scheduled tick matures the same compost");
                commitAfterRepeatedOffers(helper, fixture);
                helper.assertValueEqual(fixture.fill(), 0, "the retained collection empties the actual native composter");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "the original collection delivers exactly one real bone meal");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void nativeMatureOutputDoesNotNeedAnyRemainingSeedOrEnabledSeedBudget(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.fillNative();
        fixture.pack.clearContent();
        CompostStation collectionOnly = new CompostStation(ignored -> Optional.empty(), Set::of);
        helper.runAfterDelay(25, () -> {
            try (fixture) {
                helper.assertValueEqual(fixture.fill(), 8, "the accepted native input matures even after every source seed is gone");
                List<io.github.izakyl.folkways.core.api.work.Grown> wanted = collectionOnly.recovery(fixture.colony,
                    fixture.level, fixture.at, Stances.WHEREVER, fixture.bookings, () -> { });
                helper.assertValueEqual(wanted.size(), 1, "mature recovery is available without a seed service or catalogue");
                Node collect = wanted.getFirst().nodes().getFirst();
                helper.assertTrue(collect.spec().needs().isEmpty(), "collecting accepted compost cannot ask logistics for another seed");
                fixture.step(collect);
                helper.assertValueEqual(fixture.fill(), 0, "the native mature batch is extracted");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "only the actual one-bone-meal native drop enters the pack");
                helper.assertTrue(collectionOnly.recovery(fixture.colony, fixture.level, fixture.at,
                    Stances.WHEREVER, fixture.bookings, () -> { }).isEmpty(), "no empty or exhausted source produces a second output");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void cancelledMatureCollectionAndShutdownCanOnlyRecoverTheAcceptedBatchOnce(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.fillNative();
        helper.runAfterDelay(25, () -> {
            try (fixture) {
                Node abandoned = fixture.node();
                helper.assertTrue(abandoned.planned(fixture.level).isEmpty(), "the first real mature collection owns its turn");
                fixture.bookings.withdraw(fixture.at);
                fixture.station.release(fixture.colony, fixture.level, fixture.at);
                helper.assertValueEqual(fixture.fill(), 8, "unmarking releases only the turn, not the native batch");
                helper.assertTrue(Commit.run(fixture.level, abandoned, fixture) instanceof Outcome.Failed,
                    "a cancelled extraction cannot take the still-present native output");
                abandoned.ended(fixture.level, Ending.REVOKED);
                fixture.station.stop(fixture.colony, io.github.izakyl.folkways.core.api.colony.Closing.SHUTDOWN);
                fixture.bookings.clear();
                fixture.pack.clearContent();
                CompostBookings restoredBookings = new CompostBookings();
                restoredBookings.admit(fixture.at);
                CompostStation restored = new CompostStation(ignored -> Optional.empty(), Set::of);
                Node resumed = restored.recovery(fixture.colony, fixture.level, fixture.at, Stances.WHEREVER,
                    restoredBookings, () -> { }).getFirst().nodes().getFirst();
                fixture.step(resumed);
                helper.assertValueEqual(fixture.fill(), 0, "a reconstructed provider recovers the physical batch left at shutdown");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "cancel, release and provider reconstruction create no second copy");
                helper.assertTrue(Commit.run(fixture.level, abandoned, fixture) instanceof Outcome.Failed,
                    "the old generation remains invalid even after a replacement recovered the batch");
                helper.assertTrue(restored.recovery(fixture.colony, fixture.level, fixture.at, Stances.WHEREVER,
                    restoredBookings, () -> { }).isEmpty(), "the reconstructed empty station has no phantom recovery");
                helper.assertTrue(fixture.level.getEntitiesOfClass(ItemEntity.class, new AABB(fixture.pos).inflate(2)).isEmpty(),
                    "there is no duplicate physical drop beside the recovered batch");
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void nativeCompostDelegationAndBudgetExistWithoutOptionalCookery(GameTestHelper helper) {
        var colony = io.github.izakyl.folkways.core.api.colony.Colonies.mint(helper.getLevel().getServer());
        try {
            helper.assertTrue(colony.service(CompostContent.ID, CompostPresence.class).isPresent(), "Vanilla alone installs native compost work");
            var registered = io.github.izakyl.folkways.front.engine.Enrollments.of(CompostContent.ID);
            helper.assertTrue(registered.delegations().stream().anyMatch(choice -> choice.id().equals(CompostContent.ID)),
                "the persisted folkways:compost registration remains available");
        } finally { colony.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void oneParentSpendsItsSixteenSeedAllowanceThroughRealCoreSettlement(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Node parent = fixture.node();
            helper.assertTrue(parent.spec().needs().size() == 1 && parent.spec().needs().getFirst().count() == 16,
                "one parent reserves at most sixteen carried physical seeds");
            helper.assertTrue(parent.planned(fixture.level).isEmpty(), "the batch owns one station turn");
            long before = fixture.seeds();
            int observationsBefore = fixture.observations.get();
            for (int index = 0; index < 16; index++) {
                fixture.level.random.setSeed(0L);
                fixture.one(parent);
                helper.assertTrue(fixture.station.activeSessions().equals(List.of(parent.id())), "all actions retain the same adopted parent");
            }
            helper.assertValueEqual(fixture.seeds(), before - 16, "sixteen exact debits equal sixteen native accepted inputs");
            helper.assertValueEqual(fixture.station.stats().acceptedSeeds(), 16L, "metrics count accepted material rather than level gains");
            helper.assertValueEqual(fixture.observations.get() - observationsBefore, 16,
                "each accepted native input takes one fresh physical budget observation; discovery is not reused at execution");
            helper.assertValueEqual(fixture.fill(), 1, "one successful first layer and fifteen native failed rolls give no promised output");
            helper.assertTrue(((io.github.izakyl.folkways.core.api.work.batch.BatchSession) parent).advance(fixture.level, fixture)
                instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Complete, "the parent cannot draw a seventeenth input");
            parent.ended(fixture.level, Ending.DONE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aSowingClaimBetweenTwoAcceptedStepsProtectsTheRestOfTheBatch(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.pack.clearContent();
            fixture.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 4));
            Node parent = fixture.node();
            helper.assertTrue(parent.spec().needs().getFirst().count() == 4, "only four actual surplus inputs are booked");
            helper.assertTrue(parent.planned(fixture.level).isEmpty(), "the parent takes one exclusive turn");
            fixture.one(parent);
            fixture.claims = List.of(new SeedBudget.Claim(UUID.randomUUID(), SeedBudget.Location.pack(fixture.body.id()),
                Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance()), 3));
            fixture.one(parent);
            helper.assertValueEqual(fixture.seeds(), 67L, "the fresh claim preserves all three remaining inputs plus the stack reserve");
            helper.assertValueEqual(fixture.fill(), 1, "the denied second step leaves native compost unchanged");
            helper.assertValueEqual(fixture.station.stats().acceptedSeeds(), 1L, "only the first physical input debits the batch");
            helper.assertTrue(((io.github.izakyl.folkways.core.api.work.batch.BatchSession) parent).advance(fixture.level, fixture)
                instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Complete, "denied surplus finishes without spending the remaining allowance");
            parent.ended(fixture.level, Ending.DONE);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removingTheStationAfterOneInputDoesNotRefundAcceptedMaterial(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Node parent = fixture.node();
            helper.assertTrue(parent.planned(fixture.level).isEmpty(), "one batch owns the original generation");
            long before = fixture.seeds();
            fixture.one(parent);
            fixture.level.setBlock(fixture.pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(((io.github.izakyl.folkways.core.api.work.batch.BatchSession) parent).advance(fixture.level, fixture)
                instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Failed, "external removal stops the session");
            helper.assertValueEqual(fixture.seeds(), before - 1, "only accepted material is gone; all unspent physical seeds remain");
            parent.ended(fixture.level, Ending.REVOKED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void externalCollectionBetweenSelectionAndCommitCannotDuplicateRealOutput(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.fillNative();
        helper.runAfterDelay(25, () -> {
            try (fixture) {
                Node parent = fixture.node();
                helper.assertTrue(parent.planned(fixture.level).isEmpty(), "collection reserves the mature physical barrel");
                var session = new io.github.izakyl.folkways.core.engine.batch.BatchExecution(parent);
                var selected = (io.github.izakyl.folkways.core.api.work.batch.StepResult.Step) session.next(fixture.level, fixture);
                ComposterBlock.extractProduce(fixture.body(), fixture.level.getBlockState(fixture.pos), fixture.level, fixture.pos);
                var done = (Outcome.Done) Commit.run(fixture.level, selected.node(), fixture, session);
                session.notifySettled(fixture.level);
                helper.assertTrue(done.made().isEmpty(), "a stale collection never fabricates a second native output");
                helper.assertValueEqual(fixture.level.getEntitiesOfClass(ItemEntity.class, new AABB(fixture.pos).inflate(2)).size(), 1,
                    "only the external collector's actual drop exists");
                helper.assertValueEqual(fixture.fill(), 0, "native external extraction remains authoritative");
                parent.ended(fixture.level, Ending.DONE);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty")
    public static void discoveryReusesOneSnapshotAndUnknownInventoryNeverEnablesDestruction(GameTestHelper helper) {
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var stock = new SeedBudget.Stock("physical pack", SeedBudget.Location.pack(UUID.randomUUID()),
            List.of(new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.WHEAT_SEEDS, 16)));
        var budget = new SeedBudget(() -> {
            reads.incrementAndGet();
            return new SeedBudget.Snapshot(List.of(stock), List.of(), List.of(), Map.of());
        }, () -> Set.of(Items.WHEAT_SEEDS));
        var observation = budget.frozen();
        observation.tally(Items.WHEAT_SEEDS); observation.tally(Items.WHEAT_SEEDS); observation.reserves();
        helper.assertValueEqual(reads.get(), 1, "one discovery observation never rescans a colony per candidate");
        var unknown = new SeedBudget(() -> new SeedBudget.Snapshot(List.of(stock), List.of(), List.of(), Map.of(), List.of(), false),
            () -> Set.of(Items.WHEAT_SEEDS));
        helper.assertValueEqual(unknown.tally(Items.WHEAT_SEEDS).compostable(), 0L, "an inaccessible stock or unloaded resident blocks destructive allowance");
        helper.assertTrue(!unknown.permits(UUID.randomUUID(), new ItemStack(Items.WHEAT_SEEDS)), "escrow does not make an incomplete stock observation safe");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void withdrawingAfterPartialConsumptionPreservesEveryUnusedRealSeed(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Node parent = fixture.node();
            helper.assertTrue(parent.planned(fixture.level).isEmpty(), "the parent owns one current station generation");
            long before = fixture.seeds();
            fixture.one(parent);
            fixture.bookings.withdraw(fixture.at);
            fixture.station.release(fixture.colony, fixture.level, fixture.at);
            helper.assertTrue(((io.github.izakyl.folkways.core.api.work.batch.BatchSession) parent).advance(fixture.level, fixture)
                instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Failed, "cancel revokes the parent before another input");
            helper.assertValueEqual(fixture.seeds(), before - 1, "accepted material stays spent and every unused unit remains physical cargo");
            helper.assertValueEqual(fixture.fill(), 1, "cancellation does not empty or duplicate native accepted compost");
            parent.ended(fixture.level, Ending.REVOKED);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void externalNativeInputsMakeAnExistingSessionWaitThenRecoverExactlyOnce(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Node parent = fixture.node();
        helper.assertTrue(parent.planned(fixture.level).isEmpty(), "one parent owns the original barrel");
        long before = fixture.seeds();
        fixture.one(parent);
        ItemStack external = new ItemStack(Items.WHEAT_SEEDS, 64);
        for (int attempt = 0; attempt < 400 && fixture.fill() < 7; attempt++) {
            if (external.isEmpty()) external = new ItemStack(Items.WHEAT_SEEDS, 64);
            ComposterBlock.insertItem(fixture.body(), fixture.level.getBlockState(fixture.pos), fixture.level, external, fixture.pos);
        }
        helper.assertValueEqual(fixture.fill(), 7, "real external input reaches native maturation");
        helper.assertTrue(((io.github.izakyl.folkways.core.api.work.batch.BatchSession) parent).advance(fixture.level, fixture)
            instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Wait, "the existing parent observes external fill and waits without another draw");
        helper.assertValueEqual(fixture.seeds(), before - 1, "external inputs cannot debit the resident's unspent seed allowance");
        helper.runAfterDelay(25, () -> {
            try (fixture) {
                fixture.one(parent);
                helper.assertValueEqual(fixture.fill(), 0, "the same parent collects native output once after its scheduled tick");
                helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                    "only one real output enters the pack");
                parent.ended(fixture.level, Ending.DONE);
                helper.succeed();
            }
        });
    }

    @GameTest(template = "empty_large")
    public static void twoRegisteredCompostersShareOneRealSurplusAllowance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int surplus : List.of(16, 32)) {
            var colony = io.github.izakyl.folkways.core.api.colony.Colonies.mint(level.getServer());
            var body = PersonBody.RESIDENT.get().create(level);
            try {
                body.setNoAi(true);
                body.moveTo(helper.absolutePos(new BlockPos(2, 1, 5)).getCenter());
                body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
                body.pack().setItem(1, new ItemStack(Items.WHEAT_SEEDS, surplus));
                level.addFreshEntity(body);
                colony.hold(io.github.izakyl.folkways.plugins.person.PersonContent.ID,
                    io.github.izakyl.folkways.core.api.colony.Held.Entity.of(body)).orElseThrow();
                body.joinColony(colony.id());
                colony.entered(body);
                for (int x : List.of(2, 6)) {
                    BlockPos barrel = helper.absolutePos(new BlockPos(x, 1, 2));
                    for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                        level.setBlock(barrel.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        level.setBlock(barrel.offset(dx, 0, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        level.setBlock(barrel.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                    }
                    level.setBlock(barrel, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
                    colony.hold(CompostContent.ID, new io.github.izakyl.folkways.core.api.colony.Held.Point(WorldPos.of(level, barrel))).orElseThrow();
                }
                CompostPresence presence = colony.service(CompostContent.ID, CompostPresence.class).orElseThrow();
                var offered = presence.goals(colony.view(level));
                long advertised = offered.stream().flatMap(goal -> goal.nodes().stream()).flatMap(node -> node.spec().needs().stream())
                    .mapToLong(io.github.izakyl.folkways.core.api.work.Need::count).sum();
                helper.assertValueEqual(advertised, (long) surplus,
                    "two barrels advertise only the actual unclaimed surplus above the normal-stack reserve");
                helper.assertValueEqual(offered.size(), surplus / 16,
                    "sixteen surplus seeds cannot produce two conflicting sixteen-seed parents; thirty-two can");
                helper.assertValueEqual(Goods.countIn(body.pack(), Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance())), 64L + surplus,
                    "discovery leaves every physical seed in the original pack");
            } finally { body.discard(); colony.raze(); }
        }
        helper.succeed();
    }

    private static void commitAfterRepeatedOffers(GameTestHelper helper, Fixture fixture) {
        var colony = io.github.izakyl.folkways.core.api.colony.Colonies.mint(fixture.level.getServer());
        var works = (io.github.izakyl.folkways.core.engine.colony.ColonyWorks) colony;
        var submitted = new java.util.ArrayList<Node>(); var withdrawn = new java.util.ArrayList<UUID>();
        try {
            works.listen(fixture.level.dimension(), new io.github.izakyl.folkways.core.engine.colony.ColonyWorks.Listening() {
                public void submitted(net.minecraft.resources.ResourceLocation owner, io.github.izakyl.folkways.core.api.work.Grown work) { submitted.addAll(work.nodes()); }
                public void withdrawn(net.minecraft.resources.ResourceLocation owner, UUID node) { withdrawn.add(node); }
                public void offered() { }
            });
            var asking = new io.github.izakyl.folkways.core.api.colony.Asking(colony,
                CompostContent.ID, () -> { });
            helper.assertTrue(fixture.station.activeRecovery(fixture.colony, fixture.level, fixture.at, fixture.bookings).isEmpty(),
                "a blocked-stance query cannot create fresh unplanned compost work");
            asking.offer(Map.of(fixture.level.dimension(), fixture.goals()));
            helper.assertValueEqual(submitted.size(), 1, "one compost operation is submitted through the actual colony root contract");
            Node original = submitted.getFirst();
            helper.assertTrue(original.planned(fixture.level).isEmpty(), "the original native composter action takes its exclusive turn");
            fixture.body.mob().moveTo(fixture.pos.getX() - 40.5, fixture.pos.getY(), fixture.pos.getZ() + 0.5);
            long before = fixture.seeds();
            for (int period = 0; period < 10; period++) {
                var wanted = fixture.goals();
                var retained = fixture.station.activeRecovery(fixture.colony, fixture.level, fixture.at, fixture.bookings);
                helper.assertTrue(retained.size() == 1 && retained.getFirst().nodes().getFirst() == original,
                    "a no-stance recovery query retains only the same already adopted root");
                helper.assertTrue(wanted.size() == 1 && wanted.getFirst().nodes().getFirst() == original,
                    "the same adopted compost root stays wanted while its resident is far away");
                asking.offer(Map.of(fixture.level.dimension(), wanted));
            }
            helper.assertTrue(withdrawn.isEmpty() && submitted.size() == 1, "repeated recovery sweeps do not cancel or duplicate the real compost action");
            helper.assertValueEqual(fixture.seeds(), before, "no seed is spent during travel");
            fixture.body.mob().moveTo(fixture.pos.getX() + 1.5, fixture.pos.getY(), fixture.pos.getZ() + 0.5);
            fixture.step(original); works.ended(fixture.level.dimension(), original.id(), Ending.DONE);
        } finally { colony.raze(); }
    }

    private static final class Fixture implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos pos;
        final WorldPos at;
        final Body body;
        final SimpleContainer pack = new SimpleContainer(8);
        final CompostBookings bookings = new CompostBookings();
        final SeedBudget budget;
        final CompostStation station;
        List<SeedBudget.Claim> claims = List.of();
        final java.util.concurrent.atomic.AtomicInteger observations = new java.util.concurrent.atomic.AtomicInteger();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            pos = helper.absolutePos(new BlockPos(2, 1, 2));
            at = WorldPos.of(level, pos);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                level.setBlock(pos.offset(x, -1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            }
            level.setBlock(pos, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
            body = PersonBody.RESIDENT.get().create(level);
            body.mob().setPos(pos.getX() + 1.5, pos.getY(), pos.getZ() + 0.5);
            for (int slot = 0; slot < 7; slot++) pack.setItem(slot, new ItemStack(Items.WHEAT_SEEDS, 64));
            budget = new SeedBudget(() -> {
                observations.incrementAndGet();
                return new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("real resident pack",
                SeedBudget.Location.pack(body.id()), IntStream.range(0, pack.getContainerSize()).mapToObj(pack::getItem).toList())),
                claims, List.of(), Map.of());
            }, () -> Set.of(Items.WHEAT_SEEDS));
            station = new CompostStation(ignored -> Optional.of(budget), () -> Set.of(Items.WHEAT_SEEDS));
            bookings.admit(at);
        }

        List<io.github.izakyl.folkways.core.api.work.Grown> goals() {
            return station.recovery(colony, level, at, Stances.WHEREVER, bookings, () -> { });
        }
        Node node() { return goals().getFirst().nodes().getFirst(); }
        int fill() { return level.getBlockState(pos).getValue(ComposterBlock.LEVEL); }
        long seeds() { return Goods.countIn(pack, Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance())); }
        void fillNative() {
            for (int attempt = 0; attempt < 380 && fill() < 7; attempt++) step(node());
            helper.assertValueEqual(fill(), 7, "real surplus seeds fill the native composter");
        }
        void step(Node node) {
            helper.assertTrue(node.planned(level).isEmpty(), "the station task reserves its actual generation");
            one(node);
            node.ended(level, Ending.DONE);
            sessions.remove(node.id());
        }
        final java.util.Map<UUID, io.github.izakyl.folkways.core.engine.batch.BatchExecution> sessions = new java.util.HashMap<>();
        void one(Node parent) {
            var session = sessions.computeIfAbsent(parent.id(), ignored -> new io.github.izakyl.folkways.core.engine.batch.BatchExecution(parent));
            var next = session.next(level, this);
            helper.assertTrue(next instanceof io.github.izakyl.folkways.core.api.work.batch.StepResult.Step,
                "the session offers one physical appliance action");
            var step = (io.github.izakyl.folkways.core.api.work.batch.StepResult.Step) next;
            Outcome outcome = Commit.run(level, step.node(), this, session);
            helper.assertTrue(outcome instanceof Outcome.Done, "the real native composter action completes");
            var done = (Outcome.Done) outcome;
            done.made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual output fits"));
            session.notifySettled(level);
        }
        public Resident resident() { return body.resident(); }
        public Mob body() { return body.mob(); }
        public Container pack() { return pack; }
        public List<Store> within() { return List.of(); }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) { if (!stack.isEmpty()) throw new AssertionError("test pack has room"); }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { body.mob().discard(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); }
    }
}
