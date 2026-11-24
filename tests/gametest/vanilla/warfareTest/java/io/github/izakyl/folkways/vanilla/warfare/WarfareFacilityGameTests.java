package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.init.ModEntities;
import com.atsuishio.superbwarfare.init.ModItems;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.core.command.combat.net.FacilityOrders;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.SelectedTests;
import io.github.izakyl.folkways.core.testing.ShootRoutine;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class WarfareFacilityGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        SelectedTests.register(event, WarfareFacilityGameTests.class);
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare", template = "warfare_range",
        timeoutTicks = 1000)
    public static void assignedTurretResuppliesAndHoldsForFriendly(GameTestHelper helper) {
        var scene = new WarfareGameTests.Scene(helper, true);
        var turret = scene.station(ModEntities.MK_42.get(), 6, 10);
        var shell = ModItems.LARGE_SHELL_HE.get();
        scene.store.setItem(0, new ItemStack(shell, 1));
        var services = DefinedFacilities.of(scene.colony).orElseThrow();
        var pos = turret.blockPosition();
        var xyz = List.of(pos.getX(), pos.getY(), pos.getZ());
        String id = turret.getUUID().toString();
        String name = "Test turret";
        var data = Map.<String, Object>of("name", name,
            "extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", scene.level.dimension().location().toString()),
                "min", xyz, "max", xyz), Map.of("entity", id.toString()))),
            "controls", Map.of("aim", Map.of("part", "aim/" + id, "offset", List.of(0, 0, 0)),
                "fire", Map.of("part", "fire/" + id, "offset", List.of(0, 0, 0)),
                "ammo", Map.of("part", "ammo/" + id, "offset", List.of(0, 0, 0))),
            "routines", Map.of("shoot", ShootRoutine.with(Map.of("aim", "aim", "fire", "fire", "ammo", "ammo",
                "ammunition", List.of(List.of(BuiltInRegistries.ITEM.getKey(shell).toString(), 1))))),
            "triggers", Map.of("auto", Map.of("kind", "condition", "routine", "shoot",
                "source", "folkways_core:fire_control", "period", 10, "cooldown", 120, "enabled", true)));
        var preview = services.preview(name, Optional.of(data));
        helper.assertTrue(preview.problems().isEmpty(), "turret facility validates: " + preview.problems());
        helper.assertTrue(services.apply(preview).isEmpty(), "turret facility installed");
        AtomicReference<FireControl.Contact> observed = new AtomicReference<>();
        Cleanup.on(helper, FireControl.registerSource(ResourceLocation.parse("folkways_vanilla:test_turret"),
            (colony, server) -> !colony.id().equals(scene.colony.id()) || observed.get() == null
                ? List.of() : List.of(observed.get())));
        var friendly = PersonBody.RESIDENT.get().create(scene.level);
        friendly.setNoAi(true);
        scene.place(friendly, 43, 10);
        scene.level.addFreshEntity(friendly);
        scene.colony.hold(PersonContent.ID, Claimed.Entity.of(friendly)).orElseThrow();
        friendly.joinColony(scene.colony.id());
        scene.entities.add(friendly);

        helper.startSequence().thenExecuteAfter(15, () -> {
            helper.assertTrue(services.running(name).isEmpty(), "trigger waits for a contact");
            var target = scene.enemy(44, 10);
            observed.set(new FireControl.Contact(new Spot(target.getUUID(), target.getId(), scene.level.dimension(),
                target.position(), scene.level.getGameTime()), target.getHealth(), 1, 0));
        }).thenExecuteAfter(50, () -> {
            helper.assertTrue(services.running(name).isEmpty(), "automatic fire does not schedule an unsafe shot");
            helper.assertValueEqual(scene.shots.get(), 0, "the target behind a resident is skipped");
            friendly.discard();
        }).thenWaitUntil(() -> helper.assertTrue(scene.shots.get() == 1,
            "the clear target is assigned, supplied and fired: " + services.recent(name) + " " + scene.debug()))
            .thenSucceed();
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare", template = "warfare_range",
        timeoutTicks = 1000)
    public static void manualTurretShowsAimAndFires(GameTestHelper helper) {
        var scene = new WarfareGameTests.Scene(helper, true);
        var turret = scene.station(ModEntities.MK_42.get(), 6, 10);
        var shell = ModItems.LARGE_SHELL_HE.get();
        scene.store.setItem(0, new ItemStack(shell, 1));
        var services = DefinedFacilities.of(scene.colony).orElseThrow();
        var pos = turret.blockPosition();
        var xyz = List.of(pos.getX(), pos.getY(), pos.getZ());
        String id = turret.getUUID().toString();
        String name = "Manual turret";
        var data = Map.<String, Object>of("name", name,
            "extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", scene.level.dimension().location().toString()),
                "min", xyz, "max", xyz), Map.of("entity", id.toString()))),
            "controls", Map.of("aim", Map.of("part", "aim/" + id, "offset", List.of(0, 0, 0)),
                "fire", Map.of("part", "fire/" + id, "offset", List.of(0, 0, 0)),
                "ammo", Map.of("part", "ammo/" + id, "offset", List.of(0, 0, 0))),
            "routines", Map.of("shoot", ShootRoutine.with(Map.of("aim", "aim", "fire", "fire", "ammo", "ammo",
                "ammunition", List.of(List.of(BuiltInRegistries.ITEM.getKey(shell).toString(), 1))))),
            "readings", Map.of("nearby", Map.of("probe", "folkways_vanilla:hostile_within", "range", 48)),
            "triggers", Map.of("order", Map.of("kind", "manual", "routine", "shoot")),
            "ui", Map.of("card", List.of(), "overlay", List.of(Map.of("range", "nearby"), Map.of("path", "shoot"))));
        var preview = services.preview(name, Optional.of(data));
        helper.assertTrue(preview.problems().isEmpty(), "turret facility validates: " + preview.problems());
        helper.assertTrue(services.apply(preview).isEmpty(), "turret facility installed");
        var facility = services.facilities().getFirst();
        var target = Vec3.atBottomCenterOf(scene.at(44, 1, 10).block(scene.level));

        // The script's path hook is sampled off the tick and published a little later.
        helper.startSequence().thenWaitUntil(() -> {
            var status = FacilityOrders.status(scene.level.getServer(), scene.colony, facility.id()).orElseThrow();
            helper.assertTrue(status.overlays().stream().anyMatch(overlay ->
                    overlay instanceof Operating.Overlay.Ring ring && ring.radius() == 48),
                "the range ring takes the scan range: " + status.overlays());
            helper.assertTrue(status.overlays().stream().anyMatch(overlay ->
                    overlay instanceof Operating.Overlay.Path aim && aim.points().size() > 1),
                "the aim line follows the turret's current aim: " + status.overlays());
        }).thenExecute(() -> {
            var problems = services.run(name, "shoot", Map.of("target", Value.of(target.x, target.y, target.z)));
            helper.assertTrue(problems.isEmpty(), "firing run submitted: " + problems);
        }).thenWaitUntil(() -> {
            if (scene.shots.get() == 0 && services.running(name).isEmpty()) {
                // A run the plan gave up before the shot is asked for again, as a conditional trigger would.
                services.run(name, "shoot", Map.of("target", Value.of(target.x, target.y, target.z)));
            }
            helper.assertTrue(scene.shots.get() == 1,
                "waiting for the shot: " + services.recent(name) + " " + scene.debug());
        }).thenWaitUntil(() -> helper.assertTrue(
            services.recent(name).filter(result -> result.ending().equals("done")).isPresent(),
            "the manual firing run completes: " + services.recent(name)))
            .thenSucceed();
    }
}
