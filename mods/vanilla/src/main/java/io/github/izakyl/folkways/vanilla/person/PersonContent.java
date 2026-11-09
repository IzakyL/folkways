package io.github.izakyl.folkways.vanilla.person;

import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class PersonContent {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "person");

    public static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.person",
        ResourceLocation.withDefaultNamespace("bell"));

    public static final PageKind LOOKS = PageKind.boarded(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "looks"),
        "folkways.tab.looks", ResourceLocation.withDefaultNamespace("player_head"));

    public static final PageKind NAMES = PageKind.boarded(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "names"),
        "folkways.tab.names", ResourceLocation.withDefaultNamespace("name_tag"));

    public static final PageKind WORK_DEFAULTS = PageKind.boarded(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "work_defaults"),
        "folkways.tab.work_defaults", ResourceLocation.withDefaultNamespace("iron_pickaxe"));

    public static void declare(io.github.izakyl.folkways.core.extension.api.Declaring declaring) {
        declaring.colony(ID, scope -> {
            PersonPresence state = new PersonPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, PersonPresence.class)
            .map(value -> (io.github.izakyl.folkways.core.front.api.Facing) value));
        registering.enrollment(ID,
            new Enrollment(List.of(), Schema.none(), List.of(PAGE, NAMES, LOOKS, WORK_DEFAULTS)));
        registering.admission(ID, PersonBody.ID, new Immigration());
    }
}
