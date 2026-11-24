package io.github.izakyl.folkways.core.shell.golems.domain;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.ResidentKind;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.resident.api.Stake;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

public final class GolemContent {

    public static final ResourceLocation MENDING = ResourceLocation.fromNamespaceAndPath("folkways_core", "mending");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "golem");

    public static final ResourceLocation BODY =
        ResourceLocation.fromNamespaceAndPath("folkways_core", "golem_body");

    public static void declare(Declaring declaring) {
        declaring.colony(ID, scope -> {
            GolemPresence state = new GolemPresence(scope.colony());
            scope.share(state);
            scope.urges(state::urges);
        });
    }

    public static void enroll(Registering registering) {
        registering.residentLines(ID, colony -> colony.service(ID, GolemPresence.class).map(value -> value));
        registering.enrollment(ID,
            new Enrollment(List.of(new Delegation(BODY, new Shape.Body(GolemContent::ships),
                ResourceLocation.fromNamespaceAndPath("modulargolems", "metal_golem_holder")))));
    }

    static boolean ships(EntityType<?> type) {
        ResourceLocation body = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return ResidentKinds.of(body)
            .map(ResidentKind::id)
            .filter(declared -> Participations.between(declared, Stake.urge(ID))
                != Participation.NONE)
            .isPresent();
    }

}
