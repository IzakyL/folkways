package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Journey;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_create")
@PrefixGameTestTemplate(false)
public final class ConductorPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ConductorPlanningGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void theResidentTakingTheSeatAlsoGetsTheDrivingTask(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = WorldPos.of(level, helper.absolutePos(BlockPos.ZERO));
        UUID train = UUID.randomUUID();
        ConductorSeats seats = new ConductorSeats(train);
        seats.reconcile(List.of(new Cab(train, new SeatRef(UUID.randomUUID(), 0), true, at, Stances.at(at))));
        List<Grown> goals = new ArrayList<>();
        seats.goals(level, goals);
        var near = PersonBody.RESIDENT.get().create(level);
        var far = PersonBody.RESIDENT.get().create(level);
        var licence = near.licences().of(RailContent.trade()).orElseThrow();
        var crew = new Crew(List.of(
            new Crew.Hand(far.resident(), at.at(at.cell().offset(100, 0, 0)), 6, 64, 1,
                List.of(licence), List.of(), Optional.empty()),
            new Crew.Hand(near.resident(), at, 6, 64, 1,
                List.of(licence), List.of(), Optional.empty())));
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot((int) from.cell().distSqr(at.cell()), goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(0, goals));
            }
        };
        var situation = new Situation(ways, new Stock(List.of(), Map.of()), List.of(), Stands.NONE);
        var solved = new PlanningFixture.Rounds(new Claims(), new Cooldowns())
            .solve(situation, crew, goals, List.of(), 0);
        helper.assertTrue(solved.weave().vertices().size() == 2, "boarding and driving must both be planned");
        var tour = solved.schedule().tours().get(near.id());
        helper.assertTrue(tour != null && tour.nodes().size() == 2,
            "both boarding and driving belong to the resident nearest the seat");
        helper.assertTrue(!solved.schedule().tours().containsKey(far.id()),
            "the other resident must not get the driving task while standing on the platform");
        helper.succeed();
    }
}
