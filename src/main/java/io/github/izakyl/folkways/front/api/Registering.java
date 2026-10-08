package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class Registering extends Event implements IModBusEvent {

    public interface Registry {
        void enrollment(ResourceLocation owner, Enrollment enrollment);
        void admission(ResourceLocation owner, ResourceLocation residentKind, Admission how);
        void facing(ResourceLocation owner, Function<Colony, Optional<Facing>> provider);
    }

    private final Registry registry;
    private boolean open = true;

    public Registering(Registry registry) {
        this.registry = registry;
    }

    public void close() {
        open = false;
    }

    private void writing(Object what) {
        if (!open) {
            throw new IllegalStateException(what + " enrolled after the front stopped listening");
        }
    }

    public void enrollment(ResourceLocation owner, Enrollment enrollment) {
        writing(owner);
        registry.enrollment(owner, enrollment);
    }

    // Who answers for taking in a body of this kind; the colony then holds it for `owner`.
    public void admission(ResourceLocation owner, ResourceLocation residentKind, Admission how) {
        writing(residentKind);
        registry.admission(owner, residentKind, how);
    }

    public void facing(ResourceLocation owner, Function<Colony, Optional<Facing>> provider) {
        writing(owner);
        registry.facing(owner, provider);
    }
}
