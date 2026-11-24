package io.github.izakyl.folkways.core.shell.create.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import com.mikoalopex.createfirefightingadd.api.handheld.HandheldNozzleBindingApi;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleControllerItem;
import com.mikoalopex.createfirefightingadd.content.equipment.handheld.HandheldNozzleType;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

public final class NativeHoseSession {
    private final UUID colony;
    private final Mob actor;
    private final ServerLevel level;
    private final FireHydrantCabinetBlockEntity cabinet;
    private final UUID hydrantId;
    private final HandheldNozzleType nozzle;
    private final BooleanSupplier eligible;
    private boolean closed;

    private NativeHoseSession(UUID colony, Mob actor, ServerLevel level, FireHydrantCabinetBlockEntity cabinet,
        BooleanSupplier eligible) {
        this.eligible = eligible;
        this.colony = colony;
        this.actor = actor;
        this.level = level;
        this.cabinet = cabinet;
        hydrantId = cabinet.getHydrantId();
        nozzle = cabinet.getNozzleType();
    }

    public static Optional<NativeHoseSession> open(UUID colony, Mob actor, ServerLevel level,
        FireHydrantCabinetBlockEntity cabinet, BooleanSupplier eligible) {
        NativeHoseSession session = new NativeHoseSession(colony, actor, level, cabinet, eligible);
        if (!HoseSessions.add(session)) {
            return Optional.empty();
        }
        if (!((ResidentHydrantBinding) cabinet).folkways$bindResident(actor.getUUID())) {
            HoseSessions.remove(session);
            return Optional.empty();
        }
        ItemStack picture = new ItemStack(CreateFireFightingAdd.HANDHELD_NOZZLE_CONTROLLER_ITEM.get());
        HandheldNozzleControllerItem.bind(picture, level, cabinet.getBlockPos(), cabinet.getHydrantId(),
            cabinet.getNozzleType());
        Bodies.of(actor).ifPresent(body -> body.presentHeldItem(picture));
        actor.swing(InteractionHand.MAIN_HAND);
        ResidentSprayPacket.connection(actor, cabinet);
        return Optional.of(session);
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

    public UUID hydrantId() {
        return hydrantId;
    }

    public boolean valid() {
        return !closed && eligible.getAsBoolean()
            && actor.isAlive() && !actor.isRemoved() && actor.level() == level
            && level.isLoaded(cabinet.getBlockPos()) && !cabinet.isRemoved()
            && level.getBlockEntity(cabinet.getBlockPos()) == cabinet && cabinet.getHydrantId().equals(hydrantId)
            && cabinet.isBoundTo(actor.getUUID()) && cabinet.canServeHandheldNozzle()
            && cabinet.getNozzleType() == nozzle
            && cabinet.getFluid().is(Fluids.WATER)
            && actor.position().distanceToSqr(Vec3.atCenterOf(cabinet.getBlockPos()))
            <= HandheldNozzleBindingApi.MAX_BINDING_DISTANCE_SQR;
    }

    public enum Spray { SPRAYED, BUSY, NO_WATER }

    public Spray spray(BlockPos target) {
        if (!NativeSprayBridge.hoseReady(actor)) {
            return Spray.BUSY;
        }
        Vec3 direction = Vec3.atCenterOf(target).subtract(actor.getEyePosition()).normalize();
        Vec3 origin = NativeSprayBridge.hoseOrigin(actor.getEyePosition(), direction);
        var water = NativeSprayBridge.drainHose(cabinet,
            IFluidHandler.FluidAction.SIMULATE);
        if (!water.is(Fluids.WATER)) {
            close();
            return Spray.NO_WATER;
        }
        NativeSprayBridge.hosePaid(actor);
        water = NativeSprayBridge.drainHose(cabinet,
            IFluidHandler.FluidAction.EXECUTE);
        NativeSprayBridge.hose(level, actor, cabinet, origin, direction, water);
        ResidentSprayPacket.send(actor, cabinet, origin, direction, true);
        return Spray.SPRAYED;
    }

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
