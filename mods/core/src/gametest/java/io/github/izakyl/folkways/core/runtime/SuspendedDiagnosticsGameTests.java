package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import io.github.izakyl.folkways.core.plan.internal.WeaveDelta;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.front.engine.colony.LaborDebug;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SuspendedDiagnosticsGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SuspendedDiagnosticsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void snapshotTracksSuspensionAndItsReservations(GameTestHelper helper) throws Exception {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Node node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Placement placement = new Placement(Set.of(), List.of(), List.of(), List.of(),
                List.of(new Placement.Draw(Optional.empty(), iron, 7)));
            h.body.pack().setItem(0, new ItemStack(Items.IRON_INGOT, 7));
            h.start(node, placement);
            Claims claims = new Claims();
            claims.take(new Claim(new Resource.Cargo(h.body.resident().id(), iron), node.id(), 7));
            claims.take(new Claim(new Resource.Space(Stash.at(WorldPos.of(h.level, h.at)), iron), node.id(), 2));
            PlanningFixture.apply(h.labor, h.level, List.of(), WeaveDelta.NONE, claims.snapshot());
            claims.release(node.id());
            helper.assertTrue(h.labor.snapshot(h.level).suspended().isEmpty(), "active work is not suspended");
            long since = h.level.getGameTime();
            h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.UNLOAD_TRIP);
            LaborSnapshot snapshot = h.labor.snapshot(h.level);
            helper.assertValueEqual(snapshot.suspended().size(), 1, "the suspended node appears in the snapshot");
            LaborSnapshot.Suspended entry = snapshot.suspended().getFirst();
            helper.assertTrue(entry.work().node().equals(node.id())
                    && entry.work().owner().equals(node.spec().owner())
                    && entry.work().lastResident().equals(h.body.resident().id())
                    && entry.work().sinceTick() == since && entry.work().reason() == PreemptReason.UNLOAD_TRIP,
                "the snapshot identifies the work, resident, time and reason");
            helper.assertTrue(entry.inputs().size() == 1 && entry.inputs().getFirst().goods().equals(iron)
                    && entry.inputs().getFirst().count() == 7,
                "the snapshot includes the actual outstanding input reservation");
            helper.assertTrue(entry.claims().size() == 2
                    && entry.claims().stream().anyMatch(claim -> claim.on() instanceof Resource.Space && claim.amount() == 2),
                "the copied planner snapshot includes reserved output space");
            var formatter = LaborDebug.class.getDeclaredMethod("suspendedLine", LaborSnapshot.Suspended.class);
            formatter.setAccessible(true);
            Component message = (Component) formatter.invoke(null, entry);
            String json = Component.Serializer.toJson(message, h.level.registryAccess());
            helper.assertTrue(json.contains("folkways.debug.suspended")
                    && json.contains("folkways.debug.suspended.reason.unload_trip")
                    && json.contains(node.id().toString()) && json.contains(node.spec().owner().toString())
                    && json.contains(h.body.resident().id().toString()) && json.contains("minecraft:iron_ingot")
                    && json.contains("folkways.debug.suspended.claim.cargo")
                    && json.contains("folkways.debug.suspended.claim.space"),
                "the debug message includes the snapshot and translatable reason");
            h.runner.resume(h.level, h.body);
            helper.assertTrue(h.labor.snapshot(h.level).suspended().isEmpty(), "resumed work disappears");
            h.runner.preemptHard(h.level, h.body, Disposition.SUSPEND, PreemptReason.URGE);
            h.labor.execution().phases().cancelled(node.id());
            h.runner.check(h.level, h.body);
            helper.assertTrue(h.labor.snapshot(h.level).suspended().isEmpty(), "revoked work disappears");
            helper.assertValueEqual(snapshot.suspended().size(), 1, "previous snapshots remain immutable");
        }
        helper.succeed();
    }
}
