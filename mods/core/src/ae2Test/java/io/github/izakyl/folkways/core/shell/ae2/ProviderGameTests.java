package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.ITerminalHost;
import appeng.core.definitions.AEBlocks;
import appeng.menu.me.common.MEStorageMenu;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.shell.crafting.TerminalPreferences;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.stream.StreamSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ProviderGameTests {

    private static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "provider_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ProviderGameTests.class);
    }

    private record Bench(UUID key, WorkSite site) implements Workshop {
        @Override
        public ResourceLocation id() {
            return OWNER;
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)
                    || produce.wanted().item()
                        .filter(ResourceLocation.withDefaultNamespace("diamond")::equals).isEmpty()) {
                return List.of();
            }
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, site, Stances.WHEREVER, Workload.Once.of(20))
                .needs(new Need(ItemSpec.of(Items.IRON_INGOT), 2), new Need(ItemSpec.of(Items.COPPER_INGOT), 1))
                .gives(new Amount(ItemSpec.of(Items.DIAMOND), 1)).done();
            return List.of(Refinement.Change.of(Grown.of(new Node() {
                @Override
                public NodeSpec spec() {
                    return spec;
                }

                @Override
                public Outcome commit(ServerLevel level, Worker worker) {
                    throw new AssertionError("the provider prototype must not execute resident work");
                }
            })));
        }
    }

    private static final class Run {
        private final GameTestHelper helper;
        private final NetworkRig rig;
        private final Colony colony;
        private WorldPos at;
        private NetworkPort port;
        private ColonyProvider provider;
        private Future<ICraftingPlan> calculation;
        private ICraftingCPU cpu;

        Run(GameTestHelper helper) {
            this.helper = helper;
            rig = new NetworkRig(helper);
            helper.setBlock(new BlockPos(2, 2, 3), AEBlocks.CRAFTING_STORAGE_1K.block());
            colony = Colonies.mint(helper.getLevel().getServer());
            Cleanup.on(helper, colony::raze);
        }

        void enroll() {
            rig.stock(Items.IRON_INGOT, 2);
            rig.stock(Items.COPPER_INGOT, 1);
            port = rig.port();
            at = port.identity().home();
            colony.hold(OWNER, new Claimed.Block(at)).orElseThrow();
            colony.stores(OWNER, helper.getLevel().dimension(), List.of(at,
                WorldPos.of(helper.getLevel(), helper.absolutePos(NetworkRig.OTHER))));
            colony.publish(OWNER, helper.getLevel().dimension(), List.of(new Bench(UUID.randomUUID(),
                new WorkSite.AtBlock(at))));
        }

        void connected() {
            provider = colony.service(Ae2Integration.OWNER, NetworkPresence.class).orElseThrow()
                .provider(at).orElseThrow();
            helper.assertTrue(provider.node.isActive() && provider.node.getGrid() == port.node().getGrid(),
                "the managed off-world node is active on the enrolled ME network");
            helper.assertTrue(!provider.node.getNode().hasFlag(GridFlags.REQUIRE_CHANNEL),
                "the virtual provider consumes no channel");
            long providers = StreamSupport
                .stream(port.node().getGrid().getNodes().spliterator(), false)
                .filter(node -> node.getOwner() instanceof ColonyProvider).count();
            helper.assertTrue(providers == 1, "two enrolled terminals share exactly one virtual provider");
            helper.assertTrue(port.node().getGrid().getCraftingService().isCraftable(AEItemKey.of(Items.DIAMOND)),
                "the derived Workshop recipe is advertised by AE2");
        }

        void calculate() {
            var source = Devices.source(port.node());
            calculation = port.node().getGrid().getCraftingService().beginCraftingCalculation(helper.getLevel(),
                () -> source, AEItemKey.of(Items.DIAMOND), 1, CalculationStrategy.REPORT_MISSING_ITEMS);
        }

        ICraftingPlan plan() {
            helper.assertTrue(calculation.isDone(), "the native asynchronous calculation completed");
            try {
                var plan = calculation.get();
                helper.assertTrue(!plan.simulation() && plan.missingItems().isEmpty()
                        && plan.patternTimes().keySet().stream().anyMatch(ColonyPattern::isColonyPattern),
                    "AE2 planned a feasible route using the colony's sole diamond pattern");
                return plan;
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void submit() {
            cpu = port.node().getGrid().getCraftingService().getCpus().iterator().next();
            var submitted = port.node().getGrid().getCraftingService().submitJob(plan(), null, cpu, false,
                Devices.source(port.node()));
            helper.assertTrue(submitted.successful(), "the real AE2 CPU accepted the colony recipe");
        }


    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void virtualProviderConnectsAndParticipatesInNativeCalculation(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(60)
            .thenExecute(run::connected).thenExecute(run::calculate).thenWaitUntil(() -> run.plan())
            .thenExecute(() -> {
                var pattern = run.plan().patternTimes().keySet().iterator().next();
                var decoded = PatternDetailsHelper.decodePattern(pattern.getDefinition(),
                    helper.getLevel());
                helper.assertTrue(pattern.equals(decoded),
                    "the hidden component-encoded definition decodes identically");
                run.colony.stores(OWNER, helper.getLevel().dimension(), List.of());
            }).thenIdle(2).thenExecute(() -> helper.assertTrue(run.provider.node.getNode() == null,
                "unenrolling the access devices destroys the virtual node")).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void colonyOrderRefusesAFeasibleColonyPatternBeforeSubmission(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(60)
            .thenExecute(run::connected).thenExecute(run::calculate).thenWaitUntil(() -> run.plan())
            .thenExecute(() -> {
                var crafting = run.port.node().getGrid().getCraftingService();
                var cpu = crafting.getCpus().iterator().next();
                var tally = new Tally();
                var executor = tally.snapshot(cpu);
                var ticket = tally.ticket(UUID.randomUUID(), (Devices.NetworkKey) run.port.identity().key(),
                    List.of(), helper.getLevel().getGameTime(), ItemSpec.of(Items.DIAMOND), 1,
                    List.of(executor), true);
                Cleanup.on(helper, () -> {
                    ticket.release(Ending.DROPPED);
                    tally.clean();
                });
                helper.assertTrue(ticket.take().isEmpty(), "the colony order reserves its CPU");
                var order = new CraftOrder(run.at, ItemSpec.of(Items.DIAMOND), ticket, run.colony);
                var collect = new TakeNode(run.at, Stances.WHEREVER, ticket, Optional.of(order), run.colony);
                helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "the real calculation starts");
                helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                    collect.refusal(helper.getLevel()).filter(Ae2Refusal.MISSING_INPUTS::equals).isPresent(),
                    "the feasible colony pattern is refused at submission and exposed for replanning"))
                    .thenExecute(() -> {
                        ticket.release(Ending.DROPPED);
                        tally.clean();
                    }).thenIdle(40).thenExecute(() -> {
                        helper.assertTrue(tally.free(executor) && !cpu.isBusy() && run.provider.pushes() == 0,
                            "the refused order leaves the CPU free and never pushes colony work");
                        helper.assertTrue(run.rig.count(Items.IRON_INGOT) == 2
                                && run.rig.count(Items.COPPER_INGOT) == 1 && run.rig.count(Items.DIAMOND) == 0
                                && run.provider.buffered(AEItemKey.of(Items.IRON_INGOT)) == 0
                                && run.provider.buffered(AEItemKey.of(Items.COPPER_INGOT)) == 0,
                            "all inputs remain in network storage after the refused order");
                    }).thenSucceed();
            });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void noNetworkServiceWorkerLeavesTheCpuWaiting(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(60)
            .thenExecute(run::connected).thenExecute(run::calculate).thenWaitUntil(() -> run.plan())
            .thenExecute(run::submit).thenIdle(80).thenExecute(() -> {
                helper.assertTrue(run.provider.isBusy() && run.provider.pushes() == 0,
                    "without an idle enabled network worker no inputs are accepted");
                run.cpu.cancelJob();
            }).thenSucceed();
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void terminalFilterDoesNotRemoveTheCalculationPattern(GameTestHelper helper) {
        Run run = new Run(helper);
        helper.startSequence().thenIdle(120).thenExecute(run::enroll).thenIdle(60)
            .thenExecute(run::connected).thenExecute(() -> {
                var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.getLevel(),
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "colony-ae2"));
                var terminal = PartHelper.getPartHost(helper.getLevel(),
                    run.at.block(helper.getLevel())).getPart(Direction.NORTH);
                var menu = new MEStorageMenu(
                    MEStorageMenu.TYPE, 1, player.getInventory(),
                    (ITerminalHost) terminal);
                TerminalPreferences.set(player, false);
                try {
                    var list = menu.getClass().getDeclaredMethod("getCraftablesFromGrid");
                    list.setAccessible(true);
                    helper.assertTrue(!((Set<?>) list.invoke(menu)).contains(AEItemKey.of(Items.DIAMOND)),
                        "the native server menu's outgoing craftable list omits colony-only output");
                    TerminalPreferences.set(player, true);
                    helper.assertTrue(((Set<?>) list.invoke(menu)).contains(AEItemKey.of(Items.DIAMOND)),
                        "enabling the player preference restores the outgoing entry");
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
                TerminalPreferences.set(player, false);
                run.calculate();
            }).thenWaitUntil(() -> run.plan()).thenSucceed();
    }
}
