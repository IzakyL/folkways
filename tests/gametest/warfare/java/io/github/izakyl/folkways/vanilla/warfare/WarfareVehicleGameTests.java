package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.shell.warfare.VehicleController;
import io.github.izakyl.folkways.core.shell.warfare.WarfareVehicles;
import io.github.izakyl.folkways.core.shell.warfare.VehicleGuns;
import io.github.izakyl.folkways.core.shell.warfare.VehicleSeats;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;

/** Server-only spike: native physics moves the hull; the fixture supplies inputs, never position or velocity. */
@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class WarfareVehicleGameTests {

    private static final Logger LOGGER = LogUtils.getLogger();
    static final TicketType<UUID> TICKING =
        TicketType.create("folkways_warfare_vehicle_spike", Comparator.<UUID>naturalOrder());

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(WarfareVehicleGameTests.class);
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare_vehicles", template = "warfare_range",
        timeoutTicks = 320)
    public static void residentDrivesWheeledVehicle(GameTestHelper helper) {
        ground(helper, "lav_150", 0);
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare_vehicles", template = "warfare_range",
        timeoutTicks = 320)
    public static void residentDrivesTrackedVehicle(GameTestHelper helper) {
        ground(helper, "bmp_2", 1);
    }

    private static void ground(GameTestHelper helper, String type, int site) {
        Scene f = new Scene(helper, type, site, 128, 128);
        Vec3[] start = {null};
        float[] yaw = new float[2];
        helper.onEachTick(() -> {
            int t = f.tick();
            f.inputs(t >= 20, t >= 140 && t < 185, t >= 205, false, false, 0, 0);
            if (t == 20) {
                start[0] = f.vehicle.position();
            }
            if (t == 140) {
                f.log("forward");
                helper.assertTrue(f.vehicle.position().subtract(start[0]).horizontalDistance() > 5,
                    "120 forward ticks move a resident-driven hull more than five blocks");
                yaw[0] = f.vehicle.getYRot();
            }
            if (t == 185) {
                f.log("left");
                yaw[1] = f.vehicle.getYRot();
                helper.assertTrue(Math.abs(Mth.wrapDegrees(yaw[1] - yaw[0])) > 5, "left turns the hull");
            }
            if (t == 260) {
                f.log("right");
                helper.assertTrue(Math.abs(Mth.wrapDegrees(f.vehicle.getYRot() - yaw[1])) > 5,
                    "right turns the hull after releasing left");
                helper.assertTrue(f.vehicle.getEnergy() < f.initialEnergy, "native driving consumes stored energy");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare_vehicles", template = "warfare_range",
        timeoutTicks = 900)
    public static void residentFliesHelicopter(GameTestHelper helper) {
        Scene f = new Scene(helper, "ah_6", 2, 160, 160);
        int[] phase = {0};
        int[] airborne = {0};
        Vec3[] cruise = {null};
        helper.onEachTick(() -> {
            int t = f.tick();
            if (t < 60) {
                f.inputs(false, false, false, t >= 20, false, 0, 0);
                if (t == 59) {
                    f.log("up-alone");
                    helper.assertTrue(f.height() < 1, "up toggles hover; it does not supply takeoff power");
                }
                return;
            }
            if (phase[0] == 0) {
                f.inputs(true, false, false, false, false, 0, 0);
                if (f.height() > 12) {
                    f.log("takeoff");
                    phase[0] = 1;
                    airborne[0] = t;
                    cruise[0] = f.vehicle.position();
                }
            } else if (phase[0] == 1) {
                f.inputs(false, false, false, false, false, .15f,
                    Mth.clamp((8 - f.vehicle.getXRot()) * .2f, -2, 2));
                if (t - airborne[0] >= 70) {
                    f.log("horizontal");
                    helper.assertTrue(f.vehicle.position().subtract(cruise[0]).horizontalDistance() > 3,
                        "mouse pitch and yaw produce horizontal flight");
                    phase[0] = 2;
                }
            } else {
                f.inputs(false, false, false, false, true, 0,
                    Mth.clamp(-f.vehicle.getXRot() * .3f, -3, 3));
                if (f.vehicle.onGround() && f.height() < 1) {
                    f.log("landed");
                    helper.assertFalse(f.vehicle.isWreck(), "controlled descent preserves the helicopter");
                    helper.assertTrue(f.vehicle.getEnergy() < f.initialEnergy, "flight consumes energy");
                    helper.succeed();
                }
            }
            if (t % 40 == 0) {
                f.log("phase-" + phase[0]);
            }
        });
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare_vehicles", template = "warfare_range",
        timeoutTicks = 600)
    public static void residentFliesFixedWing(GameTestHelper helper) {
        Scene f = new Scene(helper, "a_10a", 3, 640, 48);
        helper.onEachTick(() -> {
            int t = f.tick();
            // Aircraft mouse pitch is applied once there is airspeed; no launch impulse or airborne spawn.
            float pitch = f.vehicle.getDeltaMovement().horizontalDistance() > .7 ? -12 : 0;
            f.inputs(t >= 20, false, false, false, false, 0,
                Mth.clamp((pitch - f.vehicle.getXRot()) * .3f, -3, 3));
            if (t % 40 == 0) {
                f.log("runway");
            }
            if (f.height() > 8 && f.vehicle.getDeltaMovement().horizontalDistance() > .7) {
                f.log("airborne");
                helper.assertFalse(f.vehicle.onGround(), "fixed wing leaves the runway under native lift");
                helper.assertTrue(f.vehicle.position().subtract(f.start).horizontalDistance() > 15,
                    "takeoff follows a real ground roll");
                helper.succeed();
            }
        });
    }

    @GameTest(templateNamespace = "folkways_vanilla", batch = "warfare_vehicles", template = "warfare_range",
        timeoutTicks = 360)
    public static void residentGunnerFiresVehicleWeapon(GameTestHelper helper) {
        Scene f = new Scene(helper, "lav_150", 4, 128, 64);
        var colony = Colonies.mint(f.level.getServer());
        helper.assertTrue(colony.hold(PersonContent.ID,
            Claimed.Entity.of(f.body)).isPresent(),
            "colony admits the native gunner");
        f.body.joinColony(colony.id());
        Cleanup.on(helper, colony::raze);
        Zombie target = EntityType.ZOMBIE.create(f.level);
        target.setNoAi(true);
        target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        target.getAttribute(Attributes.SCALE).setBaseValue(3);
        target.setHealth(1000);
        target.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CARVED_PUMPKIN));
        target.moveTo(f.start.add(65, 2, 25));
        f.level.addFreshEntity(target);
        Cleanup.on(helper, target::discard);
        AtomicInteger shots = new AtomicInteger();
        int[] authorized = {0};
        boolean[] permission = {false};
        Consumer<ShootEvent.Post> listener = event -> {
            if (event.getShooter() == f.body) {
                shots.incrementAndGet();
                if (permission[0]) {
                    authorized[0]++;
                }
            }
        };
        NeoForge.EVENT_BUS.addListener(listener);
        Cleanup.on(helper, () -> NeoForge.EVENT_BUS.unregister(listener));
        // Keep the native engine and occupant, but use the production mobile firing lease rather than
        // injecting a mobile AP cannon into the fixed-emplacement HE-only registry.
        int controller = f.vehicle.getTurretControllerIndex();
        if (f.vehicle.getSeatIndex(f.body) != controller) {
            helper.assertTrue(f.vehicle.changeSeat(f.body, controller), "native turret controller seat");
        }
        VehicleSeats.assign(f.vehicle, f.body, f.vehicle.getSeatIndex(f.body));
        helper.assertTrue(VehicleSeats.holds(f.vehicle, f.body), "native gunner owns a real mobile seat lease");
        Cleanup.on(helper, () -> VehicleSeats.release(f.vehicle, f.vehicle.getSeatIndex(f.body)));
        int seat = f.vehicle.getSeatIndex(f.body);
        var station = new VehiclePost(f.vehicle, seat, f.body);
        var duty = Posts.of(colony).orElseThrow().duty(station.post(f.level), () -> station.demanded(f.level));
        helper.assertTrue(((Workload.Continuous) duty.spec().workload()).holds(f.level, TestWorker.of(f.body)),
            "native gunner takes the duty post");
        Cleanup.on(helper, () -> duty.ended(f.level, Ending.DONE));
        String first = f.vehicle.getGunName(seat);
        f.vehicle.changeWeapon(seat, 1, true);
        String second = f.vehicle.getGunName(seat);
        helper.assertTrue(!first.equals(second), "changeWeapon selects another native weapon");
        f.vehicle.changeWeapon(seat, 0, false);
        f.vehicle.getPersistentData().put(WeaponRules.TAG,
            WeaponRules.write(Map.of(seat + "/" + first,
                new WeaponRules.Rule(
                    WeaponRules.Mode.ORDERED, 0, false))));

        var attack = new Vehicles.Order(Vehicles.Action.ATTACK, List.of(), -1,
            Optional.of(target.getUUID()), Optional.empty());
        helper.assertTrue(WarfareVehicles.provider().order(f.vehicle, attack).accepted(), "gunner accepts an attack order");
        float[] initialYaw = {f.vehicle.getTurretYRot()};
        int[] begin = {-1};
        helper.onEachTick(() -> {
            f.inputs(false, false, false, false, false, 0, 0);
            f.body.setTarget(null);
            // On slow runners the freshly generated target chunk can reach entity ticking several ticks
            // after the hull's; engagement cannot publish an aim until then, so the timeline starts there.
            if (begin[0] < 0) {
                if (!f.level.isPositionEntityTicking(target.blockPosition())) {
                    return;
                }
                begin[0] = f.tick();
            }
            int t = f.tick() - begin[0];
            if (t >= 5 && t < 60) {
                helper.assertValueEqual(f.vehicle.getAiTurretTargetUUID(), target.getStringUUID(),
                    "engagement publishes the native synced aiming target even without ammunition");
                helper.assertValueEqual(shots.get(), 0, "aiming before ammunition never fires");
                helper.assertTrue(f.body.getTarget() == null, "native Mob auto-fire has no target while aiming");
                if (t == 59) {
                    helper.assertTrue(Math.abs(f.vehicle.getTurretYRot() - initialYaw[0]) > 5,
                        "native turret yaw converges over ticks before the first shot");
                    Vec3 toward = target.getBoundingBox().getCenter()
                        .subtract(f.vehicle.getShootPos(f.body, 1)).normalize();
                    helper.assertTrue(f.vehicle.getBarrelVector(1).normalize().dot(toward)
                        > Math.cos(Math.toRadians(4)), "visible barrel converges toward the engagement target");
                }
            }
            // Native UUID aiming owns the turret; do not rotate it from the fixture.
            if (t == 60) {
                VehicleController.of(f.vehicle).cancel();
                // Magazine=0 uses actual hull inventory, not GunData.ammo (clamped to zero by gunTick).
                ItemStack ammunition = f.vehicle.getGunData(first).selectedAmmoConsumer().stack().copyWithCount(20);
                helper.assertFalse(ammunition.isEmpty(), "native ammunition consumer names a physical shell");
                f.vehicle.setItem(0, ammunition);
                f.log("ammo-initialized=" + ammunition);
            }
            if (t == 80) {
                helper.assertValueEqual(shots.get(), 0, "target=null emits no native automatic fire");
                helper.assertValueEqual(f.vehicle.getItem(0).getCount(), 20, "idle consumes no ammunition");
                helper.assertTrue(f.vehicle.getGunData(first).canShoot(f.vehicle),
                    "the blocked probe has usable ammunition and is otherwise ready");
                f.vehicle.vehicleShoot(f.body, first, target.getUUID(), target.position());
            }
            if (t == 100) {
                helper.assertValueEqual(shots.get(), 0, "a direct unpermitted mobile shot is blocked");
                helper.assertValueEqual(f.vehicle.getItem(0).getCount(), 20, "blocked fire spends no physical shell");
                f.log("gate-blocked weapons=" + first + "/" + second);
            }
            if (t >= 110 && t < 220 && authorized[0] < 3) {
                permission[0] = true;
                if (t == 110) {
                    helper.assertTrue(WarfareVehicles.provider().order(f.vehicle, attack).accepted(),
                        "armed gunner accepts another attack order");
                }
                boolean queued = f.vehicle.getGunData(first).canShoot(f.vehicle);
                if (t % 20 == 10) {
                    f.log("engage=" + queued + " shots=" + shots.get() + " gate="
                        + VehicleGuns.of(f.vehicle).diagnostic());
                }
            }
            if (t >= 110 && (authorized[0] >= 3 || t >= 220)) {
                VehicleController.of(f.vehicle).cancel();
            }
            if (t == 220) {
                permission[0] = false;
            }
            if (t == 260) {
                helper.assertValueEqual(f.vehicle.getAiTurretTargetUUID(), "",
                    "ending the engagement clears the synced turret target");
                helper.assertValueEqual(f.vehicle.getAiPassengerWeaponTargetUUID(), "",
                    "an unused passenger weapon station has no synced target");
                f.log("gunner shots=" + shots.get() + " authorized=" + authorized[0]
                    + " targetHealth=" + target.getHealth() + " shells=" + f.vehicle.getItem(0).getCount());
                helper.assertTrue(shots.get() > 0 && shots.get() == authorized[0],
                    "every native vehicle shot passes the actual queued discharge/emitting gate");
                helper.assertTrue(f.body.getTarget() == null, "explicit engagements need no Mob target");
                helper.assertTrue(target.getHealth() < 1000, "native vehicle projectiles hit the aimed target");
                helper.assertValueEqual(f.vehicle.getItem(0).getCount(), 20 - authorized[0],
                    "each permitted native emission consumes one real hull shell");
                helper.succeed();
            }
        });
    }

    static class Scene {

        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos origin;
        final UUID ticket = UUID.randomUUID();
        final List<ChunkPos> chunks = new ArrayList<>();
        final VehicleEntity vehicle;
        final ResidentEntity body;
        final Vec3 start;
        final int initialEnergy;
        final long started;

        Scene(GameTestHelper helper, String type, int site, int length, int width) {
            this.helper = helper;
            level = helper.getLevel();
            started = level.getGameTime();
            origin = helper.absolutePos(BlockPos.ZERO).offset(0, 0, 512 + site * 512);
            for (int x = -1; x <= length / 16; x++) {
                for (int z = -1; z <= width / 16; z++) {
                    ChunkPos chunk = new ChunkPos(origin.offset(x * 16, 0, z * 16));
                    level.getChunk(chunk.x, chunk.z);
                    level.getChunkSource().addRegionTicket(TICKING, chunk, 2, ticket, true);
                    chunks.add(chunk);
                }
            }
            for (int x = 0; x < length; x++) {
                for (int z = 0; z < width; z++) {
                    level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            vehicle = (VehicleEntity) BuiltInRegistries.ENTITY_TYPE.get(
                ResourceLocation.fromNamespaceAndPath("superbwarfare", type)).create(level);
            start = Vec3.atBottomCenterOf(origin.offset(12, 1, width / 2));
            vehicle.moveTo(start.x, start.y, start.z, -90, 0);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            initialEnergy = vehicle.getEnergy();
            level.addFreshEntity(vehicle);
            body = PersonBody.RESIDENT.get().create(level);
            body.setNoAi(true);
            body.moveTo(start);
            level.addFreshEntity(body);
            Cleanup.on(helper, () -> {
                body.stopRiding();
                body.discard();
                level.getEntitiesOfClass(Entity.class, vehicle.getBoundingBox().inflate(192),
                    entity -> entity instanceof Projectile)
                    .forEach(Entity::discard);
                vehicle.discard();
                chunks.forEach(chunk -> level.getChunkSource().removeRegionTicket(TICKING, chunk, 2, ticket, true));
            });
            helper.assertTrue(body.startRiding(vehicle), "a real resident mounts without a player");
            if (vehicle.getSeatIndex(body) != 0) {
                helper.assertTrue(vehicle.changeSeat(body, 0), "select driver seat zero");
            }
            helper.assertValueEqual(vehicle.getSeatIndex(body), 0, "resident occupies driver seat zero");
            log("spawn engine=" + vehicle.computed().getEngineType());
        }

        int tick() {
            return (int) (level.getGameTime() - started);
        }

        double height() {
            return vehicle.getY() - start.y;
        }

        void inputs(boolean forward, boolean left, boolean right, boolean up, boolean down,
                    float mouseX, float mouseY) {
            helper.assertTrue(body.getVehicle() == vehicle && !vehicle.isWreck(), "resident retains a working hull");
            body.setTarget(null);
            vehicle.setForwardInputDown(forward);
            vehicle.setBackInputDown(false);
            vehicle.setLeftInputDown(left);
            vehicle.setRightInputDown(right);
            vehicle.setUpInputDown(up);
            vehicle.setDownInputDown(down);
            vehicle.setDecoyInputDown(false);
            vehicle.setFireInputDown(false);
            vehicle.setSprintInputDown(false);
            vehicle.setMouseMoveSpeedX(mouseX);
            vehicle.setMouseMoveSpeedY(mouseY);
        }

        void log(String phase) {
            LOGGER.info("WARFARE_VEHICLE_SPIKE {} {} tick={} offset={} height={} velocity={} yaw={} pitch={} "
                + "power={} energy={} ground={} seat={} engineStarted={}",
                BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()), phase, tick(),
                vehicle.position().subtract(start), height(), vehicle.getDeltaMovement(), vehicle.getYRot(),
                vehicle.getXRot(), vehicle.getPower(), vehicle.getEnergy(), vehicle.onGround(),
                vehicle.getSeatIndex(body), vehicle.getEngineStartOver());
        }
    }
}
