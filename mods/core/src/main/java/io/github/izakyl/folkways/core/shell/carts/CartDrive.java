package io.github.izakyl.folkways.core.shell.carts;

import com.jusipat.astikorcartsredux.entity.AbstractDrawnEntity;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;

record CartDrive(AbstractDrawnEntity cart) implements Signal {

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(cart.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(cart);
    }

    @Override
    public String part() {
        return "destination/" + cart.getUUID();
    }

    @Override
    public Object leaseKey() {
        return cart.getUUID();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return cart.level() == level && level.getEntity(cart.getUUID()) == cart && cart.isAlive();
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        if (cart.getPulling() instanceof AbstractHorse horse) {
            return Optional.of(new Stances.Seat(horse.getUUID(), 0));
        }
        return cart.getPulling() instanceof Mob puller ? Optional.of(Stances.at(WorldSpaces.at(puller)))
            : Optional.empty();
    }

    @Override
    public boolean admits(Mob writer) {
        return cart.getPulling() instanceof AbstractHorse || cart.getPulling() == writer;
    }

    @Override
    public boolean enter(Mob writer) {
        var puller = cart.getPulling();
        return puller == writer || puller instanceof AbstractHorse && writer.getVehicle() == puller;
    }

    @Override
    public Cost cost() {
        return Cost.REMOTE;
    }

    @Override
    public SignalType type() {
        return SignalType.BLOCK_POSITION;
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (writer != null && !enter(writer)) {
            return Write.REFUSED;
        }
        var cell = Value.blockPosition(value).orElse(null);
        if (!valid(level) || cell == null || !level.isInWorldBounds(cell) || !level.hasChunkAt(cell)
                || !(cart.getPulling() instanceof Mob puller)
                || puller.getControllingPassenger() instanceof Player || cart.getControllingPassenger() instanceof Player) {
            return Write.REFUSED;
        }
        return puller.getNavigation().moveTo(cell.getX() + .5, cell.getY(), cell.getZ() + .5, 1)
            ? Write.WRITTEN : Write.REFUSED;
    }
}
