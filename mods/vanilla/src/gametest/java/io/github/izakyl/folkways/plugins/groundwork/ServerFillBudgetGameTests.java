package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ServerFillBudgetGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ServerFillBudgetGameTests.class);
    }

    @GameTest(template = "empty_large", batch = "server_fill_budget")
    public static void laterColoniesAndDimensionsGetTurnsDespiteAnAlwaysFirstBusyCaller(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            ServerFillBudget.Registry budget = new ServerFillBudget.Registry(4, 0);
            FillPlan first = fixture.plan(fixture.level, UUID.randomUUID());
            ServerLevel other = fixture.server.getLevel(Level.NETHER);
            helper.assertTrue(other != null && other != fixture.level, "the second work site really belongs to another dimension");
            FillPlan second = fixture.plan(other, UUID.randomUUID());
            FillPlan third = fixture.plan(fixture.level, UUID.randomUUID());
            for (FillPlan plan : List.of(first, second, third)) budget.register(fixture.server, 0, plan);
            BlockPos[] markers = {helper.absolutePos(new BlockPos(4, 1, 4)),
                helper.absolutePos(new BlockPos(6, 1, 4)), helper.absolutePos(new BlockPos(8, 1, 4))};
            for (BlockPos marker : markers) fixture.level.setBlock(marker, Blocks.AIR.defaultBlockState(), 3);
            int[] completed = new int[3];
            List<FillPlan> order = List.of(first, second, third);
            for (long tick = 0; tick < 6; tick++) {
                for (int i = 0; i < order.size(); i++) {
                    // The first site always calls first and asks for the entire allowance. The third
                    // misses its first ticket altogether; another colony must not steal that ticket.
                    if (tick == 2 && i == 2) continue;
                    int index = i;
                    int used = budget.allotted(fixture.server, tick, order.get(i), slice -> {
                        int got = 0;
                        while (slice.tryStep()) got++;
                        if (got > 0) fixture.level.setBlock(markers[index], Blocks.DIRT.defaultBlockState(), 3);
                        return got;
                    });
                    completed[i] += used;
                }
                if (tick == 0) helper.assertTrue(fixture.level.getBlockState(markers[0]).is(Blocks.DIRT)
                        && fixture.level.getBlockState(markers[1]).isAir() && fixture.level.getBlockState(markers[2]).isAir(),
                    "a later colony cannot make a physical write using a separate allowance in the same server tick");
                if (tick == 2) helper.assertTrue(completed[0] == 4 && completed[1] == 4 && completed[2] == 0,
                    "an absent owner wastes one tick instead of donating a new allowance to the first caller");
            }
            helper.assertTrue(completed[0] == 8 && completed[1] == 8 && completed[2] == 4,
                "a finite registered set receives round-robin turns even across colonies and dimensions");
            for (BlockPos marker : markers) helper.assertTrue(fixture.level.getBlockState(marker).is(Blocks.DIRT),
                "every participating site eventually obtains enough real allowance to change its own marker");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "server_fill_budget")
    public static void repeatedAndNestedCallsCannotRefreshTheSameTicksAllowance(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            FillPlan first = fixture.plan(fixture.level, UUID.randomUUID());
            FillPlan second = fixture.plan(fixture.level, UUID.randomUUID());
            ServerFillBudget.Registry budget = new ServerFillBudget.Registry(7, 0);
            budget.register(fixture.server, 10, first);
            budget.register(fixture.server, 10, second);
            helper.assertTrue(consume(budget, fixture.server, 10, first, 2) == 2, "the first planning call consumes two units");
            helper.assertTrue(consume(budget, fixture.server, 10, first, 3) == 3, "a separate commit call only sees the remainder");
            int nested = budget.allotted(fixture.server, 10, first, slice -> {
                helper.assertTrue(slice.tryStep(), "the outer retreat check consumes one of the last two units");
                return 1 + consume(budget, fixture.server, 10, first, 20);
            });
            helper.assertTrue(nested == 2 && consume(budget, fixture.server, 10, first, 20) == 0
                    && consume(budget, fixture.server, 10, second, 20) == 0,
                "neither re-entry, repeated commit attempts nor another colony can mint more work");
            helper.assertTrue(consume(budget, fixture.server, 11, first, 20) == 0
                    && consume(budget, fixture.server, 11, second, 20) == 7,
                "the next tick elects the other site and restores one server allowance");
            ServerFillBudget.Registry exceptional = new ServerFillBudget.Registry(3, 0);
            exceptional.register(fixture.server, 20, first);
            try {
                exceptional.allotted(fixture.server, 20, first, slice -> {
                    helper.assertTrue(slice.tryStep(), "one unit is spent before the callback fails");
                    throw new IllegalStateException("intentional budget fixture");
                });
                throw new AssertionError("the intentional callback failure must propagate");
            } catch (IllegalStateException expected) {
                helper.assertTrue(expected.getMessage().equals("intentional budget fixture"), "only the intentional failure is expected");
            }
            helper.assertTrue(consume(exceptional, fixture.server, 20, first, 20) == 2,
                "a thrown callback also pays for its consumed work rather than refunding the whole slice");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "server_fill_budget")
    public static void closingAndReopeningSitesCannotResetSpentWorkOrKeepClosedPlansRegistered(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            FillPlan first = fixture.plan(fixture.level, UUID.randomUUID());
            FillPlan second = fixture.plan(fixture.level, UUID.randomUUID());
            FillPlan later = fixture.plan(fixture.level, UUID.randomUUID());
            ServerFillBudget.Registry budget = new ServerFillBudget.Registry(4, 0);
            budget.register(fixture.server, 30, first);
            budget.register(fixture.server, 30, second);
            budget.register(fixture.server, 30, first);
            helper.assertTrue(budget.liveCount(fixture.server) == 2, "duplicate registration is idempotent");
            helper.assertTrue(consume(budget, fixture.server, 30, first, 2) == 2, "the original owner has already used some work");
            budget.close(fixture.server, 30, first);
            budget.close(fixture.server, 30, first);
            budget.register(fixture.server, 30, later);
            helper.assertTrue(!budget.registered(fixture.server, first) && budget.liveCount(fixture.server) == 2
                    && consume(budget, fixture.server, 30, second, 20) == 0
                    && consume(budget, fixture.server, 30, later, 20) == 0,
                "closing the owner and adding a site do not give the old tick a replacement ticket");
            helper.assertTrue(consume(budget, fixture.server, 31, second, 20) == 4,
                "the already waiting site gets the next turn ahead of the newly registered site");
            budget.close(fixture.server, 31, second);
            budget.close(fixture.server, 31, later);
            helper.assertTrue(!budget.hasPool(fixture.server) && budget.liveCount(fixture.server) == 0,
                "closing the last site releases the pool and all live plan references");
            budget.register(fixture.server, 31, later);
            helper.assertTrue(consume(budget, fixture.server, 31, later, 20) == 0,
                "a same-tick last-close/reopen cannot create a second full server allowance");
            helper.assertTrue(consume(budget, fixture.server, 32, later, 20) == 4,
                "the reopened site is live and eligible on the following tick");
            budget.close(fixture.server, 32, later);
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", batch = "server_fill_budget")
    public static void defaultAndValueEqualNormalLimitsAlwaysUseTheProductionServerPool(GameTestHelper helper) {
        try (Fixture fixture = new Fixture(helper)) {
            FillPlan defaults = fixture.plan(fixture.level, UUID.randomUUID(), null);
            FillPlan presenceStyle = fixture.plan(fixture.level, UUID.randomUUID(), FillPlan.Limits.NORMAL);
            FillPlan sameValues = fixture.plan(fixture.level, UUID.randomUUID(), new FillPlan.Limits(16384, 2_000_000L));
            FillPlan injected = fixture.plan(fixture.level, UUID.randomUUID(), new FillPlan.Limits(Integer.MAX_VALUE, 0));
            helper.assertTrue(ServerFillBudget.registered(fixture.server, defaults)
                    && ServerFillBudget.registered(fixture.server, presenceStyle)
                    && ServerFillBudget.registered(fixture.server, sameValues),
                "both production construction paths and equal normal values register in the one server pool");
            helper.assertTrue(!ServerFillBudget.registered(fixture.server, injected),
                "only an explicit non-normal fixture budget is independent of production scheduling");
            defaults.close();
            defaults.close();
            helper.assertTrue(!ServerFillBudget.registered(fixture.server, defaults),
                "the real FillPlan close path unregisters once and leaves no global ticket");
        }
        helper.succeed();
    }

    private static int consume(ServerFillBudget.Registry budget, MinecraftServer server, long tick, FillPlan plan, int wanted) {
        return budget.allotted(server, tick, plan, slice -> {
            int count = 0;
            while (count < wanted && slice.tryStep()) count++;
            return count;
        });
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final List<FillPlan> plans = new ArrayList<>();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            server = level.getServer();
        }

        FillPlan plan(ServerLevel dimension, UUID colony) {
            return plan(dimension, colony, new FillPlan.Limits(8, 0));
        }

        FillPlan plan(ServerLevel dimension, UUID colony, FillPlan.Limits limits) {
            BlockPos cell = helper.absolutePos(new BlockPos(4 + plans.size(), 1, 12));
            if (dimension != level) cell = new BlockPos(cell.getX(),
                Math.clamp(cell.getY(), dimension.getMinBuildHeight() + 8, dimension.getMaxBuildHeight() - 9), cell.getZ());
            FillPlan plan = limits == null
                ? new FillPlan(dimension, UUID.randomUUID(), colony, Set.of(cell), List::of, () -> { })
                : new FillPlan(dimension, UUID.randomUUID(), colony, Set.of(cell), List::of, () -> { }, false, limits);
            plans.add(plan);
            return plan;
        }

        @Override public void close() {
            for (FillPlan plan : plans) plan.close();
        }
    }
}
