package io.github.izakyl.folkways.vanilla.create.rail.domain;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.read.Railways;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.vanilla.create.VanillaCreateConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public final class RailContent {


    public static final ResourceLocation DRIVING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "driving");

    public static final ResourceLocation BOARDING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "boarding");

    public static final ResourceLocation RIDING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "riding");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "rail");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "conducting");

    public static final String QUICK_HANDS = "conducting_quick_hands";

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(
            new Perk(QUICK_HANDS, 4, Perk.Grant.haste(VOCATION, VanillaCreateConfig::conductingHaste))));

    private static final Workload.Haste HASTE = Workload.Haste.of(VOCATION);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        declaring.vocation(SPEC.ordered(200).grouped(VocationGroup.TRANSPORT));
        Posts.register((level, colony) -> colony.service(ID, RailPresence.class)
            .map(state -> state.posts(level)).orElseGet(List::of));
        declaring.colony(ID, scope -> {
            RailPresence state = new RailPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        if (CreateRailways.available()) {
            Railways.install(ID, new CreateRailways());
        }
        Operations.register((colony, id) -> colony.service(ID, RailPresence.class)
            .flatMap(presence -> presence.operation(id)));
        Operations.listing(colony -> colony.service(ID, RailPresence.class)
            .map(RailPresence::operations).orElseGet(List::of));
        registering.enrollment(ID, new Enrollment(List.of(), Schema.none()));
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
