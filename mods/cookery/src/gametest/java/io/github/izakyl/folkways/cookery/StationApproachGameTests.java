package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.BlockChanges;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_cookery")
@PrefixGameTestTemplate(false)
public final class StationApproachGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(StationApproachGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void approachReusesEmptyResultsUntilNearbyBlocksChange(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPos at = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        int[] reads = {0};
        KitchenStation provider = new KitchenStation() {
            public boolean matches(BlockState state) { return true; }

            public Optional<Stances> stances(ServerLevel world, WorldPos position) {
                reads[0]++;
                return Optional.empty();
            }
        };
        try (StationApproach approach = new StationApproach(level, at, provider)) {
            approach.get();
            approach.get();
            helper.assertValueEqual(reads[0], 1, "unchanged empty approaches are reused");
            BlockChanges.changed(level, at.block(level).east());
            approach.get();
            approach.get();
            helper.assertValueEqual(reads[0], 2, "a nearby block change invalidates the approach once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void nativeBoardChangesInvalidateCachedOffers(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, ModBlocks.CHOPPING_BOARD.get().defaultBlockState());
        WorldPos at = WorldPos.of(level, cell);
        KitchenStation provider = KitchenStations.find(level.getBlockState(cell)).orElseThrow();
        Stances stances = Stances.at(level, cell.south());
        UUID key = UUID.randomUUID();
        UUID colony = UUID.randomUUID();
        KitchenBookings bookings = new KitchenBookings();
        try (StationApproach approach = new StationApproach(level, at, provider)) {
            approach.get();
            var first = approach.freeze(() -> provider.freeze(key, colony, level, at, stances, bookings, () -> { }));
            var again = approach.freeze(() -> {
                throw new AssertionError("unchanged native state rebuilt the offer");
            });
            helper.assertTrue(first.isPresent() && first == again, "unchanged board state reuses its offer");
            var board = (ChoppingBoardBlockEntity) level.getBlockEntity(cell);
            board.onPutItem(level, null, new ItemStack(Items.CHICKEN));
            var busy = approach.freeze(() -> provider.freeze(key, colony, level, at, stances, bookings, () -> { }));
            helper.assertTrue(busy.isEmpty(), "a native ingredient change removes the cached idle offer");
        }
        helper.succeed();
    }
}
