package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.DirectTransport;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurveyLicenceGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SurveyLicenceGameTests.class);
    }

    private static final Ways NOWHERE = PlanningFixture.afoot(0);

    @GameTest(template = "empty")
    public static void aTradeTurnedOffIsNoLongerPlannedFor(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        var licences = body.licences();
        Vocation trade = Vocations.all().stream()
            .filter(vocation -> licences.of(vocation).isPresent())
            .findFirst().orElseThrow();
        Map<UUID, Body> present = Map.of(body.id(), body);
        Survey survey = new Survey();
        survey.read(body.id());
        survey.answer(level, new ColonyData(), present, seen -> { });
        helper.assertTrue(holds(survey, trade), "a fresh hand carries every licence it has");

        licences.allow(licences.trade(trade).orElseThrow(), false);
        survey.answer(level, new ColonyData(), present, seen -> { });
        helper.assertFalse(holds(survey, trade),
            "turning a trade off must reach the planner before it hands that trade's work out");

        licences.allow(licences.trade(trade).orElseThrow(), true);
        survey.answer(level, new ColonyData(), present, seen -> { });
        helper.assertTrue(holds(survey, trade), "turning it back on is seen too");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void transportPlanningOnlyReadsItsLevel(GameTestHelper helper) {
        ColonyData colony = new ColonyData();
        Survey survey = new Survey();
        survey.answer(helper.getLevel(), colony, Map.of(), seen -> { });
        var world = survey.known(NOWHERE, List.of()).world();
        var offering = new TransportPublication.Offering(new DirectTransport(),
            new TransportSnapshot(1, 0, TransportTravel.UNKNOWN, List.of()), true);
        colony.works().publishTransport(DirectTransport.ID, Level.NETHER, List.of(offering));
        helper.assertTrue(world.transports().get().entries().isEmpty(),
            "a Nether publication leaves the Overworld's transport gate closed");
        colony.works().publishTransport(DirectTransport.ID, helper.getLevel().dimension(), List.of(offering));
        var local = world.transports().get();
        helper.assertTrue(local.entries().size() == 1
                && local.entries().getFirst().dimension().equals(helper.getLevel().dimension()),
            "the same mode in this level opens its transport gate");
        colony.works().publishTransport(DirectTransport.ID, Level.NETHER, List.of());
        helper.assertTrue(world.transports().get().entries().equals(local.entries())
                && world.transports().get().version() > local.version()
                && world.transportVersion().getAsLong() == colony.works().transports().version(),
            "dimension views keep the publication's global version for stale checks");
        helper.succeed();
    }

    private static boolean holds(Survey survey, Vocation trade) {
        List<Crew.Hand> hands = survey.known(NOWHERE, List.of()).hands();
        return hands.size() == 1 && hands.get(0).licenceFor(trade).isPresent();
    }

    @GameTest(template = "empty")
    public static void copiedDefaultsReachThePlannersCrew(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var body = PersonBody.RESIDENT.get().create(level);
        body.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 2, 1)).getCenter());
        level.addFreshEntity(body);
        Vocation hauling = Vocations.of(Vocations.HAULING).orElseThrow();
        try {
            WorkDefaults.set(colony, PersonBody.ID, hauling.id(), true, 1);
            colony.hold(PersonContent.ID, Claimed.Entity.of(body)).orElseThrow();
            Survey survey = new Survey();
            survey.read(body.id());
            survey.answer(level, ColonyData.find(level.getServer(), colony.id()).orElseThrow(),
                Map.of(body.id(), body), seen -> { });
            helper.assertTrue(survey.known(NOWHERE, List.of()).hands().getFirst().keennessOf(hauling)
                .orElseThrow() == Keenness.FIRST, "the planner receives the copied priority through the normal survey");
        } finally {
            body.discard();
            colony.raze();
        }
        helper.succeed();
    }
}
