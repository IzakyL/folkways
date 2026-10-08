package io.github.izakyl.folkways.core.api;

import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class Relating extends Event implements IModBusEvent {

    private boolean open = true;

    public Relating() {
    }

    public void close() {
        open = false;
    }

    private void writing() {
        if (!open) {
            throw new IllegalStateException("participation declared after Relating completed");
        }
    }

    public void participation(ResourceLocation resident, Stake content, Participation how) {
        writing();
        Participations.register(resident, content, how);
    }

    public void defaultParticipation(ResourceLocation resident, Stake content, Participation how) {
        writing();
        Participations.registerDefault(resident, content, how);
    }
}
