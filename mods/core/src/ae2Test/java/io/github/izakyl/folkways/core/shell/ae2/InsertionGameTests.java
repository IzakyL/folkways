package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.core.definitions.AEBlocks;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.testing.Cleanup;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class InsertionGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(InsertionGameTests.class);
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void intermediateDepositIsReceivedByNativeCpuWithoutStockGrowth(GameTestHelper helper) {
        var rig = new NetworkRig(helper);
        helper.setBlock(new BlockPos(2, 2, 3), AEBlocks.CRAFTING_STORAGE_1K.block());
        var calculation = new AtomicReference<Future<ICraftingPlan>>();
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            var service = rig.port().node().getGrid().getCraftingService();
            List<IPatternDetails> patterns = List.of(pattern(helper, Items.RAW_IRON, Items.IRON_INGOT),
                pattern(helper, Items.IRON_INGOT, Items.DIAMOND));
            ICraftingProvider provider = new ICraftingProvider() {
                @Override
                public List<IPatternDetails> getAvailablePatterns() {
                    return patterns;
                }

                @Override
                public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
                    return true;
                }

                @Override
                public boolean isBusy() {
                    return false;
                }
            };
            service.addGlobalCraftingProvider(provider);
            Cleanup.on(helper, () -> {
                service.getCpus().forEach(cpu -> cpu.cancelJob());
                service.removeGlobalCraftingProvider(provider);
            });
            rig.stock(Items.RAW_IRON, 1);
            var source = Devices.source(rig.port().node());
            calculation.set(service.beginCraftingCalculation(helper.getLevel(), () -> source,
                AEItemKey.of(Items.DIAMOND), 1, CalculationStrategy.REPORT_MISSING_ITEMS));
        }).thenWaitUntil(() -> helper.assertTrue(calculation.get().isDone(), "native calculation completes"))
            .thenExecute(() -> {
                try {
                    var port = rig.port();
                    var service = port.node().getGrid().getCraftingService();
                    helper.assertTrue(service.submitJob(calculation.get().get(), null,
                        service.getCpus().iterator().next(), false, Devices.source(port.node())).successful(),
                        "native chained job submitted");
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).thenWaitUntil(() -> helper.assertTrue(rig.port().node().getGrid().getCraftingService()
                .getRequestedAmount(AEItemKey.of(Items.IRON_INGOT)) == 1, "CPU awaits the intermediate"))
            .thenExecute(() -> {
                var port = rig.port();
                helper.assertTrue(port.put(helper.getLevel(), List.of(new ItemStack(Items.IRON_INGOT)),
                    List.of(), true, false).done(), "native crafting receipt conserves the deposited ingot");
                helper.assertTrue(rig.count(Items.IRON_INGOT) == 0
                    && StorePorts.condemned(port.identity().home()).isEmpty(),
                    "the ingot belongs to the CPU and the access remains usable");
            }).thenSucceed();
    }

    private static IPatternDetails pattern(GameTestHelper helper, net.minecraft.world.item.Item input,
                                            net.minecraft.world.item.Item output) {
        return PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
            List.of(new GenericStack(AEItemKey.of(input), 1)),
            List.of(new GenericStack(AEItemKey.of(output), 1))), helper.getLevel());
    }
}
