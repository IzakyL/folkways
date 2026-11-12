package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Delivery;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class CarriedWorkshopGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CarriedWorkshopGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void onlyBearerCanSupplyCarriedWorkAndMissingBearerOffersNothing(GameTestHelper helper) {
        PlanningFixture bearer = new PlanningFixture(helper);
        PlanningFixture other = new PlanningFixture(helper);
        UUID resident = bearer.crew.hands().getFirst().id();
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_core", "carried_test");
        ItemSpec sticks = ItemSpec.of(Items.STICK);
        Workshop workshop = new Workshop() {
            public UUID key() {
                return resident;
            }

            public ResourceLocation id() {
                return owner;
            }

            public Optional<UUID> bearer() {
                return Optional.of(resident);
            }

            public WorkSite site() {
                return new WorkSite.AtEntity(resident, bearer.source);
            }

            public List<Refinement.Change> options(Intent intent, Grown graph) {
                if (!(intent instanceof Produce produce) || !produce.wanted().equals(sticks)) {
                    return List.of();
                }
                NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, site(), Stances.WHEREVER,
                    Workload.Once.of(1)).gives(new Amount(sticks, 4)).done();
                return List.of(Refinement.Change.of(Grown.of(new Node() {
                    public NodeSpec spec() {
                        return spec;
                    }

                    public Optional<UUID> worker() {
                        return Optional.of(resident);
                    }

                    public Outcome commit(ServerLevel level, Worker who) {
                        return Outcome.done();
                    }
                })));
            }
        };
        var stock = new Stock(List.of(), Map.of());
        var delivery = Delivery.to(UUID.randomUUID(), owner, new WorkSite.AtEntity(resident, bearer.source),
            Stances.WHEREVER, sticks, 4, count -> { });
        var goals = List.of(Grown.of(delivery));
        var rounds = new PlanningFixture.Rounds();
        try {
            var crew = new Crew(List.of(bearer.crew.hands().getFirst(), other.crew.hands().getFirst()));
            var planned = rounds.solve(bearer.situation(stock, List.of(workshop)), crew, goals, List.of(), 0);
            helper.assertTrue(planned.schedule().tours().containsKey(resident)
                && !planned.schedule().tours().containsKey(other.crew.hands().getFirst().id())
                && planned.weave().vertices().values().stream()
                    .anyMatch(vertex -> vertex.node().spec().owner().equals(owner)),
                "only the bearer receives carried production: " + planned.diagnosis());
        } finally {
            rounds.closed();
        }
        var absent = new PlanningFixture.Rounds();
        try {
            var planned = absent.solve(bearer.situation(stock, List.of(workshop)), other.crew, goals, List.of(), 0);
            helper.assertTrue(planned.schedule().tours().isEmpty(),
                "another resident cannot use an absent bearer's kit");
        } finally {
            absent.closed();
        }
        helper.succeed();
    }
}
