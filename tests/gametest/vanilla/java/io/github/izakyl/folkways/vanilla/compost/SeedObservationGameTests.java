package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class SeedObservationGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "seeds");
    private static final Map<WorldPos, Probe> PROBES = new HashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        Containers.register(BellBlockEntity.class,
            (level, bell) -> Optional.ofNullable(PROBES.get(WorldPos.of(level, bell.getBlockPos()))));
        event.register(SeedObservationGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void insertionIntoCachedEmptyStorePublishesNewSeeds(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            var before = fixture.budget.planning();
            helper.assertTrue(before.complete(), "the empty store is observed completely");
            helper.assertValueEqual(before.tally(Items.WHEAT_SEEDS).physical(), 0L, "the initial store is empty");
            fixture.store.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            var after = fixture.budget.planning();
            helper.assertValueEqual(after.tally(Items.WHEAT_SEEDS).physical(), 64L,
                "a native setChanged notification publishes newly inserted seeds in the same tick");
            helper.assertValueEqual(after.tally(Items.WHEAT_SEEDS).missing(), 0L,
                "a cached empty store cannot leave a false seed shortage");
            helper.assertValueEqual(before.tally(Items.WHEAT_SEEDS).physical(), 0L,
                "the old allocation remains immutable");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void settingsOnlyChangeReallocatesWithoutReadingContainers(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.store.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            fixture.store.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 36));
            var before = fixture.budget.planning();
            int reads = fixture.store.reads;
            CompostSettings.reserve(fixture.colony, Items.WHEAT_SEEDS, 80);
            var after = fixture.budget.planning();
            helper.assertValueEqual(before.tally(Items.WHEAT_SEEDS).compostable(), 36L,
                "the original allocation uses the default reserve");
            helper.assertValueEqual(after.tally(Items.WHEAT_SEEDS).compostable(), 20L,
                "a settings edit immediately reallocates the same observations");
            helper.assertValueEqual(fixture.store.reads, reads, "settings changes do not reopen inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unchangedPlanningPassesReuseAllocationWithoutContainerReads(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            fixture.store.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            var before = fixture.budget.planning();
            int reads = fixture.store.reads;
            for (int pass = 0; pass < 32; pass++) {
                helper.assertTrue(fixture.budget.planning() == before,
                    "unchanged inventory, claims, sowing, targets and settings reuse their allocation");
                fixture.budget.reserves();
                fixture.budget.complete();
            }
            helper.assertValueEqual(fixture.store.reads, reads, "planning passes perform zero container reads");
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        private final Colony colony;
        private final WorldPos at;
        private final Probe store;
        private final SeedBudget budget;

        private Fixture(GameTestHelper helper) {
            var level = helper.getLevel();
            BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
            level.setBlockAndUpdate(cell, Blocks.BELL.defaultBlockState());
            at = WorldPos.of(level, cell);
            store = new Probe(() -> level.getBlockEntity(cell).setChanged());
            PROBES.put(at, store);
            colony = Colonies.mint(level.getServer());
            colony.stores(OWNER, level.dimension(), List.of(at));
            budget = new SeedSupport(colony, level.getServer()).budget(level);
        }

        public void close() {
            colony.raze();
            PROBES.remove(at);
        }
    }

    private static final class Probe extends SimpleContainer {
        private final Runnable changed;
        private int reads;

        private Probe(Runnable changed) {
            super(9);
            this.changed = changed;
        }

        @Override
        public ItemStack getItem(int slot) {
            reads++;
            return super.getItem(slot);
        }

        @Override
        public void setChanged() {
            super.setChanged();
            changed.run();
        }
    }
}
