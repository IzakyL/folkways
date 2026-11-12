package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.shell.crafting.ColonyRecipes;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.internal.Commit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Controlled submission boundary, with real residents, colony workshops, furnaces and transfer commits. */
public final class NetworkBufferChecks {
    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_core:buffer_recovery_test");
    private static final BlockPos TERMINAL = new BlockPos(2, 2, 3);
    private final GameTestHelper helper;
    public final NetworkServiceFixture fixture;
    public final Colony colony;
    public final ColonyRecipes recipes;
    public NetworkBuffers buffers;
    private final Object network = new Object();
    private final StorePort port;
    private final List<Grown> submitted = new ArrayList<>();
    private final List<BiConsumer<UUID, Ending>> endings = new ArrayList<>();

    @SuppressWarnings("unchecked")
    public NetworkBufferChecks(GameTestHelper helper, boolean furnace) {
        this.helper = helper;
        fixture = furnace ? NetworkServiceFixture.furnace(helper, List.of(TERMINAL))
            : new NetworkServiceFixture(helper, List.of(TERMINAL));
        helper.setBlock(TERMINAL, Blocks.BARREL);
        fixture.enable(true);
        colony = Proxies.of(Colony.class, (proxy, method, args) -> {
            if (method.getName().equals("submit")) {
                submitted.add((Grown) args[2]);
                endings.add((BiConsumer<UUID, Ending>) args[3]);
                return null;
            }
            return method.invoke(fixture.colony, args);
        });
        recipes = new ColonyRecipes(colony);
        port = StorePorts.at(helper.getLevel(), helper.absolutePos(TERMINAL)).orElseThrow();
        buffers = new NetworkBuffers(colony, OWNER, helper.getLevel().getServer(), recipes);
        Cleanup.on(helper, () -> buffers.close());
    }

    public void refresh() {
        recipes.refresh(helper.getLevel().getServer());
    }

    public void tick() {
        buffers.tick(List.of(new NetworkBuffers.Access(port, network, port)));
    }

    public String recipe(Item output, Item input) {
        var found = recipes.entries().stream().filter(entry -> entry.recipe().outputs().stream()
            .anyMatch(amount -> Goods.includes(ItemSpec.of(output), amount.spec())))
            .filter(entry -> entry.recipe().inputs().stream()
                .anyMatch(need -> Goods.includes(need.spec(), ItemSpec.of(input))))
            .findFirst();
        helper.assertTrue(found.isPresent(), "workshop recipe is published: " + output + " from " + input
            + "; candidates=" + recipes.entries().size());
        return found.orElseThrow().reference();
    }

    public void accept(String recipe, List<ItemStack> inputs) {
        helper.assertTrue(buffers.accept(port.identity().home(), network, recipe, inputs), "batch accepted");
    }

