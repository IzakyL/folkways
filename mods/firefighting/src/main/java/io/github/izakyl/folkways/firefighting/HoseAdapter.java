package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerItem;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionMethod;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionSession;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

final class HoseAdapter implements SuppressionMethod {
    void forget(UUID colony) {
        HoseSessions.closeColony(colony);
    }

    private List<FireHydrantCabinetBlockEntity> candidates(ServerLevel level, Worker who, WorldPos target) {
        Optional<UUID> owner = Bodies.of(who.body()).flatMap(body -> body.colonyId());
        if (owner.isEmpty()) {
            return List.of();
        }
        List<FireHydrantCabinetBlockEntity> found = new ArrayList<>();
        int radius = (int) Math.ceil(range() + HandheldNozzleBindingApi.MAX_BINDING_DISTANCE + 2);
        BlockPos fire = target.block(level);
        for (int x = (fire.getX() - radius) >> 4; x <= (fire.getX() + radius) >> 4; x++) {
            for (int z = (fire.getZ() - radius) >> 4; z <= (fire.getZ() + radius) >> 4; z++) {
                var chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }
                for (var entity : chunk.getBlockEntities().values()) {
                    if (entity instanceof FireHydrantCabinetBlockEntity cabinet && !cabinet.isRemoved()
                            && cabinet.canServeHandheldNozzle() && !cabinet.hasActiveBinding() && cabinet.getFluid().is(Fluids.WATER)
                            && !HandheldNozzleControllerEntity.hasActiveBinding(level.dimension(), cabinet.getBlockPos(), cabinet.getHydrantId())
                            && Vec3.atCenterOf(cabinet.getBlockPos()).distanceToSqr(Vec3.atCenterOf(fire))
                                <= Math.pow(HandheldNozzleBindingApi.MAX_BINDING_DISTANCE + NativeSprayBridge.hoseRange(cabinet.getNozzleType()) + 2, 2)) {
                        found.add(cabinet);
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(cabinet -> cabinet.getBlockPos().distSqr(target.block(level))));
        return found;
    }

    @Override
    public double range() {
        return Math.max(com.mikoalopex.createfirefightingadd.Config.coneNozzleMaxDistance,
            com.mikoalopex.createfirefightingadd.Config.flatNozzleMaxDistance);
    }

    private static boolean serves(ServerLevel level, FireHydrantCabinetBlockEntity cabinet, BlockPos feet, BlockPos fire, double eyeHeight) {
        Vec3 base = Vec3.atBottomCenterOf(feet);
        Vec3 eye = base.add(0, eyeHeight, 0);
        Vec3 direction = Vec3.atCenterOf(fire).subtract(eye).normalize();
        return base.distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos())) <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR
            && !level.getBlockState(feet).is(Blocks.FIRE) && !level.getBlockState(feet).is(Blocks.SOUL_FIRE)
            && NativeSprayBridge.hoseReaches(level, cabinet.getNozzleType(), NativeSprayBridge.hoseOrigin(eye, direction), direction, fire);
    }

    @Override
    public Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
        for (FireHydrantCabinetBlockEntity cabinet : candidates(level, who, target).stream().limit(4).toList()) {
            Set<BlockPos> cells = new LinkedHashSet<>();
            BlockPos standing = who.body().blockPosition();
            if (Footing.withFeetAt(level, standing) && serves(level, cabinet, standing, target.block(level), who.body().getEyeHeight())) {
                return Set.of(standing);
            }
            Vec3 from = Vec3.atCenterOf(cabinet.getBlockPos());
            Vec3 toward = Vec3.atCenterOf(target.block(level)).subtract(from).normalize();
            for (int step = 0; step <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE; step += 2) {
                BlockPos near = BlockPos.containing(from.add(toward.scale(step)));
                for (BlockPos feet : Reach.footingsAround(level, near, 1, 2)) {
                    if (serves(level, cabinet, feet, target.block(level), who.body().getEyeHeight())) {
                        cells.add(feet);
                    }
                }
            }
            if (!cells.isEmpty()) {
                return Set.copyOf(cells);
            }
        }
        return Set.of();
    }

    @Override
    public boolean reaches(ServerLevel level, Worker who, WorldPos target) {
        Vec3 direction = Vec3.atCenterOf(target.block(level)).subtract(who.body().getEyePosition()).normalize();
        return candidates(level, who, target).stream().anyMatch(cabinet ->
            who.body().position().distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos()))
                <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR
                && NativeSprayBridge.hoseReaches(level, cabinet.getNozzleType(),
                    NativeSprayBridge.hoseOrigin(who.body().getEyePosition(), direction), direction, target.block(level)));
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        for (FireHydrantCabinetBlockEntity cabinet : candidates(level, who, target)) {
            Vec3 direction = Vec3.atCenterOf(target.block(level)).subtract(who.body().getEyePosition()).normalize();
            if (who.body().position().distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos()))
                    > HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR
                    || !NativeSprayBridge.hoseReaches(level, cabinet.getNozzleType(),
                        NativeSprayBridge.hoseOrigin(who.body().getEyePosition(), direction), direction, target.block(level))) {
                continue;
            }
            UUID colony = Bodies.of(who.body()).flatMap(body -> body.colonyId()).orElseThrow();
            HoseSession session = new HoseSession(colony, who.body(), level, cabinet);
            if (!HoseSessions.add(session)) {
                return Optional.empty();
            }
            if (!((ResidentHydrantBinding) cabinet).folkways$bindResident(who.body().getUUID())) {
                HoseSessions.remove(session);
                continue;
            }
            ItemStack picture = new ItemStack(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get());
            HandheldNozzleControllerItem.bind(picture, level, cabinet.getBlockPos(), cabinet.getHydrantId(), cabinet.getNozzleType());
            Bodies.of(who.body()).ifPresent(body -> body.presentHeldItem(picture));
            return Optional.of(session);
        }
        return Optional.empty();
    }
}
