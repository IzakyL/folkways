package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class FireControlGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FireControlGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void spreadsFireUntilPlayerMarksFocus(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        try {
            FireControl control = FireControl.of(colony);
            long now = level.getServer().overworld().getGameTime();
            Vec3 origin = Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO));
            List<FireControl.Contact> targets = List.of(contact(level, origin.add(5, 0, 0), now),
                contact(level, origin.add(10, 0, 0), now));
            ResourceLocation source = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "test_fire_control");
            Runnable unregister = FireControl.registerSource(source, (owner, server) ->
                owner.id().equals(colony.id()) ? targets : List.of());
            try {
                UUID first = UUID.randomUUID();
                UUID second = UUID.randomUUID();
                FireControl.Unit unit = new FireControl.Unit(level.dimension(), origin, new Vec3(1, 0, 0),
                    20, 20, true, target -> Optional.of(new FireControl.Reach(30, false)));
                control.register(first, unit, now);
                control.register(second, unit, now);
                helper.assertValueEqual(target(control, level, first), targets.getFirst().spot(),
                    "first gun gets the nearest contact");
                helper.assertValueEqual(target(control, level, second), targets.getLast().spot(),
                    "second gun gets the other contact");

                control.remove(first);
                control.remove(second);
                control.mark(targets.getLast(), now + FireControl.PERIOD);
                control.register(first, unit, now);
                control.register(second, unit, now);
                helper.assertValueEqual(target(control, level, first), targets.getLast().spot(),
                    "first gun prefers the marked contact");
                helper.assertValueEqual(target(control, level, second), targets.getLast().spot(),
                    "second gun obeys the focus mark");
            } finally {
                unregister.run();
            }
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    private static Spot target(FireControl control, ServerLevel level, UUID unit) {
        return control.assigned(unit, level.getServer()).orElseThrow().target();
    }

    private static FireControl.Contact contact(ServerLevel level, Vec3 position, long now) {
        return new FireControl.Contact(new Spot(UUID.randomUUID(), -1, level.dimension(), position, now), 20, 1, 0);
    }
}
