package io.github.izakyl.folkways.vanilla.create.rail.domain;

import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.shell.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Journey;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.shell.create.transport.ContraptionSeats;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
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
        var colony = Colonies.mint(level.getServer());
        var post = ConductorSeats.post(level, new Cab(train, new SeatRef(UUID.randomUUID(), 0), true, at,
            Stances.at(at)));
        var duty = Posts.of(colony).orElseThrow().duty(post, () -> true);
        List<Grown> goals = List.of(Grown.of(duty));
        var near = PersonBody.RESIDENT.get().create(level);
        var far = PersonBody.RESIDENT.get().create(level);
        var licence = near.licences().of(RailContent.trade()).orElseThrow();
        var crew = new Crew(List.of(
            new Crew.Hand(far.resident(), at.at(at.cell().offset(100, 0, 0)), 6, 64, 1,
                List.of(licence), List.of(), Optional.empty()),
            new Crew.Hand(near.resident(), at, 6, 64, 1,
                List.of(licence), List.of(), Optional.empty())));
        var situation = new Situation(waysFrom(at), new Stock(List.of(), Map.of()), List.of(), Stands.NONE);
        var solved = new PlanningFixture.Rounds(new Claims(), new Cooldowns())
            .solve(situation, crew, goals, List.of(), 0);
        colony.raze();
        helper.assertTrue(solved.weave().vertices().size() == 1, "boarding and driving share one duty");
        var tour = solved.schedule().tours().get(near.id());
        helper.assertTrue(tour != null && tour.nodes().equals(List.of(duty.id())),
            "both boarding and driving belong to the resident nearest the seat");
        helper.assertTrue(!solved.schedule().tours().containsKey(far.id()),
            "the other resident must not get the driving task while standing on the platform");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aRestoredDriverKeepsTheCab(GameTestHelper helper) {
        var level = helper.getLevel();
        WorldPos at = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        UUID train = UUID.randomUUID();
        var colony = Colonies.mint(level.getServer());
        var driver = PersonBody.RESIDENT.get().create(level);
        var other = PersonBody.RESIDENT.get().create(level);
        driver.setPos(at.cell().getX() + 0.5, at.cell().getY(), at.cell().getZ() + 0.5);
        var contraption = new CarriageContraption() {
            @Override
            public Direction getAssemblyDirection() {
                return Direction.NORTH;
            }
        };
        contraption.bounds = new AABB(BlockPos.ZERO);
        contraption.getSeats().add(BlockPos.ZERO);
        contraption.conductorSeats.put(BlockPos.ZERO, Couple.create(true, true));
        var carriage = CarriageContraptionEntity.create(level, contraption);
        carriage.setPos(driver.position());
        var seat = new SeatRef(carriage.getUUID(), 0);
        TransitNetwork before = TransitNetworks.get();
        TransitNetwork network = Proxies.of(TransitNetwork.class, (proxy, method, args) -> switch (method.getName()) {
            case "conductorSeats" -> List.of(seat);
            case "occupant" -> ContraptionSeats.occupant(carriage, seat.seatIndex());
            case "seatPosition" -> Optional.of(at);
            default -> throw new AssertionError("unexpected transit call: " + method.getName());
        });
        try {
            level.addFreshEntity(driver);
            driver.joinColony(colony.id());
            carriage.addSittingPassenger(driver, seat.seatIndex());
            helper.assertTrue(driver.getVehicle() == carriage && carriage.checkConductors().getFirst(),
                "Create must recognize the resident already in the controls seat");

            TransitNetworks.install(network);
            helper.assertTrue(ConductorSeats.occupiedCabs(level.getServer(), network, train, UUID.randomUUID()).isEmpty(),
                "a foreign colony must not adopt the seated resident");
            var cabs = ConductorSeats.occupiedCabs(level.getServer(), network, train, colony.id());
            helper.assertTrue(cabs.size() == 1 && cabs.iterator().next().driver().filter(driver.id()::equals).isPresent(),
                "a restored cab is offered once and retains its driver even when it controls both directions");
            var post = ConductorSeats.post(level, cabs.iterator().next());
            var conduct = Posts.of(colony).orElseThrow().duty(post, () -> true);
            List<Grown> goals = List.of(Grown.of(conduct));
            var licence = driver.licences().of(RailContent.trade()).orElseThrow();
            var crew = new Crew(List.of(
                new Crew.Hand(other.resident(), at, 6, 64, 1, List.of(licence), List.of(), Optional.empty()),
                new Crew.Hand(driver.resident(), at, 6, 64, 1, List.of(licence), List.of(), Optional.empty())));
            var situation = new Situation(waysFrom(at), new Stock(List.of(), Map.of()), List.of(), Stands.NONE);
            var solved = new PlanningFixture.Rounds(new Claims(), new Cooldowns())
                .solve(situation, crew, goals, List.of(), 0);
            var tour = solved.schedule().tours().get(driver.id());
            helper.assertTrue(tour != null && tour.nodes().equals(List.of(conduct.id()))
                && !solved.schedule().tours().containsKey(other.id()), "only the seated resident gets the driving task");
            Worker worker = Proxies.of(Worker.class, (proxy, method, args) -> switch (method.getName()) {
                case "resident" -> driver.resident();
                case "body" -> driver;
                default -> throw new AssertionError("unexpected worker call: " + method.getName());
            });
            helper.assertTrue(conduct.ready(level) && ((io.github.izakyl.folkways.core.work.api.Workload.Continuous) conduct.spec().workload())
                    .holds(level, worker) && conduct.mountedWork(),
                "the restored driver can conduct while remaining in the seat");
        } finally {
            colony.raze();
            TransitNetworks.install(before);
            driver.stopRiding();
            driver.discard();
            carriage.discard();
        }
        helper.succeed();
    }

    private static Ways waysFrom(WorldPos at) {
        return new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot((int) from.cell().distSqr(at.cell()), goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(0, goals));
            }
        };
    }
}
