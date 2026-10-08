package io.github.izakyl.folkways.core.api;

import io.github.izakyl.folkways.core.api.colony.ColonyContext;
import io.github.izakyl.folkways.core.api.colony.Contributions;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.passage.Passages;
import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Refinements;
import io.github.izakyl.folkways.core.api.work.UrgeSource;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class Declaring extends Event implements IModBusEvent {

    public Declaring() {
    }

    public void refinement(Refinement rule) {
        Refinements.register(rule);
    }

    public void urges(ResourceLocation owner, UrgeSource source) {
        Contributions.urges(owner, source);
    }

    public void colony(ResourceLocation owner, Consumer<ColonyContext> setup) {
        Contributions.colony(owner, setup);
    }

    public void vocation(VocationSpec spec) {
        Vocations.register(spec);
    }

    public void locomotion(Locomotion gait) {
        Locomotions.register(gait);
    }

    public void residentKind(ResidentKind kind) {
        ResidentKinds.register(kind);
    }

    public void passage(Passage passage) {
        Passages.register(passage);
    }
}
