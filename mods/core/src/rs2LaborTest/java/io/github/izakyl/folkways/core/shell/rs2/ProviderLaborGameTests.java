package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.autocrafting.calculation.CancellationToken;
import com.refinedmods.refinedstorage.api.autocrafting.preview.Preview;
import com.refinedmods.refinedstorage.api.autocrafting.preview.PreviewType;
import com.refinedmods.refinedstorage.api.autocrafting.task.TaskId;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.grid.GridData;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.shell.crafting.DeviceSettings;
import io.github.izakyl.folkways.core.shell.crafting.TerminalPreferences;
import io.github.izakyl.folkways.core.testing.NetworkServiceFixture;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
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
        if (Boolean.getBoolean("folkways.rs2.laborTests")) {
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
        private CompletableFuture<Optional<Preview>> calculation;
        private TaskId task;

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
            provider = workers.colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow()
                .provider(port.identity().home()).orElseThrow();
            calculation = port.crafting().getPreview(new ItemResource(output), count, CancellationToken.NONE);
        }

        Preview plan() {
            helper.assertTrue(calculation != null && calculation.isDone(), "native player-style calculation finishes");
            Preview preview = calculation.join().orElseThrow();
            helper.assertTrue(preview.type() == PreviewType.SUCCESS,
                "the sole route uses the colony Workshop: " + preview);
            return preview;
        }

        void submit() {
            plan();
            task = port.crafting().startTask(new ItemResource(output), orderCount, Devices.source(port.node()),
                false, CancellationToken.NONE).orElseThrow();
        }

        void pushed() {
            helper.assertTrue(provider.pushes() == 1 && provider.buffered(new ItemResource(input)) == inputCount
                    && provider.buffered(new ItemResource(otherInput)) == (furnace ? 0 : 1)
                    && provider.buffered(new ItemResource(Items.COAL)) == 0,
                "native push transfers exact inputs into the colony buffer");
            var saved = workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                "folkways_core", "rs2_network_buffers")).getList("batches", Tag.TAG_COMPOUND);
            helper.assertTrue(saved.size() == 1 && !saved.getCompound(0).contains("task")
                    && saved.getCompound(0).getList("goods", Tag.TAG_COMPOUND).size() == (furnace ? 1 : 2),
                "accepted inputs and recipe are persisted without a native task owner");
        }

        void delivered(boolean canceled) {
            helper.assertTrue(rig.count(output) == orderCount && rig.count(input) == 0
                    && rig.count(otherInput) == 0 && rig.count(Items.COAL) == (networkFuel ? 255 : 0)
                    && port.crafting().getStatuses().isEmpty(),
                "resident collected, processed and delivered the exact output"
                    + (canceled ? " as ordinary stock after cancellation" : " to complete the native job")
                    + "; coal=" + rig.count(Items.COAL) + "; " + workers.diagnosis());
        }
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void impossibleServiceReturnsInputsAndCancelsItsNativeJob(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.workers.enable(false))
            .thenWaitUntil(() -> helper.assertTrue(run.port.crafting().getStatuses().isEmpty()
                && run.rig.count(run.input) == run.inputCount && run.rig.count(run.otherInput) == 1
                && run.rig.count(run.output) == 0,
                "an impossible colony batch returns its inputs and cancels only the commissioning job"))
            .thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void playerOrderRunsThroughResidentAndWorkshop(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(false)).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void playerOrderSmeltsThroughResidentAndVanillaFurnace(GameTestHelper helper) {
        Run run = new Run(helper, true);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(false)).thenExecute(() -> run.workers.furnaceInputsConsumed())
            .thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 4800)
    public static void playerOrderSmeltsWithFuelFromTheSameNetwork(GameTestHelper helper) {
        Run run = new Run(helper, true);
        run.networkFuel = true;
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll(true);
            run.workers.clearStoredFuel();
            var presence = run.workers.colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
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
                helper.assertTrue(run.provider.buffered(new ItemResource(Items.COAL)) == 0,
                    "coal is not a pattern input or service-buffer material");
                run.workers.running = true;
            }).thenWaitUntil(() -> run.delivered(false)).thenWaitUntil(() ->
                helper.assertTrue(run.workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                    "folkways_core", "rs2_network_buffers")).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                    "no service obligation or buffered input remains")).thenExecute(() -> {
                helper.assertTrue(run.provider.pushes() == 4, "four fuel-free pattern inputs were accepted");
                run.workers.furnaceInputsConsumed();
            }).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 4800)
    public static void playerOrderSmeltsWithThreeIdleFurnacesAndNetworkFuel(GameTestHelper helper) {
        Run run = new Run(helper, true);
        run.networkFuel = true;
        run.furnaceCount = 3;
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll(true);
            run.workers.clearStoredFuel();
            run.workers.running = true;
            var presence = run.workers.colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
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
                helper.assertTrue(run.provider.buffered(new ItemResource(Items.COAL)) == 0,
                    "coal is not a pattern input or service-buffer material");
                run.workers.running = true;
            }).thenWaitUntil(() -> run.delivered(false)).thenWaitUntil(() ->
                helper.assertTrue(run.workers.colony.kept(ResourceLocation.fromNamespaceAndPath(
                    "folkways_core", "rs2_network_buffers")).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                    "no service obligation or buffered input remains")).thenExecute(() -> {
                helper.assertTrue(run.provider.pushes() == 4, "four fuel-free pattern inputs were accepted");
                run.workers.furnaceInputsConsumed();
            }).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void multipleIngotOrderNeedsOnlyRawIron(GameTestHelper helper) {
        Run run = new Run(helper, true);
        helper.startSequence().thenIdle(120).thenExecute(() -> {
            run.enroll(true);
            run.rig.stock(Items.RAW_IRON, 3);
        }).thenIdle(60).thenExecute(() -> run.calculate(4)).thenWaitUntil(() -> run.plan())
            .thenExecute(() -> helper.assertTrue(run.rig.count(Items.COAL) == 0,
                "four ingots can be planned with only raw iron in the network")).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
    public static void cancelingPlayerOrderDoesNotCancelColonyWork(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(true)).thenIdle(60)
            .thenExecute(run::calculate).thenWaitUntil(() -> run.plan()).thenExecute(run::submit)
            .thenWaitUntil(run::pushed).thenExecute(() -> run.provider.cancelTask(run.task)).thenIdle(40)
            .thenExecute(run::pushed).thenExecute(() -> run.workers.running = true)
            .thenWaitUntil(() -> run.delivered(true)).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
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

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 2400)
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
    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void terminalFilterKeepsTheNativeCalculationRoute(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(() -> run.enroll(false)).thenIdle(60)
            .thenExecute(() -> {
                var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "colony-rs2"));
                var grid = (Grid) helper.getLevel()
                    .getBlockEntity(helper.absolutePos(NetworkRig.TERMINAL));
                TerminalPreferences.set(player, false);
                TerminalRecipes.opening(player);
                try {
                    var data = GridData.of(grid);
                    helper.assertTrue(!data.autocraftableResources().contains(new ItemResource(Items.DIAMOND)),
                        "the server's initial GridData excludes the colony-only craftable entry");
                    TerminalPreferences.set(player, true);
                    helper.assertTrue(GridData.of(grid)
                            .autocraftableResources().contains(new ItemResource(Items.DIAMOND)),
                        "the player's enabled preference restores the outgoing entry");
                } finally {
                    TerminalRecipes.opening((ServerPlayer) null);
                }
                TerminalPreferences.set(player, false);
                run.calculate();
            }).thenWaitUntil(() -> run.plan()).thenSucceed();
    }

}
