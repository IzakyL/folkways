package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.core.definitions.AEBlocks;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.shell.crafting.PatternTimes;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
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
        helper.setBlock(new BlockPos(2, 2, 3), AEBlocks.CRAFTING_STORAGE_1K.block());
        helper.setBlock(new BlockPos(2, 2, 4), AEBlocks.PATTERN_PROVIDER.block());
        helper.setBlock(new BlockPos(2, 2, 5), AEBlocks.MOLECULAR_ASSEMBLER.block());
        var recipe = helper.getLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
            .filter(holder -> holder.id().equals(ResourceLocation.withDefaultNamespace("oak_planks")))
            .findFirst().orElseThrow();
        ItemStack[] inputs = new ItemStack[9];
        Arrays.fill(inputs, ItemStack.EMPTY);
        inputs[0] = new ItemStack(Items.OAK_LOG);
        ItemStack pattern = PatternDetailsHelper.encodeCraftingPattern(recipe, inputs,
            new ItemStack(Items.OAK_PLANKS, 4), false, false);
        var provider = (PatternProviderBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(new BlockPos(2, 2, 4)));
        provider.getLogic().getPatternInv().setItemDirect(0, pattern);
        return rig;
    }

    private static Colony colony(WorldPos at) {
        UUID id = UUID.randomUUID();
        Map<ResourceLocation, CompoundTag> kept = new HashMap<>();
        return Proxies.of(Colony.class, (proxy, method, args) -> switch (method.getName()) {
            case "id" -> id;
            case "kept" -> kept.getOrDefault(args[0], new CompoundTag());
            case "keep" -> { kept.put((ResourceLocation) args[0],
                ((CompoundTag) args[1]).copy()); yield null; }
            case "service" -> Optional.empty();
            case "storeRules" -> List.of();
            case "storesIn" -> List.of(at);
            default -> throw new AssertionError(method.getName());
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 1000)
    public static void workshopSubmitsOnlyAtDeviceAndCollectsCompletedCraft(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            rig.stock(Items.OAK_LOG, 8);
            NetworkPort port = rig.port();
            var crafting = port.node().getGrid().getCraftingService();
            helper.assertTrue(crafting.isCraftable(AEItemKey.of(Items.OAK_PLANKS)) && !crafting.getCpus().isEmpty(),
                "native provider advertises the pattern and the native crafting CPU is formed");
            WorldPos at = port.identity().home();
            Tally tally = new Tally();
            var cpus = crafting.getCpus().stream().map(cpu -> tally.snapshot(cpu)).toList();
            var network = (Devices.NetworkKey) port.identity().key();
            var times = new PatternTimes(colony(at),
                Ae2Integration.OWNER);
            var calculations = new Calculations(times);
            Cleanup.on(helper, calculations::close);
            var wanted = Goods.specOf(new ItemStack(Items.OAK_PLANKS));
            helper.assertTrue(calculations.quotes.snapshot().find(network, wanted, 16).isEmpty(),
                "a cache miss offers no unvalidated crafting route and enqueues the exact quantity tier");
            helper.startSequence().thenWaitUntil(() -> {
                calculations.update(network, "test", port, Set.of(AEItemKey.of(Items.OAK_PLANKS)));
                helper.assertTrue(calculations.quotes.snapshot().find(network, wanted, 16).isPresent(),
                    "the server publishes a completed feasible native preview");
            }).thenExecute(() -> {
                Depot depot = new Depot(UUID.randomUUID(), new WorkSite.AtBlock(at), network, tally, colony(at),
                    List.of(), helper.getLevel().getGameTime(), Set.of(wanted), cpus,
                    List.of(new Depot.Device(at, Stances.WHEREVER, true, true, Optional.empty())),
                    calculations.quotes.snapshot(), Integer.MAX_VALUE);
                Produce want = Produce.of(Ae2Integration.OWNER, depot.site(), ItemSpec.of(Items.OAK_PLANKS), 16,
                    Produce.UNBOUNDED);
                var options = depot.options(want, Grown.of(want));
                helper.assertTrue(options.size() == 1 && rig.count(Items.OAK_LOG) == 8
                        && crafting.getCpus().stream().noneMatch(cpu -> cpu.isBusy()),
                    "options do no calculation, submission or stock mutation");
                var competingBefore = depot.options(want, Grown.of(want));
                var option = options.getFirst();
                var hold = option.holds().getFirst();
                helper.assertTrue(hold.take().isEmpty() && hold.estimatedWaitTicks() > 0,
                    "the accepted hold reserves a CPU and prices its expected wait");
                helper.assertTrue(competingBefore.getFirst().holds().getFirst().take()
                    .filter(Ae2Refusal.CPU_BUSY::equals).isPresent(),
                    "competing precomputed holds cannot double-book a CPU");
                var competing = depot.options(want, Grown.of(want));
                helper.assertTrue(competing.isEmpty(), "one reserved CPU cannot be promised a second time");
                OrderNode submit = (OrderNode) option.replacement().nodes().getFirst();
                TakeNode collect = (TakeNode) option.replacement().nodes().getLast();
                helper.assertTrue(submit.commit(helper.getLevel(), null) instanceof Outcome.Done,
                    "resident execution starts the asynchronous native calculation");
                helper.succeedWhen(() -> {
                    var refusal = collect.refusal(helper.getLevel());
                    helper.assertTrue(refusal.isEmpty(), "native calculation and submit succeed: " + refusal);
                    helper.assertTrue(collect.ready(helper.getLevel()), "native CPU and assembler finish the order");
                    var result = collect.commit(helper.getLevel(), null);
                    helper.assertTrue(result instanceof Outcome.Done done
                            && done.made().stream().mapToInt(ItemStack::getCount).sum() == 16,
                        "one take at the registered device hands over all crafted goods");
                    helper.assertTrue(rig.count(Items.OAK_PLANKS) == 0 && rig.count(Items.OAK_LOG) == 4,
                        "native crafting consumed four logs and the resident collected sixteen planks");
                    helper.assertTrue(collect.refusal(helper.getLevel()).isEmpty(),
                        "collecting the output must not make the completed order look canceled");
                    hold.release(Ending.DONE);
                    tally.observed(helper.getLevel().getGameTime() + 1);
                    tally.clean();
                });
            });
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void missingIngredientsRejectAsynchronouslyAndReleaseCpu(GameTestHelper helper) {
        NetworkRig rig = craftingRig(helper);
        helper.runAfterDelay(120, () -> {
            NetworkPort port = rig.port();
            var crafting = port.node().getGrid().getCraftingService();
            var cpu = crafting.getCpus().iterator().next();
            Tally tally = new Tally();
            var ticket = tally.ticket(UUID.randomUUID(), (Devices.NetworkKey) port.identity().key(), List.of(),
                helper.getLevel().getGameTime(), ItemSpec.of(Items.OAK_PLANKS), 16,
                List.of(tally.snapshot(cpu)), true);
            helper.assertTrue(ticket.take().isEmpty(), "the calculation has a reserved CPU");
            CraftOrder order = new CraftOrder(port.identity().home(), ItemSpec.of(Items.OAK_PLANKS), ticket,
                colony(port.identity().home()));
            helper.assertTrue(order.start(helper.getLevel()).isEmpty(), "calculation begins without blocking");
            helper.succeedWhen(() -> {
                helper.assertTrue(order.poll(helper.getLevel()).filter(Ae2Refusal.MISSING_INPUTS::equals).isPresent(),
                    "a simulated plan names the missing-input refusal");
                ticket.release(Ending.DROPPED);
                tally.clean();
                helper.assertTrue(tally.free(tally.snapshot(cpu)) && !cpu.isBusy(),
                    "replanning releases the reservation without submitting an impossible job");
            });
        });
    }
}
