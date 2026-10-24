package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayFluidType;
import com.mikoalopex.createfirefightingadd.api.nozzle.NozzleSprayHitContext;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItemHandler;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.FireExtinguisherSprayEffects;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

// 0.2.5 exposes spray only for ServerPlayer. Call its block effect for one approved fire cell, with its cone and cost.
final class NativeSprayBridge {
    private static final Object OPTIONS;
    private static final Method APPLY;
    private static final Object WATER;

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
        } catch (ReflectiveOperationException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private NativeSprayBridge() {
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
