package io.github.izakyl.folkways.core.shell.create.firefighting;

import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

public final class NativeHose {
    public static final double BINDING_DISTANCE = HandheldNozzleBindingApi.MAX_BINDING_DISTANCE;

    private NativeHose() {
    }

    public static boolean available(ServerLevel level, FireHydrantCabinetBlockEntity cabinet) {
        return cabinet.canServeHandheldNozzle() && !cabinet.hasActiveBinding() && cabinet.getFluid().is(Fluids.WATER)
            && !HandheldNozzleControllerEntity.hasActiveBinding(level.dimension(), cabinet.getBlockPos(),
                cabinet.getHydrantId());
    }

    public static double range() {
        return Math.max(NativeSprayBridge.hoseRange(HandheldNozzleType.CONE),
            NativeSprayBridge.hoseRange(HandheldNozzleType.FLAT));
    }

    public static boolean nearTarget(FireHydrantCabinetBlockEntity cabinet, BlockPos target) {
        return Vec3.atCenterOf(cabinet.getBlockPos()).distanceToSqr(Vec3.atCenterOf(target))
            <= Math.pow(BINDING_DISTANCE + NativeSprayBridge.hoseRange(cabinet.getNozzleType()) + 2, 2);
    }

    public static boolean inRange(FireHydrantCabinetBlockEntity cabinet, BlockPos feet, BlockPos fire, Mob actor) {
        Vec3 base = Vec3.atBottomCenterOf(feet);
        return SprayFootings.within(base, Vec3.atCenterOf(cabinet.getBlockPos()),
            HandheldNozzleBindingApi.MAX_BINDING_DISTANCE)
            && SprayFootings.within(base.add(0, actor.getEyeHeight(), 0), Vec3.atCenterOf(fire),
                NativeSprayBridge.hoseRange(cabinet.getNozzleType()));
    }

    public static boolean serves(ServerLevel level, FireHydrantCabinetBlockEntity cabinet, BlockPos feet,
        BlockPos fire, Mob actor) {
        return inRange(cabinet, feet, fire, actor) && SprayFootings.serves(level, actor, feet, fire,
            NativeSprayBridge.hoseRange(cabinet.getNozzleType()), true);
    }
}
