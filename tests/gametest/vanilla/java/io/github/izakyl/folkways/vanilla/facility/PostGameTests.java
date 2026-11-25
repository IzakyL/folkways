package io.github.izakyl.folkways.vanilla.facility;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.FacilityRefusal;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class PostGameTests {

    private static final String NAME = "Test post";

    private PostGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void staffedPostWritesOnTime(GameTestHelper helper) {
        sequence(helper, "standing");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void sharedFootingUsesOneResidentNode(GameTestHelper helper) {
        sequence(helper, null);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void differentStancesAreRefused(GameTestHelper helper) {
        try (var fixture = new FacilityGateFixture(helper)) {
            BlockPos lever = fixture.lever.at().cell();
            BlockPos second = lever.west(10);
            fixture.level.setBlockAndUpdate(second.below(), Blocks.STONE.defaultBlockState());
            fixture.level.setBlockAndUpdate(second, Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR));
            var data = definition(fixture, null);
            data.put("extent", DefinitionCodec.extent(new FacilityExtent(fixture.door.realm(), second, lever)));
            data.put("controls", Map.of("lever", leverAt(0), "second", leverAt(-10)));
            data.put("routines", Map.of("run", timed("second")));
            var service = DefinedFacilities.of(fixture.colony).orElseThrow();
            var preview = service.preview(NAME, Optional.of(data));
            helper.assertTrue(preview.problems().stream().anyMatch(problem ->
                problem.detail().equals(FacilityRefusal.SEQUENCE_STANCE.translationKey())),
                "expected the localized stance refusal: " + preview.problems());
            helper.succeed();
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void sequenceRequestsAnOnDemandPost(GameTestHelper helper) {
        sequence(helper, "on_demand");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void writeAllMakesNoPlanNodeAtStaffedPost(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        var labor = startLabor(helper, fixture);
        helper.onEachTick(labor::tick);
        var data = definition(fixture, "standing");
        data.put("routines", Map.of("run", Map.of("use", "folkways_core:write_all", "with", Map.of("lever", true))));
        var service = install(helper, fixture, data);
        long[] submitted = {-1};
        helper.startSequence().thenWaitUntil(() -> assertStaffed(helper, fixture, service))
            .thenExecute(() -> {
                submitted[0] = labor.census().submitted();
                run(helper, service);
            }).thenWaitUntil(() -> helper.assertTrue(powered(fixture) && done(service), "waiting for write_all"))
            .thenExecute(() -> helper.assertTrue(labor.census().submitted() == submitted[0],
                "a staffed write must not submit a plan node"))
            .thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void staffedPostDeliversAndCollectsWithoutWork(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        var labor = startLabor(helper, fixture);
        helper.onEachTick(labor::tick);
        var data = portDefinition(fixture, 2);
        data.put("routines", Map.of("run", Map.of("source", """
            def step(state, input, args):
                if input["_failure"] != None:
                    return state, [fail_permanent(input["_failure"])]
                items = [{"item": "minecraft:iron_ingot", "count": 1}]
                if state == None:
                    return "collect", [deliver("chest", items)]
                if state == "collect":
                    return "done", [collect("chest", items)]
                return state, [done()]
            """)));
        var service = install(helper, fixture, data);
        var chest = Containers.at(fixture.level, fixture.lever.at().cell().south(2)).orElseThrow();
        long[] submitted = {-1};
        boolean[] deposited = {false};
        helper.onEachTick(() -> deposited[0] |= !chest.isEmpty());
        helper.startSequence().thenWaitUntil(() -> assertStaffed(helper, fixture, service))
            .thenExecute(() -> {
                fixture.body.pack().insert(new ItemStack(Items.IRON_INGOT));
                submitted[0] = labor.census().submitted();
                run(helper, service);
            }).thenWaitUntil(() -> helper.assertTrue(done(service), "waiting for deliver and collect: " + service.recent(NAME)))
            .thenExecute(() -> {
                helper.assertTrue(deposited[0] && chest.isEmpty() && fixture.body.pack().count(Items.IRON_INGOT) == 1,
                    "the ingot passed through the chest back into the occupant's pack");
                helper.assertTrue(labor.census().submitted() == submitted[0], "no transfer plan nodes");
            }).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void timedTransferAndLever(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        var labor = startLabor(helper, fixture);
        helper.onEachTick(labor::tick);
        var data = portDefinition(fixture, 2);
        data.put("routines", Map.of("run", Map.of("use", "folkways_core:write_sequence", "with", Map.of("steps",
            List.of(Map.of("into", "chest", "item", "minecraft:iron_ingot", "count", 1, "atTick", 0),
                Map.of("control", "lever", "value", true, "atTick", 12))))));
        var service = install(helper, fixture, data);
        var chest = Containers.at(fixture.level, fixture.lever.at().cell().south(2)).orElseThrow();
        long[] deposited = {-1};
        long[] powered = {-1};
        helper.onEachTick(() -> {
            if (!chest.isEmpty() && deposited[0] < 0) {
                deposited[0] = fixture.level.getGameTime();
            }
            if (powered(fixture) && powered[0] < 0) {
                powered[0] = fixture.level.getGameTime();
            }
        });
        helper.startSequence().thenWaitUntil(() -> assertStaffed(helper, fixture, service))
            .thenExecute(() -> {
                fixture.body.pack().insert(new ItemStack(Items.IRON_INGOT));
                run(helper, service);
            }).thenWaitUntil(() -> helper.assertTrue(powered[0] >= 0 && done(service), "waiting for timed steps: " + service.recent(NAME)))
            .thenExecute(() -> helper.assertTrue(deposited[0] >= 0 && powered[0] - deposited[0] == 12,
                "the lever toggled exactly 12 ticks after the transfer")).thenSucceed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 600)
    public static void uncoveredTransferUsesPlan(GameTestHelper helper) {
        suppliedDelivery(helper, 9);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core", timeoutTicks = 900)
    public static void courierBringsStockToStaffedPost(GameTestHelper helper) {
        suppliedDelivery(helper, 2);
    }

    private static void suppliedDelivery(GameTestHelper helper, int offset) {
        var fixture = new FacilityGateFixture(helper);
        var labor = startLabor(helper, fixture);
        helper.onEachTick(labor::tick);
        var lever = fixture.lever.at().cell();
        for (BlockPos floor : BlockPos.betweenClosed(lever.offset(-3, -1, 1), lever.offset(0, -1, 12))) {
            fixture.level.setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
        }
        var data = portDefinition(fixture, offset);
        data.put("routines", Map.of("run", Map.of("source", """
            def step(state, input, args):
                if input["_failure"] != None:
                    return state, [fail_permanent(input["_failure"])]
                if state == None:
                    return "done", [deliver("chest", [{"item": "minecraft:iron_ingot", "count": 1}])]
                return state, [done()]
            """)));
        var service = install(helper, fixture, data);
        var chest = Containers.at(fixture.level, lever.south(offset)).orElseThrow();
        var stockAt = lever.south(11).west();
        fixture.level.setBlockAndUpdate(stockAt, Blocks.BARREL.defaultBlockState());
        var stock = Containers.at(fixture.level, stockAt).orElseThrow();
        stock.setItem(0, new ItemStack(Items.IRON_INGOT));
        fixture.colony.stores(DefinedFacilities.ID, fixture.level.dimension(), List.of(fixture.door.at(stockAt)));
        WorkDefaults.set(fixture.colony, PersonBody.ID, Vocations.HAULING, true, 1);
        long[] submitted = {-1};
        var run = helper.startSequence().thenWaitUntil(() -> assertStaffed(helper, fixture, service))
            .thenExecute(() -> {
                ResidentEntity courier = PersonBody.RESIDENT.get().create(fixture.level);
                courier.setPos(lever.south(10).west().getBottomCenter());
                courier.setOnGround(true);
                fixture.level.addFreshEntity(courier);
                fixture.colony.hold(PersonBody.ID, Claimed.Entity.of(courier)).orElseThrow();
                courier.joinColony(fixture.colony.id());
                fixture.colony.entered(courier);
                Cleanup.on(helper, courier::discard);
                submitted[0] = labor.census().submitted();
                run(helper, service);
            });
        if (offset > 4) {
            run.thenWaitUntil(() -> helper.assertTrue(labor.census().submitted() > submitted[0],
                "an uncovered delivery submits ordinary work")).thenSucceed();
            return;
        }
        run.thenWaitUntil(() -> helper.assertTrue(done(service), "waiting for hauled stock: "
                + service.recent(NAME) + " " + labor.diagnosis().unassigned()))
            .thenExecute(() -> {
                helper.assertTrue(!chest.isEmpty() && stock.isEmpty(), "the stock reached the port");
                helper.assertTrue(labor.census().submitted() > submitted[0], "hauling submits ordinary work");
                assertStaffed(helper, fixture, service);
            }).thenSucceed();
    }

    private static Map<String, Object> portDefinition(FacilityGateFixture fixture, int offset) {
        var data = definition(fixture, "standing");
        var lever = fixture.lever.at().cell();
        var chest = lever.south(offset);
        fixture.level.setBlockAndUpdate(chest.below(), Blocks.STONE.defaultBlockState());
        fixture.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        data.put("extent", DefinitionCodec.extent(new FacilityExtent(fixture.door.realm(), lever.west(), chest)));
        data.put("controls", Map.of("lever", leverAt(0), "chest",
            Map.of("part", "folkways_core:item", "offset", List.of(0, 0, offset))));
        return data;
    }

    private static void sequence(GameTestHelper helper, String staffing) {
        var fixture = new FacilityGateFixture(helper);
        var labor = startLabor(helper, fixture);
        var service = install(helper, fixture, definition(fixture, staffing));
        long[] submitted = {-1};
        long[] on = {-1};
        long[] off = {-1};
        UUID[] holder = {null};
        var sequence = helper.startSequence();
        if ("standing".equals(staffing)) {
            sequence.thenWaitUntil(() -> assertStaffed(helper, fixture, service));
        }
        sequence.thenExecute(() -> {
            submitted[0] = labor.census().submitted();
            if ("on_demand".equals(staffing)) {
                helper.assertTrue(!staffed(fixture, service), "on-demand post starts vacant");
            }
            run(helper, service);
        });
        helper.onEachTick(() -> {
            labor.tick();
            if (submitted[0] < 0 || off[0] >= 0) {
                return;
            }
            if (powered(fixture)) {
                if (staffing != null) {
                    assertStaffed(helper, fixture, service);
                } else {
                    var route = labor.route(fixture.body);
                    helper.assertTrue(route.size() == 1 && fixture.colony.isHeld(route.getFirst().id()),
                        "one resident must hold the timed node throughout the sequence");
                    UUID node = route.getFirst().id();
                    if (holder[0] == null) {
                        holder[0] = node;
                    }
                    helper.assertTrue(node.equals(holder[0]), "both steps must use the same node");
                }
                if (on[0] < 0) {
                    on[0] = fixture.level.getGameTime();
                }
            } else if (on[0] >= 0) {
                off[0] = fixture.level.getGameTime();
            }
        });
        sequence.thenWaitUntil(() -> helper.assertTrue(off[0] >= 0 && done(service), "waiting for both writes"))
            .thenExecute(() -> {
                helper.assertTrue(off[0] - on[0] == 20, "lever writes were " + (off[0] - on[0]) + " ticks apart");
                long expected = "standing".equals(staffing) ? 0 : 1;
                helper.assertTrue(labor.census().submitted() - submitted[0] == expected,
                    "only the sequence node or the requested duty node may be submitted");
            }).thenSucceed();
    }

    private static LaborFixture startLabor(GameTestHelper helper, FacilityGateFixture fixture) {
        ResidentEntity resident = (ResidentEntity) fixture.body.mob();
        fixture.colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(fixture.colony.id());
        fixture.colony.entered(resident);
        LaborFixture labor = new LaborFixture(fixture.colony, fixture.level);
        Cleanup.on(helper, () -> {
            labor.close();
            fixture.close();
        });
        return labor;
    }

    private static DefinedFacilities install(GameTestHelper helper, FacilityGateFixture fixture,
            Map<String, Object> data) {
        var service = DefinedFacilities.of(fixture.colony).orElseThrow();
        var preview = service.preview(NAME, Optional.of(data));
        helper.assertTrue(preview.problems().isEmpty(), "valid post definition: " + preview.problems());
        helper.assertTrue(service.apply(preview).isEmpty(), "install the definition");
        return service;
    }

    private static boolean staffed(FacilityGateFixture fixture, DefinedFacilities service) {
        FacilityDefinition facility = service.facilities().stream().filter(one -> one.name().equals(NAME))
            .findFirst().orElseThrow();
        return service.posts().coveringPost(fixture.level, facility, fixture.lever)
            .flatMap(post -> service.posts().occupant(fixture.level, post))
            .filter(occupant -> occupant.body() == fixture.body.mob()).isPresent();
    }

    private static void assertStaffed(GameTestHelper helper, FacilityGateFixture fixture, DefinedFacilities service) {
        helper.assertTrue(staffed(fixture, service), "the fixture resident is at the post");
    }

    private static boolean done(DefinedFacilities service) {
        return service.recent(NAME).filter(result -> result.ending().equals("done")).isPresent();
    }

    private static void run(GameTestHelper helper, DefinedFacilities service) {
        var problems = service.run(NAME, "run", Map.of());
        helper.assertTrue(problems.isEmpty(), "run accepted: " + problems);
    }

    private static boolean powered(FacilityGateFixture fixture) {
        return fixture.level.getBlockState(fixture.lever.at().cell()).getValue(LeverBlock.POWERED);
    }

    private static Map<String, Object> definition(FacilityGateFixture fixture, String staffing) {
        BlockPos lever = fixture.lever.at().cell();
        var extent = new FacilityExtent(fixture.door.realm(), lever.west(2), lever);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", NAME);
        data.put("extent", DefinitionCodec.extent(extent));
        data.put("anchor", List.of(lever.getX(), lever.getY(), lever.getZ()));
        data.put("controls", Map.of("lever", leverAt(0)));
        data.put("routines", Map.of("run", timed("lever")));
        data.put("triggers", Map.of("run", Map.of("kind", "manual", "routine", "run")));
        if (staffing != null) {
            data.put("posts", Map.of("watch", Map.of("stance", Map.of("footing", List.of(-1, 0, 0)),
                "staffing", staffing)));
        }
        return data;
    }

    private static Map<String, Object> leverAt(int x) {
        return Map.of("part", "lever", "offset", List.of(x, 0, 0));
    }

    private static Map<String, Object> timed(String second) {
        return Map.of("use", "folkways_core:write_sequence", "with", Map.of("steps", List.of(
            Map.of("control", "lever", "value", true, "atTick", 0),
            Map.of("control", second, "value", false, "atTick", 20))));
    }
}
