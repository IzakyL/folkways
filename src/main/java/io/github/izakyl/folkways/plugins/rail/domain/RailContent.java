package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.perk.Perk;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.VocationSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.plugins.CreateMod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public final class RailContent {

    public static final ResourceLocation TAKING_SEAT = ResourceLocation.fromNamespaceAndPath("folkways", "taking_seat");

    public static final ResourceLocation DRIVING = ResourceLocation.fromNamespaceAndPath("folkways", "driving");

    public static final ResourceLocation BOARDING = ResourceLocation.fromNamespaceAndPath("folkways", "boarding");

    public static final ResourceLocation RIDING = ResourceLocation.fromNamespaceAndPath("folkways", "riding");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "rail");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "conducting");

    public static final String QUICK_HANDS = "conducting_quick_hands";

    static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.rail",
        ResourceLocation.withDefaultNamespace("minecart"));

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(new Perk(QUICK_HANDS, 4)));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::conductingHaste);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        if (CreateMod.loaded()) {
            TransitNetworks.install(new CreateTransitNetwork());
        }
        declaring.vocation(SPEC);
        declaring.colony(ID, scope -> {
            RailPresence state = new RailPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, RailPresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID, new Enrollment(List.of(), Schema.none(), List.of(PAGE)));
    }

    static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    public static Optional<RailPresence> holderOf(MinecraftServer server, UUID train) {
        for (Colony colony : Colonies.all(server)) {
            Optional<RailPresence> trains = presenceIn(colony.service(ID, Object.class));
            if (trains.isPresent() && trains.get().holds(train)) {
                return trains;
            }
        }
        return Optional.empty();
    }

    public static Optional<RailPresence> presenceIn(Optional<?> works) {
        return works.filter(RailPresence.class::isInstance).map(RailPresence.class::cast);
    }
}
