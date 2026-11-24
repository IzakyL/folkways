package io.github.izakyl.folkways.core.shell.create.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.content.equipment.extinguisher.FireExtinguisherItem;
import com.mikoalopex.createfirefightingadd.content.fluids.nozzle.FireExtinguisherSprayEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

public final class NativeExtinguisher {
    public static final double RANGE = FireExtinguisherSprayEffects.RANGE;

    private NativeExtinguisher() {
    }

    public static boolean is(ItemStack stack) {
        return stack.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
    }

    public static ItemStack find(Container pack) {
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (is(stack)) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static FluidStack fluid(ItemStack stack) {
        return FireExtinguisherItem.getFluid(stack);
    }

    public static boolean spray(ServerLevel level, Mob actor, BlockPos target, ItemStack stack) {
        Vec3 origin = actor.getEyePosition();
        Vec3 direction = Vec3.atCenterOf(target).subtract(origin).normalize();
        return NativeSprayBridge.spray(level, actor, origin, direction, target, stack);
    }
}
