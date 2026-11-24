package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class PlayerFacilityGameTests {

    private static final String NAME = "North gate";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(PlayerFacilityGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void playerGateReplacesAndRestoresPrefab(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        Cleanup.on(helper, fixture::close);
        DefinedFacilities service = DefinedFacilities.of(fixture.colony).orElseThrow();
        BlockPos station = fixture.lever.at().cell().north();
        fixture.level.setBlockAndUpdate(station, Blocks.CRAFTING_TABLE.defaultBlockState());
        var data = definition(fixture);
        data.put("extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", fixture.level.dimension().location().toString()),
            "min", pos(station), "max", pos(fixture.door.cell().above())))));
        data.put("anchor", pos(fixture.lever.at().cell()));
        FacilityDefinition facility = FacilityDefinition.fromData(UUID.randomUUID(), data);
        var found = service.discover(fixture.level, facility.extent());
        helper.assertTrue(found.controls().stream().anyMatch(candidate ->
            candidate.position().equals(fixture.lever)
                && candidate.kind().equals(new Rig.Signalled(SignalType.BOOL))
                && candidate.reading().equals(Optional.of(Value.of(false)))), "the lever is a readable candidate");
        helper.assertTrue(found.prefabs().size() == 1 && found.overlaps().isEmpty(), "one prefab would be covered");

        helper.assertTrue(facility.projections(fixture.level, Map.of("0", Value.of(true))).stream()
            .filter(Contribution.Hop.class::isInstance).count() == 4,
            "a single column without axes offers crossings along both axes");
        BlockPos door = fixture.door.cell();
        data.put("projections", List.of(Map.of("gate", Map.of("cells", List.of(pos(door), pos(door.above())),
            "axes", List.of("x"), "open_when", Map.of("lever", true)))));
        var preview = service.preview(NAME, Optional.of(data));
        helper.assertTrue(preview.problems().isEmpty() && preview.covered().size() == 1,
            "valid gate preview: " + preview.problems());
        helper.assertTrue(service.apply(preview).isEmpty(), "gate installed");
        helper.assertTrue(Prefabs.at(fixture.level, station).isEmpty(), "prefab is hidden");
        helper.assertTrue(Passages.published(fixture.level, fixture.colony.id()).isEmpty(),
            "the closed player gate publishes no hops");
        fixture.powerLever();
        helper.runAfterDelay(2, () -> {
            helper.assertTrue(fixture.route() instanceof Faring.Yes yes
                && yes.by().legs().stream().anyMatch(leg -> leg.hop().isPresent()), "the route uses the open gate");
            var removal = service.preview(NAME, Optional.empty());
            helper.assertTrue(removal.restored().size() == 1 && service.apply(removal).isEmpty(), "gate deleted");
            helper.assertTrue(Prefabs.at(fixture.level, station).isPresent(), "prefab restored");
            helper.assertTrue(Passages.published(fixture.level, fixture.colony.id()).isEmpty(),
                "deleting the player gate removes both directed hops");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void manualAndEnabledScheduleSubmitResidentWork(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        ResidentEntity resident = (ResidentEntity) fixture.body.mob();
        fixture.colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(fixture.colony.id());
        fixture.colony.entered(resident);
        LaborFixture labor = new LaborFixture(fixture.colony, fixture.level);
        Cleanup.on(helper, () -> {
            labor.close();
            fixture.close();
        });
        helper.onEachTick(labor::tick);
        DefinedFacilities service = DefinedFacilities.of(fixture.colony).orElseThrow();
        long closeAt = fixture.level.getDayTime() + 250;
        Map<String, Object> data = definition(fixture);
        data.put("triggers", Map.of("open", Map.of("kind", "manual", "routine", "open"),
            "night", schedule("close", closeAt, true)));
        helper.assertTrue(service.apply(service.preview(NAME, Optional.of(data))).isEmpty(), "scheduled facility added");
        helper.assertTrue(service.run(NAME, "open", Map.of("lever", Value.of(true))).isEmpty(), "manual run accepted");
        long[] reopenAt = {0};
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(done(service, "open") && fixture.open(), "manual open is pending");
        }).thenWaitUntil(() -> {
            helper.assertTrue(done(service, "close") && !fixture.open(), "scheduled close is pending");
        }).thenExecute(() -> {
            // Resident work can finish after a second prearranged schedule has already fired.
            // Start the disabled-trigger observation from the completed close.
            reopenAt[0] = fixture.level.getDayTime() + 2;
            data.put("triggers", Map.of("open", Map.of("kind", "manual", "routine", "open"),
                "night", schedule("close", closeAt, true), "reopen", schedule("open", reopenAt[0], false)));
            helper.assertTrue(service.apply(service.preview(NAME, Optional.of(data))).isEmpty(),
                "disabled reopening schedule installed");
        }).thenWaitUntil(() -> {
            helper.assertTrue(fixture.level.getDayTime() > reopenAt[0], "waiting past the disabled schedule");
        }).thenExecute(() -> {
            helper.assertTrue(service.running(NAME).isEmpty() && done(service, "close") && !fixture.open(),
                "the disabled trigger did not submit work or reopen the gate");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void invalidDefinitionsPointToTheProblem(GameTestHelper helper) {
        try (var fixture = new FacilityGateFixture(helper)) {
            DefinedFacilities service = DefinedFacilities.of(fixture.colony).orElseThrow();
            var data = definition(fixture);
            data.put("ui", Map.of("card", List.of(Map.of("readout", "missing", "as", "text"))));
            rejected(helper, service, NAME, data, "ui.card[0].readout");
            data = definition(fixture);
            data.put("routines", Map.of("open",
                Map.of("use", "folkways_core:write_all", "with", Map.of("lever", 4))));
            rejected(helper, service, NAME, data, "routines.open.with.lever");
            data = definition(fixture);
            data.put("controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(-20, 0, 0))));
            rejected(helper, service, NAME, data, "controls.lever.offset");
            data = definition(fixture);
            data.put("projections", List.of(Map.of("gate", Map.of(
                "cells", List.of(pos(fixture.door.cell())), "axes", List.of("y"),
                "open_when", Map.of("lever", true)))));
            rejected(helper, service, NAME, data, "projections[0].gate.axes");
            data = definition(fixture);
            helper.assertTrue(service.apply(service.preview(NAME, Optional.of(data))).isEmpty(), "first gate added");
            data.put("name", "Other gate");
            rejected(helper, service, "Other gate", data, "extent");
        }
        helper.succeed();
    }

    private static void rejected(GameTestHelper helper, DefinedFacilities service, String name, Map<String, Object> data,
                                 String path) {
        var preview = service.preview(name, Optional.of(data));
        helper.assertTrue(preview.problems().stream().anyMatch(problem -> problem.path().equals(path)),
            "expected " + path + " in " + preview.problems());
        helper.assertFalse(service.apply(preview).isEmpty(), "invalid preview cannot be applied");
    }

    private static boolean done(DefinedFacilities service, String routine) {
        return service.read(NAME).orElseThrow().recent()
            .filter(result -> result.routine().equals(routine) && result.ending().equals("done")).isPresent();
    }

    private static Map<String, Object> schedule(String routine, long time, boolean enabled) {
        return Map.of("kind", "schedule", "routine", routine, "dayTime", Math.floorMod(time, 24000),
            "enabled", enabled);
    }

    private static Map<String, Object> definition(FacilityGateFixture fixture) {
        BlockPos door = fixture.door.cell();
        BlockPos lever = fixture.lever.at().cell();
        var extent = new FacilityExtent(fixture.door.realm(), lever, door.above());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", NAME);
        data.put("extent", io.github.izakyl.folkways.core.facility.api.DefinitionCodec.extent(extent));
        data.put("controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(0, 0, 0))));
        data.put("routines", Map.of("open", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", true)),
            "close", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", false))));
        data.put("triggers", Map.of("open", Map.of("kind", "manual", "routine", "open")));
        data.put("ui", Map.of("card", List.of(Map.of("button", "open"),
            Map.of("readout", "lever", "as", "light"))));
        data.put("projections", List.of(Map.of("gate", Map.of("cells", List.of(pos(door), pos(door.above())),
            "open_when", Map.of("lever", true)))));
        return data;
    }

    private static List<Integer> pos(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }
}
