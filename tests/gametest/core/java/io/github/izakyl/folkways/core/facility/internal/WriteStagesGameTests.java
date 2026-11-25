package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class WriteStagesGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WriteStagesGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void plansOnlyTravelThatRemains(GameTestHelper helper) {
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 1));
        WorldPos site = WorldPos.of(helper.getLevel(), start);
        var id = Routines.WRITE_STAGES;
        Routine routine = Routines.of(id).orElseThrow();
        var arguments = Map.of("x", Value.of(3), "y", Value.of(2), "z", Value.of(4));
        var rig = new Rig(Map.of("x", part(site, "x", 3), "y", part(site, "y", 0), "z", part(site, "z", 1)));
        var call = new Routine.Call(UUID.randomUUID(), id, UUID.randomUUID(), site, Map.of(),
            arguments, Map.of(), Optional.of(rig));
        var snapshot = routine.snapshot(helper.getLevel(), call);
        var plan = routine.expand(snapshot).orElseThrow().replacement();
        helper.assertTrue(plan.nodes().size() == 3 && plan.links().size() == 2
            && plan.nodes().getLast().id().equals(call.id()), "two unsettled axes precede the final reading check");
        helper.succeed();
    }

    private static Rig.Part part(WorldPos site, String name, double travel) {
        return new Rig.Part(name, new ControlRef(site, name), new WorkSite.AtBlock(site), Stances.WHEREVER,
            Control.Cost.INSTANT, new Rig.Signalled(new SignalType.Real(0, 16, 1)), Optional.of(Value.of(travel)));
    }
}
