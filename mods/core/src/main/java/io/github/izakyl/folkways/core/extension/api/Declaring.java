package io.github.izakyl.folkways.core.extension.api;

import io.github.izakyl.folkways.core.colony.api.ColonyContext;
import io.github.izakyl.folkways.core.colony.api.UrgeSource;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.travel.api.Passages;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;
import net.neoforged.fml.event.IModBusEvent;

public final class Declaring extends Event implements IModBusEvent {

    private final Consumer<Refinement> refinements;
    private final BiConsumer<ResourceLocation, UrgeSource> urges;
    private final BiConsumer<ResourceLocation, Consumer<ColonyContext>> colonies;

    public Declaring(Consumer<Refinement> refinements, BiConsumer<ResourceLocation, UrgeSource> urges,
            BiConsumer<ResourceLocation, Consumer<ColonyContext>> colonies) {
        this.refinements = refinements;
        this.urges = urges;
        this.colonies = colonies;
    }

    public void refinement(Refinement rule) {
        refinements.accept(rule);
    }

    public void urges(ResourceLocation owner, UrgeSource source) {
        urges.accept(owner, source);
    }

    public void colony(ResourceLocation owner, Consumer<ColonyContext> setup) {
        colonies.accept(owner, setup);
    }

    public void vocation(VocationSpec spec) {
        Vocations.register(spec);
    }

    public void kit(ResourceLocation vocation, ItemSpec tool) {
        Vocations.addKit(vocation, tool);
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
