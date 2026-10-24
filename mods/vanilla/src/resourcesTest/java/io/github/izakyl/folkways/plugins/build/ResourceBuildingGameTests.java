package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.resources.ResourceItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = io.github.izakyl.folkways.plugins.FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class ResourceBuildingGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ResourceBuildingGameTests.class); }

    @GameTest(template = "empty_large")
    public static void blueprintHaulsWoodResourcesAndPlacesExactPlankVariants(GameTestHelper helper) {
        build(helper, true);
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void vanillaPlacementAlsoConsumesWoodResources(GameTestHelper helper) {
        var installed = SchematicPlacement.adapter();
        SchematicPlacement.install(new VanillaSchematics());
        try { build(helper, false); } finally { SchematicPlacement.install(installed); }
        helper.succeed();
    }

    private static void build(GameTestHelper helper, boolean create) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(8, 2, 8));
        for (BlockPos pos : BlockPos.betweenClosed(anchor.offset(-3, -1, -3), anchor.offset(7, -1, 4)))
            level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        var blocks = List.of(Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.CHERRY_PLANKS);
        List<BlueprintBlock> cells = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            cells.add(new BlueprintBlock(new BlockPos(index, 0, 0), blocks.get(index).defaultBlockState()));
            level.setBlock(anchor.east(index), Blocks.AIR.defaultBlockState(), 3);
        }
        Blueprint blueprint = new Blueprint(UUID.randomUUID(), "resources", cells, BlueprintVolume.of(4, 1, 1).orElseThrow(), 0).confirm(level);
        helper.assertTrue(blueprint.confirm(level) == blueprint, "the target is resolved once");
        var order = BlueprintBuildOrder.create(blueprint.id(), "resources", WorldPos.of(level, anchor), Optional.empty(), "", 0);
        List<Grown> asked = new ArrayList<>();
        BuildSite site = new BuildSite(UUID.randomUUID(), new BuildSite.Asks() {
            public void submit(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension, Grown work,
                    java.util.function.BiConsumer<UUID, Ending> ended) { asked.add(work); }
            public void withdraw(UUID node) { }
        }, BuildTarget.of(order, blueprint), new TemporaryScaffolds(() -> { }));
        site.start(level, Set.of());
        site.tick(level);
        helper.assertTrue(!asked.isEmpty(), "the site requests builders");
        var source = new io.github.izakyl.folkways.core.api.terms.Stash(f.source);
        level.setBlock(f.source.cell(), Blocks.BARREL.defaultBlockState(), 3);
        var store = io.github.izakyl.folkways.core.api.terms.Containers.at(level, f.source.cell()).orElseThrow();
        store.setItem(0, new ItemStack(ResourceItems.WOOD.get(), 4));
        var stock = new io.github.izakyl.folkways.core.engine.plan.Stock(List.of(
            new io.github.izakyl.folkways.core.engine.plan.Stock.Holding(source, store.getItem(0))), Map.of(source, 26));
        var body = (io.github.izakyl.folkways.plugins.person.ResidentEntity) f.worker.body();
        body.setPos(net.minecraft.world.phys.Vec3.atBottomCenterOf(anchor.south(2)));
        var hand = f.crew.hands().getFirst();
        var licences = new ArrayList<>(hand.licences());
        licences.add(body.licences().of(BuildContent.trade()).orElseThrow());
        var crew = new io.github.izakyl.folkways.core.engine.plan.Crew(List.of(new io.github.izakyl.folkways.core.engine.plan.Crew.Hand(
            hand.who(), hand.at(), 6, 6, 1, licences, List.of(), Optional.empty())));
        var rounds = new PlanningFixture.Rounds();
        try {
            var solved = rounds.solve(f.situation(stock, List.of()), crew, asked, List.of(), 0);
            var tour = solved.schedule().tours().get(hand.id());
            helper.assertTrue(tour != null, "resource-only stores produce a scheduled build: " + solved.diagnosis());
            for (UUID id : tour.nodes()) {
                var vertex = solved.weave().vertices().get(id);
                Node node = vertex.node();
                Worker worker = PlanningFixture.placed(f.worker, vertex.plan(), List.of(new Worker.Store(f.source, store)));
                if (node.spec().workload() instanceof Workload.Within shift) {
                    for (int step = 0; step < 12 && !site.complete(); step++) {
                        Workload.Within.Turn turn = shift.next(level, worker);
                        if (!(turn instanceof Workload.Within.Turn.Piece piece)) break;
                        Outcome outcome = Commit.run(level, piece.node(), worker);
                        helper.assertTrue(outcome instanceof Outcome.Done, "resource-backed building piece commits: " + outcome);
                        shift.ended(level, piece.node(), Ending.DONE);
                        site.tick(level);
                    }
                } else {
                    helper.assertTrue(Commit.run(level, node, worker) instanceof Outcome.Done, "physical resource haul succeeds");
                }
            }
            helper.assertTrue(site.complete() && store.isEmpty() && f.worker.pack().isEmpty(), "four resources build four exact blocks");
            for (int index = 0; index < blocks.size(); index++) helper.assertTrue(level.getBlockState(anchor.east(index)).is(blocks.get(index)),
                "blueprint plank variant " + index + " is preserved");
            helper.assertTrue(SchematicPlacement.adapter().getClass().getName().contains(create ? "CreateSchematicPlacement" : "VanillaSchematics"),
                "resident building uses the Create cannon placement adapter");
        } finally { rounds.closed(); site.close(); }
    }
}
