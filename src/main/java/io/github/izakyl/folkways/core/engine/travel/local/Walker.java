package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.resident.Locomotion;
import io.github.izakyl.folkways.core.api.resident.Locomotions;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;

record Walker(Kind kind, Locomotion locomotion, Mob body) {

    record Kind(ResourceLocation locomotion, int width, int height) {
    }

    // A body the colony has no kind for is surveyed as if it walked, when anything walks at all.
    static Optional<Walker> of(Mob body) {
        return Bodies.of(body).map(Body::kind).map(kind -> kind.locomotion())
            .or(() -> Locomotions.of(Locomotions.WALKING))
            .map(locomotion -> new Walker(new Kind(locomotion.id(), Mth.floor(body.getBbWidth() + 1.0F),
                Mth.floor(body.getBbHeight() + 1.0F)), locomotion, body));
    }
}
