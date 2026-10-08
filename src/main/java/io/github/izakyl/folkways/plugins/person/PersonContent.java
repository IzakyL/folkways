package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class PersonContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "person");

    public static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.person",
        ResourceLocation.withDefaultNamespace("bell"));

    public static final PageKind LOOKS = PageKind.boarded(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "looks"),
        "folkways.tab.looks", ResourceLocation.withDefaultNamespace("player_head"));

    public static final PageKind NAMES = PageKind.boarded(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "names"),
        "folkways.tab.names", ResourceLocation.withDefaultNamespace("name_tag"));

    public static void declare(io.github.izakyl.folkways.core.api.Declaring declaring) {
        declaring.colony(ID, scope -> {
            PersonPresence state = new PersonPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, PersonPresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID,
            new Enrollment(List.of(), Schema.none(), List.of(PAGE, NAMES, LOOKS)));
        registering.admission(ID, PersonBody.ID, new Immigration());
    }
}
