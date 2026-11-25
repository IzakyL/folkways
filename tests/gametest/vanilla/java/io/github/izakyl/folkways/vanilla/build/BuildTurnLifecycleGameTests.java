package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysVanillaMod.ID)
@PrefixGameTestTemplate(false)
public final class BuildTurnLifecycleGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(BuildTurnLifecycleGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void continuesTurnWhenTheSameAskIsGrownAgain(GameTestHelper helper) {
        SiteRig.floor(helper);
        var level = helper.getLevel();
        BlockPos target = helper.absolutePos(new BlockPos(10, 1, 10));
        var rig = SiteRig.start(level, SiteRig.target(level, Map.of(target, Blocks.STONE.defaultBlockState())));
        var labor = new ColonyLabor(new ColonyData(), level.dimension());
        try {
            var turn = rig.asked.getFirst().nodes().getFirst();
            var id = turn.id();
            var vertices = Map.of(id, new Vertex(id, turn, Placement.NOWHERE));
            PlanningFixture.applyWhole(labor, level, List.of(), vertices);
            helper.assertTrue(labor.takeable(id, level).isPresent(), "the original turn can be taken");

            // A refit can replace the plan while reusing the exact node from the original ask.
            PlanningFixture.applyWhole(labor, level, List.of(), vertices, List.of(), List.of(), Set.of(id),
                List.of(), Set.of());
            helper.assertTrue(labor.onTheGraph(id) && labor.takeable(id, level).orElseThrow() == turn,
                "the same turn remains available after the refit");
            helper.assertTrue(turn.readiness(level).isReady(), "the stay is still live");
            helper.assertTrue(rig.withdrawn.isEmpty(), "continuing the turn needs no withdrawal");
        } finally {
            rig.site.close();
            labor.close();
        }
        helper.succeed();
    }
}
