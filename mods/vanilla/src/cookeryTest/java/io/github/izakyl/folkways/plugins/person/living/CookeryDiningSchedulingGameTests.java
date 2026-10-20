package io.github.izakyl.folkways.plugins.person.living;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryDiningSchedulingGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(CookeryDiningSchedulingGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void realSchedulerSuppliesFarEndAndSharesItsMealWithTheOnlyNearEndChair(GameTestHelper helper) {
        var rig = new DiningSchedulingGameTests.Rig(helper, ModBlocks.TABLE_OAK.get().defaultBlockState(),
            ModBlocks.CHAIR_OAK.get().defaultBlockState(), new ItemStack(Items.MUSHROOM_STEW));
        var front = io.github.izakyl.folkways.front.engine.colony.ColonyFront.of(rig.colony);
        var zone = front.zonesIn(rig.level).stream().filter(z -> z.delegation().equals(LivingContent.DINING)).findFirst().orElseThrow();
        var cells = new java.util.HashSet<net.minecraft.core.BlockPos>();
        cells.add(rig.chair);
        for (int x = 0; x < 8; x++) {
            var top = rig.table.east(x);
            rig.level.setBlockAndUpdate(top.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            rig.level.setBlockAndUpdate(top, ModBlocks.TABLE_OAK.get().defaultBlockState());
            cells.add(top);
            // Occupied native displays are deliberately left alone; only the far end needs a serving.
            if (x < 7) DiningTables.inventory(rig.level, top).orElseThrow().setItem(0, new ItemStack(Items.BREAD));
        }
        var far = rig.table.east(7);
        front.erase(zone.id());
        front.addZone(rig.level, LivingContent.DINING, cells, zone.settings()).orElseThrow();
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(DiningTables.inventory(rig.level, far).orElseThrow()
                .getItem(0).is(Items.MUSHROOM_STEW), "real planning must deliver to the far native slot: " + rig.status()))
            .thenExecute(() -> {
                helper.assertTrue(rig.setting, "the actual executor supplied the distant table from the only chair end");
                helper.assertTrue(rig.person.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(far)) > 16,
                    "delivery succeeds more than four blocks from the physical destination");
                helper.assertValueEqual(rig.sourceCount(), 0, "one real soup is withdrawn from stock");
                rig.hungry();
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.living.mood(rig.person.getUUID()).dined >= rig.hungryAt,
                "the near end chair shares food on the far end of the connected table: " + rig.status()))
            .thenExecute(() -> {
                helper.assertTrue(DiningTables.inventory(rig.level, far).orElseThrow().isEmpty(), "one distant serving is consumed");
                for (int x = 0; x < 7; x++) helper.assertTrue(DiningTables.inventory(rig.level, rig.table.east(x))
                    .orElseThrow().getItem(0).is(Items.BREAD), "other physical displays keep their original food");
                helper.assertTrue(rig.mountedTicks >= 40, "shared meal still uses the actual native chair");
                rig.close();
            }).thenSucceed();
    }

    @GameTest(template = "empty", timeoutTicks = 1400)
    public static void realSchedulerFinishesWindupWhileMountedOnNativeChair(GameTestHelper helper) {
        ItemStack soup = new ItemStack(Items.MUSHROOM_STEW);
        soup.set(DataComponents.CUSTOM_NAME, Component.literal("Dinner at the native table"));
        var rig = new DiningSchedulingGameTests.Rig(helper, ModBlocks.TABLE_OAK.get().defaultBlockState(),
            ModBlocks.CHAIR_OAK.get().defaultBlockState(), soup);
        helper.startSequence()
            .thenWaitUntil(() -> helper.assertTrue(DiningTables.inventory(rig.level, rig.table).orElseThrow()
                .getItem(0).is(Items.MUSHROOM_STEW), "the actual scheduler must deliver to a native table slot"))
            .thenExecute(() -> {
                helper.assertTrue(rig.setting, "a real setting_table task completed");
                helper.assertTrue(ItemStack.isSameItemSameComponents(
                    DiningTables.inventory(rig.level, rig.table).orElseThrow().getItem(0), soup),
                    "scheduled delivery preserves every meal component");
                helper.assertTrue(rig.sourceCount() < rig.startingStock,
                    "the table was supplied from real stock while ordinary pocket rations remained protected");
                rig.hungry();
            })
            .thenWaitUntil(() -> helper.assertTrue(rig.living.mood(rig.person.getUUID()).dined >= rig.hungryAt,
                "the native passenger must complete the scheduler's dining windup, rather than stalling after mounting"))
            .thenExecute(() -> {
                helper.assertTrue(rig.dining, "the native diner displayed its eating gesture");
                helper.assertTrue(rig.mountedTicks >= 40, "a nonplayer resident stayed on the native chair throughout windup");
                helper.assertTrue(rig.living.foodLevelOf(rig.person.getUUID()).orElseThrow() > rig.foodBefore,
                    "finishing the mounted meal nourishes the real resident");
                helper.assertTrue(DiningTables.inventory(rig.level, rig.table).orElseThrow().isEmpty(),
                    "one real displayed serving was consumed");
                int bowls = 0;
                for (int slot = 0; slot < rig.person.pack().getContainerSize(); slot++) {
                    ItemStack stack = rig.person.pack().getItem(slot);
                    if (stack.is(Items.BOWL)) bowls += stack.getCount();
                }
                helper.assertValueEqual(bowls, 1, "the real executor stows the soup's one native bowl");
                helper.assertFalse(rig.person.isPassenger(), "completion releases the native seat");
                helper.assertTrue(io.github.izakyl.folkways.core.api.terms.Footing.withFeetAt(rig.level, rig.person.blockPosition()),
                    "the diner steps into a reachable aisle, ready to resume work");
                helper.assertTrue(DiningTables.available(rig.level, rig.chair), "the next resident can claim that chair");
                helper.assertTrue(rig.person.leisureMotion().isEmpty(), "completion releases the synchronized dining pose");
                rig.close();
            })
            .thenSucceed();
    }
}
