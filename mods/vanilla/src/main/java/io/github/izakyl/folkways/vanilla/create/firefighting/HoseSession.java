package io.github.izakyl.folkways.vanilla.create.firefighting;

import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import io.github.izakyl.folkways.core.shell.create.firefighting.NativeHoseSession;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlRefusal;
import io.github.izakyl.folkways.vanilla.damagecontrol.SuppressionSession;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class HoseSession implements SuppressionSession {
    private final ServerLevel level;
    private final FireHydrantCabinetBlockEntity cabinet;
    private final NativeHoseSession hose;
    private boolean performed;

    HoseSession(ServerLevel level, FireHydrantCabinetBlockEntity cabinet, NativeHoseSession hose) {
        this.level = level;
        this.cabinet = cabinet;
        this.hose = hose;
    }

    UUID hydrantId() {
        return hose.hydrantId();
    }

    @Override
    public boolean valid() {
        return hose.valid();
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
        switch (hose.spray(target)) {
            case BUSY -> {
                return Outcome.failed(DamageControlRefusal.BUSY);
            }
            case NO_WATER -> {
                return Outcome.failed(DamageControlRefusal.NO_WATER);
            }
            case SPRAYED -> performed = true;
        }
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
        hose.close();
    }
}
