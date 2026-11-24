package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class PersonContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "person");

    public static void declare(io.github.izakyl.folkways.core.extension.api.Declaring declaring) {
        declaring.colony(ID, scope -> {
            PersonPresence state = new PersonPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
        });
    }

    public static void enroll(Registering registering) {
        registering.panel(ID, colony -> colony.service(ID, PersonPresence.class).map(ColonyPanel.class::cast));
        registering.enrollment(ID,
            new Enrollment(List.of(), Schema.none()));
        registering.admission(ID, PersonBody.ID, new Immigration());
    }
}