    public void lostWorker(boolean replacement, boolean dead) {
        refresh();
        accept(recipe(Items.DIAMOND, Items.IRON_INGOT),
            List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.COPPER_INGOT)));
        tick();
        UUID previous = fixture.person.id();
        if (replacement) {
            fixture.addServiceResident();
        }
        if (dead) {
            fixture.person.discard();
        } else {
            fixture.enable(false);
        }
        tick();
        tick();
        if (replacement) {
            helper.assertTrue(submitted.size() == 2 && submitted.getLast().nodes().stream()
                .allMatch(node -> node.worker().filter(previous::equals).isEmpty()),
                "an unavailable worker is replaced even while old submitted nodes exist");
        } else {
            helper.assertTrue(colony.kept(OWNER).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                "an impossible batch releases its ledger");
            Container returned = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(TERMINAL));
            helper.assertTrue(Goods.countIn(returned, ItemSpec.of(Items.IRON_INGOT)) == 2
                && Goods.countIn(returned, ItemSpec.of(Items.COPPER_INGOT)) == 1,
                "all uncollected inputs return exactly once");
        }
    }

    public void lostWorkerAfterCollection() {
        refresh();
        accept(recipe(Items.DIAMOND, Items.IRON_INGOT),
            List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.COPPER_INGOT)));
        tick();
        var fetch = submitted.getFirst().nodes().getFirst();
        helper.assertTrue(Commit.run(helper.getLevel(), fetch, TestWorker.of(fixture.person)) instanceof Outcome.Done,
            "buffer inputs have moved into the first resident's pack before fetch settlement");
        fixture.addServiceResident();
        fixture.enable(false);
        tick();
        tick();
        helper.assertTrue(submitted.size() == 1 && colony.kept(OWNER).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
            "collected inputs cannot be fetched a second time by a replacement worker");
        helper.assertTrue(fixture.person.pack().count(Items.IRON_INGOT) == 2,
            "already collected goods stay with their physical owner for ordinary unloading");
    }

    public void deletedRecipe() {
        refresh();
        accept(recipe(Items.DIAMOND, Items.IRON_INGOT),
            List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.COPPER_INGOT)));
        tick();
        fixture.colony.publish(NetworkServiceFixture.OWNER, helper.getLevel().dimension(), List.of());
        refresh();
        tick();
        helper.assertTrue(colony.kept(OWNER).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
            "deleted recipes cannot pin an accepted batch forever");
        Container returned = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(TERMINAL));
        helper.assertTrue(Goods.countIn(returned, ItemSpec.of(Items.IRON_INGOT)) == 2,
            "recipe removal returns the buffered input");
    }

    public void furnaceDependencies() {
        refresh();
        fixture.addServiceResident();
        accept(recipe(Items.IRON_INGOT, Items.RAW_IRON), List.of(new ItemStack(Items.RAW_IRON)));
        accept(recipe(Items.GOLD_INGOT, Items.RAW_GOLD), List.of(new ItemStack(Items.RAW_GOLD)));
        tick();
        helper.assertTrue(submitted.size() == 2, "two independent workers have furnace batches");
        var first = submitted.getFirst();
        var second = submitted.getLast();
        helper.assertTrue(!second.follows().isEmpty() && second.follows().stream().allMatch(link ->
            first.nodes().stream().anyMatch(node -> node.id().equals(link.from()))),
            "the later booking waits for the first booking's native extraction");
        helper.assertTrue(second.nodes().stream().allMatch(node -> second.follows().stream()
            .anyMatch(link -> link.to().equals(node.id()))), "all second-batch work respects Hold.after prerequisites");
    }

    public void startSmeltingAndReload() {
        refresh();
        accept(recipe(Items.IRON_INGOT, Items.RAW_IRON), List.of(new ItemStack(Items.RAW_IRON)));
        tick();
        Grown graph = submitted.getFirst();
        finish(0, graph.nodes().getFirst());
        fixture.person.pack().insert(new ItemStack(Items.COAL));
        fixture.clearStoredFuel();
        for (Node node : graph.nodes()) {
            if (node.spec().doing().getPath().equals("loading") || node.spec().doing().getPath().equals("fueling")) {
                Outcome result = Commit.run(helper.getLevel(), node, TestWorker.of(fixture.person));
                helper.assertTrue(result instanceof Outcome.Done, "real furnace step commits");
                node.changed(helper.getLevel(), NodeState.SETTLING);
                if (node.spec().doing().getPath().equals("fueling")) {
                    endings.getFirst().accept(node.id(), Ending.DONE);
                }
            }
        }
        helper.assertTrue(!furnace().getItem(0).isEmpty() && !furnace().getItem(1).isEmpty(),
            "save occurs after loading and fueling but before cooking settles");
        buffers.close();
        refresh();
        buffers = new NetworkBuffers(colony, OWNER, helper.getLevel().getServer(), recipes);
        tick();
        var resumed = submitted.getLast();
        Node loading = resumed.nodes().stream().filter(node -> node.spec().doing().getPath().equals("loading"))
            .findFirst().orElseThrow();
        helper.assertTrue(loading.spec().needs().isEmpty() && loading.spec().leaves().isEmpty(),
            "a committed load resumes only its settlement and requests no spent inputs");
        helper.assertTrue(resumed.nodes().stream().noneMatch(node -> node.spec().doing().getPath().equals("fueling")),
            "completed fueling is not regenerated or re-reserved");
    }

    private Container furnace() {
        return (Container) helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(8, 2, 5)));
    }

    private void finish(int graph, Node node) {
        Outcome result = Commit.run(helper.getLevel(), node, TestWorker.of(fixture.person));
        helper.assertTrue(result instanceof Outcome.Done, "stage commits: " + node.spec().doing());
        ((Outcome.Done) result).made().forEach(stack -> fixture.person.pack().insert(stack));
        endings.get(graph).accept(node.id(), Ending.DONE);
    }

    public void completeReloadedSmelt() {
        Grown resumed = submitted.getLast();
        for (Node node : resumed.nodes()) {
            helper.assertTrue(node.ready(helper.getLevel()), "resumed step becomes ready after real furnace cooking");
        }
        int graph = submitted.size() - 1;
        for (Node node : resumed.nodes()) {
            finish(graph, node);
        }
        tick();
        Container returned = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(TERMINAL));
        helper.assertTrue(Goods.countIn(returned, ItemSpec.of(Items.IRON_INGOT)) == 1
            && fixture.person.pack().count(Items.RAW_IRON) == 0 && fixture.person.pack().count(Items.COAL) == 0
            && colony.kept(OWNER).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
            "reload delivers exactly one ingot with no raw input or fuel replay");
    }
}
