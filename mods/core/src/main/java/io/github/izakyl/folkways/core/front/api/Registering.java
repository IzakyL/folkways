package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class Registering extends Event implements IModBusEvent {

    public interface Registry {
        void enrollment(ResourceLocation owner, Enrollment enrollment);

        void admission(ResourceLocation owner, ResourceLocation residentKind, Admission how);

        void residentLines(ResourceLocation owner, Function<Colony, Optional<ResidentLines>> provider);

        void ghostSource(ResourceLocation owner, Function<Colony, Optional<GhostSource>> provider);

        void memberPointing(ResourceLocation owner, Function<Colony, Optional<MemberPointing>> provider);

        void holding(ResourceLocation delegation, Function<Colony, Optional<HoldingKind>> kind);

        void panel(ResourceLocation owner, Function<Colony, Optional<ColonyPanel>> panel);
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

    public void residentLines(ResourceLocation owner, Function<Colony, Optional<ResidentLines>> provider) {
        writing(owner);
        registry.residentLines(owner, provider);
    }

    public void ghostSource(ResourceLocation owner, Function<Colony, Optional<GhostSource>> provider) {
        writing(owner);
        registry.ghostSource(owner, provider);
    }

    public void memberPointing(ResourceLocation owner, Function<Colony, Optional<MemberPointing>> provider) {
        writing(owner);
        registry.memberPointing(owner, provider);
    }

    // What a module makes of each thing the colony holds under this delegation; several modules may add to one.
    public void holding(ResourceLocation delegation, Function<Colony, Optional<HoldingKind>> kind) {
        writing(delegation);
        registry.holding(delegation, kind);
    }

    // What the module shows on the book's own pages.
    public void panel(ResourceLocation owner, Function<Colony, Optional<ColonyPanel>> panel) {
        writing(owner);
        registry.panel(owner, panel);
    }
}
