package io.github.izakyl.folkways.create.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerItem;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Reach;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
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
        int radius = (int) Math.ceil(range() + HandheldNozzleBindingApi.MAX_BINDING_DISTANCE + 2);
        BlockPos fire = target.block(level);
        for (FireHydrantCabinetBlockEntity cabinet : Hydrants.nearby(level, fire, radius)) {
            if (cabinet.canServeHandheldNozzle() && !cabinet.hasActiveBinding() && cabinet.getFluid().is(Fluids.WATER)
                && !HandheldNozzleControllerEntity.hasActiveBinding(level.dimension(), cabinet.getBlockPos(),
                    cabinet.getHydrantId())
                && Vec3.atCenterOf(cabinet.getBlockPos()).distanceToSqr(Vec3.atCenterOf(fire))
                <= Math.pow(HandheldNozzleBindingApi.MAX_BINDING_DISTANCE
                    + NativeSprayBridge.hoseRange(cabinet.getNozzleType()) + 2, 2)) {
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
        return Math.max(NativeSprayBridge.hoseRange(HandheldNozzleType.CONE),
            NativeSprayBridge.hoseRange(HandheldNozzleType.FLAT));
    }

    private static boolean inRange(FireHydrantCabinetBlockEntity cabinet, BlockPos feet, BlockPos fire, Worker who) {
        Vec3 base = Vec3.atBottomCenterOf(feet);
        return SprayFootings.within(base, Vec3.atCenterOf(cabinet.getBlockPos()),
            HandheldNozzleBindingApi.MAX_BINDING_DISTANCE)
            && SprayFootings.within(base.add(0, who.body().getEyeHeight(), 0), Vec3.atCenterOf(fire),
                NativeSprayBridge.hoseRange(cabinet.getNozzleType()));
    }

    private static boolean serves(ServerLevel level, FireHydrantCabinetBlockEntity cabinet, BlockPos feet,
        BlockPos fire, Worker who) {
        return inRange(cabinet, feet, fire, who) && SprayFootings.serves(level, who.body(), feet, fire,
            NativeSprayBridge.hoseRange(cabinet.getNozzleType()), true);
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
            && serves(level, cabinet, standing, fire, who)) {
            return Set.of(standing);
        }
        BlockPos anchor = cabinet.getBlockPos();
        int span = (int) Math.ceil(HandheldNozzleBindingApi.MAX_BINDING_DISTANCE);
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
            if (!inRange(cabinet, feet, fire, who)) {
                continue;
            }
            if (++checked > MAX_SPRAY_CHECKS) {
                break;
            }
            if (!serves(level, cabinet, feet, fire, who)) {
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
    public boolean reaches(ServerLevel level, Worker who, WorldPos target) {
        return candidates(level, who, target).stream().anyMatch(cabinet -> adjacent(level, who, cabinet));
    }

    private static boolean adjacent(ServerLevel level, Worker who, FireHydrantCabinetBlockEntity cabinet) {
        return Reach.stanceHolding(who.body().position(), pickupFootings(level, cabinet)).isPresent()
            && who.body().position().distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos())) <= 6.25
            && Reach.inSight(level, who.body().blockPosition(), cabinet.getBlockPos());
    }

    @Override
    public Optional<SuppressionSession> open(ServerLevel level, Worker who, WorldPos target) {
        if (who.body().level() != level || !who.body().isAlive() || who.body().isRemoved()) {
            return Optional.empty();
        }
        for (FireHydrantCabinetBlockEntity cabinet : candidates(level, who, target)) {
            if (!adjacent(level, who, cabinet)
                || sprayFootings(level, who, target, cabinet).isEmpty()
                || Bodies.of(who.body()).flatMap(body -> body.licences()
                    .of(DamageControlContent.trade())).isEmpty()) {
                continue;
            }
            return open(level, who, cabinet);
        }
        return Optional.empty();
    }

    private Optional<SuppressionSession> open(ServerLevel level, Worker who,
        FireHydrantCabinetBlockEntity cabinet) {
        if (who.body().level() != level || !who.body().isAlive() || who.body().isRemoved()
            || !level.isLoaded(cabinet.getBlockPos()) || level.getBlockEntity(cabinet.getBlockPos()) != cabinet
            || !cabinet.canServeHandheldNozzle() || cabinet.hasActiveBinding()
            || !cabinet.getFluid().is(Fluids.WATER)
            || HandheldNozzleControllerEntity.hasActiveBinding(level.dimension(), cabinet.getBlockPos(),
                cabinet.getHydrantId())
            || !adjacent(level, who, cabinet)
            || Bodies.of(who.body()).flatMap(body -> body.licences()
                .of(DamageControlContent.trade())).isEmpty()) {
            return Optional.empty();
        }
        UUID colony = Bodies.of(who.body()).flatMap(body -> body.colonyId()).orElseThrow();
        HoseSession session = new HoseSession(colony, who.body(), level, cabinet);
        if (!HoseSessions.add(session)) {
            return Optional.empty();
        }
        if (!((ResidentHydrantBinding) cabinet).folkways$bindResident(who.body().getUUID())) {
            HoseSessions.remove(session);
            return Optional.empty();
        }
        ItemStack picture = new ItemStack(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get());
        HandheldNozzleControllerItem.bind(picture, level, cabinet.getBlockPos(), cabinet.getHydrantId(),
            cabinet.getNozzleType());
        Bodies.of(who.body()).ifPresent(body -> body.presentHeldItem(picture));
        who.body().swing(InteractionHand.MAIN_HAND);
        ResidentSprayPacket.connection(who.body(), cabinet);
        return Optional.of(session);
    }
}
