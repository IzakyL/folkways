package io.github.izakyl.folkways.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.Facings;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenStations;
import io.github.izakyl.folkways.plugins.orders.Orders;
import io.github.izakyl.folkways.plugins.orders.OrdersContent;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeedSupportGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SeedSupportGameTests.class); }

    @GameTest(template = "empty")
    public static void mintingAColonyRegistersOneSeedServiceAndTaskPermissionEnablesSeedBudgets(GameTestHelper helper) {
        try (var rig = new Fixture(helper)) {
            var delegation = Enrollments.of(CookeryContent.ID).delegations().stream()
                .filter(choice -> choice.id().equals(SeedPresence.GRASS)).findFirst().orElseThrow();
            helper.assertTrue(delegation.shape() instanceof Shape.Volume, "seed grassland is a real book volume delegation");
            helper.assertTrue(delegation.schema().settings().stream().anyMatch(setting -> setting instanceof Schema.Setting.Flag flag
                && flag.key().equals(SeedPresence.RESTORE_GRASS) && flag.byDefault()), "native grass restoration is a zone setting");
            var seeds = rig.colony.service(SeedPresence.ID, SeedPresence.class).orElseThrow();
            helper.assertTrue(rig.colony.service(SeedPresence.ID, SeedSupport.class).isEmpty(),
                "one owner shares exactly its one complete seed service");
            rig.body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(2, new ItemStack(Items.WHEAT_SEEDS, 2));
            rig.body.pack().setItem(3, new ItemStack(ModItems.STRAW_HAT.get()));
            BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
            rig.level.setBlock(at, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
            var station = KitchenStations.find(rig.level.getBlockState(at)).orElseThrow();
            var bookings = new KitchenBookings();
            ColonyFront.of(rig.colony).setSetting(CookeryContent.ID, "wear_hat", new ColonySettings.Value.Flag(false));
            helper.assertTrue(!seeds.enabled(), "disabling task hat borrowing disables autonomous seed support");
            helper.assertTrue(station.recovery(rig.colony.id(), rig.level, WorldPos.of(rig.level, at), Stances.WHEREVER,
                bookings, () -> {}).isEmpty(), "the registered composter does not feed while seed task support is disabled");
            ColonyFront.of(rig.colony).setSetting(CookeryContent.ID, "wear_hat", new ColonySettings.Value.Flag(true));
            helper.assertTrue(seeds.enabled(), "task permission enables the demand before a first shared hat is borrowed");
            helper.assertValueEqual(seeds.budget(rig.level).tally(Items.WHEAT_SEEDS).physical(), 130L,
                "the single registered seed service exposes its actual physical stock budget");
            helper.assertTrue(!station.recovery(rig.colony.id(), rig.level, WorldPos.of(rig.level, at), Stances.WHEREVER,
                bookings, () -> {}).isEmpty(), "the real provider sees surplus without requiring ineffective permanent armor for composting");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realDoubleChestAliasesAndPlayerSlotsCountOnlyPhysicalAccessibleSeeds(GameTestHelper helper) {
        try (var rig = new Fixture(helper)) {
            BlockPos left = helper.absolutePos(new BlockPos(2, 1, 2));
            var state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
            BlockPos right = left.relative(ChestBlock.getConnectedDirection(state));
            rig.level.setBlock(left, state, Block.UPDATE_CLIENTS);
            rig.level.setBlock(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT), Block.UPDATE_CLIENTS);
            var chest = Containers.at(rig.level, left).orElseThrow();
            helper.assertValueEqual(chest.getContainerSize(), 54, "the fixture is a real joined double chest");
            chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            chest.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 16));
            rig.body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 20));
            WorldPos first = WorldPos.of(rig.level, left);
            WorldPos second = WorldPos.of(rig.level, right);
            rig.colony.stores(CookeryContent.ID, rig.level.dimension(), List.of(first, second));
            var tally = rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS);
            helper.assertValueEqual(tally.physical(), 100L, "both chest clicks still describe eighty physical seeds plus twenty carried seeds");
            helper.assertValueEqual(tally.compostable(), 36L, "the combined physical surplus is protected by one sixty-four seed reserve");
            WorldPos anchor = WorldPos.of(rig.level, Containers.anchor(rig.level, left));
            rig.colony.rule(CookeryContent.ID, rig.level.dimension(), List.of(new StoreRule.Slots(anchor, 0, 1)));
            tally = rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS);
            helper.assertValueEqual(tally.physical(), 84L, "the player's excluded chest slots never enter the colony seed allowance");
            helper.assertValueEqual(tally.compostable(), 20L, "only the accessible physical surplus may be composted");
            helper.assertValueEqual(chest.getItem(1).getCount(), 16, "budget inspection does not move or destroy excluded seeds");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void playerFiledStandingTargetsProtectTheirHighWaterCountsAcrossBoxes(GameTestHelper helper) {
        try (var rig = new Fixture(helper)) {
            BlockPos first = helper.absolutePos(new BlockPos(1, 1, 1));
            BlockPos second = helper.absolutePos(new BlockPos(3, 1, 1));
            rig.level.setBlock(first, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            rig.level.setBlock(second, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            var store = Containers.at(rig.level, first).orElseThrow();
            for (int slot = 0; slot < 3; slot++) store.setItem(slot, new ItemStack(Items.WHEAT_SEEDS, 64));
            store.setItem(3, new ItemStack(Items.WHEAT_SEEDS, 8));
            rig.colony.stores(CookeryContent.ID, rig.level.dimension(), List.of(WorldPos.of(rig.level, first), WorldPos.of(rig.level, second)));
            var front = ColonyFront.of(rig.colony);
            helper.assertTrue(front.setSetting(OrdersContent.ID, "item", new ColonySettings.Value.Items(List.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(Items.WHEAT_SEEDS))))),
                "the real player order item setting accepts the seed");
            helper.assertTrue(front.setSetting(OrdersContent.ID, "count", new ColonySettings.Value.Count(80)), "the real order high-water setting accepts eighty");
            var facing = Facings.of(rig.colony, OrdersContent.ID).orElseThrow();
            helper.assertTrue(!facing.act(OrdersContent.ID, "maintain", Optional.of(first), rig.colony.view(rig.level)).refused(), "the first maintain order is filed through the real front action");
            helper.assertTrue(!facing.act(OrdersContent.ID, "maintain", Optional.of(second), rig.colony.view(rig.level)).refused(), "the second box has its own real standing order");
            helper.assertValueEqual(Orders.targets(rig.colony).size(), 2, "the bridge reads both actual persisted player orders");
            helper.assertValueEqual(rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS).compostable(), 40L,
                "160 ordered seeds reuse the implicit reserve rather than adding another sixty-four");
            var target = Orders.targets(rig.colony).getFirst();
            helper.assertTrue(!facing.act(OrdersContent.ID, "withdraw:" + target.goods().describe(), Optional.of(first), rig.colony.view(rig.level)).refused(),
                "withdrawing the real order immediately updates persisted metadata");
            helper.assertValueEqual(rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS).compostable(), 120L,
                "only the remaining real eighty-seed order stays protected");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inventoriesFromOtherDimensionsJoinOneGlobalPhysicalReserve(GameTestHelper helper) {
        try (var rig = new Fixture(helper)) {
            ServerLevel nether = rig.level.getServer().getLevel(Level.NETHER);
            helper.assertTrue(nether != null, "the real server has its Nether dimension");
            BlockPos at = new BlockPos(helper.absolutePos(new BlockPos(2, 1, 2)).getX(), 180,
                helper.absolutePos(new BlockPos(2, 1, 2)).getZ());
            nether.getChunk(at);
            var before = nether.getBlockState(at);
            try {
                nether.setBlock(at, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                Containers.at(nether, at).orElseThrow().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
                rig.body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 20));
                rig.colony.stores(CookeryContent.ID, nether.dimension(), List.of(WorldPos.of(nether, at)));
                helper.assertTrue(rig.support.snapshot().complete(), "both real inventories are loaded");
                helper.assertValueEqual(rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS).physical(), 84L, "Nether warehouse seeds and Overworld carried seeds are counted once");
                helper.assertValueEqual(rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS).compostable(), 20L, "dimensions share the one planting reserve");
            } finally {
                nether.setBlock(at, before, Block.UPDATE_ALL);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnloadedHeldResidentMakesAllCompostAllowancesConservative(GameTestHelper helper) {
        try (var rig = new Fixture(helper)) {
            rig.body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 36));
            rig.colony.hold(PersonContent.ID, new Held.Entity(UUID.randomUUID(),
                BuiltInRegistries.ENTITY_TYPE.getKey(PersonBody.RESIDENT.get()))).orElseThrow();
            helper.assertTrue(!rig.support.snapshot().complete(), "a held resident without a loaded entity has unknown physical stock");
            helper.assertValueEqual(rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS).physical(), 100L, "known physical seeds are still reported faithfully");
            helper.assertValueEqual(rig.support.budget(rig.level).tally(Items.WHEAT_SEEDS).compostable(), 0L, "unknown carried claims pause destructive work");
            helper.assertTrue(rig.support.budget(rig.level).reserves().isEmpty(), "no duplicate reserve is requested while stock is unknown");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void loadedMultiResidentWarehouseSnapshotsKeepOnePhysicalReserveAtScale(GameTestHelper helper) throws Exception {
        var level = helper.getLevel(); var colony = Colonies.mint(level.getServer());
        var bodies = new java.util.ArrayList<ResidentEntity>();
        try {
            for (int index = 0; index < 64; index++) {
                var body = PersonBody.RESIDENT.get().create(level); body.setNoAi(true);
                body.moveTo(helper.absolutePos(new BlockPos(2, 1, 5)).getCenter()); level.addFreshEntity(body);
                colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
                body.joinColony(colony.id()); colony.entered(body); body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS)); bodies.add(body);
            }
            var stores = new java.util.ArrayList<WorldPos>();
            for (int index = 0; index < 24; index++) {
                var at = helper.absolutePos(new BlockPos(index % 6 + 1, 1, index / 6 + 1));
                level.setBlock(at, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
                Containers.at(level, at).orElseThrow().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
                stores.add(WorldPos.of(level, at));
            }
            colony.stores(CookeryContent.ID, level.dimension(), stores);
            var support = new SeedSupport(colony, level.getServer()); var budget = support.budget(level);
            long expected = 24L * 64 + 64;
            for (int warm = 0; warm < 5; warm++) support.snapshot();
            var durations = new java.util.ArrayList<Long>();
            for (int read = 0; read < 20; read++) {
                long began = System.nanoTime(); var snapshot = support.snapshot(); durations.add(System.nanoTime() - began);
                helper.assertTrue(snapshot.complete(), "all registered residents and warehouses are real and loaded");
                helper.assertValueEqual(snapshot.stock().size(), 88, "24 stores and 64 carried inventories are each observed once");
                long actual = snapshot.stock().stream().flatMap(stock -> stock.stacks().stream())
                    .filter(stack -> stack.is(Items.WHEAT_SEEDS)).mapToLong(ItemStack::getCount).sum();
                helper.assertValueEqual(actual, expected, "a large snapshot preserves actual physical seed ownership");
            }
            long reserveBegan = System.nanoTime(); var reserves = budget.reserves(); long reserveNanos = System.nanoTime() - reserveBegan;
            helper.assertTrue(!reserves.isEmpty(), "the real crop catalogue supplies purposes at scale");
            helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).physical(), expected, "budget agrees with all real inventories");
            helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).retained(), 64L, "resident count does not multiply the one-purpose seed reserve");
            helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).compostable(), expected - 64, "only true surplus is eligible at scale");
            java.nio.file.Files.writeString(java.nio.file.Path.of("seed-scale-check.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of(
                "residents", 64, "warehouses", 24, "physical_seeds", expected, "snapshot_samples_nanos", durations,
                "catalogue_purposes", reserves.size(), "reserve_scan_nanos", reserveNanos,
                "item_registry_size", BuiltInRegistries.ITEM.size(), "timing_note", "local observation only, no cross-machine performance assertion")));
        } finally { bodies.forEach(ResidentEntity::discard); colony.raze(); }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        final SeedSupport support;
        Fixture(GameTestHelper helper) {
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.moveTo(helper.absolutePos(new BlockPos(2, 1, 4)).getCenter());
            level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            colony.entered(body);
            support = new SeedSupport(colony, level.getServer());
        }
        public void close() { body.discard(); colony.raze(); }
    }
}
