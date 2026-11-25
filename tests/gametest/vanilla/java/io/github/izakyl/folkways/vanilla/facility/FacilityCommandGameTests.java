package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.card.api.Card;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.command.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.command.combat.net.FacilityOrders;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.card.net.CardSync;
import io.github.izakyl.folkways.core.command.combat.net.UnitOrders;
import io.github.izakyl.folkways.core.command.combat.net.UnitReply;
import io.github.izakyl.folkways.core.command.combat.net.UnitRequest;
import io.github.izakyl.folkways.core.command.combat.net.UnitSnapshot;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// The facility command card's server side: what the RTS snapshot lists, what its buttons and toggles may do, and who
// hears the readings.
@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class FacilityCommandGameTests {

    private static final String NAME = "North gate";
    private static final Vehicles.Order STOP = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1,
        Optional.empty(), Optional.empty());

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityCommandGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void theFacilityCardRunsTogglesAndSyncsOnlyToItsSelector(GameTestHelper helper) {
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
        ServerPlayer commander = player(helper, fixture, "facility_commander");
        ServerPlayer bystander = player(helper, fixture, "facility_bystander");
        ServerPlayer outsider = FakePlayerFactory.get(fixture.level, new GameProfile(UUID.randomUUID(),
            "facility_outsider"));
        outsider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
        Charters.keep(fixture.colony, Charter.founded("FacilityDefinition card test", commander.getUUID())
            .with(bystander.getUUID()));

        DefinedFacilities facilities = DefinedFacilities.of(fixture.colony).orElseThrow();
        helper.assertTrue(facilities.apply(facilities.preview(NAME, Optional.of(definition(fixture)))).isEmpty(),
            "the gate is installed");
        FacilityDefinition facility = facilities.facilities().getFirst();
        var badIcon = new LinkedHashMap<>(definition(fixture));
        badIcon.put("ui", Map.of("card", List.of(Map.of("button", "open", "icon", "minecraft:no_such_item"))));
        helper.assertTrue(facilities.preview(NAME, Optional.of(badIcon)).problems().stream().anyMatch(problem ->
            problem.reason().equals("icon")), "an unknown icon item is refused");
        var badOverlay = new LinkedHashMap<>(definition(fixture));
        badOverlay.put("ui", Map.of("card", List.of(), "overlay", List.of(Map.of("range", "nowhere"),
            Map.of("path", "nowhere"))));
        var overlayProblems = facilities.preview(NAME, Optional.of(badOverlay)).problems();
        helper.assertTrue(overlayProblems.stream().anyMatch(problem -> problem.path().equals("ui.overlay[0].range")
                && problem.reason().equals("missing"))
            && overlayProblems.stream().anyMatch(problem -> problem.path().equals("ui.overlay[1].path")
                && problem.reason().equals("missing")),
            "a ring needs a number to size it and a path needs a named routine: " + overlayProblems);

        UnitSnapshot snapshot = UnitOrders.snapshot(commander, fixture.colony);
        var unit = snapshot.units().stream().filter(each -> each.id().equals(facility.id())).findFirst();
        helper.assertTrue(unit.filter(each -> each.kind() == UnitSnapshot.Kind.FACILITY
                && each.name().getString().equals(NAME)
                && each.facility().filter(view -> view.extent().equals(facility.extent())
                    && view.card().equals(facility.card()) && view.triggers().get("night")).isPresent()).isPresent(),
            "the snapshot lists the facility with its extent, interface and switches: " + unit);

        helper.assertFalse(send(outsider, fixture, facility, UnitRequest.Operation.FACILITY_RUN, "open", -1)
            .result().accepted(), "a stranger cannot press the card");
        helper.assertTrue(send(commander, fixture, facility, UnitRequest.Operation.FACILITY_RUN, "close", -1)
            .result().key().equals("folkways.rts.facility.error.hidden"), "a routine off the card is refused");
        helper.assertTrue(facilities.running(NAME).isEmpty() && facilities.recent(NAME).isEmpty(), "nothing ran yet");

        var toggled = send(commander, fixture, facility, UnitRequest.Operation.FACILITY_TOGGLE, "night", 0);
        helper.assertTrue(toggled.result().accepted() && !facilities.read(NAME).orElseThrow().triggers().get("night")
                && toggled.snapshot().flatMap(view -> view.units().stream().filter(each -> each.id()
                    .equals(facility.id())).findFirst()).flatMap(UnitSnapshot.Unit::facility)
                    .filter(view -> !view.triggers().get("night")).isPresent(),
            "the toggle switches the night trigger off and the reply shows it");
        helper.assertTrue(facilities.toggle(NAME, "open", false).stream().anyMatch(problem ->
            problem.reason().equals("background")), "a manual trigger has no switch, as on the state path");

        CombatSelectionOwnership ownership = CombatSelectionOwnership.of(fixture.colony);
        helper.assertTrue(ownership.select(commander, List.of(facility.id()))
            && ownership.selectedFacilities(commander).equals(List.of(facility.id())), "the commander selects the gate");
        helper.assertTrue(ownership.select(bystander, List.of(facility.id()))
            && ownership.select(bystander, List.of()), "facilities are shared: a second member may select and drop one");
        helper.assertTrue(ownership.select(bystander, List.of(facility.id(), resident.getUUID()))
            && ownership.snapshot(bystander).selection().equals(List.of(resident.getUUID()))
            && ownership.select(bystander, List.of()), "a facility is never selected with a resident");
        List<CardStatus> due = CardSync.due(commander);
        helper.assertTrue(due.size() == 1 && due.getFirst().readings().stream().map(CardStatus.Reading::name)
                .toList().equals(List.of("lever"))
                && due.getFirst().readings().getFirst().value().equals(Optional.of(Value.of(false)))
                && due.getFirst().disabled().isEmpty(),
            "the selector hears only the readings the interface names, and every button is pressable: " + due);
        Vec3 lever = Vec3.atCenterOf(fixture.lever.at().cell());
        helper.assertTrue(due.getFirst().overlays().equals(List.of(new Operating.Overlay.Ring(lever, 12))),
            "the selector hears the range ring, sized by its parameter and centred on the facility: " + due);
        helper.assertTrue(CardSync.due(bystander).isEmpty() && CardSync.due(outsider).isEmpty(),
            "nobody else hears them");
        helper.assertTrue(CardSync.due(commander).isEmpty(), "an unchanged status is not sent again");

        helper.assertTrue(send(commander, fixture, facility, UnitRequest.Operation.FACILITY_RUN, "open", -1)
            .result().accepted() && facilities.running(NAME).equals(List.of("open")), "the button submits its routine");
        helper.assertTrue(CardSync.due(commander).getFirst().disabled().equals(Map.of("open", "running"))
            && send(commander, fixture, facility, UnitRequest.Operation.FACILITY_RUN, "open", -1).result().key()
                .equals("folkways.rts.facility.error.disabled.running"), "a running button is disabled and refused");
        helper.succeedWhen(() -> {
            helper.assertTrue(fixture.open() && facilities.recent(NAME).filter(run -> run.ending().equals("done"))
                .isPresent(), "waiting for the resident to open the gate");
            helper.assertTrue(facilities.running(NAME).isEmpty(), "the run is over");
            helper.assertTrue(FacilityOrders.status(fixture.level.getServer(), fixture.colony, facility.id())
                    .orElseThrow().disabled().equals(Map.of("open", "state"))
                && send(commander, fixture, facility, UnitRequest.Operation.FACILITY_RUN, "open", -1).result().key()
                    .equals("folkways.rts.facility.error.disabled.state"),
                "an open gate's open button is disabled by its enabled_when and refused");
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void choiceDataRoundTripsAndRejectsInvalidElements(GameTestHelper helper) {
        try (var fixture = new FacilityGateFixture(helper)) {
            DefinedFacilities facilities = DefinedFacilities.of(fixture.colony).orElseThrow();
            var data = definition(fixture);
            var choice = Map.<String, Object>of("choice", "mode", "options", List.of("yes", "no"),
                "labels", List.of("Yes", "No"), "icons", List.of("minecraft:iron_door", ""),
                "icon", "minecraft:lever", "label", "Mode", "default", "no", "run", "open",
                "enabled_when", Map.of("lever", false));
            var ui = Map.of("card", List.of(choice));
            data.put("ui", ui);
            FacilityDefinition facility = FacilityDefinition.fromData(UUID.randomUUID(), data);
            helper.assertTrue(facility.parameters().get("mode").equals(new Value.Text("no")),
                "the unset mode takes its declared default");
            var saved = (Map<?, ?>) facility.data().get("ui");
            helper.assertTrue(ui.get("card").equals(saved.get("card")), "every choice field round-trips: " + saved);
            helper.assertTrue(facilities.preview(NAME, Optional.of(data)).problems().isEmpty(), "the full choice validates");

            var invalid = new LinkedHashMap<>(choice);
            invalid.put("default", "other");
            invalid.put("icons", List.of("minecraft:lever"));
            invalid.put("run", "close");
            data.put("triggers", Map.of("open", Map.of("kind", "manual", "routine", "open"),
                "night", Map.of("kind", "schedule", "routine", "close", "dayTime", 12000, "enabled", false)));
            data.put("ui", Map.of("card", List.of(invalid)));
            var problems = facilities.preview(NAME, Optional.of(data)).problems();
            helper.assertTrue(problems.containsAll(List.of(
                new DefinedFacilities.Problem("ui.card[0].default", "invalid", "other"),
                new DefinedFacilities.Problem("ui.card[0].icons", "invalid", ""),
                new DefinedFacilities.Problem("ui.card[0].run", "manual", "close"))),
                "the invalid elements report their specific problems: " + problems);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void choiceOrderSetsParameterAndRunsWithSelectedArgument(GameTestHelper helper) {
        try (var fixture = new FacilityGateFixture(helper)) {
            ServerPlayer commander = player(helper, fixture, "facility_choice");
            Charters.keep(fixture.colony, Charter.founded("FacilityDefinition choice test", commander.getUUID()));
            ResourceLocation routine = ResourceLocation.parse("folkways_vanilla:test_choice_argument");
            if (Routines.of(routine).isEmpty()) {
                Routines.register(new Routine() {
                    @Override
                    public ResourceLocation id() {
                        return routine;
                    }

                    @Override
                    public Optional<String> check(Call call) {
                        return new Value.Text("yes").equals(call.arguments().get("mode"))
                            && new Value.Text("yes").equals(call.parameters().get("mode"))
                            ? Optional.empty() : Optional.of("mode must be set and passed as yes");
                    }

                    @Override
                    public Optional<Refinement.Change> expand(Call call) {
                        return Optional.empty();
                    }
                });
            }

            DefinedFacilities facilities = DefinedFacilities.of(fixture.colony).orElseThrow();
            var data = definition(fixture);
            data.put("routines", Map.of("choose", Map.of("use", routine.toString(), "with", Map.of("mode", "no"))));
            data.put("triggers", Map.of("choose", Map.of("kind", "manual", "routine", "choose")));
            data.put("ui", Map.of("card", List.of(
                Map.of("choice", "mode", "options", List.of("yes", "no"), "default", "no", "run", "choose"),
                Map.of("choice", "locked", "options", List.of("no", "yes"), "enabled_when", Map.of("lever", true)))));
            var preview = facilities.preview(NAME, Optional.of(data));
            helper.assertTrue(facilities.apply(preview).isEmpty(), "the choice card is installed: " + preview.problems());
            FacilityDefinition facility = facilities.facilities().getFirst();

            var disabled = send(commander, fixture, facility, UnitRequest.Operation.FACILITY_CHOICE, "locked", 1, 1);
            helper.assertTrue(disabled.result().key().equals("folkways.rts.facility.error.disabled.state")
                && FacilityOrders.status(fixture.level.getServer(), fixture.colony, facility.id()).orElseThrow()
                    .disabled().equals(Map.of(Card.Element.Choice.statusKey(1), "state"))
                && facilities.facility(facility.id()).orElseThrow().parameters().get("locked").equals(new Value.Text("no")),
                "enabled_when refuses the choice and leaves the parameter unchanged");
            helper.assertTrue(send(commander, fixture, facility, UnitRequest.Operation.FACILITY_PARAMETER, "mode", 0, 1)
                .result().key().equals("folkways.rts.facility.error.hidden"), "the number order does not accept a choice");
            var chosen = send(commander, fixture, facility, UnitRequest.Operation.FACILITY_CHOICE, "mode", 0, 0);
            helper.assertTrue(chosen.result().accepted() && facilities.running(NAME).equals(List.of("choose"))
                && facilities.facility(facility.id()).orElseThrow().parameters().get("mode").equals(new Value.Text("yes")),
                "the selected option is saved and passed to the routine: " + chosen.result());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void gantryScriptTargetRoundTrips(GameTestHelper helper) {
        try (var fixture = new FacilityGateFixture(helper)) {
            var data = definition(fixture);
            BlockPos hook = fixture.lever.at().cell().above(8);
            var frame = Map.of("origin", pos(hook), "axes", Map.of(
                "x", List.of(1, 0, 0), "y", List.of(0, -1, 0), "z", List.of(0, 0, 1)));
            var response = new LinkedHashMap<String, Object>();
            for (String axis : List.of("x", "y", "z")) {
                response.put(axis, Map.of("channel", "lever", "stop", false, "rates",
                    List.of(Map.of("value", true, "velocity", 0.125, "start_ticks", 1, "stop_ticks", 1))));
            }
            var settings = Map.of("frame", frame, "response", response, "hold", List.of("y"));
            data.put("routines", Map.of("move", Map.of("source", ScriptedFacilityFixture.POSITION_SOURCE, "with", settings)));
            data.put("triggers", Map.of("move", Map.of("kind", "manual", "routine", "move")));
            var ui = Map.of("card", List.of(Map.of("target", "move", "pick", "pos", "default", true)));
            data.put("ui", ui);
            FacilityDefinition facility = FacilityDefinition.fromData(UUID.randomUUID(), data);
            var saved = (Map<?, ?>) facility.data().get("ui");
            helper.assertTrue(ui.get("card").equals(saved.get("card")), "the move target round-trips: " + saved);
            helper.assertTrue(facility.card().elements().getFirst() instanceof Card.Element.Target target
                && target.routine().equals("move") && target.pick() == Card.Pick.POS && target.defaultCommand(),
                "move is a position pick and the default command");

            var savedRoutine = (Map<?, ?>) ((Map<?, ?>) facility.data().get("routines")).get("move");
            var savedSettings = (Map<?, ?>) savedRoutine.get("with");
            helper.assertTrue(savedRoutine.get("source").equals(ScriptedFacilityFixture.POSITION_SOURCE)
                && savedSettings.get("hold").equals(List.of("y")), "the scripted move retains its held axis");
        }
        helper.succeed();
    }

    private static ServerPlayer player(GameTestHelper helper, FacilityGateFixture fixture, String name) {
        ServerPlayer player = FakePlayerFactory.get(fixture.level, new GameProfile(UUID.randomUUID(), name));
        player.moveTo(fixture.body.mob().position());
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(fixture.colony.id()));
        player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
        return player;
    }

    private static long request;

    private static UnitReply send(ServerPlayer player, FacilityGateFixture fixture, FacilityDefinition facility,
            UnitRequest.Operation operation, String name, int index) {
        return send(player, fixture, facility, operation, name, index, -1);
    }

    private static UnitReply send(ServerPlayer player, FacilityGateFixture fixture, FacilityDefinition facility,
            UnitRequest.Operation operation, String name, int index, int seat) {
        return UnitOrders.handle(player, new UnitRequest(++request, fixture.colony.id(), facility.id(), operation,
            List.of(), seat, index, STOP, name, WeaponRules.DEFAULT, Optional.empty()));
    }

    private static Map<String, Object> definition(FacilityGateFixture fixture) {
        BlockPos door = fixture.door.cell();
        BlockPos lever = fixture.lever.at().cell();
        int night = Math.floorMod(fixture.level.getDayTime() + 12000, 24000);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", NAME);
        data.put("extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", fixture.level.dimension().location().toString()),
            "min", pos(lever), "max", pos(door.above())))));
        data.put("anchor", pos(lever));
        data.put("controls", Map.of("lever", Map.of("part", "lever", "offset", List.of(0, 0, 0))));
        data.put("routines", Map.of("open", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", true)),
            "close", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", false))));
        data.put("triggers", Map.of("open", Map.of("kind", "manual", "routine", "open"),
            "close", Map.of("kind", "manual", "routine", "close"),
            "night", Map.of("kind", "schedule", "routine", "close", "dayTime", night, "enabled", true)));
        data.put("parameters", Map.of("reach", 12));
        data.put("ui", Map.of("card", List.of(Map.of("button", "open", "icon", "minecraft:iron_door",
                "enabled_when", Map.of("lever", false)), Map.of("toggle", "night", "icon", "minecraft:clock"),
            Map.of("readout", "lever", "as", "light", "label", "Gate open")),
            "overlay", List.of(Map.of("range", "reach"))));
        data.put("projections", List.of(Map.of("gate", Map.of("cells", List.of(pos(door), pos(door.above())),
            "open_when", Map.of("lever", true)))));
        return data;
    }

    private static List<Integer> pos(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }
}
