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
    public static void averageBatchFillsSevenAndConsumesExactlyItsCalculatedInputs(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            helper.assertValueEqual(CompostStation.averageInputs(Items.WHEAT_SEEDS.getDefaultInstance(), 0), 21,
                "the guaranteed first layer plus six thirty-percent layers costs twenty-one seeds");
            helper.assertValueEqual(CompostStation.averageInputs(Items.WHEAT_SEEDS.getDefaultInstance(), 3), 14,
                "a partially filled bucket needs the rounded-up average of only its missing layers");
            helper.assertValueEqual(CompostStation.averageInputs(Items.CAKE.getDefaultInstance(), 0), 7,
                "native probabilities above one still accept only one layer per item");
            long before = fixture.seeds();
            fixture.step(fixture.node());
            helper.assertValueEqual(fixture.fill(), 7, "one operation fills the composter to its maturing state");
            helper.assertValueEqual(fixture.seeds(), before - 21, "the calculated batch is consumed once");
            helper.assertValueEqual(Goods.countIn(fixture.pack, Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 0L,
                "filling does not bypass native maturity or invent bone meal");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void insufficientWholeBatchDoesNotCreateAFeedTask(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.pack.clearContent();
            fixture.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 20));
            helper.assertTrue(fixture.goals().isEmpty(), "twenty surplus seeds cannot fund a twenty-one-seed batch");
            helper.assertValueEqual(fixture.seeds(), 84L, "waiting for enough surplus leaves every seed intact");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerPartialFillReturnsUnusedEscrowAndAPlayerEmptyingCannotUnderpay(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Node feed = fixture.node();
            long before = fixture.seeds();
            fixture.level.setBlock(fixture.pos, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 3), Block.UPDATE_ALL);
            fixture.step(feed);
            helper.assertValueEqual(fixture.fill(), 7, "a player's added layers are respected at commit");
            helper.assertValueEqual(fixture.seeds(), before - 14, "seven unneeded seeds return from the planned twenty-one-seed escrow");
            fixture.level.setBlock(fixture.pos, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 6), Block.UPDATE_ALL);
            Node cheap = fixture.node();
            fixture.level.setBlock(fixture.pos, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
            long afterFirst = fixture.seeds();
            fixture.step(cheap);
            helper.assertValueEqual(fixture.fill(), 0, "four planned seeds cannot fill a now-empty bucket");
            helper.assertValueEqual(fixture.seeds(), afterFirst, "a changed bucket returns its entire insufficient escrow");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void disablingAutomaticCompostReturnsPlannedInputs(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            Node feed = fixture.node();
            long before = fixture.seeds();
            fixture.enabled = false;
            fixture.step(feed);
            helper.assertValueEqual(fixture.fill(), 0, "disabling compost after planning blocks the pending feed");
            helper.assertValueEqual(fixture.seeds(), before, "disabling returns every planned input");
            helper.assertTrue(fixture.goals().isEmpty(), "disabled automation offers no new feed");
            fixture.enabled = true;
            fixture.step(fixture.node());
            helper.assertValueEqual(fixture.fill(), 7, "reenabling permits a newly checked batch");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void changingRetentionBeforeArrivalRechecksTheWholeBatch(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.pack.clearContent();
            fixture.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 21));
            Node feed = fixture.node();
            fixture.retained = 65;
            fixture.step(feed);
            helper.assertValueEqual(fixture.fill(), 0, "an increased live seed reserve blocks the previously affordable batch");
            helper.assertValueEqual(fixture.seeds(), 85L, "the entire stale batch returns to physical inventory");
            helper.assertTrue(fixture.goals().isEmpty(), "the new reserve cannot fund another whole batch");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void sevenWaitsForNativeMaturityThenCollectsOneActualBoneMeal(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.fillNative();
        fixture.enabled = false;
        helper.assertValueEqual(fixture.fill(), 7, "the average batch advances the composter to seven");
        helper.assertTrue(fixture.goals().isEmpty(), "maturing compost supplies no feed or premature collect task");
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
            fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 21));
            Node feed = fixture.node();
            fixture.claims = List.of(new SeedBudget.Claim(UUID.randomUUID(), SeedBudget.Location.pack(fixture.body.id()),
                Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance()), 1));
            fixture.step(feed);
            helper.assertValueEqual(fixture.fill(), 0, "fresh sowing priority blocks the old planned compost input");
            helper.assertValueEqual(fixture.seeds(), 85L, "the real escrow is returned once, not consumed or duplicated");
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
            helper.assertValueEqual(fixture.fill(), 7, "a genuinely new station task can feed normally");
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
            helper.assertValueEqual(fixture.fill(), 7, "the real composter batch remains committed");
            helper.assertValueEqual(fixture.seeds(), before - 21, "its entire batch is spent despite the intervening event exception");
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
            helper.assertValueEqual(fixture.fill(), 7, "the retained original feed performs exactly one calculated batch");
            helper.assertValueEqual(fixture.seeds(), before - 21, "only one actual batch is consumed after travel");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void aNativeMatureCompostCollectionStaysWantedUntilTheFarResidentArrives(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper); fixture.fillNative();
        helper.assertValueEqual(fixture.fill(), 7, "the average batch reaches seven before native maturity");
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
    public static void twoFeedOffersFromOnePreviewCannotConsumeTheSingleSurplusTwice(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            var secondPos = fixture.pos.east(2);
            var secondAt = WorldPos.of(fixture.level, secondPos);
            fixture.level.setBlock(secondPos, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
            fixture.bookings.admit(secondAt);
            try {
                fixture.pack.clearContent();
                fixture.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
                fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 21));
                var planning = fixture.station.planning(fixture.colony);
                Node first = fixture.station.recovery(fixture.colony, fixture.level, fixture.at, Stances.WHEREVER,
                    fixture.bookings, () -> { }, planning).getFirst().nodes().getFirst();
                Node second = fixture.station.recovery(fixture.colony, fixture.level, secondAt, Stances.WHEREVER,
                    fixture.bookings, () -> { }, planning).getFirst().nodes().getFirst();
                fixture.step(first);
                helper.assertValueEqual(fixture.seeds(), 64L, "only the first batch may spend the twenty-one surplus seeds");
                fixture.step(second);
                helper.assertValueEqual(fixture.fill(), 7, "the first real station received its batch");
                helper.assertValueEqual(fixture.level.getBlockState(secondPos).getValue(ComposterBlock.LEVEL), 0,
                    "the second station cannot reuse the shared preview after the first consumed its surplus");
                helper.assertValueEqual(fixture.seeds(), 64L, "core returns the second full escrow, preserving the seed reserve exactly");
            } finally {
                fixture.level.setBlock(secondPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedPreviewStillRechecksClaimsConfigurationAndPhysicalRemovalAtCommit(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            var secondPos = fixture.pos.east(2);
            var secondAt = WorldPos.of(fixture.level, secondPos);
            fixture.level.setBlock(secondPos, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
            fixture.bookings.admit(secondAt);
            try {
                fixture.pack.clearContent();
                fixture.pack.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
                fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 21));
                var planning = fixture.station.planning(fixture.colony);
                Node first = fixture.station.recovery(fixture.colony, fixture.level, fixture.at, Stances.WHEREVER,
                    fixture.bookings, () -> { }, planning).getFirst().nodes().getFirst();
                Node second = fixture.station.recovery(fixture.colony, fixture.level, secondAt, Stances.WHEREVER,
                    fixture.bookings, () -> { }, planning).getFirst().nodes().getFirst();
                fixture.claims = List.of(new SeedBudget.Claim(UUID.randomUUID(), SeedBudget.Location.pack(fixture.body.id()),
                    Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance()), 1));
                fixture.step(first);
                helper.assertValueEqual(fixture.seeds(), 85L, "a newly bound sowing seed returns the first stale escrow intact");
                fixture.claims = List.of();fixture.retained = 65;
                fixture.step(second);
                helper.assertValueEqual(fixture.seeds(), 85L, "a changed live configuration returns the second stale escrow intact");
                fixture.retained = 64;
                fixture.pack.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 20));
                Node removed = fixture.station.recovery(fixture.colony, fixture.level, fixture.at, Stances.WHEREVER,
                    fixture.bookings, () -> { }, planning).getFirst().nodes().getFirst();
                fixture.step(removed);
                helper.assertValueEqual(fixture.seeds(), 84L, "physical removal after preview cannot fund a nonexistent final seed");
                helper.assertValueEqual(fixture.fill(), 0, "none of the stale feed proofs mutates the original composter");
                helper.assertValueEqual(fixture.level.getBlockState(secondPos).getValue(ComposterBlock.LEVEL), 0,
                    "the second composter also remains empty");
            } finally {
                fixture.level.setBlock(secondPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
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
        boolean enabled = true;
        int retained = 64;

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
            budget = new SeedBudget(() -> new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("real resident pack",
                SeedBudget.Location.pack(body.id()), IntStream.range(0, pack.getContainerSize()).mapToObj(pack::getItem).toList())),
                claims, List.of(), Map.of()), () -> Set.of(Items.WHEAT_SEEDS), ignored -> retained);
            station = new CompostStation(ignored -> Optional.of(budget), () -> Set.of(Items.WHEAT_SEEDS), ignored -> enabled);
            bookings.admit(at);
        }

        List<io.github.izakyl.folkways.core.api.work.Grown> goals() {
            return station.recovery(colony, level, at, Stances.WHEREVER, bookings, () -> { });
        }
        Node node() { return goals().getFirst().nodes().getFirst(); }
        int fill() { return level.getBlockState(pos).getValue(ComposterBlock.LEVEL); }
        long seeds() { return Goods.countIn(pack, Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance())); }
        void fillNative() {
            step(node());
            helper.assertValueEqual(fill(), 7, "real surplus seeds fill the native composter");
        }
        void step(Node node) {
            helper.assertTrue(node.planned(level).isEmpty(), "the station task reserves its actual generation");
            Outcome outcome;
            try { outcome = Commit.run(level, node, this); }
            catch (RuntimeException | LinkageError broken) { node.ended(level, Ending.REVOKED); throw broken; }
            helper.assertTrue(outcome instanceof Outcome.Done, "the real native composter action completes");
            ((Outcome.Done) outcome).made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual output fits"));
            node.ended(level, Ending.DONE);
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
