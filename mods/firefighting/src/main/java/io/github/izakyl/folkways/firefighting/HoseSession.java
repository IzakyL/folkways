package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlRefusal;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionSession;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

final class HoseSession implements SuppressionSession {
    private final UUID colony;
    private final Mob actor;
    private final ServerLevel level;
    private final FireHydrantCabinetBlockEntity cabinet;
    private final UUID hydrantId;
    private final HandheldNozzleType nozzle;
    private boolean closed;
    private boolean performed;

    HoseSession(UUID colony, Mob actor, ServerLevel level, FireHydrantCabinetBlockEntity cabinet) {
        this.colony = colony;
        this.actor = actor;
        this.level = level;
        this.cabinet = cabinet;
        hydrantId = cabinet.getHydrantId();
        nozzle = cabinet.getNozzleType();
    }

    UUID colony() {
        return colony;
    }

    Mob actor() {
        return actor;
    }

    FireHydrantCabinetBlockEntity cabinet() {
        return cabinet;
    }

    UUID hydrantId() {
        return hydrantId;
    }

    @Override
    public boolean valid() {
        return !closed && Bodies.of(actor).filter(body -> body.colonyId().filter(colony::equals).isPresent()
            && body.licences().of(DamageControlContent.trade()).isPresent()).isPresent()
            && actor.isAlive() && !actor.isRemoved() && actor.level() == level
            && level.isLoaded(cabinet.getBlockPos()) && !cabinet.isRemoved()
            && level.getBlockEntity(cabinet.getBlockPos()) == cabinet && cabinet.getHydrantId().equals(hydrantId)
            && cabinet.isBoundTo(actor.getUUID()) && cabinet.canServeHandheldNozzle()
            && cabinet.getNozzleType() == nozzle
            && cabinet.getFluid().is(Fluids.WATER)
            && actor.position().distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos()))
            <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR;
    }

    @Override
    public Set<BlockPos> footings(ServerLevel world, Worker who, WorldPos target) {
        return world == level && valid() ? HoseAdapter.sprayFootings(level, who, target, cabinet) : Set.of();
    }

    @Override
    public Outcome suppress(ServerLevel world, BlockPos target) {
        performed = false;
        if (world != level || !valid()) {
            close();
            return Outcome.failed(DamageControlRefusal.NOT_LOADED);
        }
        if (!level.isLoaded(target)) {
            return Outcome.failed(DamageControlRefusal.NOT_LOADED);
        }
        var state = level.getBlockState(target);
        if (!state.is(Blocks.FIRE) && !state.is(Blocks.SOUL_FIRE)) {
            return Outcome.done();
        }
        if (!NativeSprayBridge.hoseReady(actor)) {
            return Outcome.failed(DamageControlRefusal.BUSY);
        }
        Vec3 direction = Vec3.atCenterOf(target).subtract(actor.getEyePosition()).normalize();
        Vec3 origin = NativeSprayBridge.hoseOrigin(actor.getEyePosition(), direction);
        var water = NativeSprayBridge.drainHose(cabinet,
            IFluidHandler.FluidAction.SIMULATE);
        if (!water.is(Fluids.WATER)) {
            close();
            return Outcome.failed(DamageControlRefusal.NO_WATER);
        }
        NativeSprayBridge.hosePaid(actor);
        water = NativeSprayBridge.drainHose(cabinet,
            IFluidHandler.FluidAction.EXECUTE);
        NativeSprayBridge.hose(level, actor, cabinet, origin, direction, water);
        performed = true;
        ResidentSprayPacket.send(actor, cabinet, origin, direction, true);
        var after = level.getBlockState(target);
        return !after.is(Blocks.FIRE) && !after.is(Blocks.SOUL_FIRE)
            ? Outcome.done() : Outcome.failed(DamageControlRefusal.NO_REACH);
    }

    @Override
    public boolean performed() {
        return performed;
    }

    @Override
    public void close() {
        close(true);
    }

    void close(boolean notify) {
        if (closed) {
            return;
        }
        closed = true;
        HoseSessions.remove(this);
        ((ResidentHydrantBinding) cabinet).folkways$unbindResident(actor.getUUID(), notify
            && !cabinet.isRemoved() && level.isLoaded(cabinet.getBlockPos())
            && level.getBlockEntity(cabinet.getBlockPos()) == cabinet);
        NativeSprayBridge.stopHose(level, actor);
        ResidentSprayPacket.send(actor, cabinet, actor.getEyePosition(), Vec3.ZERO, false);
        Bodies.of(actor).ifPresent(body -> body.clearHandPresentation());
    }
}
