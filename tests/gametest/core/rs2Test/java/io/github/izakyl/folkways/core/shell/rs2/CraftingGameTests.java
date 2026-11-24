package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import com.refinedmods.refinedstorage.common.api.autocrafting.Autocrafter;
import com.refinedmods.refinedstorage.common.api.support.network.AbstractNetworkNodeContainerBlockEntity;
import com.refinedmods.refinedstorage.common.autocrafting.CraftingPatternState;
import com.refinedmods.refinedstorage.common.autocrafting.PatternState;
import com.refinedmods.refinedstorage.common.autocrafting.patterngrid.PatternType;
import com.refinedmods.refinedstorage.common.content.Blocks;
import com.refinedmods.refinedstorage.common.content.DataComponents;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.inventory.internal.StoreTransfers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.shell.crafting.CraftRefusals;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDepot;
import io.github.izakyl.folkways.core.shell.crafting.OrderNode;
import io.github.izakyl.folkways.core.shell.crafting.OrderedOutputs;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import io.github.izakyl.folkways.core.shell.crafting.TakeNode;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class CraftingGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CraftingGameTests.class);
    }

    static NetworkRig craftingRig(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        BlockPos pos = new BlockPos(2, 3, 2);
        helper.setBlock(pos, Blocks.INSTANCE.getAutocrafter().getDefault());
        ItemStack pattern = new ItemStack(com.refinedmods.refinedstorage.common.content.Items.INSTANCE.getPattern());
        pattern.set(DataComponents.INSTANCE.getPatternState(),
            new PatternState(UUID.randomUUID(), PatternType.CRAFTING));
        pattern.set(DataComponents.INSTANCE.getCraftingPatternState(), new CraftingPatternState(false,
            CraftingInput.ofPositioned(1, 1, List.of(new ItemStack(Items.OAK_LOG)))));
        var provider = (AbstractNetworkNodeContainerBlockEntity<?>) helper.getLevel()
            .getBlockEntity(helper.absolutePos(pos));
        var container = (Autocrafter) provider.getContainerProvider().getContainers().iterator().next();
        container.getPatternContainer().setItem(0, pattern);
        return rig;
    }

    static List<Tally.Executor> executors(NetworkPort port, Tally tally) {
        var crafting = port.crafting();
        var pattern = crafting.getPatterns().iterator().next();
        var provider = crafting.getProviderByPattern(pattern);
        return List.of(tally.snapshot(provider, provider.getSteps(pattern), !crafting.getStatuses().isEmpty(),
            Set.of(Goods.specOf(new ItemStack(Items.OAK_PLANKS)))));
    }

    static OrderedOutputs orderedOutputs(GameTestHelper helper, Colony colony) {
        return new OrderedOutputs(colony, helper.getLevel().getServer(), NetworkPresence.ORDERED_OUTPUTS);
    }

    static Colony colony(WorldPos at) {
        return colony(List.of(at));
    }

    static Colony colony(List<WorldPos> admitted) {
        UUID id = UUID.randomUUID();
        Map<ResourceLocation, CompoundTag> kept = new HashMap<>();
        return Proxies.of(Colony.class, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "kept" -> kept.getOrDefault(args[0], new CompoundTag());
            case "keep" -> { kept.put((ResourceLocation) args[0],
                ((CompoundTag) args[1]).copy()); yield null; }
            case "service" -> Optional.empty();
            case "storeRules" -> List.of();
            case "storesIn" -> admitted;
            default -> throw new AssertionError(method.getName());
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void workshopSubmitsOnlyAtDeviceAndCollectsCompletedCraft(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 8);
            NetworkPort port = rig.port();
            var crafting = port.crafting();
            helper.assertTrue(crafting.getOutputs().contains(new ItemResource(Items.OAK_PLANKS))
                    && !crafting.getPatterns().isEmpty(),
                "native provider advertises the pattern and the native autocrafter is connected");
            WorldPos at = port.identity().home();
            Tally tally = new Tally();
            var executors = executors(port, tally);
            var network = (Rs2Devices.NetworkKey) port.identity().key();
            var times = new PatternTimes(colony(at),
                Rs2Integration.OWNER);
            var calculations = new Calculations(times);
            Cleanup.on(helper, calculations::close);
            var wanted = Goods.specOf(new ItemStack(Items.OAK_PLANKS));
            helper.assertTrue(calculations.quotes.snapshot().find(network, wanted, 16).isEmpty(),
                "a cache miss offers no unvalidated crafting route and enqueues the exact quantity tier");
            helper.startSequence().thenWaitUntil(() -> {
                calculations.update(network, "test", port, Set.of(new ItemResource(Items.OAK_PLANKS)));
                helper.assertTrue(calculations.quotes.snapshot().find(network, wanted, 16).isPresent(),
                    "the server publishes a completed feasible native preview");
                helper.assertTrue(calculations.quotes.snapshot().find(network, wanted, 16).orElseThrow().ticks() == 880,
                    "RS2 TaskPlan prices four pattern iterations rather than sixteen output items");
            }).thenExecute(() -> {
                Colony colony = colony(at);
                Depot depot = new Depot(UUID.randomUUID(), new WorkSite.AtBlock(at), network, tally, colony,
                    orderedOutputs(helper, colony), Set.of(wanted), executors,
                    List.of(new NetworkDepot.Device(at, Stances.WHEREVER, true, true, Optional.empty(), false,
                        Optional.empty())), calculations.quotes.snapshot(), Integer.MAX_VALUE);
                Produce want = Produce.of(Rs2Integration.OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 16,
                    Produce.UNBOUNDED);
                var options = depot.options(want, Grown.of(want));
                helper.assertTrue(options.size() == 1 && rig.count(Items.OAK_LOG) == 8
                        && crafting.getStatuses().isEmpty(),
                    "options do no calculation, submission or stock mutation");
                var competingBefore = depot.options(want, Grown.of(want));
                var option = options.getFirst();
                var hold = option.holds().getFirst();
                helper.assertTrue(hold.take().isEmpty() && hold.estimatedWaitTicks() > 0,
                    "the accepted hold reserves an executor and prices its expected wait");
                helper.assertTrue(competingBefore.getFirst().holds().getFirst().take()
                    .filter(CraftRefusals.RS2.busy()::equals).isPresent(),
                    "competing precomputed holds cannot double-book an executor");
                var competing = depot.options(want, Grown.of(want));
                helper.assertTrue(competing.isEmpty(), "one reserved executor cannot be promised a second time");
                OrderNode submit = (OrderNode) option.replacement().nodes().getFirst();
                TakeNode collect = (TakeNode) option.replacement().nodes().getLast();
                helper.assertTrue(submit.commit(helper.getLevel(), null) instanceof Outcome.Done,
                    "resident execution starts the asynchronous native calculation");
                helper.succeedWhen(() -> {
                    var refusal = collect.refusal(helper.getLevel());
                    helper.assertTrue(refusal.isEmpty(), "native calculation and submit succeed: " + refusal);
                    helper.assertTrue(collect.ready(helper.getLevel()), "native autocrafter finish the order");
                    var result = collect.commit(helper.getLevel(), null);
                    helper.assertTrue(result instanceof Outcome.Done done
                            && done.made().stream().mapToInt(ItemStack::getCount).sum() == 16,
                        "one take at the registered device hands over all crafted goods");
                    helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0 && rig.count(Items.OAK_LOG) == 4,
                        "native crafting consumed four logs and the resident collected sixteen planks");
                    helper.assertTrue(collect.refusal(helper.getLevel()).isEmpty(),
                        "collecting the output must not make the completed order look canceled");
                    hold.release(Ending.DONE);
                    tally.clean();
                });
            });
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void ordinaryConsumerCannotTakeAnotherNodesCraftedOutput(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var labor = new LaborFixture(colony, level);
        Cleanup.on(helper, () -> {
            labor.close();
            colony.raze();
        });
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 4);
            NetworkPort port = rig.port();
            WorldPos at = port.identity().home();
            WorldPos other = WorldPos.of(level, helper.absolutePos(NetworkRig.OTHER));
            colony.stores(ResourceLocation.withDefaultNamespace("rs2_order_test"), level.dimension(), List.of(at, other));
            var secondPort = Rs2Devices.at(level, other.block(level)).orElseThrow();
            var item = ItemSpec.of(Items.OAK_PLANKS);
            Tally tally = new Tally();
            var ticket = tally.ticket(colony.id(), (Rs2Devices.NetworkKey) port.identity().key(),
                item, 16, executors(port, tally));
            Cleanup.on(helper, () -> {
                ticket.release(Ending.DROPPED);
                tally.clean();
            });
            helper.assertTrue(ticket.take().isEmpty(), "the first node owns the native executor ticket");
            var outputs = orderedOutputs(helper, colony);
            var order = new CraftOrder(at, item, ticket, colony, outputs);
            var collect = Rs2Devices.COLLECTION.take(at, Stances.WHEREVER, ticket, order, colony);
            helper.assertTrue(order.start(level).isEmpty(), "the first node orders sixteen planks");
            helper.startSequence().thenWaitUntil(() -> {
                var refusal = collect.refusal(level);
                helper.assertTrue(refusal.isEmpty(), "the native order succeeds: " + refusal);
                helper.assertTrue(collect.ready(level), "the output has arrived and the first node has yet to take it");
            }).thenExecute(() -> rig.stock(Items.OAK_PLANKS, 1)).thenWaitUntil(() -> {
                colony.inventory().ensureObserved();
                labor.tick();
                var stores = colony.inventory().snapshot().stores();
                helper.assertTrue(stores.size() == 1 && stores.values().stream().anyMatch(store ->
                    store.labor() && store.aliases().containsAll(List.of(at, other))
                        && store.entries().stream().filter(entry -> Goods.matches(entry.prototype(), item))
                            .mapToLong(entry -> entry.count()).sum() == 1),
                    "ordinary stock sees the unrelated plank once across both network access points");
            }).thenExecute(() -> {
                var rules = colony.storeRules(other);
                helper.assertTrue(!StoreTransfers.take(secondPort, level, List.of(new Pull.Matching(item, 16)),
                    rules, false).done(), "the second node cannot take the first node's sixteen planks");
                helper.assertTrue(StoreTransfers.take(secondPort, level, List.of(new Pull.Matching(item, 1)),
                    rules, false).done(), "the second node can take the unrelated plank");
                var result = collect.commit(level, null);
                helper.assertTrue(result instanceof Outcome.Done done
                        && done.made().stream().mapToInt(ItemStack::getCount).sum() == 16,
                    "the first node collects its complete order from the private buffer");
                ticket.release(Ending.DONE);
                tally.clean();
            }).thenSucceed();
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void missingIngredientsRejectAsynchronouslyAndReleaseExecutor(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            NetworkPort port = rig.port();
            var crafting = port.crafting();
            Tally tally = new Tally();
            var executor = executors(port, tally).getFirst();
            var ticket = tally.ticket(UUID.randomUUID(), (Rs2Devices.NetworkKey) port.identity().key(),
                ItemSpec.of(Items.OAK_PLANKS), 16, List.of(executor));
            helper.assertTrue(ticket.take().isEmpty(), "the calculation has a reserved executor");
            Colony colony = colony(port.identity().home());
            CraftOrder order = new CraftOrder(port.identity().home(), ItemSpec.of(Items.OAK_PLANKS), ticket,
                colony, orderedOutputs(helper, colony));
            helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "calculation begins without blocking");
            helper.succeedWhen(() -> {
                helper.assertTrue(order.poll(helper.getLevel()).filter(CraftRefusals.RS2.missingInputs()::equals).isPresent(),
                    "a simulated plan names the missing-input refusal");
                ticket.release(Ending.DROPPED);
                tally.clean();
                helper.assertTrue(tally.free((Rs2Devices.NetworkKey) port.identity().key(), executor)
                        && crafting.getStatuses().isEmpty(),
                    "replanning releases the reservation without submitting an impossible job");
            });
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 800)
    public static void withdrawnDeviceCancelsItsTaskAndAllowsReplanning(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 128);
            var port = rig.port();
            var admitted = new java.util.ArrayList<>(List.of(port.identity().home()));
            var tally = new Tally();
            var network = (Rs2Devices.NetworkKey) port.identity().key();
            var executor = executors(port, tally).getFirst();
            var ticket = tally.ticket(UUID.randomUUID(), network, ItemSpec.of(Items.OAK_PLANKS), 512, List.of(executor));
            helper.assertTrue(ticket.take().isEmpty(), "the order owns its reservation");
            Colony colony = colony(admitted);
            CraftOrder order = new CraftOrder(port.identity().home(), ItemSpec.of(Items.OAK_PLANKS), ticket,
                colony, orderedOutputs(helper, colony));
            helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "preview starts at the admitted device");
            var withdrawn = new java.util.concurrent.atomic.AtomicBoolean();
            helper.succeedWhen(() -> {
                if (!withdrawn.get()) {
                    helper.assertTrue(order.poll(helper.getLevel()).isEmpty(), "submission has no refusal");
                    helper.assertTrue(!port.crafting().getStatuses().isEmpty(), "the native task is submitted");
                    admitted.clear();
                    withdrawn.set(true);
                }
                helper.assertTrue(order.poll(helper.getLevel()).filter(CraftRefusals.RS2.noDevice()::equals).isPresent(),
                    "device withdrawal refuses the order so the planner can replace it");
                helper.assertTrue(port.crafting().getStatuses().isEmpty(), "only the owned task is canceled");
                ticket.release(Ending.DROPPED);
                tally.clean();
                helper.assertTrue(tally.free(network, executor), "failure releases the executor reservation");
                helper.assertTrue(rig.count(Items.OAK_LOG) * 4 + rig.count(Items.OAK_PLANKS) == 512,
                    "cancellation returns the unconsumed inputs without losing goods");
            });
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void networkChangeRejectsAnUnsubmittedPreview(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 8);
            var port = rig.port();
            var tally = new Tally();
            var executor = executors(port, tally).getFirst();
            var network = (Rs2Devices.NetworkKey) port.identity().key();
            var ticket = tally.ticket(UUID.randomUUID(), network, ItemSpec.of(Items.OAK_PLANKS), 16, List.of(executor));
            helper.assertTrue(ticket.take().isEmpty(), "the preview has a reservation");
            Colony colony = colony(port.identity().home());
            CraftOrder order = new CraftOrder(port.identity().home(), ItemSpec.of(Items.OAK_PLANKS), ticket,
                colony, orderedOutputs(helper, colony));
            helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "preview starts asynchronously");
            var crafting = port.crafting();
            helper.setBlock(NetworkRig.CABLE, net.minecraft.world.level.block.Blocks.AIR);
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(order.poll(helper.getLevel()).filter(CraftRefusals.RS2.noDevice()::equals).isPresent(),
                    "a changed network cannot receive an order from an old preview");
                helper.assertTrue(crafting.getStatuses().isEmpty(), "no task is submitted to the old network");
                ticket.release(Ending.DROPPED);
                tally.clean();
                helper.assertTrue(tally.free(network, executor), "replanning can reserve the executor again");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void exceptionalPreviewRefusesAndReleasesItsHold(GameTestHelper helper) {
        failedNativeRequest(helper, false);
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void rejectedSubmissionRefusesAndReleasesItsHold(GameTestHelper helper) {
        failedNativeRequest(helper, true);
    }

    private static void failedNativeRequest(GameTestHelper helper, boolean rejectSubmission) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 8);
            var port = rig.port();
            var original = port.node().getNetwork();
            var nativeCrafting = port.crafting();
            var nativeProvider = nativeCrafting.getProviderByPattern(nativeCrafting.getPatterns().iterator().next());
            var rejectedProvider = Proxies.of(PatternProvider.class, (proxy, method, args) -> {
                if (method.getName().equals("addTask")) {
                    throw new IllegalStateException("test submission failure");
                }
                return method.invoke(nativeProvider, args);
            });
            var crafting = Proxies.of(AutocraftingNetworkComponent.class, (proxy, method, args) -> {
                if (method.getName().equals("getPreview") && !rejectSubmission) {
                    return CompletableFuture.failedFuture(new IllegalStateException("test preview failure"));
                }
                if (method.getName().equals("getProviderByPattern") && rejectSubmission) {
                    return rejectedProvider;
                }
                return method.invoke(nativeCrafting, args);
            });
            // Decorate only the boundary under test; all storage, topology and native preview work stay real.
            Network network = Proxies.of(Network.class, (proxy, method, args) -> {
                if (method.getName().equals("getComponent") && args[0] == AutocraftingNetworkComponent.class) {
                    return crafting;
                }
                return method.invoke(original, args);
            });
            port.node().setNetwork(network);
            var tally = new Tally();
            var executor = executors(port, tally).getFirst();
            var key = (Rs2Devices.NetworkKey) port.identity().key();
            var ticket = tally.ticket(UUID.randomUUID(), key, ItemSpec.of(Items.OAK_PLANKS), 16, List.of(executor));
            Cleanup.on(helper, () -> {
                ticket.release(Ending.DROPPED);
                tally.clean();
                port.node().setNetwork(original);
            });
            helper.assertTrue(ticket.take().isEmpty(), "the request owns its executor reservation");
            Colony colony = colony(port.identity().home());
            var order = new CraftOrder(port.identity().home(), ItemSpec.of(Items.OAK_PLANKS), ticket,
                colony, orderedOutputs(helper, colony));
            helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "preview begins at the native device");
            helper.succeedWhen(() -> {
                var expected = rejectSubmission ? CraftRefusals.RS2.submissionFailed() : CraftRefusals.RS2.calculationFailed();
                helper.assertTrue(order.poll(helper.getLevel()).filter(expected::equals).isPresent(),
                    "the boundary failure produces its specific replanning refusal");
                ticket.release(Ending.DROPPED);
                tally.clean();
                helper.assertTrue(tally.free(key, executor) && nativeCrafting.getStatuses().isEmpty()
                        && rig.count(Items.OAK_LOG) == 8 && rig.count(Items.OAK_PLANKS) == 0,
                    "the failed request releases its hold without a native task or stock mutation");
            });
        });
    }

}
