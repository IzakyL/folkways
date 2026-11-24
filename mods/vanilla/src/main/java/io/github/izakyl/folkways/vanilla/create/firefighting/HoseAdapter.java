package io.github.izakyl.folkways.vanilla.create.firefighting;

import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.shell.create.firefighting.HoseSessions;
import io.github.izakyl.folkways.core.shell.create.firefighting.Hydrants;
import io.github.izakyl.folkways.core.shell.create.firefighting.NativeHose;
import io.github.izakyl.folkways.core.shell.create.firefighting.NativeHoseSession;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlRefusal;
import io.github.izakyl.folkways.vanilla.damagecontrol.SuppressionMethod;
import io.github.izakyl.folkways.vanilla.damagecontrol.SuppressionSession;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentPathNavigation;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

final class HoseAdapter implements SuppressionMethod {
    private static final int MAX_SPRAY_CHECKS = 512;
    private static final int MAX_PATH_CHECKS = 16;

    void forget(UUID colony) {
        HoseSessions.closeColony(colony);
    }

    private List<FireHydrantCabinetBlockEntity> candidates(ServerLevel level, Worker who, WorldPos target) {
        Optional<UUID> owner = Bodies.of(who.body()).flatMap(body -> body.colonyId());
        if (owner.isEmpty()) {
            return List.of();
        }
        List<FireHydrantCabinetBlockEntity> found = new ArrayList<>();
        int radius = (int) Math.ceil(range() + NativeHose.BINDING_DISTANCE + 2);
        BlockPos fire = target.block(level);
        for (FireHydrantCabinetBlockEntity cabinet : Hydrants.nearby(level, fire, radius)) {
            if (NativeHose.available(level, cabinet) && NativeHose.nearTarget(cabinet, fire)) {
                found.add(cabinet);
                if (found.size() == 4) {
                    break;
                }
            }
        }
        return found;
    }

    @Override
    public double range() {
        return NativeHose.range();
    }

    @Override
    public Set<BlockPos> footings(ServerLevel level, Worker who, WorldPos target) {
        for (FireHydrantCabinetBlockEntity cabinet : candidates(level, who, target)) {
            if (!sprayFootings(level, who, target, cabinet, false).isEmpty()) {
                Set<BlockPos> pickup = pickupFootings(level, cabinet);
                if (!pickup.isEmpty()) {
                    return pickup;
                }
            }
        }
        return Set.of();
    }

    @Override
    public Optional<Plan> plan(ServerLevel level, Worker who, WorldPos target) {
        for (FireHydrantCabinetBlockEntity cabinet : candidates(level, who, target)) {
            Set<BlockPos> spray = sprayFootings(level, who, target, cabinet);
            if (spray.isEmpty()) {
                spray = sprayFootings(level, who, target, cabinet, false);
            }
            Optional<Stances> pickup = Stances.of(level, pickupFootings(level, cabinet));
            if (!spray.isEmpty() && pickup.isPresent()) {
                UUID hydrant = cabinet.getHydrantId();
                HandheldNozzleType nozzle = cabinet.getNozzleType();
                return Optional.of(new Plan(pickup.get(), spray.stream().map(pos -> WorldPos.of(level, pos))
                    .collect(Collectors.toUnmodifiableSet()),
                    () -> cabinet.getHydrantId().equals(hydrant) && cabinet.getNozzleType() == nozzle
                        ? open(level, who, cabinet) : Optional.empty(),
                    () -> !level.isLoaded(cabinet.getBlockPos()) || cabinet.isRemoved()
                        || level.getBlockEntity(cabinet.getBlockPos()) != cabinet
                        ? DamageControlRefusal.NOT_LOADED : cabinet.hasActiveBinding()
                        ? DamageControlRefusal.BUSY : !cabinet.getFluid().is(Fluids.WATER)
                        ? DamageControlRefusal.NO_WATER : DamageControlRefusal.NO_REACH));
            }
        }
        return Optional.empty();
    }

