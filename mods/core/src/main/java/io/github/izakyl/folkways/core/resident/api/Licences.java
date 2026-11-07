package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

public interface Licences extends INBTSerializable<CompoundTag> {

    final class Trade {

        private final Vocation vocation;

        public Trade(Vocation vocation) {
            this.vocation = vocation;
        }

        public Vocation vocation() {
            return vocation;
        }
    }

    Optional<Trade> trade(Vocation vocation);

    Optional<Licence> of(Vocation vocation);

    boolean allows(Trade trade);

    void allow(Trade trade, boolean allowed);

    Keenness keennessOf(Trade trade);

    void setKeenness(Trade trade, Keenness how);

    long revision();
}
