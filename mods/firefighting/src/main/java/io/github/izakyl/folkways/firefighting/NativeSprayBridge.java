package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayFluidType;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayHitContext;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.FireExtinguisherSprayEffects;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

// 0.2.5 exposes spray only for ServerPlayer. Call its block effect for one approved fire cell, with its cone and cost.
final class NativeSprayBridge {
    private static final Object OPTIONS;
    private static final Method APPLY;
    private static final Object WATER;
    private static final Method HOSE_SHAPE;
    private static final Method HOSE_RANGE;
    private static final Method TRACE;
    private static final Class<?> SAMPLE;
    private static final Method SOUND;
    private static final Method STOP_SOUND;

    static {
        try {
            String prefix = "com.mikoalopex.createfirefightingadd.content.fluids.nozzle.NozzleSprayEffects";
            Class<?> effects = Class.forName(prefix);
            Class<?> options = Class.forName(prefix + "$BlockEffectOptions");
            Class<?> sound = Class.forName(prefix + "$ExtinguishSound");
            var silent = effects.getDeclaredField("NO_EXTINGUISH_SOUND");
            silent.setAccessible(true);
            Method projectile = options.getDeclaredMethod("projectilePath", sound);
            projectile.setAccessible(true);
            OPTIONS = projectile.invoke(null, silent.get(null));
            Class<?> behavior = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle.AbstractSprayDeviceBlockEntity$FluidBehavior");
            WATER = Arrays.stream(behavior.getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals("WATER")).findFirst().orElseThrow();
            APPLY = effects.getDeclaredMethod("applyBlockSample", NozzleSprayHitContext.class,
                behavior, boolean.class, options);
            APPLY.setAccessible(true);
            Class<?> handheld = com.mikoalopex.createfirefightingadd.content.fluids.nozzle.HandheldNozzleSprayEffects.class;
            HOSE_SHAPE = method(handheld, "shapeFor", com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType.class);
            HOSE_RANGE = method(handheld, "rangeFor", com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType.class);
            Class<?> sampler = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayEffectSampler");
            SAMPLE = Class.forName(sampler.getName() + "$SampleConsumer");
            TRACE = method(sampler, "traceRays", Level.class, Vec3.class, Vec3.class,
                com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayShape.class,
                int.class, int.class, long.class, long.class, double.class, SAMPLE);
            Class<?> sounds = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle.NozzleSpraySounds");
            SOUND = method(sounds, "tick", Level.class, String.class, Vec3.class, SoundSource.class);
            STOP_SOUND = method(sounds, "stop", Level.class, String.class, Vec3.class, SoundSource.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private NativeSprayBridge() {
    }

    private static Method method(Class<?> type, String name, Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    static Object waterBehavior() { return WATER; }

    static Vec3 hoseOrigin(Vec3 eye, Vec3 direction) {
        return eye.add(direction.scale(.75)).add(0, -.18, 0);
    }

    static com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayShape hoseShape(
            com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType type) {
        try {
            return (com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayShape) HOSE_SHAPE.invoke(null, type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions hose shape failed", failure);
        }
    }

    static int hoseRange(com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType type) {
        try {
            return (int) HOSE_RANGE.invoke(null, type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions hose range failed", failure);
        }
    }

    static boolean hoseReaches(ServerLevel level,
            com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType nozzle,
            Vec3 origin, Vec3 direction, BlockPos target) {
        if (!nozzle.hasNozzle() || !level.isLoaded(target)) {
            return false;
        }
        Vec3 point = Vec3.atCenterOf(target);
        double distance = point.subtract(origin).dot(direction);
        // A fire occupies a whole block: its centre need not fall inside a narrow fan for a native ray to hit it.
        if (distance <= 0 || distance > hoseRange(nozzle)) {
            return false;
        }
        var hit = level.clip(new ClipContext(origin, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
            CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    static void hose(ServerLevel level, Mob actor,
            com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity cabinet,
            Vec3 origin, Vec3 direction, BlockPos target, FluidStack water) {
        int range = hoseRange(cabinet.getNozzleType());
        long tick = level.getGameTime();
        Object callback = Proxy.newProxyInstance(SAMPLE.getClassLoader(), new Class<?>[] {SAMPLE},
            (proxy, method, args) -> {
                if (method.getName().equals("accept") && target.equals(args[0])) {
                    var state = (BlockState) args[1];
                    if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
                        var context = new NozzleSprayHitContext(level, target, state, water.copy(), NozzleSprayFluidType.WATER,
                            false, origin, (Vec3) args[2], (Vec3) args[3], (double) args[4],
                            com.mikoalopex.createfirefightingadd.content.items.firefighter.FirefighterRecordStore.activeOwners(level, cabinet.getBlockPos()));
                        APPLY.invoke(null, context, WATER, false, OPTIONS);
                    }
                }
                return null;
            });
        try {
            // The addon's sampler supplies the same centre ray, spread, range and occlusion. Only the approved cell receives effects.
            TRACE.invoke(null, level, origin, direction, hoseShape(cabinet.getNozzleType()), range,
                Math.max(8, Math.min(28, range / 2)), tick, tick * 37L, .75, callback);
            SOUND.invoke(null, level, "handheld:" + actor.getUUID(), origin, SoundSource.PLAYERS);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions resident hose spray failed", failure);
        }
    }

    static void stopHose(ServerLevel level, Mob actor) {
        try {
            STOP_SOUND.invoke(null, level, "handheld:" + actor.getUUID(), actor.position(), SoundSource.PLAYERS);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions resident hose sound failed", failure);
        }
    }

    static boolean spray(ServerLevel level, Mob actor, Vec3 origin, Vec3 direction, BlockPos target, ItemStack stack) {
        if (actor.level() != level || !actor.isAlive() || actor.isRemoved() || !level.isLoaded(target)) {
            return false;
        }
        var state = level.getBlockState(target);
        if (!state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)) {
            return false;
        }
        Vec3 point = Vec3.atCenterOf(target);
        Vec3 offset = point.subtract(origin);
        direction = direction.normalize();
        double axial = offset.dot(direction);
        double radius = axial * Math.tan(Math.toRadians(FireExtinguisherSprayEffects.HALF_ANGLE)) + .65;
        if (axial <= 0 || axial > FireExtinguisherSprayEffects.RANGE
                || offset.lengthSqr() - axial * axial > radius * radius) {
            return false;
        }
        var hit = level.clip(new ClipContext(origin, point, ClipContext.Block.COLLIDER,
            ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.MISS && !hit.getBlockPos().equals(target)) {
            return false;
        }
        var fluid = FireExtinguisherItem.getFluid(stack);
        if (!fluid.is(Fluids.WATER) || fluid.getAmount() < 5) {
            return false;
        }
        // One pulse represents the addon's two-tick interval. Never grant a free odd-tick shot.
        var handler = new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, stack);
        if (handler.drainForSpray(5, FluidAction.SIMULATE).getAmount() != 5) {
            return false;
        }
        handler.drainForSpray(5, FluidAction.EXECUTE);
        var context = new NozzleSprayHitContext(level, target, state, fluid.copy(), NozzleSprayFluidType.WATER,
            false, origin, point, direction, axial, List.of());
        try {
            APPLY.invoke(null, context, WATER, false, OPTIONS);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions 0.2.5 block spray bridge failed", failure);
        }
        Vec3 midway = origin.add(point).scale(.5);
        level.sendParticles(ParticleTypes.SPLASH, midway.x, midway.y, midway.z, 8, .12, .12, .12, .02);
        return true;
    }
}
