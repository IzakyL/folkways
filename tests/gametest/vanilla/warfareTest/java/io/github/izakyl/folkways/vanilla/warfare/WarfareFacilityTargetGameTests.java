package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.init.ModEntities;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.command.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.command.combat.net.SelectionOrders;
import io.github.izakyl.folkways.core.command.combat.net.UnitOrders;
import io.github.izakyl.folkways.core.command.combat.net.UnitRequest;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.testing.SelectedTests;
import io.github.izakyl.folkways.core.testing.ShootRoutine;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class WarfareFacilityTargetGameTests {
    private static final String NAME = "Target test turret";
    private static final String ERROR = "folkways.rts.facility.error.";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        SelectedTests.register(event, WarfareFacilityTargetGameTests.class);
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare", template = "warfare_range",
        timeoutTicks = 600)
    public static void cardRunsTheShootScriptFromEntityAndPositionRequests(GameTestHelper helper) {
        var scene = new WarfareGameTests.Scene(helper, true);
        var turret = scene.station(ModEntities.MK_42.get(), 6, 10);
        var services = DefinedFacilities.of(scene.colony).orElseThrow();
        var data = definition(scene, turret.getUUID(), false);
        var invalid = new LinkedHashMap<>(data);
        invalid.put("ui", Map.of("card", List.of(Map.of("target", "aim", "pick", "area"))));
        helper.assertTrue(services.preview(NAME, Optional.of(invalid)).problems().stream()
            .anyMatch(problem -> problem.path().endsWith(".pick")), "unknown pick is refused in the definition");
        FacilityDefinition facility = install(helper, services, data);
        ServerPlayer player = commander(scene);
        var target = scene.enemy(44, 10);
        var entity = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.of(target.getUUID()), Optional.empty());
        var point = new Vehicles.Order(Vehicles.Action.STOP, List.of(scene.at(44, 2, 14)), -1, Optional.empty(),
            Optional.empty());
        helper.assertValueEqual(send(player, scene, facility, 0, point).key(), ERROR + "pick",
            "position cannot be sent to an entity-only element");
        helper.assertValueEqual(send(player, scene, facility, 1, entity).key(), ERROR + "pick",
            "entity cannot be sent to a position-only element");
        helper.assertValueEqual(send(player, scene, facility, 2, point).key(), ERROR + "hidden",
            "a target request must name a card element");

        helper.assertTrue(send(player, scene, facility, 0, entity).accepted(), "the entity target runs the shoot script");
        helper.assertValueEqual(send(player, scene, facility, 1, point).key(), ERROR + "disabled.running",
            "a second element for the same running routine is disabled");
        helper.startSequence()
            .thenWaitUntil(() -> completed(helper, services))
            .thenExecute(() -> helper.assertTrue(send(player, scene, facility, 1, point).accepted(),
                "the position target runs the shoot script"))
            .thenWaitUntil(() -> completed(helper, services))
            .thenSucceed();
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare", template = "warfare_range")
    public static void scannedHostileRunsTheAssignedConditionWithoutResidents(GameTestHelper helper) {
        var scene = new WarfareGameTests.Scene(helper, false, 3, 10, true);
        var turret = scene.station(ModEntities.MK_42.get(), 6, 10);
        var services = DefinedFacilities.of(scene.colony).orElseThrow();
        FacilityDefinition facility = install(helper, services, definition(scene, turret.getUUID(), true));
        var target = scene.enemy(44, 10);
        scene.enemy(70, 10);
        helper.assertTrue(scene.colony.residents().isEmpty(), "there are no residents to report contact");
        helper.succeedWhen(() -> {
            helper.assertValueEqual(services.read(NAME).orElseThrow().readings().get("nearby"), 1.0,
                "scan counts only hostiles within range");
            var assignment = FireControl.of(scene.colony).assigned(facility.id(), scene.level.getServer());
            helper.assertTrue(assignment.filter(each -> each.target().target().equals(target.getUUID())).isPresent(),
                "fire control assigns the scanned hostile");
            helper.assertValueEqual(services.running(NAME), List.of("aim"), "fire control submits the shoot script with the UUID");
        });
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare", template = "warfare_range")
    public static void directRtsAttackPrioritizesTheMarkedHostile(GameTestHelper helper) {
        var scene = new WarfareGameTests.Scene(helper, false);
        ServerPlayer player = commander(scene);
        var near = scene.enemy(12, 10);
        var far = scene.enemy(22, 10);
        var control = FireControl.of(scene.colony);
        var server = scene.level.getServer();
        long now = server.overworld().getGameTime();
        UUID gun = UUID.randomUUID();
        Vec3 origin = scene.body.position();
        var unit = new FireControl.Unit(scene.level.dimension(), origin, new Vec3(1, 0, 0), 20, 20, true,
            spot -> Optional.of(new FireControl.Reach(50, false)));
        var unregister = FireControl.registerSource(
            FireControl.ID.withSuffix("/test_" + gun),
            (colony, current) -> colony.id().equals(scene.colony.id())
                ? List.of(FireControl.contact(near, now), FireControl.contact(far, now)) : List.of());
        try {
            control.register(gun, unit, now);
            helper.assertValueEqual(control.assigned(gun, server).orElseThrow().target().target(), near.getUUID(),
                "unmarked contacts are ordered by distance");
            UUID resident = scene.body.getUUID();
            helper.assertTrue(CombatSelectionOwnership.of(scene.colony).select(player, List.of(resident)),
                "the commander selects a resident");
            helper.assertTrue(SelectionOrders.attack(player, scene.colony.id(), List.of(resident), far.getUUID()),
                "the RTS accepts an explicit attack");
            control.remove(gun);
            control.register(gun, unit, now);
            helper.assertValueEqual(control.assigned(gun, server).orElseThrow().target().target(), far.getUUID(),
                "the RTS mark gives the farther target priority");
        } finally {
            unregister.run();
        }
        helper.succeed();
    }

    private static void completed(GameTestHelper helper, DefinedFacilities services) {
        helper.assertTrue(services.running(NAME).isEmpty()
            && services.recent(NAME).filter(result -> result.ending().equals("done")).isPresent(),
            "waiting for the shoot script to finish aiming: " + services.recent(NAME));
    }

    private static FacilityDefinition install(GameTestHelper helper, DefinedFacilities services, Map<String, Object> data) {
        var preview = services.preview(NAME, Optional.of(data));
        helper.assertTrue(preview.problems().isEmpty(), "turret facility validates: " + preview.problems());
        helper.assertTrue(services.apply(preview).isEmpty(), "turret facility installed");
        return services.facilities().getFirst();
    }

    private static ServerPlayer commander(WarfareGameTests.Scene scene) {
        ServerPlayer player = FakePlayerFactory.get(scene.level, new GameProfile(UUID.randomUUID(), "facility_target"));
        player.moveTo(scene.body.position());
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(scene.colony.id()));
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
        Charters.keep(scene.colony, Charter.founded(NAME, player.getUUID()));
        return player;
    }

    private static Vehicles.Result send(ServerPlayer player, WarfareGameTests.Scene scene, FacilityDefinition facility, int index,
                                        Vehicles.Order order) {
        return UnitOrders.handle(player, new UnitRequest(0, scene.colony.id(), facility.id(),
            UnitRequest.Operation.FACILITY_TARGET, List.of(), -1, index, order, "aim", WeaponRules.DEFAULT,
            Optional.empty())).result();
    }

    private static Map<String, Object> definition(WarfareGameTests.Scene scene, UUID turret, boolean scan) {
        WorldPos at = scene.at(6, 1, 10);
        var xyz = List.of(at.cell().getX(), at.cell().getY(), at.cell().getZ());
        var triggers = new LinkedHashMap<String, Object>();
        triggers.put("manual", Map.of("kind", "manual", "routine", "aim"));
        var data = new LinkedHashMap<String, Object>();
        data.put("name", NAME);
        data.put("extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", scene.level.dimension().location().toString()),
            "min", xyz, "max", xyz), Map.of("entity", turret.toString()))));
        data.put("controls", Map.of("aim", Map.of("part", "aim/" + turret, "offset", List.of(0, 0, 0))));
        data.put("routines", Map.of("aim", ShootRoutine.with(
            Map.of("aim", "aim", "ammunition", List.of(List.of("superbwarfare:large_shell_he", 1))))));
        data.put("triggers", triggers);
        data.put("ui", Map.of("card", List.of(Map.of("target", "aim", "pick", "entity"),
            Map.of("target", "aim", "pick", "pos", "default", true))));
        if (scan) {
            data.put("readings", Map.of("nearby", Map.of("probe", "folkways_vanilla:hostile_within", "range", 48)));
            triggers.put("auto", Map.of("kind", "condition", "routine", "aim", "source", "folkways_core:fire_control",
                "period", 10, "cooldown", 120, "enabled", true));
        }
        return data;
    }
}
