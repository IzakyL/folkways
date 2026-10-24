package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.Config;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlock;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerItem.Binding;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.HandheldNozzleClientSprayVisuals;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayShape;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

// The addon's public renderer accepts Player. Feed its existing projectile state and hose renderer resident anchors instead.
@OnlyIn(Dist.CLIENT)
final class ResidentSprayVisuals {
    private record View(Level level, ResidentSprayPacket packet, Object nativeState, SprayTail tail) { }

    private static final Map<UUID, View> ACTIVE = new LinkedHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static long ticks;
    private static final Constructor<?> STATE;
    private static final Field PROJECTILES;
    private static final Field BEHAVIOR;
    private static final Field TRAILS;
    private static final Method SPAWN;
    private static final Method TICK;
    private static final Method SPEED;
    private static final Method GRAVITY;
    private static final Method FRICTION;
    private static final Method LIFETIME;
    private static final Method CABINET_ANCHOR;
    private static final Constructor<?> CONTROLLER_ANCHOR;
    private static final Method RENDER_HOSE;

    static {
        try {
            Class<?> visuals = HandheldNozzleClientSprayVisuals.class;
            Class<?> state = Class.forName(visuals.getName() + "$VisualState");
            STATE = state.getDeclaredConstructor();
            STATE.setAccessible(true);
            PROJECTILES = field(state, "projectiles");
            BEHAVIOR = field(state, "behavior");
            TRAILS = field(state, "trailParticles");
            TICK = method(visuals, "tickExisting", Level.class, state);
            SPEED = method(visuals, "projectileSpeed", HandheldNozzleType.class);
            GRAVITY = method(visuals, "projectileGravity", HandheldNozzleType.class);
            FRICTION = method(visuals, "projectileFriction", HandheldNozzleType.class);
            LIFETIME = method(visuals, "projectileLifetime", HandheldNozzleType.class);
            Class<?> projectiles = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayProjectileVisuals");
            SPAWN = method(projectiles, "spawnProjectiles", List.class, Vec3.class, Vec3.class, SprayShape.class,
                int.class, int.class, double.class, NativeSprayBridge.waterBehavior().getClass(), boolean.class,
                Vec3.class, double.class, RandomSource.class);
            Class<?> hoses = Class.forName("com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleHoseRenderer");
            Class<?> cabinet = Class.forName(hoses.getName() + "$CabinetAnchor");
            Class<?> controller = Class.forName(hoses.getName() + "$ControllerAnchor");
            CABINET_ANCHOR = method(hoses, "cabinetAnchor", FireHydrantCabinetBlockEntity.class, Direction.class);
            CONTROLLER_ANCHOR = controller.getDeclaredConstructor(Vec3.class, Vec3.class, Vec3.class, double.class,
                long.class, boolean.class, Binding.class);
            CONTROLLER_ANCHOR.setAccessible(true);
            RENDER_HOSE = method(hoses, "renderCurveTube", RenderLevelStageEvent.class, Minecraft.class, cabinet, controller);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private ResidentSprayVisuals() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener(ResidentSprayVisuals::tick);
        NeoForge.EVENT_BUS.addListener(ResidentSprayVisuals::render);
        NeoForge.EVENT_BUS.addListener(ResidentSprayVisuals::logout);
    }

    static void accept(ResidentSprayPacket packet) {
        if (!packet.active()) {
            View old = ACTIVE.get(packet.resident());
            if (old != null && old.packet().hydrant().equals(packet.hydrant())) {
                ACTIVE.put(packet.resident(), new View(old.level(), old.packet(), old.nativeState(),
                    old.tail().stop(ticks)));
            }
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        try {
            View old = ACTIVE.get(packet.resident());
            Object state = old != null && old.level() == level
                ? old.nativeState() : STATE.newInstance();
            BEHAVIOR.set(state, NativeSprayBridge.waterBehavior());
            TRAILS.setInt(state, packet.nozzle() == HandheldNozzleType.FLAT ? 4 : 3);
            View view = new View(level, packet, state, SprayTail.start(ticks));
            ACTIVE.put(packet.resident(), view);
            SPAWN.invoke(null, PROJECTILES.get(state), packet.origin(), packet.direction(), NativeSprayBridge.hoseShape(packet.nozzle()),
                Config.serverProjectilesPerTick, LIFETIME.invoke(null, packet.nozzle()), SPEED.invoke(null, packet.nozzle()),
                NativeSprayBridge.waterBehavior(), false, new Vec3(0, -(double) GRAVITY.invoke(null, packet.nozzle()), 0),
                FRICTION.invoke(null, packet.nozzle()), RANDOM);
            // A one-tick burst may finish before the next client tick; render its first droplets on receipt too.
            TICK.invoke(null, level, state);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions resident spray visuals failed", failure);
        }
    }

    private static boolean connected(View view) {
        var actor = view.level().getEntity(view.packet().actor());
        return actor != null && actor.isAlive() && actor.getUUID().equals(view.packet().resident())
            && view.level().getBlockEntity(view.packet().cabinet()) instanceof FireHydrantCabinetBlockEntity cabinet
            && cabinet.getHydrantId().equals(view.packet().hydrant());
    }

    private static void tick(ClientTickEvent.Post event) {
        ticks++;
        Level level = Minecraft.getInstance().level;
        ACTIVE.values().removeIf(view -> level == null || level != view.level());
        var cursor = ACTIVE.values().iterator();
        while (cursor.hasNext()) {
            View view = cursor.next();
            try {
                TICK.invoke(null, view.level(), view.nativeState());
                if (view.tail().finished(ticks, ((List<?>) PROJECTILES.get(view.nativeState())).isEmpty())) {
                    cursor.remove();
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("FireFighting Additions resident spray tick failed", failure);
            }
        }
    }

    private static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        for (View view : ACTIVE.values()) {
            if (!view.tail().hose(ticks) || !connected(view)) {
                continue;
            }
            var cabinet = (FireHydrantCabinetBlockEntity) view.level().getBlockEntity(view.packet().cabinet());
            var packet = view.packet();
            Binding binding = new Binding(packet.cabinet(), view.level().dimension(), packet.hydrant(), packet.nozzle());
            try {
                Object start = CABINET_ANCHOR.invoke(null, cabinet, cabinet.getBlockState().getValue(FireHydrantCabinetBlock.FACING));
                Object end = CONTROLLER_ANCHOR.newInstance(packet.origin(), packet.direction().scale(-1), new Vec3(0, 1, 0),
                    .62, view.level().getGameTime(), false, binding);
                RENDER_HOSE.invoke(null, event, mc, start, end);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("FireFighting Additions resident hose rendering failed", failure);
            }
        }
    }

    private static void logout(ClientPlayerNetworkEvent.LoggingOut event) { ACTIVE.clear(); }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
