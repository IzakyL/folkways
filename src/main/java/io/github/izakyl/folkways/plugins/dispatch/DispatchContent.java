package io.github.izakyl.folkways.plugins.dispatch;

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
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Registering;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.plugins.CreateMod;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

public final class DispatchContent {

    public static final ResourceLocation COLLECTING = ResourceLocation.fromNamespaceAndPath("folkways", "collecting");

    public static final ResourceLocation TIDYING = ResourceLocation.fromNamespaceAndPath("folkways", "tidying");

    public static final ResourceLocation KEEPING_POST = ResourceLocation.fromNamespaceAndPath("folkways", "keeping_post");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "dispatch");

    public static final ResourceLocation PORT =
        ResourceLocation.fromNamespaceAndPath("folkways", "port");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways", "dispatching");

    public static final String QUICK_HANDS = "dispatch_quick_hands";

    static final PageKind PAGE = PageKind.boarded(ID, "folkways.page.dispatch",
        ResourceLocation.withDefaultNamespace("chest_minecart"));

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(new Perk(QUICK_HANDS, 4)));

    private static final Workload.Haste HASTE =
        Workload.Haste.ranked(QUICK_HANDS, FolkwaysConfig::dispatchHaste);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        if (CreateMod.loaded()) {
            PackageNetworks.install(new CreatePackageNetwork());
        }
        declaring.vocation(SPEC);
        declaring.colony(ID, scope -> {
            DispatchPresence state = new DispatchPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.facing(ID, colony -> colony.service(ID, DispatchPresence.class)
            .map(value -> (io.github.izakyl.folkways.front.api.Facing) value));
        registering.enrollment(ID, new Enrollment(
            List.of(new Delegation(PORT, new Shape.Block(state -> PackageNetworks.get().isPort(state)))),
            Schema.none(), List.of(PAGE)));
    }

    static Vocation trade() {
        return Vocations.required(VOCATION);
    }

    static Optional<DispatchPresence> holderOf(MinecraftServer server, UUID network) {
        for (Colony colony : Colonies.all(server)) {
            Optional<DispatchPresence> dispatch = presenceIn(colony.service(ID, Object.class));
            if (dispatch.isPresent() && dispatch.get().holds(network)) {
                return dispatch;
            }
        }
        return Optional.empty();
    }

    static Optional<DispatchPresence> presenceIn(Optional<?> works) {
        return works.filter(DispatchPresence.class::isInstance).map(DispatchPresence.class::cast);
    }
}
