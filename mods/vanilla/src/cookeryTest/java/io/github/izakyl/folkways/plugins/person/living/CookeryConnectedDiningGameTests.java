package io.github.izakyl.folkways.plugins.person.living;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.*;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryConnectedDiningGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(CookeryConnectedDiningGameTests.class); }

    @GameTest(template = "empty")
    public static void splitNativeTableRefundsOldSupplyAndCancelsReservedRemoteMeal(GameTestHelper helper) {
        try (var f = new DiningGameTests.Fixture(helper, ModBlocks.TABLE_OAK.get().defaultBlockState())) {
            connect(f, 8);
            BlockPos far = f.at.east(7);
            Node supply = f.goals().stream().filter(node -> node.spec().focus().filter(far::equals).isPresent()).findFirst().orElseThrow();
            Container display = DiningTables.inventory(f.level, far).orElseThrow();
            display.setItem(1, new ItemStack(Items.MUSHROOM_STEW));
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            helper.assertTrue(meal.spec().focus().filter(far::equals).isPresent(), "near chair reserved the far physical serving");
            // Breaking an empty middle piece must invalidate both snapshots before any resurvey.
            f.level.setBlockAndUpdate(f.at.east(3), Blocks.AIR.defaultBlockState());
            f.worker.supplied = List.of(new ItemStack(Items.MUSHROOM_STEW));
            Outcome.Done cancelled = (Outcome.Done) supply.commit(f.level, f.worker);
            helper.assertValueEqual(cancelled.unused().stream().mapToInt(ItemStack::getCount).sum(), 1, "old supply returns its actual input");
            helper.assertTrue(display.getItem(0).isEmpty(), "split cannot remotely write the old empty slot");
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Failed, "old shared meal cannot cross the new split");
            helper.assertTrue(display.getItem(1).is(Items.MUSHROOM_STEW), "failed meal leaves the original native serving untouched");
            helper.assertValueEqual(f.living.foodLevelOf(f.person.getUUID()).orElseThrow(), 4, "split grants no nourishment");
            f.dining.refresh(f.level.getServer());
            helper.assertTrue(f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).isEmpty(),
                "the far half without a chair cannot supply the near half");
            f.level.setBlockAndUpdate(f.at.east(3), ModBlocks.TABLE_OAK.get().defaultBlockState());
            f.dining.refresh(f.level.getServer());
            Node retry = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            helper.assertTrue(retry.commit(f.level, f.worker) instanceof Outcome.Done, "reconnecting releases and reclaims the real chair and serving");
            helper.assertTrue(display.isEmpty(), "exactly the original far serving was consumed once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeTableCanBeSuppliedFromItsEndWithoutAChairAtThatEnd(GameTestHelper helper) {
        try (var f = new DiningGameTests.Fixture(helper, ModBlocks.TABLE_OAK.get().defaultBlockState())) {
            connect(f, 8);
            BlockPos end = f.at.east(8);
            f.level.setBlockAndUpdate(end.below(), Blocks.STONE.defaultBlockState());
            f.dining.refresh(f.level.getServer());
            Node supply = f.goals().stream().filter(node -> node.spec().focus().filter(f.at::equals).isPresent()).findFirst().orElseThrow();
            var stances = (Stances.Cells) supply.spec().stances();
            helper.assertTrue(stances.cells().contains(io.github.izakyl.folkways.core.api.terms.WorldPos.of(f.level, end)),
                "the end without chairs is a real planned service stance for the opposite physical slot");
            f.person.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(end));
            f.worker.supplied = List.of(new ItemStack(Items.MUSHROOM_STEW));
            helper.assertTrue(supply.commit(f.level, f.worker) instanceof Outcome.Done, "any reachable end supplies the whole connected table");
            helper.assertTrue(f.display.getItem(0).is(Items.MUSHROOM_STEW), "food reaches the opposite native inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void connectedNativeDisplaysKeepPerCellTargetsAndPhysicalComponents(GameTestHelper helper) {
        try (var f = new DiningGameTests.Fixture(helper, ModBlocks.TABLE_OAK.get().defaultBlockState())) {
            connect(f, 8);
            helper.assertValueEqual(f.goals().size(), 16, "two requested servings per physical cell remain sixteen real targets");
            BlockPos far = f.at.east(7);
            var node = f.goals().stream().filter(goal -> goal.spec().focus().filter(far::equals).isPresent()).findFirst().orElseThrow();
            ItemStack soup = new ItemStack(Items.MUSHROOM_STEW);
            soup.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Far-end dinner"));
            f.worker.supplied = List.of(soup);
            helper.assertTrue(f.person.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(far)) > 16,
                "supplier is at the other end, farther than the former four-block limit");
            Outcome.Done placed = (Outcome.Done) node.commit(f.level, f.worker);
            helper.assertTrue(placed.unused().isEmpty(), "one actual supply was used");
            Container display = DiningTables.inventory(f.level, far).orElseThrow();
            helper.assertTrue(ItemStack.isSameItemSameComponents(display.getItem(0), soup), "distant native storage preserves meal components");
            helper.assertTrue(f.display.isEmpty(), "near native storage has no copied serving");
            Node meal = f.dining.urge(f.worker, f.colony.view(f.level), Urge.SPARE, false).orElseThrow().take().get();
            Outcome.Done eaten = (Outcome.Done) meal.commit(f.level, f.worker);
            helper.assertTrue(display.isEmpty(), "shared meal removes food at its actual far location");
            helper.assertValueEqual(eaten.made().stream().filter(item -> item.is(Items.BOWL)).mapToInt(ItemStack::getCount).sum(), 1,
                "shared native food yields exactly one original container");
            helper.assertTrue(meal.commit(f.level, f.worker) instanceof Outcome.Failed, "replay cannot duplicate a shared meal");
        }
        helper.succeed();
    }

    private static void connect(DiningGameTests.Fixture f, int length) {
        f.zone(Items.MUSHROOM_STEW);
        var settings = f.front.zone(f.zone).orElseThrow().settings();
        f.front.erase(f.zone);
        var cells = new HashSet<BlockPos>();
        cells.add(f.chair);
        for (int x = 0; x < length; x++) {
            BlockPos at = f.at.east(x);
            f.level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(at, ModBlocks.TABLE_OAK.get().defaultBlockState());
            cells.add(at);
        }
        f.zone = f.front.addZone(f.level, LivingContent.DINING, cells, settings).orElseThrow().id();
        f.dining.refresh(f.level.getServer());
    }
}
