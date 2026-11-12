package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEBlocks;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.shell.crafting.DeviceSettings;
import io.github.izakyl.folkways.core.testing.NetworkServiceFixture;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.concurrent.Future;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ProviderLaborGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (Boolean.getBoolean("folkways.ae2.laborTests")) {
            event.register(ProviderLaborGameTests.class);
        }
    }

    private static final class Run {
        private final GameTestHelper helper;
        private final NetworkRig rig;
        private final boolean furnace;
        private final Item input;
        private final Item otherInput;
        private final Item output;
        private final int inputCount;
        private long orderCount = 1;
        private boolean networkFuel;
        private int furnaceCount = 1;
        private NetworkServiceFixture workers;
        private NetworkPort port;
        private ColonyProvider provider;
        private Future<ICraftingPlan> calculation;
        private ICraftingCPU cpu;

        Run(GameTestHelper helper) {
            this(helper, false);
        }

        Run(GameTestHelper helper, boolean furnace) {
            this.helper = helper;
            this.furnace = furnace;
            input = furnace ? Items.RAW_IRON : Items.IRON_INGOT;
            otherInput = Items.COPPER_INGOT;
            output = furnace ? Items.IRON_INGOT : Items.DIAMOND;
            inputCount = furnace ? 1 : 2;
            rig = new NetworkRig(helper);
            helper.setBlock(new BlockPos(2, 2, 3), AEBlocks.CRAFTING_STORAGE_1K.block());
        }

        void enroll(boolean enabled) {
            var terminals = List.of(NetworkRig.TERMINAL, NetworkRig.OTHER);
            workers = furnace ? NetworkServiceFixture.furnaces(helper, terminals, furnaceCount)
                : new NetworkServiceFixture(helper, terminals);
            workers.enable(enabled);
            port = rig.port();
            rig.stock(input, inputCount);
            if (!furnace) {
                rig.stock(otherInput, 1);
            }
        }

        void calculate() {
            calculate(1);
        }

        void calculate(long count) {
            orderCount = count;
            if (furnace) {
                workers.furnaceRecipeInputs();
                helper.assertTrue(rig.count(Items.COAL) == (networkFuel ? 256 : 0),
                    "the network holds exactly the configured fuel stock");
            }
            provider = workers.colony.service(Ae2Integration.OWNER, NetworkPresence.class).orElseThrow()
                .provider(port.identity().home()).orElseThrow();
            var source = Devices.source(port.node());
            calculation = port.node().getGrid().getCraftingService().beginCraftingCalculation(helper.getLevel(),
                () -> source, AEItemKey.of(output), count, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        ICraftingPlan plan() {
            helper.assertTrue(calculation != null && calculation.isDone(), "native player-style calculation finishes");
            try {
                var plan = calculation.get();
                helper.assertTrue(!plan.simulation() && plan.patternTimes().keySet().stream()
                        .anyMatch(ColonyPattern::isColonyPattern), "the sole route uses the colony Workshop");
                return plan;
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void submit() {
            cpu = port.node().getGrid().getCraftingService().getCpus().iterator().next();
            helper.assertTrue(port.node().getGrid().getCraftingService().submitJob(plan(), null, cpu, false,
                Devices.source(port.node())).successful(), "a native player-style order is accepted");
        }

        void pushed() {
            helper.assertTrue(provider.pushes() == 1 && provider.buffered(AEItemKey.of(input)) == inputCount
                    && provider.buffered(AEItemKey.of(otherInput)) == (furnace ? 0 : 1)
                    && provider.buffered(AEItemKey.of(Items.COAL)) == 0,
                "native push transfers exact inputs into the colony buffer");
            var saved = workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                "folkways_core", "ae2_network_buffers")).getList("batches", Tag.TAG_COMPOUND);
            helper.assertTrue(saved.size() == 1 && !saved.getCompound(0).contains("task")
                    && saved.getCompound(0).getList("goods", Tag.TAG_COMPOUND).size() == (furnace ? 1 : 2),
                "accepted inputs and recipe are persisted without a native task owner");
        }

        void delivered(boolean canceled) {
            helper.assertTrue(rig.count(output) == orderCount && rig.count(input) == 0
                    && rig.count(otherInput) == 0 && rig.count(Items.COAL) == (networkFuel ? 255 : 0) && !cpu.isBusy(),
                "resident collected, processed and delivered the exact output"
                    + (canceled ? " as ordinary stock after cancellation" : " to complete the native job")
                    + "; stock=" + rig.count(input) + "," + rig.count(otherInput)
                    + "," + rig.count(output) + "; coal=" + rig.count(Items.COAL)
                    + "; busy=" + cpu.isBusy() + "; pushes=" + provider.pushes()
                    + "; buffer=" + workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                        "folkways_core", "ae2_network_buffers")).getList("batches", Tag.TAG_COMPOUND).size()
                    + "; " + workers.diagnosis());
        }
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void impossibleServiceReturnsInputsAndCancelsItsNativeJob(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.workers.enable(false))
            .thenWaitUntil(() -> helper.assertTrue(!run.cpu.isBusy()
                && run.rig.count(run.input) == run.inputCount && run.rig.count(run.otherInput) == 1
                && run.rig.count(run.output) == 0,
                "an impossible colony batch returns its inputs and cancels only the commissioning job"))
            .thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void playerOrderRunsThroughResidentAndWorkshop(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(false)).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void playerOrderSmeltsThroughResidentAndVanillaFurnace(GameTestHelper helper) {
        Run run = new Run(helper, true);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(false)).thenExecute(() -> run.workers.furnaceInputsConsumed())
            .thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 4800)
    public static void playerOrderSmeltsWithFuelFromTheSameNetwork(GameTestHelper helper) {
        Run run = new Run(helper, true);
        run.networkFuel = true;
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll(true);
            run.workers.clearStoredFuel();
            var presence = run.workers.colony.service(Ae2Integration.OWNER, NetworkPresence.class).orElseThrow();
            for (BlockPos terminal : List.of(NetworkRig.TERMINAL, NetworkRig.OTHER)) {
                helper.assertTrue(presence.settings.at(WorldPos.of(helper.getLevel(), helper.absolutePos(terminal)))
                        .equals(DeviceSettings.Device.DEFAULT),
                    "enrolled network devices retain default take permissions");
            }
            run.rig.stock(Items.RAW_IRON, 3);
            run.rig.stock(Items.COAL, 256);
        }).thenIdle(60).thenExecute(() -> run.calculate(4)).thenWaitUntil(() -> run.plan())
            .thenExecute(run::submit).thenWaitUntil(() -> helper.assertTrue(run.provider.pushes() > 0,
                "native processing has pushed its first fuel-free pattern")).thenIdle(20).thenExecute(() -> {
                helper.assertTrue(run.provider.buffered(AEItemKey.of(Items.COAL)) == 0,
                    "coal is not a pattern input or service-buffer material");
                run.workers.running = true;
            }).thenWaitUntil(() -> run.delivered(false)).thenWaitUntil(() ->
                helper.assertTrue(run.workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                    "folkways_core", "ae2_network_buffers")).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                    "no service obligation or buffered input remains")).thenExecute(() -> {
                helper.assertTrue(run.provider.pushes() == 4, "four fuel-free pattern inputs were accepted");
                run.workers.furnaceInputsConsumed();
            }).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 4800)
    public static void playerOrderSmeltsWithThreeIdleFurnacesAndNetworkFuel(GameTestHelper helper) {
        Run run = new Run(helper, true);
        run.networkFuel = true;
        run.furnaceCount = 3;
        helper.setBlock(new BlockPos(5, 2, 3), AEBlocks.CRAFTING_STORAGE_1K.block());
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll(true);
            helper.assertTrue(run.port.node().getGrid().getCraftingService().getCpus().size() == 2,
                "the ME network has two independent crafting CPUs");
            run.workers.clearStoredFuel();
            for (int index = 1; index < 4; index++) {
                run.workers.addServiceResident();
            }
            run.workers.running = true;
            var presence = run.workers.colony.service(Ae2Integration.OWNER, NetworkPresence.class).orElseThrow();
            for (BlockPos terminal : List.of(NetworkRig.TERMINAL, NetworkRig.OTHER)) {
                helper.assertTrue(presence.settings.at(WorldPos.of(helper.getLevel(), helper.absolutePos(terminal)))
                        .equals(DeviceSettings.Device.DEFAULT),
                    "enrolled network devices retain default take permissions");
            }
            run.rig.stock(Items.RAW_IRON, 3);
            run.rig.stock(Items.COAL, 256);
        }).thenIdle(60).thenExecute(() -> run.calculate(4)).thenWaitUntil(() -> run.plan())
            .thenExecute(run::submit).thenWaitUntil(() -> helper.assertTrue(run.provider.pushes() > 0,
                "native processing has pushed its first fuel-free pattern")).thenExecute(() -> {
                helper.assertTrue(run.provider.buffered(AEItemKey.of(Items.COAL)) == 0,
                    "coal is not a pattern input or service-buffer material");
                run.workers.running = true;
            }).thenWaitUntil(() -> run.delivered(false)).thenWaitUntil(() ->
                helper.assertTrue(run.workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                    "folkways_core", "ae2_network_buffers")).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                    "no service obligation or buffered input remains")).thenExecute(() -> {
                helper.assertTrue(run.provider.pushes() == 4, "four fuel-free pattern inputs were accepted");
                run.workers.furnaceInputsConsumed();
            }).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void multipleIngotOrderNeedsOnlyRawIron(GameTestHelper helper) {
        Run run = new Run(helper, true);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll(true);
            run.rig.stock(Items.RAW_IRON, 3);
        }).thenIdle(60).thenExecute(() -> run.calculate(4)).thenWaitUntil(() -> run.plan())
            .thenExecute(() -> helper.assertTrue(run.rig.count(Items.COAL) == 0,
                "four ingots can be planned with only raw iron in the network")).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void cancelingPlayerOrderDoesNotCancelColonyWork(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.cpu.cancelJob()).thenIdle(40)
            .thenExecute(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(true)).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void disabledServiceWaitsForTheResidentToOptIn(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(false)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit).thenIdle(60)
            .thenExecute(() -> {
                helper.assertTrue(run.provider.isBusy() && run.provider.pushes() == 0,
                    "a resident with network service off does not accept network work");
                run.workers.enable(true);
            }).thenWaitUntil(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(false)).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void unenrolledAnchorKeepsInputsAvailableAtAnotherTerminal(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> {
                var level = helper.getLevel();
                run.workers.colony.stores(NetworkServiceFixture.OWNER, level.dimension(),
                    List.of(WorldPos.of(level, helper.absolutePos(NetworkRig.OTHER))));
                run.workers.colony.claimAt(run.port.identity().home()).ifPresent(claim ->
                    run.workers.colony.release(claim.id(),
                        ClaimRelease.LET_GO));
                run.workers.running = true;
            }).thenWaitUntil(() -> run.delivered(false)).thenSucceed();
    }
}
