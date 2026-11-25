package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.ColonyContext;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.Proxies;
import io.github.izakyl.folkways.core.work.api.Stall;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.LeverBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class FacilityRetryGameTests {

    private static final String FACILITY = "retry_facility";

    private FacilityRetryGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityRetryGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void routineSucceedsAfterTwoFailures(GameTestHelper helper) {
        var test = new Fixture(helper, "fail");
        helper.startSequence().thenWaitUntil(() -> test.waiting(1, 20))
            .thenWaitUntil(() -> test.waiting(2, 40))
            .thenExecute(test::recover)
            .thenWaitUntil(test::done)
            .thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 350)
    public static void retryExhaustionStallsUntilManualRun(GameTestHelper helper) {
        var test = new Fixture(helper, "fail");
        helper.startSequence().thenWaitUntil(() -> test.waiting(3, 80))
            .thenWaitUntil(() -> test.stalled(3))
            .thenExecute(() -> {
                test.recover();
                test.run();
                helper.assertTrue(test.request().getInt("retries") == 0 && test.stalls().isEmpty(),
                    "manual run resets the retry counter and removes the stall");
            }).thenWaitUntil(test::done).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void permanentFailureStallsImmediately(GameTestHelper helper) {
        var test = new Fixture(helper, "fail_permanent");
        helper.succeedWhen(() -> test.stalled(0));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void reloadedFacilityKeepsRetryDeadline(GameTestHelper helper) {
        var test = new Fixture(helper, "fail");
        long[] deadline = new long[1];
        helper.startSequence().thenWaitUntil(() -> test.waiting(1, 20)).thenExecute(() -> {
            deadline[0] = test.request().getLong("due");
            CompoundTag saved = test.gate.colony.kept(DefinedFacilities.ID).copy();
            test.facilities.apply(test.facilities.preview(FACILITY, Optional.empty()));
            test.gate.colony.keep(DefinedFacilities.ID, saved);
            test.reload();
            test.recover();
        }).thenExecuteAfter(5, () -> {
            helper.assertTrue(test.request().getLong("due") == deadline[0]
                    && test.request().getInt("retries") == 1 && test.facilities.running(FACILITY).isEmpty(),
                "the reloaded trigger keeps waiting for its original deadline");
        }).thenWaitUntil(() -> {
            test.done();
            helper.assertTrue(test.facilities.recent(FACILITY).orElseThrow().time() >= deadline[0],
                "the retry runs at or after the original deadline");
        }).thenSucceed();
    }

    private static final class Fixture {

        final GameTestHelper helper;
        final FacilityGateFixture gate;
        DefinedFacilities facilities;
        Runnable close = () -> { };

        Fixture(GameTestHelper helper, String failure) {
            this.helper = helper;
            gate = new FacilityGateFixture(helper);
            Cleanup.on(helper, () -> {
                close.run();
                gate.close();
            });
            facilities = DefinedFacilities.of(gate.colony).orElseThrow();
            BlockPos lever = gate.lever.at().cell();
            var definition = Map.<String, Object>of("name", FACILITY,
                "extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", gate.level.dimension().location().toString()),
                    "min", pos(lever), "max", pos(lever)))),
                "controls", Map.of("lever", Map.of("part", "lever", "offset", pos(BlockPos.ZERO))),
                "routines", Map.of("operate", Map.of("source", """
                    def step(state, readings, args):
                        if readings["lever"]:
                            return None, [done()]
                        return None, [%s("retry.test.failure", on_fail="unused")]
                    """.formatted(failure))),
                "triggers", Map.of("manual", Map.of("kind", "manual", "routine", "operate")));
            var problems = facilities.apply(facilities.preview(FACILITY, Optional.of(definition)));
            helper.assertTrue(problems.isEmpty(), "installing retry fixture: " + problems);
            run();
        }

        void run() {
            helper.assertTrue(facilities.run(FACILITY, "operate", Map.of()).isEmpty(), "submit a manual routine");
        }

        void recover() {
            BlockPos lever = gate.lever.at().cell();
            gate.level.setBlockAndUpdate(lever, gate.level.getBlockState(lever).setValue(LeverBlock.POWERED, true));
        }

        CompoundTag request() {
            return gate.colony.kept(DefinedFacilities.ID).getCompound(FACILITY).getCompound("requests").getCompound("manual");
        }

        void waiting(int retries, int delay) {
            var result = facilities.recent(FACILITY);
            helper.assertTrue(result.filter(value -> value.ending().equals("failed")).isPresent(),
                "waiting for a retryable failure");
            var request = request();
            helper.assertTrue(request.getInt("retries") == retries && !request.hasUUID("active")
                    && request.getLong("due") == result.orElseThrow().time() + delay && stalls().isEmpty(),
                "waiting for retry " + retries + " with a " + delay + " tick delay");
        }

        List<Stall> stalls() {
            return facilities.stalls(gate.level.dimension());
        }

        void stalled(int retries) {
            var stalls = stalls();
            helper.assertTrue(stalls.size() == 1 && request().getInt("retries") == retries
                    && stalls.getFirst().why().translationKey().equals("retry.test.failure"),
                "waiting for a stall with the reported failure after " + retries + " retries");
        }

        void done() {
            helper.assertTrue(facilities.recent(FACILITY).filter(result -> result.ending().equals("done")).isPresent()
                    && stalls().isEmpty() && request().isEmpty(), "waiting for a successful retry");
        }

        @SuppressWarnings("unchecked")
        void reload() {
            DefinedFacilities.install(Proxies.of(ColonyContext.class, (proxy, method, args) -> switch (method.getName()) {
                case "colony" -> gate.colony;
                case "server" -> gate.level.getServer();
                case "share" -> {
                    facilities = (DefinedFacilities) args[0];
                    yield null;
                }
                case "tick" -> {
                    Consumer<MinecraftServer> tick = (Consumer<MinecraftServer>) args[0];
                    helper.onEachTick(() -> tick.accept(gate.level.getServer()));
                    yield null;
                }
                case "closed" -> {
                    Consumer<Closing> shutdown = (Consumer<Closing>) args[0];
                    close = () -> shutdown.accept(Closing.SHUTDOWN);
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            }));
        }
    }

    private static List<Integer> pos(BlockPos at) {
        return List.of(at.getX(), at.getY(), at.getZ());
    }
}
