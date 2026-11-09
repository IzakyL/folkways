package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.core.work.api.Manifest;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
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
public final class RouteOrderGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "route_order");
    private static final ItemSpec BREAD = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));


    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(RouteOrderGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void oneWorkersStepsFedFromOneStoreAreFetchedInOneVisit(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        List<String> route = route(f, f.target, 3);
        helper.assertTrue(route.equals(List.of("take", "take", "take", "put", "put", "put", "C0", "C1", "C2")),
            "every take at the source comes before every put at the target, not back and forth: " + route);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void stepsWithNoStoreAtHandFetchEverythingBeforeWalkingOut(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        List<String> route = route(f, f.source.at(f.source.cell().offset(3, 0, 4)), 3);
        helper.assertTrue(route.equals(List.of("take", "take", "take", "C0", "C1", "C2")),
            "the fetches are made together before the work: " + route);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void onlyReachingIntoTheSameStoreTheSameWayJoins(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var source = TransferNode.Endpoint.store(new Stash(f.source));
        var target = TransferNode.Endpoint.store(new Stash(f.target));
        var pack = TransferNode.Endpoint.pack();
        var goods = new Manifest(List.of(new ItemStack(Items.BREAD)), List.of());
        TransferNode take = transfer(source, pack, goods, f.source);
        TransferNode takeAgain = transfer(source, pack, goods, f.source);
        TransferNode putBack = transfer(pack, source, goods, f.source);
        TransferNode takeElsewhere = transfer(target, pack, goods, f.target);
        helper.assertTrue(takeAgain.joins(take), "a second take from the same store is the same reach");
        helper.assertTrue(!putBack.joins(take), "putting back is not taking");
        helper.assertTrue(!takeElsewhere.joins(take), "another store is another reach");
        helper.assertTrue(!Nodes.done(take.spec()).joins(take), "work joins nothing unless it says so");
        helper.succeed();
    }

    private static TransferNode transfer(TransferNode.Endpoint from, TransferNode.Endpoint to, Manifest goods,
            WorldPos at) {
        return new TransferNode(UUID.randomUUID(), from, to, goods, Stances.at(at), new WorkSite.AtBlock(at));
    }

    // Plans a chain of steps at one place for one worker, each needing one bread from the source, and names the
    // worker's route: takes, puts and the steps in order.
    private static List<String> route(PlanningFixture f, WorldPos at, int steps) {
        List<Node> nodes = new ArrayList<>();
        List<Before> order = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            Node node = Nodes.done(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at),
                Stances.at(at), Workload.Once.of(1)).needs(new Need(BREAD, 1)).done());
            if (!nodes.isEmpty()) {
                order.add(new Before(nodes.getLast().id(), node.id()));
            }
            nodes.add(node);
        }
        Grown goal = new Grown(nodes, order).byOneWorker();
        Stock stock = new Stock(List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.BREAD, 8))),
            Map.of(new Stash(f.source), 26, new Stash(f.target), 27));
        var solved = new PlanningFixture.Rounds().solve(f.situation(stock, List.of()), f.crew, List.of(goal),
            List.of(), 0);
        var tour = solved.schedule().tours().get(f.worker.resident().id());
        List<String> named = new ArrayList<>();
        List<UUID> ids = nodes.stream().map(Node::id).toList();
        for (UUID id : tour == null ? List.<UUID>of() : tour.nodes()) {
            Node node = solved.weave().vertices().get(id).node();
            named.add(node instanceof TransferNode transfer
                ? transfer.spec().site().equals(new WorkSite.AtBlock(f.source)) ? "take" : "put"
                : "C" + ids.indexOf(id));
        }
        return named;
    }
}
