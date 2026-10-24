package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
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
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ElevatorHoldingsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ElevatorHoldingsGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void blockHoldingsCreateOneControllerAndWithdrawalRetiresIt(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        Elevators service = new Elevators(fixture.colony);
        try {
            fixture.hold();
            service.tick(helper.getLevel().getServer());
            ElevatorController controller = fixture.controller();
            service.tick(helper.getLevel().getServer());
            helper.assertTrue(service.all().equals(List.of(fixture.lift))
                && fixture.controller() == controller, "repeated holdings synchronization preserves one controller");
            helper.assertTrue(controller.view().isEmpty(), "ownership does not require a loaded working cabin");

            fixture.holdings.clear();
            service.tick(helper.getLevel().getServer());
            helper.assertTrue(service.all().isEmpty() && controller.retired()
                && ElevatorDispatch.controller(helper.getLevel().getServer(), fixture.id, fixture.lift).isEmpty(),
                "withdrawing a block holding retires and removes an idle controller");
            helper.succeed();
        } finally {
            service.closed(Closing.SHUTDOWN);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aReconstructedServiceRestoresOwnershipFromOrdinaryHoldings(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.hold();
        Elevators first = new Elevators(fixture.colony);
        Elevators restored = new Elevators(fixture.colony);
        try {
            first.tick(helper.getLevel().getServer());
            ElevatorController previous = fixture.controller();
            first.closed(Closing.SHUTDOWN);
            helper.assertTrue(fixture.holdings.size() == 1,
                "closing the runtime service does not modify ordinary colony holdings");
            restored.tick(helper.getLevel().getServer());
            helper.assertTrue(restored.all().equals(List.of(fixture.lift))
                && fixture.controller() != previous && !fixture.controller().retired(),
                "a new service reconstructs a live controller from existing block holdings alone");
            helper.succeed();
        } finally {
            first.closed(Closing.SHUTDOWN);
            restored.closed(Closing.SHUTDOWN);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void conflictingColonyHoldingsCannotCreateTwoControllers(GameTestHelper helper) {
        Fixture owner = new Fixture(helper);
        Fixture contender = new Fixture(helper);
        owner.hold();
        contender.hold();
        Elevators first = new Elevators(owner.colony);
        Elevators second = new Elevators(contender.colony);
        try {
            first.tick(helper.getLevel().getServer());
            ElevatorController original = owner.controller();
            second.tick(helper.getLevel().getServer());
            helper.assertTrue(second.all().isEmpty() && owner.controller() == original
                && ElevatorDispatch.controller(helper.getLevel().getServer(), contender.id, contender.lift).isEmpty(),
                "even conflicting source holdings cannot assign the physical elevator twice");
            owner.holdings.clear();
            first.tick(helper.getLevel().getServer());
            second.tick(helper.getLevel().getServer());
            helper.assertTrue(original.retired() && second.all().equals(List.of(contender.lift))
                && contender.controller() != original,
                "after the first owner withdraws, the remaining holding acquires a new controller");
            helper.succeed();
        } finally {
            first.closed(Closing.SHUTDOWN);
            second.closed(Closing.SHUTDOWN);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void removingOneOfTwoHoldingsDoesNotRetireTheSharedPhysicalLift(GameTestHelper helper) {
        Fixture fixture = new Fixture(helper);
        fixture.hold();
        fixture.hold();
        Elevators service = new Elevators(fixture.colony);
        try {
            service.tick(helper.getLevel().getServer());
            ElevatorController controller = fixture.controller();
            fixture.holdings.removeFirst();
            service.tick(helper.getLevel().getServer());
            helper.assertTrue(service.all().equals(List.of(fixture.lift))
                    && fixture.controller() == controller && !controller.retired(),
                "another ordinary holding keeps the same physical lift and its standing-place reservations alive");
            fixture.holdings.clear();
            service.tick(helper.getLevel().getServer());
            helper.assertTrue(controller.retired() && service.all().isEmpty(),
                "only withdrawing the last holding retires the physical lift");
        } finally { service.closed(Closing.SHUTDOWN); }
        helper.succeed();
    }

    private static final class Fixture {
        final GameTestHelper helper;
        final UUID id = UUID.randomUUID();
        final List<Holding> holdings = new ArrayList<>();
        final ElevatorRef lift;
        final Colony colony;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            lift = new ElevatorRef(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(1, 1, 1)));
            colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[] {Colony.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "id" -> id;
                    case "holdings" -> {
                        if (args == null || args.length != 1 || !ElevatorPlugin.ID.equals(args[0])) {
                            throw new AssertionError("service must query its own ordinary holdings");
                        }
                        yield List.copyOf(holdings);
                    }
                    default -> throw new AssertionError("unexpected colony API: " + method.getName());
                });
        }

        void hold() {
            holdings.add(new Holding(UUID.randomUUID(), ElevatorPlugin.ID,
                new Held.Block(WorldPos.of(helper.getLevel(), lift.pulley()))));
        }

        ElevatorController controller() {
            return ElevatorDispatch.controller(helper.getLevel().getServer(), id, lift).orElseThrow();
        }
    }
}
