package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.testing.NetworkServiceFixture;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ServiceInputsGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_core",
        "service_inputs_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (Boolean.getBoolean("folkways.ae2.laborTests") || Boolean.getBoolean("folkways.rs2.laborTests")) {
            event.register(ServiceInputsGameTests.class);
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void serviceDrawsOnlyItsPackAndRestoresFailedDraws(GameTestHelper helper) {
        var fixture = new NetworkServiceFixture(helper, List.of());
        var site = WorkSite.at(helper.getLevel(), fixture.person.blockPosition());
        var stances = Stances.at(helper.getLevel(), fixture.person.blockPosition());
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, site, stances, Workload.Once.of(1))
            .needs(new Need(ItemSpec.of(Items.IRON_INGOT), 2), new Need(ItemSpec.of(Items.COPPER_INGOT), 1)).done();
        AtomicReference<Outcome> result = new AtomicReference<>(Outcome.done());
        Node original = new Node() {
            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public Outcome commit(ServerLevel level, Worker who) {
                helper.assertValueEqual(who.supplied().stream().mapToInt(ItemStack::getCount).sum(), 3,
                    "the original needs are supplied despite being hidden from the network plan");
                return result.get();
            }
        };
        var pack = new SimpleContainer(4);
        pack.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        var store = new SimpleContainer(new ItemStack(Items.COPPER_INGOT, 5));
        var worker = TestWorker.of(fixture.person, fixture.person.resident(), pack)
            .within(new Worker.Store(site.where(), store));
        Node bound = ServiceNode.bind(original, fixture.person.getUUID());
        helper.assertTrue(Commit.run(helper.getLevel(), bound, worker) instanceof Outcome.Failed,
            "nearby store goods cannot fill missing network inputs");
        helper.assertValueEqual(Goods.countIn(pack, ItemSpec.of(Items.IRON_INGOT)), 2L,
            "a partial draw restores earlier needs");
        helper.assertValueEqual(store.getItem(0).getCount(), 5, "the nearby store is untouched");
        pack.setItem(1, new ItemStack(Items.COPPER_INGOT));
        result.set(Outcome.failed(LaborRefusal.GOODS_GONE));
        helper.assertTrue(Commit.run(helper.getLevel(), bound, worker) instanceof Outcome.Failed,
            "a failed workshop commit restores the supplied pack goods");
        helper.assertValueEqual(Goods.countIn(pack, ItemSpec.of(Items.COPPER_INGOT)), 1L,
            "failed commit restores copper");
        result.set(Outcome.done());
        helper.assertTrue(Commit.run(helper.getLevel(), bound, worker) instanceof Outcome.Done,
            "a complete pack supplies the exact inputs");
        helper.assertTrue(pack.isEmpty(), "successful work consumes its needs");
        helper.assertTrue(spec.needs().stream().noneMatch(Need::carried), "ordinary workshop needs stay unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    @SuppressWarnings("unchecked")
    public static void chainStagesSurviveRegeneratedWorkAfterLoad(GameTestHelper helper) {
        BlockPos terminal = new BlockPos(2, 2, 3);
        var fixture = new NetworkServiceFixture(helper, List.of(terminal));
        fixture.enable(true);
        helper.setBlock(terminal, Blocks.BARREL);
        AtomicReference<Grown> submitted = new AtomicReference<>();
        AtomicReference<BiConsumer<UUID, Ending>> ended = new AtomicReference<>();
        Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[]{Colony.class},
            (proxy, method, args) -> {
                if (method.getName().equals("submit")) {
                    submitted.set((Grown) args[2]);
                    ended.set((BiConsumer<UUID, Ending>) args[3]);
                    return null;
                }
                return method.invoke(fixture.colony, args);
            });
        var recipes = new ColonyRecipes(colony);
        recipes.refresh(helper.getLevel().getServer());
        var entry = recipes.entries().stream().filter(value -> value.workshop().id()
            .equals(NetworkServiceFixture.OWNER)).findFirst().orElseThrow();
        var port = StorePorts.at(helper.getLevel(), helper.absolutePos(terminal)).orElseThrow();
        Object network = new Object();
        var access = List.of(new NetworkBuffers.Access(port, network, port));
        WorldPos at = port.identity().home();
        var buffers = new NetworkBuffers(colony, OWNER, helper.getLevel().getServer(), recipes);
        try {
            helper.assertTrue(buffers.accept(at, network, entry.reference(),
                List.of(new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.COPPER_INGOT))),
                "the chain batch is accepted");
            buffers.tick(access);
            Grown first = submitted.get();
            helper.assertValueEqual(first.nodes().size(), 4, "fetch, two workshop steps, delivery");
            for (int index = 1; index < first.nodes().size(); index++) {
                helper.assertTrue(first.links().contains(new Before(first.nodes().get(index - 1).id(),
                    first.nodes().get(index).id())), "the plain chain retains each dependency");
            }
            ended.get().accept(first.nodes().get(0).id(), Ending.DONE);
            ended.get().accept(first.nodes().get(1).id(), Ending.DONE);
        } finally {
            buffers.close();
        }
        // A new buffer reads only persisted data: its transient Grown is absent and node UUIDs are regenerated.
        var restored = new NetworkBuffers(colony, OWNER, helper.getLevel().getServer(), recipes);
        try {
            helper.assertTrue(restored.keeps(fixture.person.getUUID()).equals(
                List.of(new Amount(ItemSpec.of(Items.EMERALD), 1))),
                "before the first tick, restored keeps protects the intermediate of completed stage one");
            restored.tick(access);
            Grown next = submitted.get();
            helper.assertValueEqual(next.nodes().size(), 2, "only the second workshop step and delivery remain");
            helper.assertTrue(next.nodes().getFirst().spec().leaves().stream().anyMatch(amount ->
                amount.spec().equals(ItemSpec.of(Items.EMERALD)) && amount.count() == -1),
                "persisted stage two still denotes the emerald-consuming step");
            helper.assertTrue(next.links().contains(new Before(
                next.nodes().getFirst().id(), next.nodes().getLast().id())),
                "the regenerated workshop step still precedes delivery");
            ended.get().accept(next.nodes().getFirst().id(), Ending.DONE);
            ended.get().accept(next.nodes().getLast().id(), Ending.DONE);
            restored.tick(access);
            helper.assertTrue(colony.kept(OWNER).getList("batches", Tag.TAG_COMPOUND).isEmpty(),
                "the restored batch finishes at the same stage boundary");
        } finally {
            restored.close();
        }
        helper.succeed();
    }
}
