package io.github.izakyl.folkways.airline;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class AirportTower extends BlockEntity {
    UUID dutyColony;
    volatile UUID dutyWork;

    public AirportTower(BlockPos pos, BlockState state) {
        super(AirlineContent.AIRPORT_TOWER_ENTITY.get(), pos, state);
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel server) {
            TowerDuty.withdraw(this, server);
        }
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel server) {
            TowerDuty.withdraw(this, server);
        }
        super.onChunkUnloaded();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server && Airports.validTower(server, worldPosition)) {
            Airports.request(server, worldPosition);
        }
    }
}
