package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.atsuishio.superbwarfare.block.AircraftCatapultBlock;
import com.atsuishio.superbwarfare.block.CatapultControllerBlock;
import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import com.atsuishio.superbwarfare.init.ModBlocks;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.izakyl.folkways.airline.Airport;
import io.github.izakyl.folkways.airline.AirportRegistry;
import io.github.izakyl.folkways.airline.Airports;
import io.github.izakyl.folkways.airline.GroundGraph;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.VehicleProfiles;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.rts.combat.command.ResidentCommands;
import io.github.izakyl.folkways.core.rts.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.rts.combat.group.UnitGroups;
import io.github.izakyl.folkways.core.rts.combat.net.UnitOrders;
import io.github.izakyl.folkways.core.rts.combat.net.UnitReply;
import io.github.izakyl.folkways.core.rts.combat.net.UnitRequest;
import io.github.izakyl.folkways.core.rts.combat.net.UnitSnapshot;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.SelectedTests;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.firearms.FolkwaysFirearmsMod;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;

@EventBusSubscriber(modid = FolkwaysFirearmsMod.ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class VehicleCommandGameTests {
    private static dev.ryanhcode.sable.api.physics.constraint.FixedConstraintHandle anchor(
            ServerSubLevel ship, BlockPos pos) {
        var system = SubLevelPhysicsSystem.require(ship.getLevel());
        system.updatePose(ship);
        system.getPipeline().resetVelocity(ship);
        Vector3d local = new Vector3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        Vector3d world = ship.logicalPose().transformPosition(new Vector3d(local));
        Quaterniond orientation = new Quaterniond(ship.logicalPose().orientation()).conjugate();
        return system.getPipeline().addConstraint(ship, null,
            new dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration(
                local, world, orientation));
    }

    private static int site = 100;
    private static int platformSite;
    private static final String WORK = "folkways_test:vehicle_original_work";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        SelectedTests.register(event, VehicleCommandGameTests.class);
    }

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.urges(Commands.SELECTION, (colony, who, view) -> who.body().getPersistentData().getBoolean(WORK)
            ? List.of(new Urge(Commands.SELECTION, Urge.SPARE, false, () -> new OriginalWork(who))) : List.of());
    }

    private static final class OriginalWork implements Node, Workload.Continuous {
        private final NodeSpec spec;

        OriginalWork(Worker who) {
            spec = NodeSpec.of(who.resident().id(), Commands.SELECTION,
                WorkSite.on(who.body().level(), who.resident().id(), who.body().blockPosition()),
                Stances.WHEREVER, this).done();
        }

        public NodeSpec spec() {
            return spec;
        }

        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }

        public boolean holds(ServerLevel level, Worker who) {
            var tag = who.body().getPersistentData();
            tag.putInt(WORK + "/ticks", tag.getInt(WORK + "/ticks") + 1);
            return tag.getBoolean(WORK);
        }
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void runwayRequirementsFollowAircraftModel(GameTestHelper helper) {
        List<Scene> models = List.of(new Scene(helper, "a_10a", 160, 96),
            new Scene(helper, "j_16", 160, 96), new Scene(helper, "ju_87", 160, 96),
            new Scene(helper, "ac_130h", 160, 96));
        helper.runAfterDelay(20, () -> {
            for (Scene f : models) {
                f.bindDriver();
                var needs = WarfareVehicles.INSTANCE.status(f.vehicle).runway().orElseThrow();
                f.log("RUNWAY_REQUIREMENTS " + needs);
                helper.assertTrue(needs.takeoff() > needs.margin(), "fixed wing needs a ground roll");
                helper.assertTrue(needs.landing() > needs.margin(), "landing includes touchdown braking distance");
                helper.assertTrue(needs.width() >= f.vehicle.getBbWidth(), "OBB span fits required width");
                var shortSite = new LandingSites.Site("short-" + f.vehicle.getUUID(), "Short",
                    LandingSites.Kind.RUNWAY_END, WorldSpaces.at(f.vehicle), -90,
                    needs.takeoff() - 1, needs.width());
                AtomicBoolean available = new AtomicBoolean(true);
                LandingSites.register(level -> available.get() && level == f.level ? List.of(shortSite) : List.of());
                Cleanup.on(helper, () -> available.set(false));
                helper.assertFalse(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.TAKE_OFF,
                    List.of(), -1, Optional.empty(), Optional.empty())).accepted(),
                    "server rejects a short runway");
                helper.assertTrue(needs.refusal(shortSite, true, 0).getString().contains("1"),
                    "candidate reason reports length deficit");
            }
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void automaticTakeoffChoosesSuitableFreeRunway(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 320, 96);
        f.bindDriver();
        helper.runAfterDelay(20, () -> {
            var needs = DirectVehicleMotion.runway(f.vehicle).orElseThrow();
            var airport = new LandingSites.Airport("auto-" + f.vehicle.getUUID(), "Automatic", Optional.empty());
            var position = WorldSpaces.at(f.vehicle);
            var shortEnd = new LandingSites.Site(airport.id() + "/short", "Short", LandingSites.Kind.RUNWAY_END,
                position, -90, needs.takeoff() - 1, needs.width(), List.of(), airport);
            var narrow = new LandingSites.Site(airport.id() + "/narrow", "Narrow", LandingSites.Kind.RUNWAY_END,
                position, -90, needs.takeoff(), needs.width() - 1, List.of(), airport);
            var suitable = new LandingSites.Site(airport.id() + "/fit", "Fit", LandingSites.Kind.RUNWAY_END,
                position, -90, needs.takeoff(), needs.width(), List.of(), airport);
            var available = new AtomicBoolean(true);
            LandingSites.register(level -> available.get() && level == f.level
                ? List.of(shortEnd, narrow, suitable) : List.of());
            Cleanup.on(helper, () -> available.set(false));
            helper.assertTrue(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.TAKE_OFF,
                List.of(), -1, Optional.empty(), Optional.empty())).accepted(), "takeoff needs no site choice");
            helper.assertValueEqual(VehicleAirports.home(f.vehicle).orElseThrow(), airport.id(),
                "departure establishes home airport");
            // The selected end is booked; a competing assignment cannot use either unsuitable end.
            VehicleRunwayTraffic.release(f.vehicle);
            var claim = AirportTraffic.request(f.level,
                AirportTraffic.Priority.COMBAT, suitable)
                .orElseThrow();
            helper.assertTrue(VehicleAirports.assign(f.vehicle, true, Optional.empty()).isEmpty(),
                "automatic assignment skips booked runway and rejects short or narrow alternatives");
            claim.close();
            helper.assertValueEqual(VehicleAirports.assign(f.vehicle, true, Optional.empty()).orElseThrow().id(),
                suitable.id(), "freed suitable runway is assigned automatically");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void fixedWingLandAcceptsOccupiedHomeStand(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 320, 96);
        f.bindDriver();
        helper.runAfterDelay(20, () -> {
            var needs = DirectVehicleMotion.runway(f.vehicle).orElseThrow();
            BlockPos tower = BlockPos.containing(f.start.add(0, 5, 80));
            f.level.setBlockAndUpdate(tower, BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("folkways_airline:airport_tower")).defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH));
            Airports.register(f.level, tower);
            Cleanup.on(helper, () -> Airports.remove(f.level, tower));
            var stand = new Airport.Stand(BlockPos.containing(f.start.add(32, -1, 64)),
                Direction.SOUTH, 64, 64, 32);
            var geometry = new Airport("Occupied home", tower, List.of(), List.of(), List.of(stand),
                new GroundGraph(List.of(), List.of()), List.of());
            // Seed surveyed geometry without coupling this command regression to the tower scanner.
            try {
                var cache = AirportRegistry.class.getDeclaredMethod("cache", Airport.class);
                cache.setAccessible(true);
                cache.invoke(AirportRegistry.of(f.level), geometry);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException(exception);
            }
            var home = new LandingSites.Airport(WorldSpaces.at(f.level, tower).toString(),
                "Occupied home", Optional.empty());
            var site = new LandingSites.Site(home.id() + "/runway", "Home runway",
                LandingSites.Kind.RUNWAY_END, WorldSpaces.at(f.vehicle), -90,
                needs.landing(), needs.width(), List.of(), home);
            var available = new AtomicBoolean(true);
            LandingSites.register(level -> available.get() && level == f.level ? List.of(site) : List.of());
            Cleanup.on(helper, () -> available.set(false));
            VehicleAirports.home(f.vehicle, home.id());
            var parked = (VehicleEntity) f.vehicle.getType().create(f.level);
            parked.moveTo(Vec3.atBottomCenterOf(stand.pos().above()));
            f.level.addFreshEntity(parked);
            Cleanup.on(helper, parked::discard);
            helper.assertTrue(stand.maxLength() >= VehicleAirports.length(f.vehicle)
                && stand.maxWingspan() >= needs.width(), "the only stand fits but is occupied");
            helper.assertTrue(parked.isAlive(), "parked aircraft occupies the home stand");
            f.vehicle.moveTo(f.start.add(-80, 30, 0));
            helper.assertTrue(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.LAND,
                List.of(), -1, Optional.empty(), Optional.empty())).accepted(),
                "fixed-wing Land succeeds with an occupied home stand");
            VehicleRunwayTraffic.release(f.vehicle);
            helper.assertValueEqual(VehicleAirports.assign(f.vehicle, false, Optional.empty()).orElseThrow().id(),
                site.id(), "home runway is assigned without a stand");
            VehicleRunwayTraffic.release(f.vehicle);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void automaticLandingReturnsToHomeAirport(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        var homeAirport = new LandingSites.Airport("home-" + f.vehicle.getUUID(), "Home", Optional.empty());
        var awayAirport = new LandingSites.Airport("away-" + f.vehicle.getUUID(), "Away", Optional.empty());
        var home = new LandingSites.Site(homeAirport.id() + "/pad", "Home pad", LandingSites.Kind.HELIPAD,
            WorldSpaces.at(f.vehicle), 0, 32, 32, List.of(), homeAirport);
        var away = new LandingSites.Site(awayAirport.id() + "/pad", "Away pad", LandingSites.Kind.HELIPAD,
            WorldSpaces.at(f.level, BlockPos.containing(f.start.add(80, 0, 0))),
            0, 32, 32, List.of(), awayAirport);
        var available = new AtomicBoolean(true);
        LandingSites.register(level -> available.get() && level == f.level ? List.of(home, away) : List.of());
        Cleanup.on(helper, () -> available.set(false));
        f.bindDriver();
        helper.assertValueEqual(VehicleAirports.home(f.vehicle).orElseThrow(), homeAirport.id(),
            "parking location initializes home");
        var saved = f.vehicle.getPersistentData().copy();
        f.vehicle.getPersistentData().remove(VehicleSeats.CREW);
        f.vehicle.getPersistentData().merge(saved);
        f.vehicle.moveTo(f.start.add(80, 20, 0));
        helper.assertTrue(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.LAND,
            List.of(), -1, Optional.empty(), Optional.empty())).accepted(), "landing needs no site choice");
        VehicleRunwayTraffic.release(f.vehicle);
        helper.assertValueEqual(VehicleAirports.assign(f.vehicle, false, Optional.empty()).orElseThrow().id(),
            home.id(), "home wins over a nearer airport after restoring persisted vehicle state");
        VehicleRunwayTraffic.release(f.vehicle);
        helper.assertValueEqual(VehicleAirports.assign(f.vehicle, false, Optional.of(awayAirport.id()))
            .orElseThrow().id(), away.id(), "explicit airport choice overrides home");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void controllerRestoresFlightAndSurfaceState(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 160, 96);
        f.vehicle.travel();
        f.bindDriver();
        f.vehicle.moveTo(f.start.add(0, 50, 0));
        f.vehicle.setOnGround(false);
        Vec3 destination = f.start.add(80, 50, 0);
        f.order(Vehicles.Action.MOVE, destination);
        Vehicles.altitude(f.vehicle, Vehicles.Altitude.HIGH);
        var before = VehicleController.of(f.vehicle).route();
        VehicleController.remove(f.vehicle);
        var restored = VehicleController.of(f.vehicle);
        helper.assertValueEqual(restored.route(), before, "route survives controller recreation");
        helper.assertValueEqual(Vehicles.altitude(f.vehicle), Vehicles.Altitude.HIGH, "altitude remains persisted");
        helper.assertTrue(VehicleController.drive(f.vehicle, f.vehicle.getEngineInfo()), "restored aircraft drives");
        helper.assertTrue(f.vehicle.getDeltaMovement().horizontalDistance() > 0, "flight does not freeze");
        restored.cancel();
        f.vehicle.moveTo(f.start);
        f.vehicle.setOnGround(false);
        f.vehicle.setDeltaMovement(Vec3.ZERO);
        helper.assertTrue(VehicleController.atRest(f.vehicle), "pavement support replaces the ground flag");
        helper.assertTrue(WarfareVehicles.INSTANCE.leave(f.vehicle, -1).accepted(), "crew can leave supported hull");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void unavailableHomeIsNotReassigned(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        String home = "unavailable-" + f.vehicle.getUUID();
        String away = "temporary-" + f.vehicle.getUUID();
        AtomicBoolean active = new AtomicBoolean(true);
        LandingSites.registerAirports((level, id) -> active.get() && level == f.level && id.equals(home));
        var site = new LandingSites.Site(away, "Temporary pad", LandingSites.Kind.HELIPAD,
            WorldSpaces.at(f.vehicle), 0, 64, 64);
        LandingSites.register(level -> active.get() && level == f.level ? List.of(site) : List.of());
        Cleanup.on(helper, () -> active.set(false));
        VehicleAirports.home(f.vehicle, home);
        helper.assertValueEqual(VehicleAirports.home(f.vehicle).orElseThrow(), home, "registered home retained");
        helper.assertValueEqual(VehicleAirports.assign(f.vehicle, false, Optional.empty()).orElseThrow().id(), away,
            "temporary landing site selected");
        VehicleAirports.landed(f.vehicle, away, false);
        helper.assertValueEqual(VehicleAirports.home(f.vehicle).orElseThrow(), home, "temporary site is not saved");
        VehicleRunwayTraffic.release(f.vehicle);
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void uncontrolledHelicopterDoesNotDirtyControlFlag(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.vehicle.travel();
        f.vehicle.getEntityData().packDirty();
        DirectVehicleMotion.controlled(f.vehicle, true);
        int flag = f.vehicle.getEntityData().packDirty().getFirst().id();
        DirectVehicleMotion.controlled(f.vehicle, false);
        f.vehicle.getEntityData().packDirty();
        DirectVehicleMotion.work(f.vehicle.getEngineInfo(), f.vehicle);
        var dirty = f.vehicle.getEntityData().packDirty();
        helper.assertTrue(dirty == null || dirty.stream().noneMatch(value -> value.id() == flag),
            "uncommanded helicopter does not dirty the control flag");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void missingDepartureFrameFallsBackToHold(GameTestHelper helper) throws ReflectiveOperationException {
        Scene f = new Scene(helper, "a_10a", 160, 96);
        f.vehicle.travel();
        f.bindDriver();
        var controller = VehicleController.of(f.vehicle);
        var departure = VehicleController.class.getDeclaredField("departure");
        departure.setAccessible(true);
        departure.set(controller, new LandingSites.Site("missing", "Missing runway", LandingSites.Kind.RUNWAY_END,
            new WorldPos(new Realm.Frame(UUID.randomUUID()), BlockPos.ZERO), 0, 256, 64));
        helper.assertTrue(VehicleController.drive(f.vehicle, f.vehicle.getEngineInfo()), "tick remains controlled");
        helper.assertTrue(departure.get(controller) == null, "missing frame clears departure");
        helper.assertTrue(controller.action().endsWith(".hold"), "missing frame enters safe hold");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void catapultCancelResetsAllLevers(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 256, 96);
        CatapultFixture fixture = new CatapultFixture(f, null);
        f.bindDriver();
        helper.runAfterDelay(20, () -> {
            var rail = fixture.site().catapults().getFirst();
            BlockPos second = rail.controller().block(f.level).north();
            f.level.setBlockAndUpdate(second, Blocks.LEVER.defaultBlockState());
            VehicleCatapults.trigger(f.level, rail, true);
            VehicleCatapults.trigger(f.level, rail, false);
            for (Direction side : Direction.values()) {
                var state = f.level.getBlockState(rail.controller().block(f.level).relative(side));
                if (state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock) {
                    helper.assertFalse(state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED),
                        "every adjacent lever is reset");
                }
            }
            helper.assertTrue(fixture.depart(f), "departure accepted");
        });
        helper.onEachTick(() -> {
            if (fixture.shuttle.getTowingEntity() == f.vehicle) {
                VehicleController.of(f.vehicle).cancel();
                helper.assertTrue(fixture.shuttle.getTowingEntity() == null, "cancel releases native tow link");
                var rail = fixture.site().catapults().getFirst();
                for (Direction side : Direction.values()) {
                    var state = f.level.getBlockState(rail.controller().block(f.level).relative(side));
                    if (state.getBlock() instanceof net.minecraft.world.level.block.LeverBlock) {
                        helper.assertFalse(state.getValue(net.minecraft.world.level.block.LeverBlock.POWERED),
                            "cancel unpowers every lever");
                    }
                }
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 300)
    public static void staleCatapultReturnsBeforeHooking(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 256, 96);
        CatapultFixture fixture = new CatapultFixture(f, null);
        f.bindDriver();
        helper.runAfterDelay(20, () -> {
            var rail = fixture.site().catapults().getFirst();
            fixture.shuttle.moveTo(WorldSpaces.world(f.level, rail.start()).orElseThrow()
                .add(Vec3.directionFromRotation(0, rail.yaw()).scale(rail.length() - 1)));
            VehicleCatapults.trigger(f.level, rail, true);
            helper.assertTrue(fixture.depart(f), "stale powered catapult accepts a resettable departure");
            helper.assertTrue(fixture.shuttle.getTowingEntity() == null, "far-end shuttle is not hooked");
        });
        helper.succeedWhen(() -> helper.assertTrue(fixture.shuttle.getTowingEntity() == f.vehicle,
            "shuttle returns before aircraft hooks and launches"));
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void removedFrameRailUnbindsShuttle(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 160, 96);
        Platform platform = new Platform(f, false, true);
        CatapultFixture fixture = new CatapultFixture(f, platform);
        helper.runAfterDelay(20, () -> {
            var rail = fixture.site().catapults().getFirst();
            FrameCatapultMotion.bind(fixture.shuttle, rail);
            f.level.setBlockAndUpdate(rail.start().block(f.level).east(4), Blocks.AIR.defaultBlockState());
            helper.assertFalse(FrameCatapultMotion.tick(fixture.shuttle, () -> {}),
                "removed rail releases the bound frame integrator");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 600)
    public static void autoBoardChoosesEtaAndExcludesCommanded(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.body.stopRiding();
        f.body.moveTo(f.start.add(4, 0, 12));
        f.body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.03);
        ResidentEntity faster = f.person(f.start.add(12, 0, 12));
        faster.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.5);
        ResidentEntity commanded = f.person(f.start.add(2, 0, 5));
        ResidentCommands.of(f.colony).hold(commanded, WorldSpaces.at(commanded));
        helper.runAfterDelay(10, () -> {
            f.body.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.03);
            faster.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(.5);
            helper.assertTrue(WarfareVehicles.INSTANCE.boardAvailable(f.vehicle, f.colony, List.of()).accepted(),
                "automatic boarding is accepted");
            helper.assertTrue(VehicleSeats.assigned(f.vehicle, 0).filter(faster.getUUID()::equals).isPresent(),
                "longer path with faster ETA wins the driver seat: " + WarfareVehicles.INSTANCE.seats(f.vehicle)
                    + " slow=" + f.body.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    + " fast=" + faster.getAttributeValue(Attributes.MOVEMENT_SPEED));
            helper.assertTrue(WarfareVehicles.INSTANCE.seats(f.vehicle).stream()
                .noneMatch(seat -> seat.occupant().filter(commanded.getUUID()::equals).isPresent()),
                "RTS commanded residents are excluded");
        });
        helper.succeedWhen(() -> {
            if (f.tick() % 100 == 0) {
                f.log("boarding position=" + faster.position().subtract(f.start) + " balk=" + faster.balk()
                    + " doing=" + faster.doing() + " route=" + f.labor.route(faster).stream()
                        .map(node -> node.spec().toString()).toList());
            }
            helper.assertTrue(faster.getVehicle() == f.vehicle,
                "the runner walks the selected resident to the hull and boards");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 240)
    public static void boardTankWithoutWandering(GameTestHelper helper) {
        Scene f = new Scene(helper, "m_1a_2", 96, 64, false);
        f.body.stopRiding();
        f.body.moveTo(f.start.add(0, 0, 16));
        double[] nearest = {16};
        helper.runAfterDelay(10, () -> {
            var duty = new VehicleSeatDuty(f.vehicle, TestWorker.of(f.body), 0, false);
            helper.assertFalse(duty.spec().stances().cells().isEmpty(), "tank has boarding footings");
            Vec3 waiting = f.body.position();
            for (var cell : duty.spec().stances().cells()) {
                for (Vec3 offset : List.of(new Vec3(Reach.IN_THE_CELL, 0, 0),
                        new Vec3(-Reach.IN_THE_CELL, 0, 0), new Vec3(0, 0, Reach.IN_THE_CELL),
                        new Vec3(0, 0, -Reach.IN_THE_CELL))) {
                    f.body.moveTo(Vec3.atBottomCenterOf(cell.cell()).add(offset));
                    helper.assertTrue(duty.arrived(f.level, f.body, duty.spec().stances(), true),
                        "reaching the selected stance allows boarding: " + cell);
                    helper.assertFalse(duty.arrived(f.level, f.body, duty.spec().stances(), false),
                        "boarding waits for travel to reach the selected stance");
                }
            }
            f.body.moveTo(waiting);
            var farthest = duty.spec().stances().cells().stream()
                .max(java.util.Comparator.comparingDouble(cell ->
                    Vec3.atBottomCenterOf(cell.cell()).distanceToSqr(f.vehicle.position())))
                .orElseThrow();
            // Use a fresh resident so dismounting the fixture does not impose a remount cooldown.
            var probe = f.person(Vec3.atBottomCenterOf(farthest.cell()).add(Reach.IN_THE_CELL, 0, 0));
            try {
                helper.assertTrue(probe.startRiding(f.vehicle), "native mounting accepts the farthest footing");
                probe.stopRiding();
                probe.moveTo(Vec3.atBottomCenterOf(farthest.cell()));
                helper.assertFalse(probe.startRiding(f.vehicle), "dismount cooldown refuses immediate remounting");
                helper.assertTrue(WarfareVehicles.INSTANCE.board(f.vehicle, probe, 0).accepted(),
                    "reserve the seat during native remount cooldown");
                var retry = new VehicleSeatDuty(f.vehicle, TestWorker.of(probe), 0, false);
                helper.assertTrue(retry.holds(f.level, TestWorker.of(probe)),
                    "a refused native mount keeps the boarding holding running");
                helper.assertFalse(probe.isPassenger(), "the waiting holding respects native remount cooldown");
                VehicleSeats.release(f.vehicle, 0);
            } finally {
                probe.stopRiding();
                probe.discard();
            }
            helper.assertTrue(WarfareVehicles.INSTANCE.board(f.vehicle, f.body, 0).accepted(),
                "assign tank driver through the boarding API");
        });
        helper.onEachTick(() -> {
            // GameTest callbacks share an unordered map: run labour before observing its result.
            f.labor.tick();
            if (f.tick() < 11 || f.body.getVehicle() == f.vehicle) {
                return;
            }
            if (f.tick() % 10 == 0) {
                f.log("boarder=" + f.body.position().subtract(f.start) + " doing=" + f.body.doing()
                    + " balk=" + f.body.balk());
            }
            helper.assertTrue(f.body.balk().isEmpty(), "boarding must not fail and back off: " + f.body.balk());
            helper.assertTrue(f.body.doing().map(doing -> doing.toward().orElse(doing.what()))
                .filter(Doings.WORKING::equals).isPresent(), "boarding keeps control: " + f.body.doing());
            double distance = f.body.position().subtract(f.start).horizontalDistance();
            helper.assertTrue(distance <= nearest[0] + 1, "boarder must not turn away from the tank");
            helper.assertTrue(Math.abs(f.body.getX() - f.start.x) <= 2, "boarder stays on its approach");
            nearest[0] = Math.min(nearest[0], distance);
        });
        helper.succeedWhen(() -> helper.assertTrue(f.body.getVehicle() == f.vehicle,
            "driver walks sixteen blocks to the tank and boards within twelve seconds"));
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 170)
    public static void boardTankFromOppositeSides(GameTestHelper helper) {
        Scene f = new Scene(helper, "m_1a_2", 96, 64, false);
        f.vehicle.setYRot(0);
        f.body.stopRiding();
        f.body.moveTo(f.start.add(-10.5, 0, 2.5));
        ResidentEntity second = f.person(f.start);
        second.moveTo(f.start.add(1.5, 0, 21.5));
        // NeoForge also invokes this hook for a tracking-only exit, with no removal reason.
        // Tracking resumes without onAddedToLevel; the resident must remain available to labour.
        helper.assertTrue(second.getRemovalReason() == null, "the resident has not left the level");
        second.onRemovedFromLevel();
        for (var parked : Map.of("ztz_99a", 15, "bmp_2", 30).entrySet()) {
            VehicleEntity obstacle = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("superbwarfare", parked.getKey())).create(f.level);
            obstacle.moveTo(f.start.x + parked.getValue(), f.start.y, f.start.z, 0, 0);
            f.level.addFreshEntity(obstacle);
            Cleanup.on(helper, obstacle::discard);
        }
        List<ResidentEntity> boarders = List.of(f.body, second);
        double[] nearest = {Math.hypot(10.5, 2.5), Math.hypot(1.5, 21.5)};
        int[] mounted = {-1, -1};
        helper.runAfterDelay(10, () -> {
            // Reproduce navigation being unavailable while a spawned/dismounted resident settles.
            second.setOnGround(false);
            helper.assertTrue(second.getNavigation().createPath(f.vehicle.blockPosition(), 0) == null,
                "unsettled ground navigation cannot calculate a path yet");
            helper.assertTrue(WarfareVehicles.INSTANCE.boardAvailable(f.vehicle, f.colony, List.of()).accepted(),
                "Board assigns both crew seats using the normal automatic command");
            for (ResidentEntity body : boarders) {
                int seat = WarfareVehicles.INSTANCE.seats(f.vehicle).stream()
                    .filter(value -> value.occupant().filter(body.getUUID()::equals).isPresent())
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                        "Automatic boarding assigned no seat to " + boardingState(f, body))).index();
                var duty = new VehicleSeatDuty(f.vehicle, TestWorker.of(body), seat, false);
                helper.assertTrue(duty.ready(f.level), "boarding duty remains ready: " + boardingState(f, body));
                BlockPos closest = VehicleSeatDuty.footing(f.vehicle, body).orElseThrow(() ->
                    new IllegalStateException("No boarding footing for " + boardingState(f, body)));
                double distance = Vec3.atBottomCenterOf(closest).distanceToSqr(body.position());
                helper.assertFalse(duty.spec().stances().cells().isEmpty(), "boarding needs a reachable stance");
                for (var stance : duty.spec().stances().cells()) {
                    helper.assertTrue(Vec3.atBottomCenterOf(stance.cell()).distanceToSqr(body.position()) <= distance + .01,
                        "boarding must target this resident's nearest reachable stance, not the far side: "
                            + stance.cell().subtract(BlockPos.containing(f.start)) + " nearest="
                            + closest.subtract(BlockPos.containing(f.start)));
                }
            }
        });
        helper.onEachTick(() -> {
            f.labor.tick();
            if (f.tick() < 11) {
                return;
            }
            for (int n = 0; n < boarders.size(); n++) {
                ResidentEntity body = boarders.get(n);
                if (body.getVehicle() == f.vehicle) {
                    if (mounted[n] < 0) {
                        mounted[n] = f.tick() - 10;
                        f.log("opposite boarder=" + n + " mounted after=" + mounted[n]);
                    }
                    continue;
                }
                double distance = body.position().subtract(f.start).horizontalDistance();
                if (f.tick() % 10 == 0) {
                    f.log("opposite boarder=" + n + " offset=" + body.position().subtract(f.start)
                        + " route=" + f.labor.route(body).stream().map(node -> node.spec().stances()).toList());
                }
                helper.assertTrue(distance <= nearest[n] + 1,
                    "boarder " + n + " must not walk away from the tank: " + body.position().subtract(f.start));
                double lateral = n == 0 ? Math.abs(body.getZ() - (f.start.z + 2.5))
                    : Math.abs(body.getX() - (f.start.x + 1.5));
                helper.assertTrue(lateral <= 2,
                    "boarder " + n + " takes its clear approach instead of walking around the tank: "
                        + body.position().subtract(f.start));
                nearest[n] = Math.min(nearest[n], distance);
            }
        });
        // The farther resident starts 21.6 blocks from the centre, about 15 from the hull's boarding ring.
        // At normal walking speed (~3.4 blocks/second), eight seconds allows planning and mount retries.
        helper.succeedWhen(() -> helper.assertTrue(boarders.stream().allMatch(body -> body.getVehicle() == f.vehicle),
            "both residents board within eight seconds of Board"));
    }

    private static String boardingState(Scene scene, ResidentEntity body) {
        return "resident=" + body.getUUID() + " offset=" + body.position().subtract(scene.start)
            + " onGround=" + body.onGround() + " passenger=" + body.isPassenger()
            + " alive=" + body.isAlive() + " removal=" + body.getRemovalReason()
            + " colony=" + body.colonyId() + " hasFooting=" + VehicleSeatDuty.hasFooting(scene.vehicle, body)
            + " footing=" + VehicleSeatDuty.footing(scene.vehicle, body)
            + " seats=" + WarfareVehicles.INSTANCE.seats(scene.vehicle);
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void manualAssignmentAndSeatSwap(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        ResidentEntity second = f.person(f.start.add(0, 0, 6));
        helper.assertTrue(WarfareVehicles.INSTANCE.board(f.vehicle, second, 1).accepted(), "assign second seat");
        helper.assertTrue(second.startRiding(f.vehicle), "second resident mounts");
        helper.assertTrue(f.vehicle.getSeatIndex(second) == 1 || f.vehicle.changeSeat(second, 1), "native seat one");
        helper.assertTrue(WarfareVehicles.INSTANCE.board(f.vehicle, f.body, 1).accepted(), "swap occupied seats");
        helper.assertValueEqual(f.vehicle.getSeatIndex(second), 0, "old occupant becomes driver");
        helper.assertValueEqual(f.vehicle.getSeatIndex(f.body), 1, "driver moves to selected seat");
        helper.assertTrue(VehicleSeats.holds(f.vehicle, second) && VehicleSeats.holds(f.vehicle, f.body),
            "both leases follow the swapped native seats");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 500)
    public static void leavingResumesOriginalWork(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.body.stopRiding();
        f.body.getPersistentData().putBoolean(WORK, true);
        int[] before = {0};
        helper.runAfterDelay(40, () -> {
            before[0] = f.body.getPersistentData().getInt(WORK + "/ticks");
            helper.assertTrue(before[0] > 0, "resident performs original work before control");
            f.bindDriver();
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(f.body.getVehicle() == f.vehicle, "continuous vehicle duty retains passenger");
            helper.assertTrue(WarfareVehicles.INSTANCE.leave(f.vehicle, -1).accepted(), "leave all seats");
            before[0] = f.body.getPersistentData().getInt(WORK + "/ticks");
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(f.tick() > 110, "wait for release");
            helper.assertTrue(!f.body.isPassenger() && !f.body.getPersistentData().contains(VehicleSeats.SEAT),
                "seat and control reservation are released");
            helper.assertTrue(f.body.getPersistentData().getInt(WORK + "/ticks") > before[0],
                "the original labour source resumes without losing its job");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void boardedResidentRemainsCommandable(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        helper.assertTrue(Commands.commandable(f.body), "leased passenger remains RTS commandable");
        helper.assertTrue(WarfareVehicles.INSTANCE.ownsSeat(f.body), "passenger belongs to a vehicle unit");
        helper.assertTrue(WarfareVehicles.INSTANCE.seats(f.vehicle).getFirst().occupant()
            .filter(f.body.getUUID()::equals).isPresent(), "vehicle roster exposes its resident");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 900)
    public static void groundVehicleReachesWaypointAndStops(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 128, 64);
        f.bindDriver();
        Vec3 destination = f.start.add(35, 0, 0);
        f.order(Vehicles.Action.MOVE, destination);
        helper.succeedWhen(() -> {
            f.log("ground-controller");
            helper.assertTrue(f.vehicle.position().distanceTo(destination) < 4, "hull reaches waypoint");
            helper.assertTrue(f.vehicle.getDeltaMovement().horizontalDistance() < .05, "hull stops at waypoint");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1400)
    public static void commandedTanksPassHeadOn(GameTestHelper helper) {
        Scene f = new Scene(helper, "t_90a", 160, 128);
        f.bindDriver();
        Vec3 opposite = f.start.add(70, 0, 0);
        VehicleEntity second = separationVehicle(f, "t_90a", opposite, 90);
        List<VehicleEntity> vehicles = List.of(f.vehicle, second);
        f.order(Vehicles.Action.ATTACK_MOVE, opposite);
        separationOrder(f, second, f.start);
        watchSeparation(f, vehicles, false);
        helper.succeedWhen(() -> {
            helper.assertTrue(f.vehicle.position().subtract(opposite).horizontalDistance() < 6,
                "first tank reaches the opposite position");
            helper.assertTrue(second.position().subtract(f.start).horizontalDistance() < 6,
                "second tank reaches the opposite position");
            assertParked(helper, vehicles);
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1400)
    public static void commandedGroundVehiclesSpreadAtDestination(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 160, 128);
        f.bindDriver();
        VehicleEntity second = separationVehicle(f, "bmp_2", f.start.add(0, 0, -20), -90);
        VehicleEntity third = separationVehicle(f, "t_90a", f.start.add(0, 0, 20), -90);
        List<VehicleEntity> vehicles = List.of(f.vehicle, second, third);
        Vec3 destination = f.start.add(65, 0, 0);
        for (var vehicle : vehicles) {
            separationOrder(f, vehicle, destination);
        }
        watchSeparation(f, vehicles, false);
        helper.succeedWhen(() -> {
            for (var vehicle : vehicles) {
                helper.assertTrue(vehicle.position().subtract(destination).horizontalDistance() < 25,
                    "each vehicle parks near the common destination");
                helper.assertTrue(VehicleController.of(vehicle).action().endsWith(".hold"), "route completes");
            }
            assertParked(helper, vehicles);
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1800)
    public static void commandedHelicoptersKeepSeparation(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 128);
        f.bindDriver();
        VehicleEntity second = separationVehicle(f, "ah_6", f.start.add(0, 0, 24), -90);
        List<VehicleEntity> vehicles = List.of(f.vehicle, second);
        Vec3 destination = f.start.add(65, 0, 0);
        helper.runAfterDelay(20, () -> {
            for (var vehicle : vehicles) {
                separationOrder(f, vehicle, destination);
            }
        });
        watchSeparation(f, vehicles, true);
        helper.succeedWhen(() -> {
            for (var vehicle : vehicles) {
                helper.assertTrue(vehicle.getY() > f.start.y + 15, "helicopter lifts into cruise");
                helper.assertTrue(vehicle.position().subtract(destination).horizontalDistance() < 25,
                    "helicopter reaches the shared destination area");
                helper.assertTrue(VehicleController.of(vehicle).action().endsWith(".hold"), "flight completes");
            }
            assertParked(helper, vehicles);
        });
    }

    private static VehicleEntity separationVehicle(Scene f, String type, Vec3 at, float yaw) {
        VehicleEntity vehicle = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(
            ResourceLocation.fromNamespaceAndPath("superbwarfare", type)).create(f.level);
        vehicle.moveTo(at.x, at.y, at.z, yaw, 0);
        vehicle.setEnergy(vehicle.getMaxEnergy());
        f.level.addFreshEntity(vehicle);
        ResidentEntity driver = f.person(at);
        f.helper.assertTrue(WarfareVehicles.INSTANCE.board(vehicle, driver, 0).accepted(), "assign extra driver");
        f.helper.assertTrue(driver.startRiding(vehicle), "extra driver mounts before movement order");
        if (vehicle.getSeatIndex(driver) != 0) {
            f.helper.assertTrue(vehicle.changeSeat(driver, 0), "extra driver occupies seat zero");
        }
        f.helper.assertTrue(VehicleSeats.holds(vehicle, driver), "extra driver holds its assigned seat");
        Cleanup.on(f.helper, () -> {
            VehicleController.remove(vehicle);
            vehicle.discard();
        });
        return vehicle;
    }

    private static void separationOrder(Scene f, VehicleEntity vehicle, Vec3 destination) {
        var result = WarfareVehicles.INSTANCE.order(vehicle, new Vehicles.Order(Vehicles.Action.MOVE,
            List.of(WorldSpaces.at(f.level, BlockPos.containing(destination))), -1,
            Optional.empty(), Optional.empty()));
        f.helper.assertTrue(result.accepted(), "accept shared movement order: " + result.key());
    }

    private static void watchSeparation(Scene f, List<VehicleEntity> vehicles, boolean airborne) {
        f.helper.onEachTick(() -> {
            for (int i = 0; i < vehicles.size(); i++) {
                for (int j = i + 1; j < vehicles.size(); j++) {
                    VehicleEntity first = vehicles.get(i);
                    VehicleEntity second = vehicles.get(j);
                    Vec3 delta = first.position().subtract(second.position());
                    double minimum = (first.getBbWidth() + second.getBbWidth()) / 2 + 0.5;
                    f.helper.assertTrue(delta.horizontalDistance() > minimum || airborne
                        && Math.abs(delta.y) > Math.max(first.getBbHeight(), second.getBbHeight()) + 2,
                        "continuous hull separation tick=" + f.tick() + " delta=" + delta + " minimum=" + minimum);
                    f.helper.assertFalse(first.getBoundingBox().intersects(second.getBoundingBox()),
                        "native hull bounds never overlap");
                }
            }
            if (f.tick() % 200 == 0) {
                for (var vehicle : vehicles) {
                    LogUtils.getLogger().info("VEHICLE_SEPARATION tick={} id={} position={} velocity={} action={}",
                        f.tick(), vehicle.getId(), vehicle.position().subtract(f.start), vehicle.getDeltaMovement(),
                        VehicleController.of(vehicle).action());
                }
            }
        });
    }

    private static void assertParked(GameTestHelper helper, List<VehicleEntity> vehicles) {
        for (var vehicle : vehicles) {
            helper.assertTrue(vehicle.getDeltaMovement().length() < .05, "each vehicle settles independently");
        }
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1800)
    public static void helicopterWaypointAndLandingSite(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.bindDriver();
        Vec3 destination = f.start.add(18, 10, 0);
        var site = new LandingSites.Site("test/" + f.vehicle.getUUID(),
            "Vehicle test pad", LandingSites.Kind.HELIPAD,
            WorldSpaces.at(f.level, BlockPos.containing(f.start.add(18, 0, 0))), -90, 32, 32);
        // The provider is scoped to this live entity and returns nothing after cleanup.
        LandingSites.register(level -> level == f.level && f.vehicle.isAlive()
            ? List.of(site) : List.of());
        f.order(Vehicles.Action.MOVE, destination);
        boolean[] landing = {false};
        int[] hovering = {-1};
        helper.onEachTick(() -> {
            if (f.tick() % 100 == 0) {
                f.log("helicopter-controller");
            }
            if (hovering[0] < 0 && VehicleController.of(f.vehicle).action().endsWith(".hold")
                && f.vehicle.getDeltaMovement().length() < .05) {
                hovering[0] = f.tick();
            }
            if (!landing[0] && hovering[0] >= 0 && f.tick() - hovering[0] >= 40) {
                helper.assertTrue(f.vehicle.getDeltaMovement().length() < .05, "waypoint hold is zero-speed hover");
                landing[0] = true;
                helper.assertTrue(WarfareVehicles.INSTANCE.order(f.vehicle, new Vehicles.Order(Vehicles.Action.LAND,
                    List.of(), -1, Optional.empty(), Optional.of(site.airport().id()))).accepted(),
                    "land at selected airport");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(landing[0], "takeoff and waypoint completed before landing");
            helper.assertTrue(f.vehicle.onGround() && f.vehicle.position().distanceTo(f.start.add(18, 0, 0)) < 6,
                "helicopter lands on the chosen pad");
            helper.assertTrue(f.vehicle.getDeltaMovement().length() < .05, "landed helicopter comes to rest");
            helper.assertFalse(f.vehicle.isWreck(), "native landing preserves the hull");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1000)
    public static void fixedWingTakesOffAndLoiters(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 640, 96);
        f.bindDriver();
        helper.runAfterDelay(20, () -> {
            registerRunway(f, DirectVehicleMotion.runway(f.vehicle).orElseThrow().takeoff() + 32);
            f.order(Vehicles.Action.TAKE_OFF, null);
        });
        helper.succeedWhen(() -> {
            if (f.tick() % 100 == 0) {
                f.log("fixed-wing-controller");
            }
            helper.assertTrue(f.height() > 8 && !f.vehicle.onGround(), "native flight takes off from runway");
            helper.assertTrue(VehicleController.of(f.vehicle).action().equals("folkways.rts.unit.state.loiter"), "controller enters loiter");
            helper.assertTrue(f.tick() > 350 && !f.vehicle.isWreck(), "aircraft remains flying while waiting");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1600)
    public static void helicopterMoveFromParkedCruisesAboveTerrain(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.bindDriver();
        Vec3 destination = f.start.add(60, 0, 0);
        double[] liftedAt = {-1};
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(f.height() < 1, "helicopter starts parked");
            helper.assertTrue(WarfareVehicles.INSTANCE.capabilities(f.vehicle).airframe()
                == Vehicles.Airframe.HELICOPTER, "engine type classifies the airframe");
            // No Take off: Move alone departs a parked helicopter.
            f.order(Vehicles.Action.MOVE, destination);
        });
        helper.onEachTick(() -> {
            if (liftedAt[0] < 0 && f.height() > 3) {
                liftedAt[0] = f.vehicle.position().subtract(f.start).horizontalDistance();
            }
            if (f.tick() % 100 == 0) {
                f.log("helicopter-cruise");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(liftedAt[0] >= 0 && liftedAt[0] < 2, "lifts off before setting off: " + liftedAt[0]);
            helper.assertTrue(VehicleController.of(f.vehicle).action().endsWith(".hold"), "route completes");
            helper.assertTrue(f.vehicle.position().subtract(destination).horizontalDistance() < 4,
                "hovers over the destination");
            helper.assertTrue(f.vehicle.getDeltaMovement().length() < .05, "settles into a hover");
            double cruise = VehicleController.height(Vehicles.Altitude.CRUISE, false, 0);
            helper.assertTrue(Math.abs(f.height() - cruise) < 3,
                "holds the cruise band above the terrain, not the clicked ground: " + f.height());
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 600)
    public static void helicopterDepartsCurrentPad(GameTestHelper helper) {
        helicopterDeparture(helper, true, false);
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 600)
    public static void helicopterDepartsOpenGround(GameTestHelper helper) {
        helicopterDeparture(helper, false, false);
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 600)
    public static void helicopterDepartsWithOtherPadsOccupied(GameTestHelper helper) {
        helicopterDeparture(helper, true, true);
    }

    private static void helicopterDeparture(GameTestHelper helper, boolean onPad, boolean occupied) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.bindDriver();
        var airport = new LandingSites.Airport("departure-" + f.vehicle.getUUID(), "Departure", Optional.empty());
        // A contains the hull away from its center; B is nearer but does not contain it.
        var a = new LandingSites.Site(airport.id() + "/a", "Pad A", LandingSites.Kind.HELIPAD,
            WorldSpaces.at(f.level, BlockPos.containing(f.start.add(onPad ? -24 : -48, 0, 0))),
            0, 64, 64, List.of(), airport);
        var b = new LandingSites.Site(airport.id() + "/b", "Pad B", LandingSites.Kind.HELIPAD,
            WorldSpaces.at(f.level, BlockPos.containing(f.start.add(18, 0, 0))),
            0, 16, 16, List.of(), airport);
        var runway = new LandingSites.Site(airport.id() + "/runway", "Runway", LandingSites.Kind.RUNWAY_END,
            WorldSpaces.at(f.vehicle), 0, 160, 64, List.of(), airport);
        var available = new AtomicBoolean(true);
        LandingSites.register(level -> available.get() && level == f.level ? List.of(a, b, runway) : List.of());
        Cleanup.on(helper, () -> {
            available.set(false);
            VehicleRunwayTraffic.release(f.vehicle);
        });
        if (occupied) {
            var parked = (VehicleEntity) f.vehicle.getType().create(f.level);
            parked.moveTo(b.pose(f.level).orElseThrow().position());
            f.level.addFreshEntity(parked);
            Cleanup.on(helper, parked::discard);
        }
        AtomicBoolean ordered = new AtomicBoolean(false);
        AtomicBoolean climbed = new AtomicBoolean(false);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(f.height() < 1, "helicopter starts parked");
            f.order(Vehicles.Action.MOVE, f.start.add(60, 0, 0));
            ordered.set(true);
            var claimA = AirportTraffic.request(f.level, AirportTraffic.Priority.COMBAT, a).orElseThrow();
            var claimB = AirportTraffic.request(f.level, AirportTraffic.Priority.COMBAT, b).orElseThrow();
            try {
                helper.assertTrue(claimA.state() == (onPad ? AirportTraffic.State.WAITING : AirportTraffic.State.HELD),
                    "only the pad beneath the helicopter is reserved");
                helper.assertTrue(claimB.state() == AirportTraffic.State.HELD, "departure never reserves pad B");
            } finally {
                claimA.close();
                claimB.close();
            }
        });
        helper.onEachTick(() -> {
            if (ordered.get() && !climbed.get()) {
                helper.assertTrue(f.vehicle.position().subtract(f.start).horizontalDistance() < 2,
                    "lifts vertically from its parking location");
                if (f.height() > 3) {
                    climbed.set(true);
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(climbed.get() && f.height() > 10, "lifts clear before flying the order");
            var claim = AirportTraffic.request(f.level, AirportTraffic.Priority.COMBAT, a).orElseThrow();
            try {
                helper.assertTrue(claim.state() == AirportTraffic.State.HELD, "pad A is released after lifting clear");
            } finally {
                claim.close();
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 2000)
    public static void helicopterClimbsBeforeHillAhead(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.bindDriver();
        int wall = 80;
        int rise = 24;
        BlockPos base = BlockPos.containing(f.start);
        for (int x = wall; x < wall + 6; x++) {
            for (int z = -40; z < 40; z++) {
                for (int y = 0; y < rise; y++) {
                    f.level.setBlock(base.offset(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
        }
        Vec3 destination = f.start.add(120, 0, 0);
        double top = f.start.y + rise;
        boolean[] passed = {false};
        double[] lowest = {Double.POSITIVE_INFINITY};
        helper.runAfterDelay(20, () -> f.order(Vehicles.Action.MOVE, destination));
        helper.onEachTick(() -> {
            double front = f.vehicle.getX() + f.vehicle.getBbWidth() / 2;
            // Approaching the ridge, the hull must already be above it: the climb began before the terrain rose.
            if (front > f.start.x + wall - 12 && front < f.start.x + wall) {
                lowest[0] = Math.min(lowest[0], f.vehicle.getY() - top);
                helper.assertTrue(f.vehicle.getY() > top + 2,
                    "climbs before the hill, not into it: " + (f.vehicle.getY() - top));
            }
            helper.assertFalse(f.vehicle.horizontalCollision, "never flies into the hill");
            passed[0] |= f.vehicle.getX() > f.start.x + wall + 8;
            if (f.tick() % 100 == 0) {
                f.log("helicopter-hill lowest=" + lowest[0]);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(passed[0], "crosses the hill");
            helper.assertTrue(Double.isFinite(lowest[0]), "the approach was observed");
            helper.assertTrue(f.vehicle.position().subtract(destination).horizontalDistance() < 4,
                "reaches the far side");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1600)
    public static void helicopterStopHoversInPlace(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.bindDriver();
        Vec3[] stopped = {null};
        Vec3[] rest = {null};
        int[] still = {0};
        double[] away = {0};
        helper.runAfterDelay(20, () -> f.order(Vehicles.Action.MOVE, f.start.add(140, 0, 0)));
        helper.onEachTick(() -> {
            if (stopped[0] == null && f.height() > 12
                && f.vehicle.getDeltaMovement().horizontalDistance() > .2) {
                f.order(Vehicles.Action.STOP, null);
                stopped[0] = f.vehicle.position();
            }
            if (stopped[0] != null) {
                // It brakes to a hover: never turning back toward where Stop was given.
                double distance = f.vehicle.position().subtract(stopped[0]).horizontalDistance();
                helper.assertTrue(distance > away[0] - 2, "a stopped helicopter does not fly back");
                away[0] = Math.max(away[0], distance);
                helper.assertTrue(f.vehicle.getY() > stopped[0].y - 2, "a stopped helicopter does not sink");
                helper.assertFalse(f.vehicle.onGround(), "a stopped helicopter stays in the air");
                if (f.vehicle.getDeltaMovement().length() < .02) {
                    if (still[0]++ == 0) {
                        rest[0] = f.vehicle.position();
                    }
                } else {
                    still[0] = 0;
                }
            }
            if (f.tick() % 100 == 0) {
                f.log("helicopter-stop away=" + away[0]);
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(still[0] >= 60, "comes to rest and stays");
            helper.assertTrue(f.vehicle.position().distanceTo(rest[0]) < 1, "hovers in place");
            helper.assertTrue(Math.abs(f.vehicle.getY() - stopped[0].y) < 6,
                "hovers near the height it was stopped at: " + (f.vehicle.getY() - stopped[0].y));
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1400)
    public static void fixedWingLoiterFromParkedTakesOff(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 640, 96);
        f.bindDriver();
        Vec3 point = f.start.add(240, 0, 0);
        helper.runAfterDelay(20, () -> {
            registerRunway(f, DirectVehicleMotion.runway(f.vehicle).orElseThrow().takeoff() + 32);
            helper.assertTrue(WarfareVehicles.INSTANCE.capabilities(f.vehicle).airframe()
                == Vehicles.Airframe.FIXED_WING, "engine type classifies the airframe");
            helper.assertTrue(f.height() < 1, "aircraft starts parked");
            f.order(Vehicles.Action.LOITER, point);
            // Other fixtures in this batch publish airports too; any assignment proves the airport path ran.
            helper.assertTrue(VehicleAirports.home(f.vehicle).isPresent(),
                "loiter departs through an airport's runway assignment");
        });
        helper.succeedWhen(() -> {
            if (f.tick() % 100 == 0) {
                f.log("fixed-wing-loiter terrain=" + f.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    f.vehicle.getBlockX(), f.vehicle.getBlockZ()) + " startY=" + f.start.y
                    + " clamp=" + (f.vehicle.getEngineInfo() instanceof EngineInfo.Aircraft a ? a.getClampPitch() : -1));
            }
            helper.assertTrue(f.tick() > 500 && !f.vehicle.isWreck(), "keeps flying");
            double cruise = VehicleController.height(Vehicles.Altitude.CRUISE, true, 0);
            helper.assertTrue(!f.vehicle.onGround() && Math.abs(f.height() - cruise) < 10,
                "took off and holds the cruise band: " + f.height());
            helper.assertTrue(VehicleController.of(f.vehicle).action().endsWith(".loiter"), "loiters");
            helper.assertTrue(f.vehicle.position().subtract(point).horizontalDistance() < 160,
                "circles the clicked point");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void aircraftAltitudePersists(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 96, 64);
        Scene ground = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        ground.bindDriver();
        helper.runAfterDelay(2, () -> {
            helper.assertValueEqual(Vehicles.altitude(f.vehicle), Vehicles.Altitude.CRUISE, "new aircraft cruise");
            var player = commander(f);
            var reply = UnitOrders.handle(player, new UnitRequest(1, f.colony.id(), f.vehicle.getUUID(),
                UnitRequest.Operation.ALTITUDE, List.of(), -1, Vehicles.Altitude.HIGH.ordinal(),
                new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.empty(), Optional.empty()), "",
                WeaponRules.DEFAULT, Optional.empty()));
            helper.assertTrue(reply.result().accepted(), "altitude request accepted");
            helper.assertValueEqual(Vehicles.altitude(f.vehicle), Vehicles.Altitude.HIGH, "server stores the band");
            var decoded = roundTrip(f, reply.snapshot().orElseThrow()).units().stream()
                .filter(unit -> unit.id().equals(f.vehicle.getUUID())).findFirst().orElseThrow();
            helper.assertValueEqual(decoded.altitude(), Vehicles.Altitude.HIGH, "snapshot carries the band");
            helper.assertValueEqual(decoded.capabilities().airframe(), Vehicles.Airframe.HELICOPTER,
                "snapshot carries the airframe");
            CompoundTag saved = new CompoundTag();
            f.vehicle.saveWithoutId(saved);
            var restored = (VehicleEntity) f.vehicle.getType().create(f.level);
            restored.load(saved);
            helper.assertValueEqual(Vehicles.altitude(restored), Vehicles.Altitude.HIGH, "band survives a reload");
            helper.assertFalse(UnitOrders.handle(commander(ground), new UnitRequest(2, ground.colony.id(),
                ground.vehicle.getUUID(),
                UnitRequest.Operation.ALTITUDE, List.of(), -1, Vehicles.Altitude.LOW.ordinal(),
                new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.empty(), Optional.empty()), "",
                WeaponRules.DEFAULT, Optional.empty())).result().accepted(), "ground vehicles have no altitude");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1000)
    public static void depletedHelicopterUsesNativeFailure(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        f.bindDriver();
        f.order(Vehicles.Action.MOVE, f.start.add(0, 12, 0));
        int[] drained = {-1};
        double[] height = {0};
        float[] power = {0};
        helper.onEachTick(() -> {
            // Drain in a steady hover, so leftover climb cannot mask the lost lift.
            if (drained[0] < 0 && f.height() > 8 && f.vehicle.getDeltaMovement().length() < .05) {
                f.vehicle.setEnergy(0);
                drained[0] = f.tick();
                height[0] = f.height();
                power[0] = f.vehicle.getPower();
            }
            if (drained[0] >= 0 && f.tick() - drained[0] == 120) {
                helper.assertTrue(f.vehicle.getPower() < power[0], "SW's depleted rotor power decays");
                helper.assertTrue(f.height() < height[0], "without energy the commanded helicopter loses lift");
                helper.assertTrue(f.vehicle.getEnergy() == 0, "no energy is manufactured by direct control");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void playerDriverKeepsNativeEngineAndInputs(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        f.body.stopRiding();
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(f.start);
        helper.assertTrue(player.startRiding(f.vehicle, true), "player boards the owned hull");
        if (f.vehicle.getSeatIndex(player) != 0) {
            helper.assertTrue(f.vehicle.changeSeat(player, 0), "player takes the driver seat");
        }
        Cleanup.on(helper, player::stopRiding);
        f.vehicle.setForwardInputDown(true);
        f.vehicle.setMouseMoveSpeedX(4);
        WarfareVehicles.tick(new EntityTickEvent.Pre(f.vehicle));
        AtomicInteger calls = new AtomicInteger();
        DirectVehicleMotion.work(new EngineInfo.Wheel() {
            @Override
            public void work(VehicleEntity vehicle) {
                calls.incrementAndGet();
            }
        }, f.vehicle);
        helper.assertValueEqual(calls.get(), 1, "player driving invokes the native work exactly once");
        helper.assertTrue(f.vehicle.forwardInputDown() && f.vehicle.getMouseMoveSpeedX() == 4,
            "neither cancellation nor the direct engine boundary rewrites player inputs");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1200)
    public static void stationaryFrameHelipadBecomesLandingSite(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 160, 96);
        Platform platform = new Platform(f, false);
        helper.succeedWhen(() -> {
            var site = platform.site();
            helper.assertTrue(site.position().realm() instanceof Realm.Frame,
                "the actual tower scan exports a frame-local landing address");
            var pose = site.pose(f.level).orElseThrow();
            helper.assertTrue(pose.position().distanceTo(platform.pad()) < .01,
                "landing pose resolves to the assembled pad, not its storage plot");
            helper.assertTrue(pose.velocity().length() < .01, "anchored platform is stationary");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1800)
    public static void helicopterLandsRidesAndTakesOffMovingFrame(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 192, 96);
        Platform platform = new Platform(f, true);
        f.bindDriver();
        f.order(Vehicles.Action.MOVE, platform.pad().add(-20, 10, 0));
        int[] phase = {0};
        int[] docked = {0};
        Vec3[] local = {null};
        Vec3[] world = {null};
        helper.onEachTick(() -> {
            if (phase[0] == 0 && f.height() > 8 && !Airports.scanning(f.level, platform.tower)) {
                var site = platform.find();
                if (site.isPresent()) {
                    helper.assertTrue(WarfareVehicles.INSTANCE.order(f.vehicle, new Vehicles.Order(Vehicles.Action.LAND,
                        List.of(), -1, Optional.empty(), Optional.of(site.get().airport().id()))).accepted(),
                        "moving frame pad accepts the landing order across realms");
                    phase[0] = 1;
                }
            }
            if (phase[0] == 1 && VehicleController.of(f.vehicle).action().endsWith(".landed")) {
                phase[0] = 2;
                docked[0] = f.tick();
                local[0] = platform.ship.logicalPose().transformPositionInverse(f.vehicle.position());
                world[0] = f.vehicle.position();
                VehicleController.remove(f.vehicle);
                VehicleController.of(f.vehicle);
            }
            if (phase[0] == 2 && f.tick() - docked[0] >= 100) {
                helper.assertTrue(f.vehicle.position().distanceTo(world[0]) > 3,
                    "a landed hull travels with the moving rigid body");
                helper.assertTrue(platform.ship.logicalPose().transformPositionInverse(f.vehicle.position())
                    .distanceTo(local[0]) < .5, "deck-relative docking position stays fixed");
                f.order(Vehicles.Action.TAKE_OFF, null);
                phase[0] = 3;
            }
            if (phase[0] == 3 && f.vehicle.getY() - platform.pad().y > 5) {
                helper.assertFalse(f.vehicle.isWreck(), "landing, carrying and relaunch preserve the hull");
                helper.assertTrue(f.vehicle.getEnergy() < f.initialEnergy, "actual powered flight spends energy");
                helper.succeed();
            }
            if (f.tick() % 100 == 0) {
                f.log("moving-platform phase=" + phase[0] + " pad=" + platform.pad()
                    + " platformVelocity=" + platform.ship.latestLinearVelocity + " flaws="
                    + Airports.at(f.level, platform.tower).map(value -> value.flaws().toString()));
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "moving_runway",
        timeoutTicks = 2400)
    public static void fixedWingLandsOnMovingRunway(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 160, 96);
        f.bindDriver();
        Platform platform = new Platform(f, true, true);
        helper.runAfterDelay(20, () -> {
            var site = platform.site();
            var engine = (EngineInfo.Aircraft) f.vehicle.getEngineInfo();
            double minimum = DirectVehicleMotion.minimumSpeed(engine, f.vehicle.computed().getGravity());
            double radius = Math.max(f.vehicle.getBbWidth(), minimum / Math.toRadians(engine.getYawSpeed()));
            double clearance = f.vehicle.getBbHeight() + minimum * minimum / f.vehicle.computed().getGravity();
            f.vehicle.moveTo(platform.pad().add(-2 * radius, clearance, 0));
            f.vehicle.setYRot(-90);
            f.vehicle.setDeltaMovement(minimum, 0, 0);
            helper.assertTrue(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.LAND,
                List.of(), -1, Optional.empty(), Optional.of(site.airport().id()))).accepted(),
                "moving runway accepted");
        });
        int[] landed = {-1};
        Vec3[] local = {null};
        helper.onEachTick(() -> {
            if (f.tick() % 200 == 0) {
                f.log("moving-runway " + VehicleController.of(f.vehicle).action());
            }
            if (VehicleController.of(f.vehicle).action().equals("folkways.rts.unit.state.landed")) {
                Vec3 at = WorldSpaces.frame(f.level, platform.ship.getUniqueId()).orElseThrow().toLocal(f.vehicle.position());
                if (landed[0] < 0 && f.vehicle.getDeltaMovement().subtract(platform.site().pose(f.level)
                    .orElseThrow().velocity()).length() < .1) {
                    landed[0] = f.tick();
                    local[0] = at;
                }
                if (landed[0] >= 0 && f.tick() - landed[0] >= 100) {
                    helper.assertTrue(at.distanceTo(local[0]) < .5, "fixed wing holds a deck-local parking point");
                    helper.assertFalse(f.vehicle.isWreck(), "landing preserves the aircraft");
                    helper.succeed();
                }
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void catapultIsRunwayAccessory(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 256, 96);
        CatapultFixture fixture = new CatapultFixture(f, null);
        helper.runAfterDelay(20, () -> {
            var site = fixture.site();
            helper.assertValueEqual(site.catapults().size(), 1, "runway scan finds one accessory");
            var catapult = site.catapults().getFirst();
            helper.assertValueEqual(catapult.length(), 12, "rail length retained under its runway end");
            helper.assertValueEqual(catapult.power(), 15, "controller supplies launch power");
            helper.assertTrue(catapult.start().sameRealm(site.position()), "accessory retains the runway realm");
            helper.assertTrue(catapult.shuttleExitSpeed() > 0 && catapult.shuttleExitSpeed() < 1.5,
                "finite rail speed includes native .8 damping");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void catapultShortRunwayIsAccepted(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 256, 96);
        f.bindDriver();
        CatapultFixture fixture = new CatapultFixture(f, null);
        helper.runAfterDelay(20, () -> {
            var scanned = fixture.site();
            var needs = DirectVehicleMotion.runway(f.vehicle).orElseThrow();
            double boosted = needs.takeoff(scanned);
            helper.assertTrue(boosted < needs.takeoff(), "native catapult reduces required roll");
            fixture.length = Math.ceil(boosted);
            var site = fixture.site();
            var plain = new LandingSites.Site(site.id(), site.name(), site.kind(), site.position(), site.yaw(),
                site.length(), site.width());
            helper.assertFalse(needs.fits(plain, true, 0), "same runway without catapult is too short");
            helper.assertTrue(needs.fits(site, true, 0), "short runway fits with native tow acceleration");
            helper.assertTrue(fixture.depart(f), "server accepts catapult-equipped short runway");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1600)
    public static void fixedWingCatapultTakesOffAndClimbs(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 320, 96);
        f.bindDriver();
        CatapultFixture fixture = new CatapultFixture(f, null);
        boolean[] attached = {false};
        boolean[] powered = {false};
        helper.runAfterDelay(20, () -> {
            f.vehicle.setOnGround(false);
            helper.assertTrue(fixture.loiter(f, f.start.add(96, 24, 0)),
                "resting aircraft departs through its catapult even without the native ground flag");
        });
        helper.onEachTick(() -> {
            attached[0] |= fixture.shuttle.getTowingEntity() == f.vehicle;
            powered[0] |= fixture.shuttle.getDeltaMovement().x > .1;
            if (f.tick() % 200 == 0) {
                f.log("catapult attached=" + attached[0] + " shuttle=" + fixture.shuttle.position()
                    + " towing=" + fixture.shuttle.getTowingUUID());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(attached[0] && powered[0], "native shuttle tows the aircraft under redstone power");
            helper.assertTrue(f.height() > 8 && !f.vehicle.onGround(), "direct controller takes over climb");
            helper.assertTrue(fixture.shuttle.getTowingEntity() == null, "tow released after launch");
            helper.assertFalse(f.vehicle.isWreck(), "launch preserves aircraft");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 160)
    public static void airborneFixedWingSkipsCatapult(GameTestHelper helper) {
        Scene f = new Scene(helper, "ju_87", 320, 96);
        f.bindDriver();
        CatapultFixture fixture = new CatapultFixture(f, null);
        helper.runAfterDelay(20, () -> {
            f.vehicle.moveTo(f.start.add(0, 40, 0));
            f.vehicle.setOnGround(false);
            helper.assertTrue(fixture.depart(f), "airborne takeoff accepted");
            var stale = new VehicleCatapultLaunch(f.vehicle, fixture.site(), fixture.site().catapults().getFirst());
            helper.assertFalse(stale.tick((EngineInfo.Aircraft) f.vehicle.getEngineInfo()),
                "unseated airborne launch aborts immediately");
        });
        helper.runAfterDelay(100, () -> {
            helper.assertTrue(f.height() > 30, "airborne plane does not return to shuttle height");
            helper.assertTrue(VehicleController.of(f.vehicle).action().endsWith("loiter"),
                "airborne takeoff continues in normal loiter state");
            helper.assertTrue(fixture.shuttle.getTowingEntity() == null, "airborne plane holds no tow link");
            helper.assertTrue(VehicleCatapults.available(f.level, fixture.site().catapults().getFirst()),
                "catapult remains available");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1600)
    public static void airborneTakeoffLeavesCatapultFree(GameTestHelper helper) {
        Scene first = new Scene(helper, "ju_87", 320, 96);
        Scene second = new Scene(helper, "ju_87", 160, 96);
        first.bindDriver();
        second.bindDriver();
        CatapultFixture fixture = new CatapultFixture(first, null);
        // Keep the waiting aircraft on its own paved fixture until the runway is free.
        boolean[] attached = {false};
        helper.runAfterDelay(20, () -> {
            first.vehicle.moveTo(first.start.add(0, 40, 0));
            first.vehicle.setOnGround(false);
            helper.assertTrue(fixture.depart(first), "airborne takeoff accepted");
            second.vehicle.moveTo(first.start);
            second.vehicle.setYRot(-90);
            helper.assertFalse(second.vehicle.isWreck(), "waiting aircraft retains a working hull");
            helper.assertTrue(fixture.depart(second), "second aircraft acquires released runway and catapult");
        });
        helper.onEachTick(() -> attached[0] |= fixture.shuttle.getTowingEntity() == second.vehicle);
        helper.succeedWhen(() -> {
            helper.assertTrue(attached[0], "second aircraft uses native catapult tow link");
            helper.assertTrue(second.vehicle.getY() > first.start.y + 8 && !second.vehicle.onGround(),
                "second aircraft launches and climbs");
            helper.assertTrue(fixture.shuttle.getTowingEntity() == null, "catapult releases second aircraft");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "moving_catapult",
        timeoutTicks = 1800)
    public static void fixedWingCatapultOnMovingFrame(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 160, 96);
        f.bindDriver();
        Platform platform = new Platform(f, true, true);
        CatapultFixture fixture = new CatapultFixture(f, platform);
        helper.runAfterDelay(20, () -> {
            f.vehicle.moveTo(platform.pad());
            f.vehicle.setYRot(-90);
            helper.assertTrue(fixture.depart(f), "moving catapult departure accepted");
        });
        boolean[] attached = {false};
        boolean[] climbed = {false};
        int[] returned = {0};
        helper.onEachTick(() -> {
            attached[0] |= fixture.shuttle.getTowingEntity() == f.vehicle;
            if (f.tick() % 200 == 0) {
                f.log("moving-catapult attached=" + attached[0] + " shuttle=" + fixture.shuttle.position()
                    + " pad=" + platform.pad());
            }
        });
        helper.succeedWhen(() -> {
            climbed[0] |= f.vehicle.getY() > platform.pad().y + 8 && !f.vehicle.onGround();
            helper.assertTrue(attached[0], "moving launch used native tow link");
            helper.assertTrue(climbed[0], "aircraft climbs from moving catapult");
            var catapult = fixture.site().catapults().getFirst();
            Vec3 start = WorldSpaces.world(f.level, catapult.start()).orElseThrow();
            if (fixture.shuttle.getTowingEntity() == null && fixture.shuttle.position().distanceTo(start) < .1) {
                returned[0]++;
            } else {
                returned[0] = 0;
            }
            helper.assertTrue(returned[0] >= 100, "released shuttle returns and holds its moving rail start for 100 ticks");
            helper.assertFalse(f.vehicle.isWreck(), "moving launch preserves hull");
        });
    }

    /** A fixed wing parked on a deck is never on the ground in the world: a Loiter order must still depart. */
    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "moving_catapult",
        timeoutTicks = 1200)
    public static void loiterDepartsFromDeckCatapult(GameTestHelper helper) {
        Scene f = new Scene(helper, "ju_87", 160, 96);
        f.bindDriver();
        Platform platform = new Platform(f, true, true);
        CatapultFixture fixture = new CatapultFixture(f, platform);
        helper.runAfterDelay(20, () -> {
            f.vehicle.moveTo(platform.pad());
            helper.assertTrue(fixture.site().length() > DirectVehicleMotion.runway(f.vehicle).orElseThrow().takeoff(),
                "deck runway exceeds the Ju-87 ordinary takeoff roll");
            f.vehicle.setYRot(-90);
        });
        helper.runAfterDelay(40, () -> helper.assertTrue(fixture.loiter(f, platform.pad().add(96, 24, 0)),
            "loiter order to a deck-parked fixed wing accepted"));
        boolean[] attached = {false};
        long[] leftDeck = {-1};
        int[] departureTicks = {0};
        Vec3[] departurePosition = {null};
        helper.onEachTick(() -> {
            attached[0] |= fixture.shuttle.getTowingEntity() == f.vehicle;
            if (leftDeck[0] < 0 && attached[0] && f.vehicle.position().distanceTo(platform.pad()) > 48
                && !f.vehicle.onGround()) {
                leftDeck[0] = f.tick();
                departureTicks[0] = f.vehicle.tickCount;
                departurePosition[0] = f.vehicle.position();
            }
            if (f.tick() % 200 == 0) {
                f.log("deck-loiter attached=" + attached[0] + " state=" + VehicleController.of(f.vehicle).action());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(attached[0], "the loiter order departs through the deck's catapult");
            helper.assertTrue(leftDeck[0] >= 0 && f.tick() - leftDeck[0] >= 200,
                "observe ten seconds of flight after leaving the deck");
            helper.assertTrue(f.vehicle.tickCount - departureTicks[0] >= 195,
                "aircraft keeps ticking after the catapult releases it");
            helper.assertTrue(f.vehicle.position().distanceTo(departurePosition[0]) > 32,
                "aircraft keeps moving after leaving the deck");
            // The deck stands high over the range, so the loiter band (over the terrain) lies below it.
            helper.assertTrue(f.vehicle.position().distanceTo(platform.pad()) > 48 && !f.vehicle.onGround()
                && !f.vehicle.isWreck(), "aircraft flies off the deck");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "moving_catapult",
        timeoutTicks = 1600)
    public static void ju87LongRunwayCatapultOnTurnedFrame(GameTestHelper helper) {
        Scene f = new Scene(helper, "ju_87", 160, 96);
        f.bindDriver();
        Platform platform = new Platform(f, true, true);
        CatapultFixture fixture = new CatapultFixture(f, platform);
        var body = RigidBodyHandle.of(platform.ship);
        body.teleport(new Vector3d(platform.ship.logicalPose().position()),
            new Quaterniond(platform.ship.logicalPose().orientation()).rotateY(Math.toRadians(144)));
        SubLevelPhysicsSystem.require(f.level).updatePose(platform.ship);
        BlockPos rail = fixture.geometry.position().block(f.level).below()
            .east((int) Math.ceil(VehicleCatapults.slack(f.vehicle)) + 3);
        // Exercise inverse-transform rounding just below the rail bottom, before any survey can bind it.
        var frame = WorldSpaces.frame(f.level, platform.ship.getUniqueId()).orElseThrow();
        fixture.shuttle.moveTo(frame.toWorld(Vec3.atBottomCenterOf(frame.local(rail)).add(0, -1.0E-7, 0)));
        // Run before any facility scan: the rail replaces the deck block and has air beneath it.
        helper.assertTrue(WorldSpaces.at(fixture.shuttle).realm() instanceof Realm.Dimension,
            "untracked shuttle at the rail bottom reproduces the missing frame address");
        for (int tick = 0; tick < 12; tick++) {
            fixture.shuttle.tick();
        }
        helper.assertTrue(frame.toLocal(fixture.shuttle.position())
            .distanceTo(Vec3.atBottomCenterOf(frame.local(rail))) < .1,
            "rail contact binds the idle shuttle before any airport survey");
        helper.runAfterDelay(10, () -> {
            var original = fixture.shuttle;
            var saved = new CompoundTag();
            original.save(saved);
            original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            fixture.shuttle = (CatapultShuttleEntity) EntityType.loadEntityRecursive(saved, f.level, entity -> entity);
            helper.assertTrue(fixture.shuttle != null && fixture.shuttle != original
                && fixture.shuttle.getUUID().equals(original.getUUID()), "reload replaces the shuttle object");
            f.level.addFreshEntity(fixture.shuttle);
            // A new object has no entry in the motion map. Exercise contact before a survey can repair it.
            for (int tick = 0; tick < 12; tick++) {
                fixture.shuttle.tick();
            }
            var reloadedFrame = WorldSpaces.frame(f.level, platform.ship.getUniqueId()).orElseThrow();
            helper.assertTrue(reloadedFrame.toLocal(fixture.shuttle.position())
                .distanceTo(Vec3.atBottomCenterOf(reloadedFrame.local(rail))) < .1,
                "reloaded shuttle binds from rail contact and stays on the turned rail");
        });
        var reverse = new LandingSites.Site("reverse-" + f.vehicle.getUUID(), "Reverse runway",
            LandingSites.Kind.RUNWAY_END, WorldSpaces.at(f.level,
                fixture.geometry.position().block(f.level).east((int) fixture.length - 1)),
            90, fixture.length, fixture.geometry.width(), List.of(), fixture.geometry.airport());
        AtomicBoolean available = new AtomicBoolean(true);
        LandingSites.register(level -> available.get() && level == f.level ? List.of(reverse) : List.of());
        Cleanup.on(helper, () -> available.set(false));
        helper.runAfterDelay(20, () -> {
            var local = WorldSpaces.frame(f.level, platform.ship.getUniqueId()).orElseThrow()
                .toLocal(fixture.shuttle.position());
            var start = WorldSpaces.at(f.level, rail).cell();
            helper.assertTrue(local.distanceTo(Vec3.atBottomCenterOf(start)) < .1,
                "idle shuttle stays on its turned rail before the first airport facility scan: local=" + local
                    + " expected=" + Vec3.atBottomCenterOf(start) + " address=" + WorldSpaces.at(fixture.shuttle));
            var site = fixture.site();
            f.vehicle.moveTo(platform.pad());
            var forward = site.pose(f.level).orElseThrow().forward();
            f.vehicle.setYRot((float) Math.toDegrees(Math.atan2(-forward.x, forward.z)));
            helper.assertTrue(site.length() > DirectVehicleMotion.runway(f.vehicle).orElseThrow().takeoff(),
                "long runway supports an ordinary Ju-87 departure");
        });
        helper.runAfterDelay(40, () -> helper.assertTrue(fixture.loiter(f, platform.pad().add(96, 24, 0)),
            "turned deck loiter accepted"));
        boolean[] attached = {false};
        helper.onEachTick(() -> {
            attached[0] |= fixture.shuttle.getTowingEntity() == f.vehicle;
            if (f.tick() % 200 == 0) {
                f.log("turned-catapult attached=" + attached[0] + " distance="
                    + f.vehicle.distanceTo(fixture.shuttle) + " slack=" + VehicleCatapults.slack(f.vehicle)
                    + " relative=" + f.vehicle.position().subtract(fixture.shuttle.position())
                    + " look=" + fixture.shuttle.getLookAngle() + " planePlatform="
                    + platform.site().pose(f.level).orElseThrow().velocity()
                    + " state=" + VehicleController.of(f.vehicle).action());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(attached[0], "departure chooses the catapult end and hooks on a turned frame");
            helper.assertTrue(f.vehicle.position().distanceTo(platform.pad()) > 48 && !f.vehicle.onGround(),
                "Ju-87 flies off the long turned deck");
            helper.assertFalse(f.vehicle.isWreck(), "turned launch preserves hull");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void busyCatapultRejectsSecondAircraft(GameTestHelper helper) {
        Scene first = new Scene(helper, "a_10a", 320, 96);
        Scene second = new Scene(helper, "a_10a", 160, 96);
        first.bindDriver();
        second.bindDriver();
        CatapultFixture fixture = new CatapultFixture(first, null);
        second.vehicle.moveTo(first.start.add(-60, 0, 48));
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(fixture.depart(first), "first aircraft receives the free runway");
            helper.assertFalse(fixture.depart(second), "busy airport cannot assign the occupied departure surface");
        });
        helper.runAfterDelay(60, () -> {
            helper.assertTrue(VehicleController.of(second.vehicle).idle(),
                "unassigned aircraft stays parked");
            helper.assertTrue(fixture.shuttle.getTowingEntity() != second.vehicle,
                "waiting aircraft cannot borrow the active shuttle");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1200)
    public static void fixedWingMinimumTakeoff(GameTestHelper helper) {
        minimumTakeoff(helper, false);
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1200)
    public static void fixedWingMinimumCatapultTakeoff(GameTestHelper helper) {
        minimumTakeoff(helper, true);
    }

    private static void minimumTakeoff(GameTestHelper helper, boolean boosted) {
        Scene f = new Scene(helper, "a_10a", 320, 96);
        f.bindDriver();
        CatapultFixture fixture = boosted ? new CatapultFixture(f, null) : null;
        double[] length = {0};
        boolean[] airborne = {false};
        boolean[] attached = {false};
        helper.runAfterDelay(20, () -> {
            var needs = DirectVehicleMotion.runway(f.vehicle).orElseThrow();
            length[0] = boosted ? needs.takeoff(fixture.site()) : needs.takeoff();
            f.vehicle.moveTo(f.start);
            f.vehicle.setDeltaMovement(Vec3.ZERO);
            f.vehicle.setYRot(-90);
            f.vehicle.setXRot(0);
            trimRunway(f, (int) length[0]);
            if (boosted) {
                fixture.length = length[0];
                helper.assertTrue(fixture.depart(f), "minimum catapult runway accepts departure");
            } else {
                var site = registerRunway(f, length[0]);
                helper.assertTrue(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.TAKE_OFF,
                    List.of(), -1, Optional.empty(), Optional.of(site.airport().id()))).accepted(),
                    "minimum runway accepted");
            }
            f.log("MINIMUM_TAKEOFF length=" + length[0] + " catapult=" + boosted);
        });
        helper.onEachTick(() -> {
            if (length[0] == 0) {
                return;
            }
            attached[0] |= boosted && fixture.shuttle.getTowingEntity() == f.vehicle;
            double along = f.vehicle.getX() - f.start.x;
            if (f.tick() % 20 == 0) {
                f.log("minimum-takeoff along=" + along + " length=" + length[0]);
            }
            if (!airborne[0] && f.height() > .01 && !f.vehicle.onGround()) {
                helper.assertTrue(along <= length[0], "liftoff occurs within the runway: " + along);
                airborne[0] = true;
                f.log("MINIMUM_LIFTOFF along=" + along + " length=" + length[0]);
            }
            helper.assertTrue(airborne[0] || along <= length[0], "ground roll cannot cross runway end");
            if (airborne[0] && f.height() > 8) {
                helper.assertTrue(!boosted || attached[0], "catapult departure uses the native tow link");
                helper.assertFalse(f.vehicle.isWreck(), "minimum runway departure preserves hull");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 1800)
    public static void fixedWingMinimumLanding(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 320, 96);
        f.bindDriver();
        for (String type : List.of("j_16", "ju_87", "ac_130h")) {
            Scene model = new Scene(helper, type, 96, 64);
            helper.runAfterDelay(20, () -> model.log("RUNWAY_REQUIREMENTS "
                + DirectVehicleMotion.runway(model.vehicle).orElseThrow()));
        }
        double[] length = {0};
        int[] stopped = {0};
        float[] previousPitch = {Float.NaN};
        double[] previousHeight = {Double.POSITIVE_INFINITY};
        helper.runAfterDelay(20, () -> {
            var needs = DirectVehicleMotion.runway(f.vehicle).orElseThrow();
            f.log("RUNWAY_REQUIREMENTS " + needs);
            length[0] = needs.landing();
            trimRunway(f, (int) length[0]);
            var site = registerRunway(f, length[0]);
            var engine = (EngineInfo.Aircraft) f.vehicle.getEngineInfo();
            double minimum = DirectVehicleMotion.minimumSpeed(engine, f.vehicle.computed().getGravity());
            double radius = Math.max(f.vehicle.getBbWidth(), minimum / Math.toRadians(engine.getYawSpeed()));
            double clearance = f.vehicle.getBbHeight() + minimum * minimum / f.vehicle.computed().getGravity();
            for (int x = -(int) Math.ceil(2 * radius / 16) - 2; x <= 0; x++) {
                ChunkPos chunk = new ChunkPos(BlockPos.containing(f.start).offset(x * 16, 0, 0));
                f.level.getChunk(chunk.x, chunk.z);
                f.level.getChunkSource().addRegionTicket(WarfareVehicleGameTests.TICKING, chunk, 2, f.ticket, true);
                f.chunks.add(chunk);
            }
            f.vehicle.moveTo(f.start.add(-2 * radius, clearance, 0));
            f.vehicle.setYRot(-90);
            f.vehicle.setDeltaMovement(minimum, 0, 0);
            helper.assertTrue(VehicleController.of(f.vehicle).order(new Vehicles.Order(Vehicles.Action.LAND,
                List.of(), -1, Optional.empty(), Optional.of(site.airport().id()))).accepted(),
                "minimum landing runway accepted");
        });
        helper.onEachTick(() -> {
            if (length[0] == 0) {
                return;
            }
            if (previousHeight[0] <= 3 && f.height() <= 3 && !Float.isNaN(previousPitch[0])) {
                helper.assertTrue(Math.abs(Mth.wrapDegrees(f.vehicle.getXRot() - previousPitch[0])) <= .501,
                    "flare and touchdown pitch change is bounded per tick");
                helper.assertTrue(f.vehicle.getDeltaMovement().y >= -.121, "flare limits the sink rate");
            }
            previousPitch[0] = f.vehicle.getXRot();
            previousHeight[0] = f.height();
            double along = f.vehicle.getX() - f.start.x;
            helper.assertTrue(along <= length[0], "landing and braking stay before runway end: " + along);
            if (VehicleController.of(f.vehicle).action().equals("folkways.rts.unit.state.landed")
                && f.vehicle.getDeltaMovement().length() < .05) {
                stopped[0]++;
                if (stopped[0] >= 100) {
                    helper.assertTrue(along >= 0 && f.height() < 1, "stops on pavement after approach");
                    helper.assertFalse(f.vehicle.isWreck(), "minimum landing preserves hull");
                    f.log("MINIMUM_LANDING stopped along=" + along + " length=" + length[0]);
                    helper.succeed();
                }
            } else {
                stopped[0] = 0;
            }
            if (f.tick() % 200 == 0) {
                f.log("minimum-landing " + VehicleController.of(f.vehicle).action());
            }
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void turnedRunwayOccupancyFollowsTheStrip(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 160, 96);
        // A runway turned 45 degrees: its world bounding box takes in a wide band beside the strip itself.
        var site = new LandingSites.Site("turned-" + f.vehicle.getUUID(), "Turned runway",
            LandingSites.Kind.RUNWAY_END, WorldSpaces.at(f.level, BlockPos.containing(f.start.add(-40, 0, 0))), -45,
            100, 15);
        var pose = site.pose(f.level).orElseThrow();
        Vec3 across = pose.forward().cross(pose.up()).normalize();
        var other = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("superbwarfare:lav_150"))
            .create(f.level);
        Cleanup.on(helper, other::discard);
        other.moveTo(pose.position().add(pose.forward().scale(50)).add(across.scale(24)));
        f.level.addFreshEntity(other);
        helper.runAfterDelay(2, () -> {
            helper.assertFalse(VehicleAirports.occupied(f.vehicle, site),
                "a vehicle parked beside a turned runway, inside its world box, leaves the strip free");
            other.moveTo(pose.position().add(pose.forward().scale(50)));
            helper.assertTrue(VehicleAirports.occupied(f.vehicle, site), "a vehicle on the turned strip occupies it");
            other.moveTo(pose.position().add(pose.forward().scale(130)));
            helper.assertFalse(VehicleAirports.occupied(f.vehicle, site), "a vehicle past the far end leaves it free");
            helper.succeed();
        });
    }

    private static LandingSites.Site registerRunway(Scene f, double length) {
        var site = new LandingSites.Site("minimum-" + f.vehicle.getUUID(), "Minimum runway",
            LandingSites.Kind.RUNWAY_END, WorldSpaces.at(f.level, BlockPos.containing(f.start)), -90, length, 64);
        AtomicBoolean available = new AtomicBoolean(true);
        LandingSites.register(level -> available.get() && level == f.level ? List.of(site) : List.of());
        Cleanup.on(f.helper, () -> available.set(false));
        return site;
    }

    private static void trimRunway(Scene f, int length) {
        BlockPos threshold = BlockPos.containing(f.start).below();
        for (int x = -12; x < 308; x++) {
            if (x >= 0 && x < length) {
                continue;
            }
            for (int z = -48; z < 48; z++) {
                f.level.setBlock(threshold.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
            }
        }
    }

    private static final class CatapultFixture {
        private final Scene scene;
        private final LandingSites.Site geometry;
        private final Platform platform;
        private CatapultShuttleEntity shuttle;
        private double length;

        CatapultFixture(Scene scene, Platform platform) {
            this.platform = platform;
            this.scene = scene;
            var level = scene.level;
            BlockPos threshold = platform == null ? BlockPos.containing(scene.start) : platform.center.above();
            length = platform == null ? 256 : platform.runway.length();
            geometry = new LandingSites.Site("catapult-runway-" + scene.vehicle.getUUID(), "Catapult runway",
                LandingSites.Kind.RUNWAY_END, WorldSpaces.at(level, threshold), -90, length, 64, List.of(),
                platform == null
                    ? new LandingSites.Airport("catapult-airport-" + scene.vehicle.getUUID(), "Catapult airport",
                        Optional.empty()) : platform.runway.airport());
            BlockPos rail = threshold.below().east((int) Math.ceil(VehicleCatapults.slack(scene.vehicle)) + 3);
            BlockPos controller = rail.west();
            level.setBlockAndUpdate(controller, ModBlocks.CATAPULT_CONTROLLER.get().defaultBlockState()
                .setValue(CatapultControllerBlock.FACING, Direction.EAST)
                .setValue(CatapultControllerBlock.LAUNCH_POWER, 15));
            for (int x = 0; x < 12; x++) {
                level.setBlockAndUpdate(rail.east(x), ModBlocks.AIRCRAFT_CATAPULT.get().defaultBlockState()
                    .setValue(AircraftCatapultBlock.FACING, Direction.EAST));
            }
            level.setBlockAndUpdate(controller.south(), Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACING, Direction.SOUTH)
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                    net.minecraft.world.level.block.state.properties.AttachFace.WALL));
            shuttle = new CatapultShuttleEntity(level);
            Vec3 position = Vec3.atBottomCenterOf(rail);
            if (platform != null) {
                position = WorldSpaces.world(level, WorldSpaces.at(level, rail)).orElseThrow();
            }
            shuttle.moveTo(position);
            shuttle.setYRot(-90);
            level.addFreshEntity(shuttle);
            AtomicBoolean available = new AtomicBoolean(true);
            LandingSites.register(target -> available.get() && target == level ? List.of(new LandingSites.Site(
                geometry.id(), geometry.name(), geometry.kind(), geometry.position(), geometry.yaw(), length,
                geometry.width(), List.of(), geometry.airport())) : List.of());
            Cleanup.on(scene.helper, () -> {
                available.set(false);
                shuttle.discard();
            });
        }

        LandingSites.Site site() {
            return LandingSites.in(scene.level).stream().filter(site -> site.id().equals(geometry.id()))
                .findFirst().orElseThrow();
        }

        /** The card's departure: a Loiter order to a parked fixed wing, which departs through its airport first. */
        boolean loiter(Scene plane, Vec3 over) {
            var result = WarfareVehicles.INSTANCE.order(plane.vehicle, new Vehicles.Order(Vehicles.Action.LOITER,
                List.of(WorldSpaces.at(plane.level, BlockPos.containing(over))), -1, Optional.empty(),
                Optional.empty()));
            plane.log("loiter-order=" + result + " ground=" + plane.vehicle.onGround());
            return result.accepted();
        }

        boolean depart(Scene plane) {
            // This fixture exercises assistance: an ordinary takeoff roll must not fit.
            length = Math.min(length, Math.ceil(DirectVehicleMotion.runway(scene.vehicle).orElseThrow()
                .takeoff(site())));
            if (platform != null) {
                var runway = platform.runway;
                platform.runway = new LandingSites.Site(runway.id(), runway.name(), runway.kind(), runway.position(),
                    runway.yaw(), length, runway.width(), List.of(), runway.airport());
            }
            var result = WarfareVehicles.INSTANCE.order(plane.vehicle, new Vehicles.Order(Vehicles.Action.TAKE_OFF,
                List.of(), -1, Optional.empty(), Optional.empty()));
            plane.log("catapult-order=" + result + " owner=" + VehicleSeats.owner(plane.vehicle)
                + " holds=" + VehicleSeats.holds(plane.vehicle, plane.body)
                + " pose=" + site().pose(plane.level).isPresent());
            return result.accepted();
        }
    }

    private static final class Platform {
        private final Scene scene;
        private final ServerSubLevel ship;
        private final BlockPos tower;
        private final BlockPos center;

        private LandingSites.Site runway;

        Platform(Scene scene, boolean moving) {
            this(scene, moving, false);
        }

        Platform(Scene scene, boolean moving, boolean fixedWing) {
            this.scene = scene;
            var level = scene.level;
            BlockPos base = BlockPos.containing(scene.start.add(48, 3, 0));
            List<BlockPos> cells = new ArrayList<>();
            int radius = 7;
            for (int z = -radius; z <= radius; z++) {
                for (int x = -radius; x <= radius; x++) {
                    boolean edge = Math.abs(x) == radius || Math.abs(z) == radius;
                    boolean marking = Math.abs(x) <= 3 && Math.abs(z) <= 5
                        && (Math.abs(x) == 3 || z == 0);
                    var block = edge ? Blocks.WHITE_CONCRETE
                        : marking ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE;
                    BlockPos pos = base.offset(x, 0, z);
                    level.setBlockAndUpdate(pos, block.defaultBlockState());
                    cells.add(pos);
                }
            }
            for (int z = -12; z < -radius; z++) {
                BlockPos pos = base.offset(0, 0, z);
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                cells.add(pos);
            }
            for (int y = 1; y < 5; y++) {
                BlockPos pos = base.offset(0, y, -12);
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
                cells.add(pos);
            }
            BlockPos sourceTower = base.offset(0, 5, -12);
            level.setBlockAndUpdate(sourceTower, BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("folkways_airline:airport_tower")).defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
            cells.add(sourceTower);
            if (fixedWing && scene.vehicle.getEngineInfo() == null) {
                scene.vehicle.travel();
            }
            var needs = fixedWing ? DirectVehicleMotion.runway(scene.vehicle).orElseThrow() : null;
            int runwayLength = fixedWing ? (int) needs.landing() + 32 : 0;
            if (fixedWing) {
                for (int x = -16; x < runwayLength; x++) {
                    for (int z = -(int) needs.width(); z <= (int) needs.width(); z++) {
                        BlockPos pos = base.offset(x, 0, z);
                        level.setBlock(pos, Blocks.STONE.defaultBlockState(), 2);
                        cells.add(pos);
                    }
                }
            }
            List<BlockPos> unique = cells.stream().distinct().toList();
            ship = SubLevelAssemblyHelper.assembleBlocks(level, base, unique, BoundingBox3i.from(unique));
            center = ship.getPlot().getCenterBlock();
            tower = center.offset(0, 5, -12);
            // Rapier uses float world positions: reserve near-origin sites for sub-block deck motion.
            int site = platformSite++ * 1024;
            for (int x = fixedWing ? -24 : -8; x <= Math.max(8, runwayLength / 16 + 4); x++) {
                for (int z = -8; z <= 8; z++) {
                    ChunkPos chunk = new ChunkPos((site >> 4) + x, (site >> 4) + z);
                    level.getChunk(chunk.x, chunk.z);
                    level.getChunkSource().addRegionTicket(WarfareVehicleGameTests.TICKING,
                        chunk, 2, scene.ticket, true);
                    scene.chunks.add(chunk);
                }
            }
            Vec3 oldPad = ship.logicalPose().transformPosition(Vec3.atBottomCenterOf(center.above()));
            Vector3d position = new Vector3d(ship.logicalPose().position()).add(site - oldPad.x, 100 - oldPad.y,
                site - oldPad.z);
            RigidBodyHandle.of(ship).teleport(position, new Quaterniond(ship.logicalPose().orientation()));
            SubLevelPhysicsSystem.require(level).updatePose(ship);
            scene.vehicle.moveTo(pad().add(-20, 10, 0));
            scene.body.moveTo(scene.vehicle.position());
            var anchor = moving ? null : anchor(ship, center);
            double height = ship.logicalPose().position().y();
            scene.helper.onEachTick(() -> {
                if (moving && RigidBodyHandle.of(ship).isValid()) {
                    var body = RigidBodyHandle.of(ship);
                    double gravity = DimensionPhysicsData.getGravity(level, ship.logicalPose().position()).y();
                    // A test tug holds altitude and supplies constant body velocity without moving the helicopter.
                    Vector3d target = new Vector3d(1,
                        (height - ship.logicalPose().position().y()) * 20 - gravity / 40, 0);
                    body.addLinearAndAngularVelocity(target.sub(body.getLinearVelocity()),
                        new Vector3d(body.getAngularVelocity()).negate());
                }
            });
            if (fixedWing) {
                runway = new LandingSites.Site("moving-runway-" + ship.getUniqueId(), "Moving runway",
                    LandingSites.Kind.RUNWAY_END, WorldSpaces.at(level, center.above()), -90,
                    runwayLength, 2 * (int) needs.width() + 1);
                AtomicBoolean available = new AtomicBoolean(true);
                LandingSites.register(target -> available.get() && target == level ? List.of(runway) : List.of());
                Cleanup.on(scene.helper, () -> available.set(false));
            } else {
                Airports.register(level, tower);
                Airports.rename(level, tower, "vehicle-platform-" + ship.getUniqueId());
                Airports.request(level, tower);
            }
            Cleanup.on(scene.helper, () -> {
                Airports.remove(level, tower);
                if (anchor != null) {
                    anchor.remove();
                }
                if (SubLevelContainer.getContainer(level).getSubLevel(ship.getUniqueId()) == ship) {
                    SubLevelContainer.getContainer(level).removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
                }
            });
        }

        Vec3 pad() {
            return ship.logicalPose().transformPosition(Vec3.atBottomCenterOf(center.above()));
        }

        Optional<LandingSites.Site> find() {
            if (runway != null) {
                return Optional.of(runway);
            }
            return LandingSites.in(scene.level).stream().filter(site -> site.position().realm()
                .equals(new Realm.Frame(ship.getUniqueId()))).findFirst();
        }

        LandingSites.Site site() {
            scene.helper.assertTrue(find().isPresent(), "scanned Sable pad is present: "
                + Airports.at(scene.level, tower).map(value -> value.flaws().toString()));
            return find().orElseThrow();
        }
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 600)
    public static void authorizedGunnerEmitsAndHitsTarget(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 160, 96);
        f.bindDriver();
        Zombie target = f.target();
        AtomicInteger shots = f.shots();
        helper.runAfterDelay(20, () -> f.ammo());
        helper.runAfterDelay(40, () -> f.attack(target));
        helper.succeedWhen(() -> {
            if (f.tick() % 100 == 0) {
                f.log("authorized-fire shots=" + shots.get() + " health=" + target.getHealth()
                    + " gate=" + VehicleGuns.of(f.vehicle).diagnostic());
            }
            helper.assertTrue(shots.get() > 0, "official mobile lease permits native emissions");
            helper.assertTrue(target.getHealth() < 1000, "native projectile hits designated target");
            helper.assertTrue(f.body.getTarget() == null, "no native Mob auto fire");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 700)
    public static void vehicleDisabledAndOrderedRules(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 160, 96);
        f.bindDriver();
        Zombie target = f.target();
        AtomicInteger shots = f.shots();
        String key = "0/" + f.vehicle.getGunName(0);
        f.rule(key, WeaponRules.Mode.DISABLED);
        helper.runAfterDelay(20, () -> f.ammo());
        helper.runAfterDelay(40, () -> f.attack(target));
        helper.runAfterDelay(140, () -> {
            helper.assertValueEqual(shots.get(), 0, "DISABLED blocks explicit attacks");
            f.rule(key, WeaponRules.Mode.ORDERED);
            f.order(Vehicles.Action.STOP, null);
        });
        helper.runAfterDelay(240, () -> {
            helper.assertValueEqual(shots.get(), 0, "ORDERED blocks unsolicited fire");
            f.attack(target);
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(f.tick() > 240 && shots.get() > 0, "ORDERED permits designated target");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void squadWeaponRulesAffectSelection(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.body.stopRiding();
        var group = UnitGroups.of(f.colony).stopSelection(List.of(f.body));
        TestWorker.of(f.body).pack().setItem(0, new ItemStack(Items.IRON_SWORD));
        String category = WeaponRules.category(new ItemStack(Items.IRON_SWORD));
        group.weaponRule(category, new WeaponRules.Rule(WeaponRules.Mode.DISABLED, 0, false));
        var profiles = LoadoutProfiles.of(f.colony);
        var profile = profiles.create("Vehicle squad rules test");
        profiles.replace(new LoadoutProfile(profile.id(), profile.name(),
            List.of(LoadoutProfile.Entry.weapon(
                ItemSpec.of(Items.IRON_SWORD)))));
        profiles.assign(List.of(f.body.id()), Optional.of(profile.id()));
        var worker = TestWorker.of(f.body);
        Zombie target = f.target();
        helper.assertTrue(Weapons.select(worker, target, null) == null, "DISABLED removes squad weapon selection");
        group.weaponRule(category, new WeaponRules.Rule(WeaponRules.Mode.ORDERED, 0, false));
        helper.assertTrue(Weapons.select(worker, target, null) == null, "ORDERED removes uncommanded selection");
        group.weaponRule(category, WeaponRules.DEFAULT);
        var weapon = Weapons.select(worker, target, null);
        helper.assertTrue(weapon != null, "FREE restores usable squad weapon selection");
        weapon.stop();
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 300)
    public static void vehicleCrewAndOwnershipRestoreAfterReload(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        f.rule("0/" + f.vehicle.getGunName(0), WeaponRules.Mode.ORDERED);
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(f.vehicle.save(saved), "native save includes recursive passengers");
        UUID bodyId = f.body.getUUID();
        f.body.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        f.vehicle.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        helper.runAfterDelay(5, () -> {
            VehicleEntity restored = (VehicleEntity) EntityType.loadEntityRecursive(saved, f.level, entity -> entity);
            f.level.addFreshEntityWithPassengers(restored);
            Cleanup.on(helper, () -> {
                restored.getPassengers().forEach(Entity::discard);
                restored.discard();
            });
            helper.runAfterDelay(20, () -> {
                Entity passenger = f.level.getEntity(bodyId);
                helper.assertTrue(passenger != null && passenger.getVehicle() == restored, "native passenger restores");
                helper.assertTrue(VehicleSeats.owner(restored).filter(f.colony.id()::equals).isPresent(),
                    "colony ownership restores");
                helper.assertTrue(passenger instanceof ResidentEntity resident && VehicleSeats.holds(restored, resident)
                    && Commands.commandable(resident), "stable seat lease restores and remains commandable");
                helper.assertFalse(WeaponRules.read(restored.getPersistentData()
                    .getCompound(WeaponRules.TAG)).isEmpty(),
                    "weapon rules persist with the hull");
                helper.succeed();
            });
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void vehicleCrewVocationExcludesDisallowed(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.body.stopRiding();
        var trade = f.body.licences().trade(Vocations.required(WarfareVehicles.CREW)).orElseThrow();
        f.body.licences().allow(trade, false);
        helper.assertFalse(WarfareVehicles.INSTANCE.boardAvailable(f.vehicle, f.colony, List.of()).accepted(),
            "disallowed resident is not recruited");
        helper.assertTrue(WarfareVehicles.INSTANCE.seats(f.vehicle).stream().allMatch(seat -> seat.occupant().isEmpty()),
            "no seat reservation for disallowed vocation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void vehicleAutoBoardOnlyProfileSeats(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.body.stopRiding();
        for (int n = 0; n < 5; n++) {
            f.person(f.start.add(4 + n, 0, 8));
        }
        VehicleProfile profile = WarfareVehicles.profile(f.vehicle, f.colony);
        helper.assertTrue(profile.seats().equals(Set.of(0)), "default requires driver and no passenger seats");
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(WarfareVehicles.INSTANCE.boardAvailable(f.vehicle, f.colony, List.of()).accepted(),
                "auto boarding accepted");
            helper.assertValueEqual((int) WarfareVehicles.INSTANCE.seats(f.vehicle).stream()
                .filter(seat -> seat.occupant().isPresent()).count(), 1, "only required driver assigned");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 700)
    public static void vehicleIndependentGunnersFireDifferentTargets(GameTestHelper helper) {
        Scene f = new Scene(helper, "bmp_2", 160, 128);
        f.bindDriver();
        ResidentEntity left = f.person(f.start.add(0, 0, 8));
        ResidentEntity right = f.person(f.start.add(0, 0, 10));
        for (int seat = 1; seat <= 2; seat++) {
            ResidentEntity body = seat == 1 ? left : right;
            helper.assertTrue(WarfareVehicles.INSTANCE.board(f.vehicle, body, seat).accepted(), "assign shooting port");
            helper.assertTrue(body.startRiding(f.vehicle), "mount shooting port");
            helper.assertTrue(f.vehicle.getSeatIndex(body) == seat || f.vehicle.changeSeat(body, seat), "native port");
        }
        Zombie first = f.target();
        Zombie second = f.target();
        first.moveTo(f.start.add(0, 2, 45));
        second.moveTo(f.start.add(0, 2, -45));
        Map<UUID, Integer> hits = new HashMap<>();
        Consumer<ShootEvent.Post> listener = event -> {
            if (event.getShooter() == left || event.getShooter() == right) {
                hits.merge(event.getShooter().getUUID(), 1, Integer::sum);
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        Cleanup.on(helper, () -> NeoForge.EVENT_BUS.unregister(listener));
        f.rule("0/" + f.vehicle.getGunName(0), WeaponRules.Mode.DISABLED);
        helper.runAfterDelay(20, () -> {
            for (int seat = 1; seat <= 2; seat++) {
                var data = f.vehicle.getGunData(f.vehicle.getGunName(seat));
                f.vehicle.setItem(seat - 1, data.selectedAmmoConsumer().stack().copyWithCount(64));
            }
        });
        helper.succeedWhen(() -> {
            if (f.tick() % 100 == 0) {
                f.log("ports=" + hits + " targets=" + first.getHealth() + "/" + second.getHealth()
                    + " gate=" + VehicleGuns.of(f.vehicle).diagnostic());
            }
            helper.assertTrue(hits.getOrDefault(left.getUUID(), 0) > 0
                && hits.getOrDefault(right.getUUID(), 0) > 0, "both independent seats emit");
            helper.assertTrue(first.getHealth() < 1000 && second.getHealth() < 1000,
                "independent seats hit two different free-fire targets");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 600)
    public static void vehicleTransportRetainsSquad(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        ResidentEntity first = f.person(f.start.add(0, 0, 8));
        ResidentEntity second = f.person(f.start.add(0, 0, 10));
        var group = UnitGroups.of(f.colony).stopSelection(List.of(first, second));
        UUID id = group.id();
        helper.assertTrue(WarfareVehicles.INSTANCE.boardAvailable(f.vehicle, f.colony, List.of(first, second)).accepted(),
            "whole squad assigned passenger seats");
        boolean[] left = {false};
        helper.onEachTick(() -> {
            if (!left[0] && first.getVehicle() == f.vehicle && second.getVehicle() == f.vehicle) {
                helper.assertTrue(WarfareVehicles.INSTANCE.leave(f.vehicle, -2).accepted(), "passengers disembark");
                left[0] = true;
            }
        });
        helper.succeedWhen(() -> {
            if (f.tick() % 100 == 0) {
                f.log("transport first=" + first.position().subtract(f.start) + " second="
                    + second.position().subtract(f.start) + " aboard=" + first.isPassenger() + "/"
                    + second.isPassenger() + " balk=" + first.balk() + "/" + second.balk()
                    + " route=" + f.labor.route(first).stream().map(node -> node.spec().toString()).toList());
            }
            helper.assertTrue(left[0] && first.getVehicle() == null && second.getVehicle() == null, "both disembarked");
            helper.assertTrue(UnitGroups.of(f.colony).ofResident(first.id()).orElseThrow().id().equals(id)
                && UnitGroups.of(f.colony).ofResident(second.id()).orElseThrow().id().equals(id),
                "original squad identity and members survive transport");
            helper.assertTrue(f.body.getVehicle() == f.vehicle, "driver remains aboard");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 900)
    public static void vehicleSupplyReachesStockTarget(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        ItemStack ammo = f.vehicle.getGunData(f.vehicle.getGunName(0)).selectedAmmoConsumer().stack();
        VehicleProfile base = WarfareVehicles.profile(f.vehicle, f.colony);
        var profiles = new VehicleProfiles(f.colony, f.level.registryAccess());
        profiles.replace(new VehicleProfile(base.id(), base.name(), base.type(), base.seats(), Optional.empty(),
            List.of(new VehicleProfile.Stock(ItemSpec.exact(ammo), 12, 4)), 0, base.rules()));
        BlockPos chest = BlockPos.containing(f.start.add(8, 0, 10));
        f.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((Container) f.level.getBlockEntity(chest)).setItem(0, ammo.copyWithCount(12));
        var owner = WarfareVehicles.ID;
        f.colony.hold(owner, new Claimed.Area(Set.of(WorldPos.of(f.level, BlockPos.containing(f.start.add(-4, 0, -4))),
            WorldPos.of(f.level, chest)))).orElseThrow();
        f.colony.stores(owner, f.level.dimension(), List.of(WorldPos.of(f.level, chest)));
        // ColonyWorks.pulse runs the registered supply service even in GameTestServer.
        // A second service would publish competing receipts for the same stock deficit.
        // Let supply planning see only the mounted driver before admitting an available hauler.
        // A seat duty must reserve its resident rather than strand an ordinary fetch route on them.
        helper.onEachTick(() -> helper.assertTrue(f.labor.route(f.body).stream()
            .noneMatch(node -> node.spec().owner().equals(owner) && !node.spec().needs().isEmpty()),
            "mounted driver never reserves the supply receipt"));
        helper.runAfterDelay(100, () -> {
            ResidentEntity hauler = f.person(f.start.add(5, 0, 8));
            helper.onEachTick(() -> {
                if (f.tick() > 0 && f.tick() % 100 == 0) {
                    f.log("supply hauler=" + hauler.position().subtract(f.start) + " riding=" + hauler.isPassenger()
                        + " doing=" + hauler.doing() + " balk=" + hauler.balk() + " diagnosis=" + f.labor.diagnosis()
                        + " route=" + f.labor.route(hauler).stream().map(Node::id).toList()
                        + " chest=" + ((Container) f.level.getBlockEntity(chest)).getItem(0));
                }
                helper.assertTrue(f.labor.route(hauler).stream()
                    .filter(node -> node.spec().owner().equals(owner) && !node.spec().needs().isEmpty()).count() <= 1,
                    "one supply service publishes one receipt for the stock deficit");
                helper.assertTrue(hauler.getPersistentData().getCompound(VehicleSeats.SEAT).isEmpty(),
                    "the supply hauler has no boarding duty");
                helper.assertFalse(hauler.isPassenger(), "the supply hauler works on foot");
                helper.assertTrue(f.body.getVehicle() == f.vehicle, "the driver retains the mounted duty during supply");
            });
        });
        helper.succeedWhen(() -> {
            helper.assertValueEqual(VehicleSupply.count(f.vehicle, profiles.assigned(f.vehicle).orElseThrow().stock()
                .getFirst()), 12, "ordinary fetch labour delivers ammunition to target");
            helper.assertTrue(((Container) f.level.getBlockEntity(chest)).getItem(0).isEmpty(),
                "ammunition is physically removed from colony storage");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void vehicleProfileAndBindingReload(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        VehicleProfile base = WarfareVehicles.profile(f.vehicle, f.colony);
        var profiles = new VehicleProfiles(f.colony, f.level.registryAccess());
        VehicleProfile edited = new VehicleProfile(UUID.randomUUID(), "Reloaded stock", base.type(), Set.of(0, 2),
            Optional.empty(), List.of(new VehicleProfile.Stock(ItemSpec.of(Items.ARROW), 24, 6)), 1000,
            Map.of("0/" + f.vehicle.getGunName(0), new WeaponRules.Rule(WeaponRules.Mode.ORDERED, 100, false)));
        profiles.replace(edited);
        profiles.bind(f.vehicle, edited.id(), edited.type());
        CompoundTag saved = new CompoundTag();
        helper.assertTrue(f.vehicle.save(saved), "native hull save");
        VehicleEntity restored = (VehicleEntity) EntityType.loadEntityRecursive(saved, f.level, entity -> entity);
        var reloaded = new VehicleProfiles(f.colony, f.level.registryAccess());
        helper.assertTrue(reloaded.get(edited.id()).orElseThrow().equals(edited), "all profile fields reload");
        helper.assertTrue(reloaded.assigned(restored).orElseThrow().equals(edited), "native NBT restores profile binding");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 450)
    public static void idleGroundVehicleReleasesCrewOnlyWhenEnabled(GameTestHelper helper) {
        Scene enabled = new Scene(helper, "lav_150", 96, 64);
        Scene disabled = new Scene(helper, "lav_150", 96, 64);
        enabled.bindDriver();
        disabled.bindDriver();
        enabled.vehicle.getPersistentData().putBoolean(Vehicles.RELEASE_IDLE, true);
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(enabled.vehicle.onGround() && disabled.vehicle.onGround(), "vehicles grounded");
            enabled.order(Vehicles.Action.STOP, null);
            disabled.order(Vehicles.Action.STOP, null);
        });
        helper.runAfterDelay(200, () -> {
            helper.assertTrue(enabled.body.getVehicle() == enabled.vehicle, "crew stays before idle delay");
            enabled.order(Vehicles.Action.STOP, null);
        });
        helper.runAfterDelay(380, () -> helper.assertTrue(enabled.body.getVehicle() == enabled.vehicle,
            "accepted STOP resets idle delay"));
        helper.runAfterDelay(405, () -> {
            helper.assertTrue(enabled.vehicle.getPassengers().isEmpty(), "enabled vehicle releases crew");
            helper.assertTrue(VehicleSeats.assigned(enabled.vehicle, 0).isEmpty(), "seat assignment released");
            helper.assertTrue(disabled.body.getVehicle() == disabled.vehicle, "disabled vehicle keeps crew");
            helper.assertTrue(VehicleSeats.owner(enabled.vehicle).filter(enabled.colony.id()::equals).isPresent(),
                "idle release preserves colony ownership");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void bmpSnapshotAndAllVehicleWeaponsEncode(GameTestHelper helper) {
        Scene f = new Scene(helper, "bmp_2", 96, 64);
        f.bindDriver();
        helper.runAfterDelay(2, () -> {
            var player = commander(f);
            var snapshot = UnitOrders.snapshot(player, f.colony);
            var decoded = roundTrip(f, snapshot);
            var bmp = decoded.units().stream().filter(unit -> unit.id().equals(f.vehicle.getUUID())).findFirst()
                .orElseThrow();
            helper.assertTrue(bmp.weapons().containsKey("1/MG1"), "BMP missing-name shooting port encodes");
            helper.assertTrue(bmp.weapons().get("1/MG1").getString().contains("MG1"), "raw weapon fallback survives");
            int checked = 0;
            for (var resource : f.level.getServer().getResourceManager()
                .listResources("sbw/vehicles", path -> path.getPath().endsWith(".json")).keySet()) {
                String path = resource.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1, path.length() - 5);
                var type = BuiltInRegistries.ENTITY_TYPE.get(
                    ResourceLocation.fromNamespaceAndPath("superbwarfare", name));
                Entity entity = type.create(f.level);
                if (!(entity instanceof VehicleEntity vehicle)) {
                    continue;
                }
                try {
                    var provider = WarfareVehicles.INSTANCE;
                    var unit = new UnitSnapshot.Unit(vehicle.getUUID(),
                        UnitSnapshot.Kind.VEHICLE, vehicle.getDisplayName(),
                        WorldSpaces.at(vehicle), Optional.empty(), List.of(), provider.seats(vehicle),
                        provider.capabilities(vehicle), new Vehicles.Status(0, 0, 0, 0, Map.of(),
                            "folkways.rts.unit.state.stop"), provider.weapons(vehicle), Map.of(),
                        Optional.empty(), false, Vehicles.Altitude.DEFAULT);
                    roundTrip(f, new UnitSnapshot(f.colony.id(),
                        List.of(unit), List.of(), List.of(), List.of()));
                    checked++;
                } finally {
                    VehicleController.remove(vehicle);
                    vehicle.discard();
                }
            }
            helper.assertTrue(checked >= 43, "all bundled vehicle JSON definitions encode; checked=" + checked);
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void vehicleSnapshotIncludesRouteAndSeatTargets(GameTestHelper helper) {
        Scene f = new Scene(helper, "bmp_2", 96, 64);
        f.bindDriver();
        Zombie target = f.target();
        WorldPos destination = WorldSpaces.at(f.level, BlockPos.containing(f.start.add(30, 0, 0)));
        helper.assertTrue(WarfareVehicles.INSTANCE.order(f.vehicle, new Vehicles.Order(Vehicles.Action.ATTACK_MOVE,
            List.of(destination), -1, Optional.of(target.getUUID()), Optional.empty())).accepted(), "order accepted");
        helper.runAfterDelay(2, () -> {
            VehicleController.of(f.vehicle).tick();
            var decoded = roundTrip(f, UnitOrders.snapshot(
                commander(f), f.colony));
            var status = decoded.units().stream().filter(unit -> unit.id().equals(f.vehicle.getUUID())).findFirst()
                .orElseThrow().status();
            helper.assertTrue(status.route().points().equals(List.of(destination)) && status.route().current() == 0,
                "destination and accepted waypoint list survive encoding");
            helper.assertTrue(status.target().filter(value -> value.entityId() == target.getId()
                && value.name().getString().equals(target.getDisplayName().getString())).isPresent(),
                "ordered entity id and localized name survive encoding: " + status.target()
                    + " expected=" + target.getId() + "/" + target.getDisplayName().getString());
            helper.assertTrue(status.firing().stream().anyMatch(seat -> seat.seat() == 0
                && seat.target().filter(value -> value.id().equals(target.getUUID())).isPresent()),
                "shooting position has its independent current target");
            helper.assertTrue(status.action().equals("folkways.rts.unit.state.attack_move"), "action is a translation key");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void duplicateSeatWeaponsKeepSeparateAmmunition(GameTestHelper helper) {
        Scene f = new Scene(helper, "bmp_2", 96, 64);
        helper.runAfterDelay(2, () -> {
            // Native data packs may share a gun name between seats. Exercise that shape without changing global data.
            var weapons = f.vehicle.getSeat(2).weapons();
            var original = List.copyOf(weapons);
            try {
                weapons.clear();
                weapons.add("MG1");
                var status = WarfareVehicles.INSTANCE.status(f.vehicle);
                helper.assertTrue(status.ammunition().containsKey("1/MG1")
                    && status.ammunition().containsKey("2/MG1"), "identical names retain both seat ammunition entries");
                var entries = new LinkedHashMap<>(status.ammunition());
                entries.put("1/MG1", 7);
                entries.put("2/MG1", 19);
                var unit = new UnitSnapshot.Unit(f.vehicle.getUUID(),
                    UnitSnapshot.Kind.VEHICLE, f.vehicle.getDisplayName(),
                    WorldSpaces.at(f.vehicle), Optional.empty(), List.of(), WarfareVehicles.INSTANCE.seats(f.vehicle),
                    WarfareVehicles.INSTANCE.capabilities(f.vehicle), new Vehicles.Status(status.health(),
                        status.maxHealth(), status.energy(), status.maxEnergy(), entries, status.action()),
                    WarfareVehicles.INSTANCE.weapons(f.vehicle), Map.of(), Optional.empty(), false,
                    Vehicles.Altitude.DEFAULT);
                var decoded = roundTrip(f, new UnitSnapshot(f.colony.id(),
                    List.of(unit), List.of(), List.of(), List.of())).units().getFirst().status().ammunition();
                helper.assertTrue(decoded.get("1/MG1") == 7 && decoded.get("2/MG1") == 19,
                    "different seat counts survive the network independently");
            } finally {
                weapons.clear();
                weapons.addAll(original);
            }
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 900)
    public static void vehicleServerGroupsAppendAndPatrol(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64);
        f.bindDriver();
        var player = commander(f);
        var ownership = CombatSelectionOwnership.of(f.colony);
        helper.assertTrue(ownership.select(player, List.of(f.vehicle.getUUID())), "server accepts vehicle selection");
        helper.assertTrue(ownership.selectedVehicle(player, f.vehicle), "vehicle claimed by player");
        helper.assertTrue(ownership.bindGroup(player, 0), "bind shortcut one");
        ownership.select(player, List.of());
        helper.assertTrue(ownership.recallGroup(player, 0), "recall vehicle shortcut");
        helper.assertTrue(ownership.snapshot(player).selection().equals(List.of(f.vehicle.getUUID())),
            "authoritative shortcut restores vehicle");
        WorldPos first = WorldSpaces.at(f.level, BlockPos.containing(f.start.add(18, 0, 0)));
        WorldPos second = WorldSpaces.at(f.level, BlockPos.containing(f.start.add(36, 0, 0)));
        var stop = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.empty(), Optional.empty());
        BiFunction<UnitRequest.Operation,
            List<WorldPos>, UnitReply> send = (operation, points) ->
                UnitOrders.handle(player,
                    new UnitRequest(1, f.colony.id(), f.vehicle.getUUID(),
                        operation, List.of(), -1, 0, points.isEmpty() ? stop : new Vehicles.Order(Vehicles.Action.MOVE,
                            points, -1, Optional.empty(), Optional.empty()), "", WeaponRules.DEFAULT, Optional.empty()));
        helper.assertTrue(send.apply(UnitRequest.Operation.APPEND,
            List.of(first)).result().accepted(), "first waypoint receipt accepted");
        helper.assertTrue(send.apply(UnitRequest.Operation.APPEND,
            List.of(second)).result().accepted(), "append receipt accepted");
        helper.assertTrue(send.apply(UnitRequest.Operation.CYCLE,
            List.of()).result().accepted(), "close patrol receipt accepted");
        helper.onEachTick(() -> ownership.heartbeat(player));
        AtomicBoolean reachedSecond = new AtomicBoolean();
        helper.succeedWhen(() -> {
            var route = WarfareVehicles.INSTANCE.status(f.vehicle).route();
            helper.assertTrue(route.points().equals(List.of(first, second)) && route.cycleStart() == 0,
                "accepted route and loop entry persist");
            if (route.current() == 1) {
                reachedSecond.set(true);
            }
            helper.assertTrue(reachedSecond.get() && route.current() == 0,
                "native hull advances to second leg then loops to first");
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void destroyedAndAbsentVehiclesReleaseCrew(GameTestHelper helper) {
        Scene wreck = new Scene(helper, "lav_150", 96, 64, false);
        wreck.bindDriver();
        wreck.vehicle.setWreck(true);
        WarfareVehicles.tick(new EntityTickEvent.Pre(wreck.vehicle));
        helper.assertFalse(wreck.body.getPersistentData().contains(VehicleSeats.SEAT), "wreck releases crew work");
        helper.assertTrue(VehicleSeats.owner(wreck.vehicle).isEmpty(), "wreck loses ownership");
        Scene removed = new Scene(helper, "lav_150", 96, 64, false);
        removed.bindDriver();
        removed.vehicle.discard();
        helper.assertFalse(removed.body.getPersistentData().contains(VehicleSeats.SEAT), "discard releases crew");
        var loan = new CompoundTag();
        loan.putUUID("vehicle", UUID.randomUUID());
        loan.putInt("seat", 0);
        removed.body.getPersistentData().put(VehicleSeats.SEAT, loan);
        helper.assertFalse(WarfareVehicles.urges(TestWorker.of(removed.body)).isEmpty(),
            "brief unload retains a bounded reservation");
        loan.putLong("absentSince", removed.level.getGameTime() - 200);
        helper.assertTrue(WarfareVehicles.urges(TestWorker.of(removed.body)).isEmpty(),
            "persistent absence releases ordinary work");
        helper.assertFalse(removed.body.getPersistentData().contains(VehicleSeats.SEAT), "expired loan is removed");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void reassignmentCannotReleaseAnotherVehiclesLoan(GameTestHelper helper) {
        Scene first = new Scene(helper, "lav_150", 96, 64, false);
        Scene second = new Scene(helper, "lav_150", 96, 64, false);
        first.body.stopRiding();
        second.body.stopRiding();
        ResidentEntity resident = first.person(first.start.add(0, 0, 8));
        VehicleSeats.assign(first.vehicle, resident, 0);
        VehicleSeats.assign(second.vehicle, resident, 0);
        helper.assertTrue(VehicleSeats.assigned(first.vehicle, 0).isEmpty(), "old walking reservation is released");
        helper.assertTrue(VehicleSeats.matches(resident, second.vehicle, 0), "new boarding loan belongs to B");
        // A stale disembark request for A cannot erase B's boarding task.
        VehicleSeats.release(first.vehicle, 0);
        helper.assertTrue(VehicleSeats.matches(resident, second.vehicle, 0), "A cannot release B's loan");
        // On reload, A also discards a stale reservation whose resident no longer points back to it.
        var crew = VehicleSeats.crew(first.vehicle);
        crew.putUUID("seat0", resident.id());
        first.vehicle.getPersistentData().put(VehicleSeats.CREW, crew);
        WarfareVehicles.tick(new EntityTickEvent.Pre(first.vehicle));
        helper.assertTrue(VehicleSeats.assigned(first.vehicle, 0).isEmpty(), "loaded hull drops stale lease");
        helper.assertTrue(VehicleSeats.matches(resident, second.vehicle, 0), "cleanup preserves replacement");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void boardingPathSearchHasAFixedBudget(GameTestHelper helper) {
        Scene f = new Scene(helper, "m_1a_2", 96, 64, false);
        f.body.stopRiding();
        f.body.moveTo(f.start.add(0, 0, 12));
        var original = f.body.getNavigation();
        AtomicInteger searches = new AtomicInteger();
        var blocked = new net.minecraft.world.entity.ai.navigation.GroundPathNavigation(f.body, f.level) {
            @Override
            public net.minecraft.world.level.pathfinder.Path createPath(BlockPos pos, int accuracy) {
                searches.incrementAndGet();
                return null;
            }
        };
        var access = (io.github.izakyl.folkways.core.shell.mixin.MobAccessor) f.body;
        access.folkways$setNavigation(blocked);
        try {
            helper.assertTrue(VehicleSeatDuty.footing(f.vehicle, f.body).isPresent(), "real ground candidates exist");
            helper.assertTrue(searches.get() > 0 && searches.get() <= VehicleSeatDuty.PATH_LIMIT,
                "unreachable hull does not exhaust every candidate path");
        } finally {
            access.folkways$setNavigation(original);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands",
        timeoutTicks = 140)
    public static void failedBoardingTimesOutWithoutTeleporting(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64, false);
        f.body.stopRiding();
        f.body.setNoAi(true);
        VehicleSeats.assign(f.vehicle, f.body, 0);
        var duty = new VehicleSeatDuty(f.vehicle, TestWorker.of(f.body), 0, false);
        f.body.moveTo(f.start.add(20, 0, 0));
        helper.assertFalse(duty.holds(f.level, TestWorker.of(f.body)), "distant boarding gives up");
        helper.assertFalse(f.body.isPassenger(), "distant resident is never teleported aboard");
        helper.assertTrue(VehicleSeats.assigned(f.vehicle, 0).isEmpty(), "distant loan is released");
        BlockPos stance = VehicleSeatDuty.footings(f.vehicle).getFirst();
        f.body.moveTo(VehicleSeatDuty.world(f.vehicle, stance));
        f.body.setShiftKeyDown(true);
        VehicleSeats.assign(f.vehicle, f.body, 0);
        var retry = new VehicleSeatDuty(f.vehicle, TestWorker.of(f.body), 0, false);
        helper.assertTrue(retry.holds(f.level, TestWorker.of(f.body)), "native refusal briefly retries");
        helper.runAfterDelay(101, () -> {
            helper.assertFalse(retry.holds(f.level, TestWorker.of(f.body)), "repeated refusal expires");
            helper.assertTrue(VehicleSeats.assigned(f.vehicle, 0).isEmpty(), "expired mount releases work");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void playerTakingReservedSeatReleasesWalkingCrew(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64, false);
        f.body.stopRiding();
        ResidentEntity resident = f.person(f.start.add(0, 0, 8));
        VehicleSeats.assign(f.vehicle, resident, 0);
        // FakePlayer deliberately refuses startRiding; use the native mock player like the driver fixture.
        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.moveTo(f.start);
        Cleanup.on(helper, player::stopRiding);
        helper.assertTrue(player.startRiding(f.vehicle, true), "player occupies the native driver seat");
        helper.assertTrue(f.vehicle.getSeatIndex(player) == 0 || f.vehicle.changeSeat(player, 0), "player is driver");
        resident.moveTo(VehicleSeatDuty.world(f.vehicle, VehicleSeatDuty.footings(f.vehicle).getFirst()));
        var duty = new VehicleSeatDuty(f.vehicle, TestWorker.of(resident), 0, false);
        helper.assertFalse(duty.holds(f.level, TestWorker.of(resident)), "taken seat gives up immediately");
        helper.assertTrue(VehicleSeats.assigned(f.vehicle, 0).isEmpty(), "taken seat releases reservation");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void vehicleOwnershipSurvivesDismountAndCanBeGivenUp(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64, false);
        f.bindDriver();
        var player = commander(f);
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(WarfareVehicles.INSTANCE.leave(f.vehicle, -1).accepted(), "crew disembarks");
            helper.assertTrue(VehicleSeats.owner(f.vehicle).filter(f.colony.id()::equals).isPresent(),
                "empty vehicle keeps colony ownership");
            player.moveTo(f.start.add(2048, 0, 0));
            helper.assertTrue(UnitOrders.available(player, f.colony, f.vehicle), "owned vehicle works beyond tracking");
            helper.assertTrue(UnitOrders.snapshot(player, f.colony).units().stream()
                .anyMatch(unit -> unit.id().equals(f.vehicle.getUUID())), "owned remote vehicle appears in roster");
            f.vehicle.setLocked(true);
            helper.assertFalse(UnitOrders.available(player, f.colony, f.vehicle), "locked owned vehicle is refused");
            helper.assertTrue(UnitOrders.snapshot(player, f.colony).units().stream()
                .noneMatch(unit -> unit.id().equals(f.vehicle.getUUID())), "locked vehicle is absent from roster");
            f.vehicle.setLocked(false);
            var release = new UnitRequest(1, f.colony.id(), f.vehicle.getUUID(),
                UnitRequest.Operation.RELEASE_OWNERSHIP, List.of(), -1, -1, new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.empty(),
                    Optional.empty()), "", WeaponRules.DEFAULT, Optional.empty());
            helper.assertTrue(UnitOrders.handle(player, release).result().accepted(), "explicit give-up succeeds");
            helper.assertTrue(VehicleSeats.owner(f.vehicle).isEmpty(), "give-up clears owner");
            helper.assertFalse(UnitOrders.available(player, f.colony, f.vehicle), "unowned remote vehicle is refused");
            helper.assertTrue(UnitOrders.snapshot(player, f.colony).units().stream()
                .noneMatch(unit -> unit.id().equals(f.vehicle.getUUID())), "unowned remote positions are hidden");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void unownedVehicleRequiresActualEntityTracking(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64, false);
        f.body.stopRiding();
        var player = commander(f);
        helper.assertFalse(UnitOrders.available(player, f.colony, f.vehicle), "nearby but untracked hull is hidden");
        player.setLastSectionPos(net.minecraft.core.SectionPos.of(player));
        player.setChunkTrackingView(net.minecraft.server.level.ChunkTrackingView.of(player.chunkPosition(), 10));
        f.level.getChunkSource().chunkMap.move(player);
        helper.assertTrue(f.level.getChunkSource().chunkMap.getPlayersWatching(f.vehicle).contains(player),
            "native tracker has paired the hull with this player");
        helper.assertTrue(UnitOrders.available(player, f.colony, f.vehicle), "tracked unowned hull is claimable");
        helper.assertTrue(UnitOrders.snapshot(player, f.colony).units().stream()
            .anyMatch(unit -> unit.id().equals(f.vehicle.getUUID())), "tracked unowned hull is listed");
        f.vehicle.setLocked(true);
        helper.assertFalse(UnitOrders.available(player, f.colony, f.vehicle), "locked unowned hull cannot be claimed");
        helper.assertFalse(WarfareVehicles.INSTANCE.board(f.vehicle, f.body, 0).accepted(), "locked boarding refused");
        f.vehicle.setLocked(false);
        player.setChunkTrackingView(net.minecraft.server.level.ChunkTrackingView.EMPTY);
        f.level.getChunkSource().chunkMap.move(player);
        helper.assertFalse(UnitOrders.available(player, f.colony, f.vehicle), "leaving tracking range revokes access");
        helper.succeed();
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void boardingAndSupplyResolveMovingDeckStorage(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 96, 64, false);
        Platform platform = new Platform(f, true);
        helper.runAfterDelay(10, () -> {
            f.body.stopRiding();
            f.vehicle.moveTo(platform.pad());
            f.body.moveTo(platform.pad().add(5, 0, 0));
            helper.assertTrue(WorldSpaces.at(f.vehicle).realm() instanceof Realm.Frame,
                "hull rests on real Sable deck");
            helper.assertTrue(VehicleSeatDuty.hasFooting(f.vehicle, f.body), "deck footings are found in storage");
            helper.assertTrue(WarfareVehicles.INSTANCE.board(f.vehicle, f.body, 0).accepted(),
                "deck boarding accepted");
            var duty = new VehicleSeatDuty(f.vehicle, TestWorker.of(f.body), 0, false);
            helper.assertTrue(duty.spec().stances().cells().stream()
                .allMatch(cell -> cell.realm() instanceof Realm.Frame), "boarding stance follows the moving frame");
            VehicleProfile profile = WarfareVehicles.profile(f.vehicle, f.colony);
            var view = f.colony.views(f.level.getServer()).stream().filter(v -> v.level() == f.level)
                .findFirst().orElseThrow();
            var receipt = new VehicleSupply(f.colony).receipt(f.vehicle, profile,
                new VehicleProfile.Stock(ItemSpec.of(Items.ARROW), 12, 4), view, false);
            helper.assertTrue(receipt.isPresent(), "deck supply finds storage footings without throwing");
            helper.assertTrue(receipt.orElseThrow().spec().stances().cells().stream()
                .allMatch(cell -> cell.realm() instanceof Realm.Frame), "supply stance follows the same frame");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = "folkways_firearms", template = "warfare_range", batch = "vehicle_commands")
    public static void supplySkipsMissingFootingAndRebuildsAfterParking(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 64, 48, false);
        f.bindDriver();
        var base = WarfareVehicles.profile(f.vehicle, f.colony);
        var profile = new VehicleProfile(base.id(), base.name(), base.type(), base.seats(), Optional.empty(),
            List.of(), 10000, base.rules());
        new VehicleProfiles(f.colony, f.level.registryAccess()).replace(profile);
        BlockPos chest = BlockPos.containing(f.start.add(16, 0, 10));
        f.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ItemStack battery = new ItemStack(com.atsuishio.superbwarfare.init.ModItems.BATTERY.get());
        var energy = battery.getCapability(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.ITEM);
        helper.assertTrue(energy != null, "native battery exposes FE");
        energy.receiveEnergy(Integer.MAX_VALUE, false);
        ((Container) f.level.getBlockEntity(chest)).setItem(0, battery);
        f.colony.hold(WarfareVehicles.ID, new Claimed.Area(Set.of(
            WorldPos.of(f.level, BlockPos.containing(f.start.add(-4, 0, -4))),
            WorldPos.of(f.level, chest)))).orElseThrow();
        f.colony.stores(WarfareVehicles.ID, f.level.dimension(), List.of(WorldPos.of(f.level, chest)));
        var supply = new VehicleSupply(f.colony);
        helper.runAfterDelay(10, () -> {
            io.github.izakyl.folkways.core.inventory.internal.InventoryObservations.publish(f.colony, f.level);
            var view = f.colony.views(f.level.getServer()).stream().filter(v -> v.level() == f.level)
                .findFirst().orElseThrow();
            f.vehicle.setEnergy(0);
            f.vehicle.setDeltaMovement(Vec3.ZERO);
            f.vehicle.setOnGround(true);
            var first = supply.goals(view);
            helper.assertTrue(first.size() == 1, "one charging receipt exists at first parking place");
            Node old = first.getFirst().nodes().getFirst();
            f.vehicle.moveTo(f.start.add(10, 0, 0));
            var second = supply.goals(view);
            helper.assertTrue(second.size() == 1 && !second.getFirst().nodes().getFirst().id().equals(old.id()),
                "moving to another parking place replaces stale charging receipt");
            f.vehicle.moveTo(f.start.add(10, 10, 0));
            helper.assertTrue(supply.goals(view).isEmpty(), "missing standing positions skip supply without crashing");
            f.vehicle.moveTo(f.start.add(10, 0, 0));
            new VehicleProfiles(f.colony, f.level.registryAccess()).replace(new VehicleProfile(profile.id(),
                profile.name(), profile.type(), profile.seats(), Optional.empty(),
                List.of(new VehicleProfile.Stock(ItemSpec.of(Items.ARROW), 12, 4)), 0, profile.rules()));
            helper.assertTrue(supply.goals(view).size() == 1, "stock deficit creates refill memory");
            f.vehicle.discard();
            supply.tick(f.level.getServer());
            try {
                var pending = VehicleSupply.class.getDeclaredField("pending");
                var refilling = VehicleSupply.class.getDeclaredField("refilling");
                pending.setAccessible(true);
                refilling.setAccessible(true);
                helper.assertTrue(((Map<?, ?>) pending.get(supply)).isEmpty(), "removed hull clears pending nodes");
                helper.assertTrue(((Set<?>) refilling.get(supply)).isEmpty(), "removed hull clears refill memory");
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            helper.succeed();
        });
    }

    private static ServerPlayer commander(Scene f) {
        var player = FakePlayerFactory.get(f.level,
            new GameProfile(UUID.randomUUID(), "vehicle_commander"));
        Charters.keep(f.colony,
            Charter.founded("vehicle test", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY,
            Optional.of(f.colony.id()));
        player.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(FolkwaysItems.COLONY_BOOK.get()));
        player.moveTo(f.start.add(0, 1, 12));
        // Colony cleanup may remove the service before this callback runs. Retain the live handle.
        var ownership = CombatSelectionOwnership.of(f.colony);
        Cleanup.on(f.helper, () -> {
            ownership.clearContext(player);
            player.discard();
        });
        return player;
    }

    private static UnitSnapshot roundTrip(Scene f,
        UnitSnapshot snapshot) {
        var buffer = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
            f.level.registryAccess());
        try {
            var reply = new UnitReply(1, f.colony.id(),
                Vehicles.Result.success(), Optional.of(snapshot));
            UnitReply.STREAM_CODEC.encode(buffer, reply);
            return UnitReply.STREAM_CODEC.decode(buffer)
                .snapshot().orElseThrow();
        } finally {
            buffer.release();
        }
    }

    private static final class Scene extends WarfareVehicleGameTests.Scene {
        final Colony colony;
        final LaborFixture labor;
        final List<ResidentEntity> people = new ArrayList<>();

        Scene(GameTestHelper helper, String type, int length, int width) {
            this(helper, type, length, width, true);
        }

        Scene(GameTestHelper helper, String type, int length, int width, boolean tickLabor) {
            super(helper, type, site++, length, width);
            colony = Colonies.mint(level.getServer());
            body.setNoAi(false);
            labor = new LaborFixture(colony, level);
            if (tickLabor) {
                helper.onEachTick(labor::tick);
            }
            Cleanup.on(helper, () -> {
                labor.close();
                people.forEach(Entity::discard);
                colony.raze();
            });
            join(body);
        }

        void join(ResidentEntity resident) {
            helper.assertTrue(level.getEntity(resident.getUUID()) == resident,
                "resident must be visible to server entity lookup before colony admission");
            helper.assertTrue(colony.hold(PersonContent.ID, Claimed.Entity.of(resident)).isPresent(),
                "colony admits the fixture resident");
            resident.joinColony(colony.id());
        }

        ResidentEntity person(Vec3 at) {
            ResidentEntity person = PersonBody.RESIDENT.get().create(level);
            person.moveTo(at);
            helper.assertTrue(level.addFreshEntity(person), "fixture resident is added to the level");
            people.add(person);
            join(person);
            return person;
        }

        void bindDriver() {
            helper.assertTrue(WarfareVehicles.INSTANCE.board(vehicle, body, 0).accepted(),
                "official driver assignment");
            if (body.isPassenger() && vehicle.getSeatIndex(body) != 0) {
                helper.assertTrue(vehicle.changeSeat(body, 0), "native driver occupies seat zero");
            }
        }

        void order(Vehicles.Action action, Vec3 point) {
            helper.assertTrue(WarfareVehicles.INSTANCE.order(vehicle, new Vehicles.Order(action,
                point == null ? List.of() : List.of(WorldSpaces.at(level, BlockPos.containing(point))), -1,
                Optional.empty(), Optional.empty())).accepted(), "controller accepts " + action);
        }

        void attack(Entity target) {
            helper.assertTrue(WarfareVehicles.INSTANCE.order(vehicle, new Vehicles.Order(Vehicles.Action.ATTACK,
                List.of(), -1, Optional.of(target.getUUID()), Optional.empty())).accepted(), "attack accepted");
        }

        void rule(String weapon, WeaponRules.Mode mode) {
            vehicle.getPersistentData().put(WeaponRules.TAG,
                WeaponRules.write(Map.of(weapon, new WeaponRules.Rule(mode, 0, false))));
        }

        void ammo() {
            String weapon = vehicle.getGunName(0);
            vehicle.setItem(0, vehicle.getGunData(weapon).selectedAmmoConsumer().stack().copyWithCount(40));
        }

        Zombie target() {
            Zombie target = EntityType.ZOMBIE.create(level);
            target.setNoAi(true);
            target.setNoGravity(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            target.getAttribute(Attributes.SCALE).setBaseValue(3);
            target.setHealth(1000);
            target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
            target.moveTo(start.add(60, 2, 0));
            level.addFreshEntity(target);
            Cleanup.on(helper, target::discard);
            return target;
        }

        AtomicInteger shots() {
            AtomicInteger count = new AtomicInteger();
            Consumer<ShootEvent.Post> listener = event -> {
                if (event.getShooter() == body) {
                    count.incrementAndGet();
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            Cleanup.on(helper, () -> NeoForge.EVENT_BUS.unregister(listener));
            return count;
        }
    }
}
