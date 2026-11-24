package io.github.izakyl.folkways.core.shell.carts;

import com.jusipat.astikorcartsredux.entity.PlowEntity;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public record CartPlowing(PlowEntity plow) implements Signal {

    public interface State {
        void folkways$setPlowing(boolean enabled);
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(plow.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(plow);
    }

    @Override
    public String part() {
        return "plowing/" + plow.getUUID();
    }

    @Override
    public Object leaseKey() {
        return part();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return plow.level() == level && level.getEntity(plow.getUUID()) == plow && plow.isAlive();
    }

    @Override
    public SignalType type() {
        return SignalType.BOOL;
    }

    @Override
    public Optional<Reading> feedback() {
        return Optional.of(level -> valid(level) ? Optional.of(new Value.Bool(plow.getPlowing())) : Optional.empty());
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!valid(level) || !(value instanceof Value.Bool(boolean enabled))) {
            return Write.REFUSED;
        }
        ((State) (Object) plow).folkways$setPlowing(enabled);
        return Write.WRITTEN;
    }
}
