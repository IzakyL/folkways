package io.github.izakyl.folkways.create.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.FireExtinguisherSprayEffects;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.HandheldNozzleSprayEffects;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.SprayShape;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

// 0.2.5 exposes player entry points only. Reuse its block samplers with resident origins and paid pulses.
final class NativeSprayBridge {
    static final double HOSE_FORWARD = 0.75D;
    static final double HOSE_DROP = 0.18D;
    private static final Object WATER;
    private static final int EXTINGUISHER_COST;
    private static final int EXTINGUISHER_INTERVAL;
    private static final int HOSE_COST;
    private static final Method EXTINGUISHER_BLOCKS;
    private static final Method HOSE_BLOCKS;
    private static final Method HOSE_SHAPE;
    private static final Method HOSE_RANGE;
    private static final Method SOUND;
    private static final Method STOP_SOUND;
    private static final Map<Mob, Long> EXTINGUISHER_TICKS = new WeakHashMap<>();
    private static final Map<Mob, Long> HOSE_TICKS = new WeakHashMap<>();

    static {
        try {
            EXTINGUISHER_COST = constant(FireExtinguisherSprayEffects.class, "CONSUMPTION_PER_INTERVAL");
            EXTINGUISHER_INTERVAL = constant(FireExtinguisherSprayEffects.class, "CONSUMPTION_INTERVAL");
            HOSE_COST = constant(HandheldNozzleSprayEffects.class, "CONSUMPTION_PER_TICK");
            Class<?> behavior = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle"
                + ".AbstractSprayDeviceBlockEntity$FluidBehavior");
            Class<?> rules = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle"
                + ".NozzleSprayRule");
            WATER = Arrays.stream(behavior.getEnumConstants())
                .filter(value -> ((Enum<?>) value).name().equals("WATER"))
                .findFirst().orElseThrow();
            EXTINGUISHER_BLOCKS = method(FireExtinguisherSprayEffects.class, "applyBlockEffects",
                ServerLevel.class, Vec3.class, Vec3.class, behavior, FluidStack.class, boolean.class, rules);
            HOSE_BLOCKS = method(HandheldNozzleSprayEffects.class, "applyBlockEffects", ServerLevel.class,
                BlockPos.class, Vec3.class, Vec3.class, SprayShape.class, int.class, behavior, FluidStack.class,
                boolean.class, long.class, rules);
            HOSE_SHAPE = method(HandheldNozzleSprayEffects.class, "shapeFor", HandheldNozzleType.class);
            HOSE_RANGE = method(HandheldNozzleSprayEffects.class, "rangeFor", HandheldNozzleType.class);
            Class<?> sounds = Class.forName("com.mikoalopex.createfirefightingadd.content.fluids.nozzle"
                + ".NozzleSpraySounds");
            SOUND = method(sounds, "tick", Level.class, String.class, Vec3.class, SoundSource.class);
            STOP_SOUND = method(sounds, "stop", Level.class, String.class, Vec3.class, SoundSource.class);
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private NativeSprayBridge() {
    }

    private static Method method(Class<?> type, String name,
        Class<?>... arguments) throws ReflectiveOperationException {
        Method method = type.getDeclaredMethod(name, arguments);
        method.setAccessible(true);
        return method;
    }

    private static int constant(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }

    static FluidStack drainHose(FireHydrantCabinetBlockEntity cabinet, FluidAction action) {
        return cabinet.drainForHandheldSpray(HOSE_COST, action);
    }

    static Object waterBehavior() {
        return WATER;
    }

    static Vec3 hoseOrigin(Vec3 eye, Vec3 direction) {
        return eye.add(direction.scale(HOSE_FORWARD)).add(0, -HOSE_DROP, 0);
    }

    static SprayShape hoseShape(
        HandheldNozzleType type) {
        try {
            return (SprayShape) HOSE_SHAPE.invoke(null, type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions hose shape failed", failure);
        }
    }

    static int hoseRange(HandheldNozzleType type) {
        try {
            return (int) HOSE_RANGE.invoke(null, type);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions hose range failed", failure);
        }
    }

    static void hose(ServerLevel level, Mob actor,
        FireHydrantCabinetBlockEntity cabinet,
        Vec3 origin, Vec3 direction, FluidStack water) {
        try {
            HOSE_BLOCKS.invoke(null, level, cabinet.getBlockPos(), origin, direction,
                hoseShape(cabinet.getNozzleType()), hoseRange(cabinet.getNozzleType()), WATER, water, false,
                level.getGameTime(), null);
            SOUND.invoke(null, level, "handheld:" + actor.getUUID(), origin, SoundSource.PLAYERS);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions resident hose spray failed", failure);
        }
    }

    static boolean hoseReady(Mob actor) {
        return HOSE_TICKS.getOrDefault(actor, Long.MIN_VALUE) != actor.level().getGameTime();
    }

    static void hosePaid(Mob actor) {
        HOSE_TICKS.put(actor, actor.level().getGameTime());
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
        direction = direction.normalize();
        var fluid = FireExtinguisherItem.getFluid(stack);
        if (!fluid.is(Fluids.WATER) || fluid.isEmpty()) {
            return false;
        }
        long now = level.getGameTime();
        Long previous = EXTINGUISHER_TICKS.get(actor);
        if (previous != null && now - previous < EXTINGUISHER_INTERVAL) {
            return false;
        }
        var handler = new FireExtinguisherItemHandler(CreateFireFightingAdd.FIRE_EXTINGUISHER_FLUID, stack);
        if (handler.drainForSpray(EXTINGUISHER_COST, FluidAction.SIMULATE).isEmpty()) {
            return false;
        }
        handler.drainForSpray(EXTINGUISHER_COST, FluidAction.EXECUTE);
        EXTINGUISHER_TICKS.put(actor, now);
        try {
            EXTINGUISHER_BLOCKS.invoke(null, level, origin, direction, WATER, fluid, false, null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("FireFighting Additions resident extinguisher spray failed", failure);
        }
        ResidentSprayPacket.extinguisher(actor, origin, direction);
        return true;
    }
}
