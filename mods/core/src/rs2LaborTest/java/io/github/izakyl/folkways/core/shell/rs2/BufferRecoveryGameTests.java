package io.github.izakyl.folkways.core.shell.rs2;

import io.github.izakyl.folkways.core.testing.NetworkBufferChecks;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class BufferRecoveryGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BufferRecoveryGameTests.class);
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void collectedInputsAreNeverFetchedAgainAfterWorkerLoss(GameTestHelper helper) {
        new NetworkBufferChecks(helper, false).lostWorkerAfterCollection();
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void disabledWorkerIsReassigned(GameTestHelper helper) {
        new NetworkBufferChecks(helper, false).lostWorker(true, false);
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void missingWorkerReturnsUncollectedInputs(GameTestHelper helper) {
        new NetworkBufferChecks(helper, false).lostWorker(false, true);
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void deletedRecipeReturnsUncollectedInputs(GameTestHelper helper) {
        new NetworkBufferChecks(helper, false).deletedRecipe();
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void furnaceBatchesRespectReservationPredecessors(GameTestHelper helper) {
        var checks = new java.util.concurrent.atomic.AtomicReference<NetworkBufferChecks>();
        helper.startSequence().thenIdle(120).thenExecute(() -> checks.set(new NetworkBufferChecks(helper, true)))
            .thenIdle(80).thenExecute(() -> checks.get().furnaceDependencies()).thenSucceed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void reloadMidSmeltDoesNotReplayCommittedInputsOrFuel(GameTestHelper helper) {
        var checks = new java.util.concurrent.atomic.AtomicReference<NetworkBufferChecks>();
        helper.startSequence().thenIdle(120).thenExecute(() -> checks.set(new NetworkBufferChecks(helper, true)))
            .thenIdle(80).thenExecute(() -> checks.get().startSmeltingAndReload()).thenIdle(220)
            .thenExecute(() -> checks.get().completeReloadedSmelt()).thenSucceed();
    }
}
