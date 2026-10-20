package io.github.izakyl.folkways.core.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.PointSites;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.core.engine.travel.graph.Roaming;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PointTravelGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "point_ways");
    static { PointSites.register(OWNER, (level, at) -> level.getBlockState(at).is(Blocks.COMPOSTER)); }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PointTravelGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aNativePointSeedsActualWorkFootingsAndARealWalkingJourneyUntilReleased(GameTestHelper helper)
            throws ReflectiveOperationException {
        try (var rig = new Rig(helper)) {
            ColonyWays.advance(rig.data, rig.level);
            Roaming graph = graph(rig.data, rig.level);
            helper.assertTrue(footings(graph, rig.at) == null,
                "an unclaimed native composter is neither a store nor a workshop and cannot seed a work site");
            var held = rig.colony.hold(OWNER, new Held.Point(rig.at)).orElseThrow();
            ColonyWays.advance(rig.data, rig.level);
            long[] cached = footings(graph, rig.at);
            Set<BlockPos> workable = Reach.workableCells(rig.level, rig.at.cell());
            helper.assertTrue(cached != null && cached.length > 0
                    && Arrays.stream(cached).mapToObj(BlockPos::of).collect(Collectors.toSet()).equals(workable),
                "the real colony point contributes exactly its reachable native work footings to the path cache");
            BlockPos goal = workable.stream().filter(feet -> feet.getY() == rig.body.blockPosition().getY())
                .min(Comparator.comparingDouble(feet -> feet.distSqr(rig.body.blockPosition()))).orElseThrow();
            Faring route = answering(rig.data, rig.level).journey(rig.body.resident(),
                WorldPos.of(rig.level, rig.body.blockPosition()), Set.of(WorldPos.of(rig.level, goal)));
            helper.assertTrue(route instanceof Faring.Yes yes && yes.by().ticks() > 0,
                "the actual path engine finds a walking journey to the composter's real working footing: " + route);
            ColonyWays.advance(rig.data, rig.level);
            helper.assertTrue(footings(graph, rig.at) == cached, "a stable point reuses the same computed footing cache");
            helper.assertTrue(rig.level.getBlockEntity(rig.at.cell()) == null
                    && rig.level.getBlockState(rig.at.cell()).getValue(ComposterBlock.LEVEL) == 5,
                "path preparation creates no block entity and does not change the native compost contents");
            rig.colony.release(held.id(), Release.LET_GO);
            ColonyWays.advance(rig.data, rig.level);
            helper.assertTrue(footings(graph, rig.at) == null,
                "releasing the real point removes its active site footing cache on the next advance");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aPersistedPointInAnotherDimensionNeverSeedsTheSameCoordinatesHere(GameTestHelper helper)
            throws ReflectiveOperationException {
        try (var rig = new Rig(helper)) {
            WorldPos elsewhere = WorldPos.of(Level.NETHER, rig.at.cell());
            rig.data.holdings().hold(null, OWNER, new Held.Point(elsewhere)).orElseThrow();
            ColonyWays.advance(rig.data, rig.level);
            helper.assertTrue(footings(graph(rig.data, rig.level), rig.at) == null,
                "a real persisted point belonging to another dimension cannot seed this level's identical coordinates");
            helper.assertTrue(!elsewhere.in(rig.level) && rig.colony.holdings(OWNER).size() == 1,
                "filtering paths does not destroy the persisted point in its own realm");
        }
        helper.succeed();
    }

    private static Roaming graph(ColonyData colony, ServerLevel level) throws ReflectiveOperationException {
        var method = ColonyWays.class.getDeclaredMethod("graph", ColonyData.class, ServerLevel.class);
        method.setAccessible(true);
        return (Roaming) method.invoke(null, colony, level);
    }

    private static long[] footings(Roaming graph, WorldPos at) throws ReflectiveOperationException {
        var field = Roaming.class.getDeclaredField("footingsOf");
        field.setAccessible(true);
        return (long[]) ((Map<?, ?>) field.get(graph)).get(at.cell().asLong());
    }

    private static Ways answering(ColonyData colony, ServerLevel level) throws ReflectiveOperationException {
        var type = Class.forName(ColonyWays.class.getName() + "$Answering");
        var constructor = type.getDeclaredConstructor(ColonyData.class, ServerLevel.class);
        constructor.setAccessible(true);
        return (Ways) constructor.newInstance(colony, level);
    }

    private static final class Rig implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ColonyData data;
        final ResidentEntity body;
        final WorldPos at;
        Rig(GameTestHelper helper) {
            level = helper.getLevel();
            for (int x = 0; x <= 12; x++) for (int z = 0; z <= 2; z++) helper.setBlock(x, 1, z, Blocks.STONE);
            at = WorldPos.of(level, helper.absolutePos(new BlockPos(10, 2, 1)));
            level.setBlockAndUpdate(at.cell(), Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 5));
            colony = Colonies.mint(level.getServer());
            data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1))));
            level.addFreshEntity(body);
            colony.hold(PersonBody.ID, Held.Entity.of(body)).orElseThrow();
        }
        public void close() { body.discard(); colony.raze(); ColonyWays.forget(colony.id()); }
    }
}