    static Set<BlockPos> pickupFootings(ServerLevel level, FireHydrantCabinetBlockEntity cabinet) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos feet : Reach.footingsAround(level, cabinet.getBlockPos(), 1, 1)) {
            if (!level.getBlockState(feet).is(Blocks.FIRE) && !level.getBlockState(feet).is(Blocks.SOUL_FIRE)
                && Reach.inSight(level, feet, cabinet.getBlockPos())) {
                cells.add(feet);
            }
        }
        return Set.copyOf(cells);
    }

    static Set<BlockPos> sprayFootings(ServerLevel level, Worker who, WorldPos target,
        FireHydrantCabinetBlockEntity cabinet) {
        return sprayFootings(level, who, target, cabinet, true);
    }

    private static Set<BlockPos> sprayFootings(ServerLevel level, Worker who, WorldPos target,
        FireHydrantCabinetBlockEntity cabinet, boolean reachable) {
        BlockPos fire = target.block(level);
        BlockPos standing = who.body().blockPosition();
        if (Reach.standingAt(who.body().position(), standing)
            && NativeHose.serves(level, cabinet, standing, fire, who.body())) {
            return Set.of(standing);
        }
        BlockPos anchor = cabinet.getBlockPos();
        int span = (int) Math.ceil(NativeHose.BINDING_DISTANCE);
        List<BlockPos> candidates = new ArrayList<>(Reach.footingsAround(level, anchor, span, 2));
        candidates.sort(Comparator.comparingDouble((BlockPos feet) -> feet.distSqr(anchor))
            .thenComparing(Comparator.comparingDouble((BlockPos feet) -> feet.distSqr(fire)).reversed())
            .thenComparingDouble(feet -> Vec3.atBottomCenterOf(feet).distanceToSqr(who.body().position()))
            .thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        // Native createPath updates its navigation's target and stuck timers, even without moveTo.
        // A separate instance keeps speculative work planning out of the resident's active journey.
        var probe = new ResidentPathNavigation(who.body(), level);
        probe.setCanFloat(who.body().getNavigation().canFloat());
        probe.setMaxVisitedNodesMultiplier(FolkwaysVanillaConfig.pathSearchBudget());
        int checked = 0;
        int paths = 0;
        for (BlockPos feet : candidates) {
            if (!NativeHose.inRange(cabinet, feet, fire, who.body())) {
                continue;
            }
            if (++checked > MAX_SPRAY_CHECKS) {
                break;
            }
            if (!NativeHose.serves(level, cabinet, feet, fire, who.body())) {
                continue;
            }
            if (!reachable) {
                return Set.of(feet);
            }
            var path = probe.createPath(feet, 0);
            if (path != null && path.canReach()) {
                return Set.of(feet);
            }
            if (++paths >= MAX_PATH_CHECKS) {
                break;
            }
        }
        return Set.of();
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        return candidates(level, who, target).stream()
            .flatMap(cabinet -> open(level, who, cabinet).stream())
            .findFirst();
    }

    private Optional<SuppressionSession> open(ServerLevel level, Worker who,
        FireHydrantCabinetBlockEntity cabinet) {
        if (who.body().level() != level || !who.body().isAlive() || who.body().isRemoved()
            || !level.isLoaded(cabinet.getBlockPos()) || level.getBlockEntity(cabinet.getBlockPos()) != cabinet
            || !NativeHose.available(level, cabinet)
            || Bodies.of(who.body()).flatMap(body -> body.licences()
                .of(DamageControlContent.trade())).isEmpty()) {
            return Optional.empty();
        }
        UUID colony = Bodies.of(who.body()).flatMap(body -> body.colonyId()).orElseThrow();
        return NativeHoseSession.open(colony, who.body(), level, cabinet,
            () -> Bodies.of(who.body()).filter(body -> body.colonyId().filter(colony::equals).isPresent()
                && body.licences().of(DamageControlContent.trade()).isPresent()).isPresent())
            .map(hose -> new HoseSession(level, cabinet, hose));
    }
}
