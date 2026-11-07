package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.BodyRunner;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HoldingPresentationGameTests {

    private static final int LABOR_TICKS = 20;
    private static final int PIECES = 3;
    private static final ResourceLocation TEST =
            ResourceLocation.fromNamespaceAndPath("folkways_test", "presentation");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HoldingPresentationGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void privateActionsKeepCadenceWithoutPersonalDispatch(GameTestHelper helper)
            throws ReflectiveOperationException {
        Shift dense = run(helper, false);
        Shift dispersed = run(helper, true);
        helper.assertValueEqual(
            dispersed.commits,
            dense.commits,
            "distant work targets preserve the same commit times as dense targets");
        for (int at = 1; at < PIECES; at++) {
            helper.assertValueEqual(
                dispersed.commits.get(at) - dispersed.commits.get(at - 1),
                LABOR_TICKS + 1,
                "each piece keeps its wind-up and dispatch boundaries without an outer tick");
        }
        helper.assertValueEqual(dense.endings, PIECES, "each inner piece reports exactly one ending");
        helper.assertValueEqual(dispersed.endings, PIECES, "distance preserves inner lifecycle reporting");
        helper.succeed();
    }

    private static Shift run(GameTestHelper helper, boolean dispersed)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 1, 1));
        Shift shift = new Shift(level, origin, dispersed);
        ColonyLabor labor = new ColonyLabor(new ColonyData(), level.dimension());
        PlanningFixture.apply(
            labor,
            level,
            List.of(),
            Shown.delta(
                Map.of(shift.id(), new Vertex(shift.id(), shift, Placement.NOWHERE)),
                List.of(),
                List.of()));
        var body = PersonBody.RESIDENT.get().create(level);
        body.setPos(origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D);
        BodyRunner runner = new BodyRunner(body.getUUID(), labor.execution());
        runner.follow(List.of(shift.id()));
        try {
            for (int tick = 0; tick < 100 && shift.commits.size() < PIECES; tick++) {
                shift.clock = tick;
                labor.tick(runner, level, body);
            }
            helper.assertValueEqual(
                shift.commits.size(),
                PIECES,
                "all inner work completed within the expected budget");
            for (WorkAction action : shift.pieces) {
                helper.assertTrue(
                    !labor.execution().personal().knows(((NodeSpec) action.spec()).id()),
                    "private action identities never enter the personal phase graph");
            }
            return shift;
        } finally {
            labor.close();
            body.discard();
        }
    }

    private static final class Shift implements Node {
        private final NodeSpec spec;
        private final List<WorkAction> pieces = new ArrayList<>();
        private final List<Integer> commits = new ArrayList<>();
        private int clock;
        private int endings;

        private Shift(ServerLevel level, BlockPos origin, boolean dispersed) {
            spec =
                    NodeSpec.of(
                        UUID.randomUUID(),
                        TEST,
                        WorkSite.at(level, origin),
                        Stances.WHEREVER,
                        new Workload.Custom())
                            .done();
            for (int index = 0; index < PIECES; index++) {
                BlockPos focus =
                        origin.offset(dispersed ? 64 * (index + 1) : index, dispersed ? index * 8 : 0, 0);
                NodeSpec inner =
                        NodeSpec.of(
                            UUID.randomUUID(),
                            TEST,
                            WorkSite.at(level, focus),
                            Stances.WHEREVER,
                            Workload.Once.of(LABOR_TICKS))
                                .done();
                pieces.add(
                    new WorkAction() {
                    public NodeSpec spec() {
                    return inner;
                    }

                    public Outcome commit(ServerLevel at, Worker who) {
                    commits.add(clock);
                    return Outcome.done();
                    }
                    });
            }
        }

        public NodeSpec spec() {
            return spec;
        }

        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }

        public WorkHolding acquire(HoldingContext context) {
            return new SequentialHolding(pieces, action -> endings++);
        }

    }
}
