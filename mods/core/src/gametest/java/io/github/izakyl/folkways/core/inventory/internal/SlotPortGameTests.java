package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SlotPortGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SlotPortGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void slotPortsKeepNativeLimitsRulesAndAtomicSimulation(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        var container = new SimpleContainer(3) {
            @Override
            public int getMaxStackSize(ItemStack stack) {
                return stack.is(Items.BREAD) ? 16 : 4;
            }
        };
        container.setItem(0, new ItemStack(Items.BREAD, 10));
        container.setItem(1, new ItemStack(Items.BREAD, 9));
        var port = new SlotPort(PortIdentity.block(at), container);
        List<StoreRule> rules = new ArrayList<>(List.of(new StoreRule.Slots(at, 0, 2)));
        var reader = port.reader(level, rules);
        while (!reader.step(64)) {
        }
        var reading = reader.result();
        helper.assertTrue(reading.refusesBySampling(), "native refusal discovery keeps its bounded sampler");
        Room.Slots room = (Room.Slots) reading.room();
        helper.assertTrue(room.open() == 0 && room.cap().limit(Items.BREAD.getDefaultInstance()) == 16
            && room.cap().limit(Items.IRON_INGOT.getDefaultInstance()) == 4, "per-item native limits are captured");
        Stash where = Stash.at(at);
        var stock = Stock.observed(reading.entries().stream()
            .map(entry -> new Stock.Holding(where, entry.prototype(), entry.count())).toList(),
            Map.of(where, room), Map.of(where, rules), Map.of());
        helper.assertTrue(stock.roomAt(where, ItemSpec.of(Items.BREAD)) == 13,
            "two partial slots retain their separate native top-up room");
        helper.assertFalse(port.put(level, List.of(new ItemStack(Items.BREAD, 14)), rules, false, false).done(),
            "a failed put is atomic and cannot use the excluded third slot");
        List<Pull> pull = List.of(new Pull.Exact(new ItemStack(Items.BREAD, 4)));
        helper.assertTrue(port.take(level, pull, rules, true).done() && container.countItem(Items.BREAD) == 19,
            "simulation leaves every physical slot unchanged");
        helper.assertTrue(port.take(level, pull, rules, false).done() && container.countItem(Items.BREAD) == 15,
            "real extraction writes back the complete simulated result");
        rules.add(new StoreRule.Admit(at, false, List.of(ItemSpec.of(Items.BREAD))));
        helper.assertFalse(port.put(level, List.of(new ItemStack(Items.BREAD, 2)), rules, false, false).done(),
            "ordinary puts obey admission rules");
        helper.assertTrue(port.put(level, List.of(new ItemStack(Items.BREAD, 2)), rules, true, false).done()
            && container.countItem(Items.BREAD) == 17 && container.getItem(2).isEmpty(),
            "directed puts bypass admission while retaining native and slot restrictions");
        helper.succeed();
    }
}
