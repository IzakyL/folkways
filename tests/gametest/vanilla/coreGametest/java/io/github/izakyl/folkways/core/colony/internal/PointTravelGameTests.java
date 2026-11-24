package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.travel.api.TravelSources;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Comparator;
import java.util.Set;
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

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PointTravelGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "point_ways");
    static { PointSites.register(OWNER, (level, at) -> level.getBlockState(at).is(Blocks.COMPOSTER)); }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PointTravelGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aNativePointCanBeReachedOnDemandAndReleased(GameTestHelper helper)
            throws ReflectiveOperationException {
        try (var rig = new Rig(helper)) {
            ColonyWays.advance(rig.data, rig.level);
            var held = rig.colony.hold(OWNER, new Claimed.Point(rig.at)).orElseThrow();
            ColonyWays.advance(rig.data, rig.level);
            Set<BlockPos> workable = Reach.workableCells(rig.level, rig.at.cell());
            BlockPos goal = workable.stream().filter(feet -> feet.getY() == rig.body.blockPosition().getY())
                .min(Comparator.comparingDouble(feet -> feet.distSqr(rig.body.blockPosition()))).orElseThrow();
            Faring route = answering(rig.data, rig.level).journey(rig.body.resident(),
                WorldPos.of(rig.level, rig.body.blockPosition()), Set.of(WorldPos.of(rig.level, goal)));
            helper.assertTrue(route instanceof Faring.Yes yes && yes.by().ticks() > 0,
                "the actual path engine finds a walking journey to the composter's real working footing: " + route);
            ColonyWays.advance(rig.data, rig.level);
            helper.assertTrue(rig.level.getBlockEntity(rig.at.cell()) == null
                    && rig.level.getBlockState(rig.at.cell()).getValue(ComposterBlock.LEVEL) == 5,
                "path preparation creates no block entity and does not change the native compost contents");
            rig.colony.release(held.id(), ClaimRelease.LET_GO);
            ColonyWays.advance(rig.data, rig.level);
            helper.assertTrue(rig.colony.claims(OWNER).isEmpty(), "the real point can be released after routing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aPersistedPointInAnotherDimensionSurvivesAdvancingTheWaysHere(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            WorldPos elsewhere = WorldPos.of(Level.NETHER, rig.at.cell());
            rig.data.claims().hold(null, OWNER, new Claimed.Point(elsewhere)).orElseThrow();
            ColonyWays.advance(rig.data, rig.level);
            helper.assertTrue(!elsewhere.in(rig.level) && rig.colony.claims(OWNER).size() == 1,
                "advancing the ways preserves the persisted point in its own realm");
        }
        helper.succeed();
    }

    private static Ways answering(ColonyData colony, ServerLevel level) throws ReflectiveOperationException {
        var type = Class.forName(ColonyWays.class.getName() + "$Answering");
        var constructor = type.getDeclaredConstructor(TravelSources.class, ServerLevel.class);
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
            colony.hold(PersonBody.ID, Claimed.Entity.of(body)).orElseThrow();
        }
        public void close() { body.discard(); colony.raze(); ColonyWays.forget(colony.id()); }
    }
}
