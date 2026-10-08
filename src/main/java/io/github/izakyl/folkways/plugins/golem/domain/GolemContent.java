package io.github.izakyl.folkways.plugins.golem.domain;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.ResidentKind;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Shape;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

public final class GolemContent {

    public static final ResourceLocation MENDING = ResourceLocation.fromNamespaceAndPath("folkways", "mending");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "golem");

    public static final ResourceLocation BODY =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "golem_body");

    public static void declare(Declaring declaring) {
        declaring.colony(ID, scope -> {
            GolemPresence state = new GolemPresence();
            scope.share(state);
            scope.urges(state::urges);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, GolemPresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID,
            new Enrollment(List.of(new Delegation(BODY, new Shape.Body(GolemContent::ships)))));
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
