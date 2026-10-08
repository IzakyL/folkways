package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurveyLicenceGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SurveyLicenceGameTests.class);
    }

    private static final Ways NOWHERE = new Ways() {
        public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
            return Faring.yes(Journey.afoot(0, goals));
        }

        public Faring anyoneReaches(Set<WorldPos> goals) {
            return Faring.yes(Journey.afoot(0, goals));
        }
    };

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

    private static boolean holds(Survey survey, Vocation trade) {
        List<Crew.Hand> hands = survey.known(NOWHERE, List.of()).hands();
        return hands.size() == 1 && hands.get(0).licenceFor(trade).isPresent();
    }
}
