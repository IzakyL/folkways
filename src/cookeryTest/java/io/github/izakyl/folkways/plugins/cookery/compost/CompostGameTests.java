package io.github.izakyl.folkways.plugins.cookery.compost;

import io.github.izakyl.folkways.FolkwaysMod;
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
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.seeds.SeedBudget;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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

    private static final class Fixture implements Worker, AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final UUID colony = UUID.randomUUID();
        final BlockPos pos;
        final WorldPos at;
        final Body body;
        final SimpleContainer pack = new SimpleContainer(8);
        final KitchenBookings bookings = new KitchenBookings();
        final SeedBudget budget;
        final CompostStation station;
        List<SeedBudget.Claim> claims = List.of();

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
                claims, List.of(), Map.of()), () -> Set.of(Items.WHEAT_SEEDS));
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
        public int rankOf(String perk) { return 0; }
        public List<Node> route() { return List.of(); }
        public Placement placement() { return Placement.NOWHERE; }
        public void close() { body.mob().discard(); level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL); }
    }
}
