package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.plugins.damagecontrol.DamageControlRefusal;
import io.github.izakyl.folkways.plugins.damagecontrol.SuppressionSession;
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
    private long sprayedAt = Long.MIN_VALUE;
    private boolean closed;

    HoseSession(UUID colony, Mob actor, ServerLevel level, FireHydrantCabinetBlockEntity cabinet) {
        this.colony = colony;
        this.actor = actor;
        this.level = level;
        this.cabinet = cabinet;
        hydrantId = cabinet.getHydrantId();
        nozzle = cabinet.getNozzleType();
    }

    UUID colony() { return colony; }
    Mob actor() { return actor; }
    FireHydrantCabinetBlockEntity cabinet() { return cabinet; }
    UUID hydrantId() { return hydrantId; }

    boolean valid() {
        return !closed && Bodies.of(actor).filter(body -> body.colonyId().filter(colony::equals).isPresent()
                && body.licences().of(DamageControlContent.trade()).isPresent()).isPresent()
            && actor.isAlive() && !actor.isRemoved() && actor.level() == level
            && level.isLoaded(cabinet.getBlockPos()) && !cabinet.isRemoved()
            && level.getBlockEntity(cabinet.getBlockPos()) == cabinet && cabinet.getHydrantId().equals(hydrantId)
            && cabinet.isBoundTo(actor.getUUID()) && cabinet.canServeHandheldNozzle() && cabinet.getNozzleType() == nozzle
            && cabinet.getFluid().is(Fluids.WATER)
            && actor.position().distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos()))
                <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR;
    }

    @Override
    public Outcome suppress(ServerLevel world, BlockPos target) {
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
        if (sprayedAt == level.getGameTime()) {
            return Outcome.failed(DamageControlRefusal.BUSY);
        }
        Vec3 direction = Vec3.atCenterOf(target).subtract(actor.getEyePosition()).normalize();
        Vec3 origin = NativeSprayBridge.hoseOrigin(actor.getEyePosition(), direction);
        if (!NativeSprayBridge.hoseReaches(level, nozzle, origin, direction, target)) {
            return Outcome.failed(DamageControlRefusal.NO_REACH);
        }
        var water = cabinet.drainForHandheldSpray(10,
            IFluidHandler.FluidAction.SIMULATE);
        if (!water.is(Fluids.WATER)) {
            close();
            return Outcome.failed(DamageControlRefusal.NO_WATER);
        }
        sprayedAt = level.getGameTime();
        water = cabinet.drainForHandheldSpray(10,
            IFluidHandler.FluidAction.EXECUTE);
        NativeSprayBridge.hose(level, actor, cabinet, origin, direction, target, water);
        ResidentSprayPacket.send(actor, cabinet, origin, direction, true);
        return Outcome.done();
    }

    @Override
    public void close() { close(true); }

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
