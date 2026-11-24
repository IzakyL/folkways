package io.github.izakyl.folkways.vanilla.create.dispatch;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.SupplySources;
import io.github.izakyl.folkways.core.effect.perk.Perk;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.inventory.api.InventoryProviders;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.core.shell.create.dispatch.PackageNetworks;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class DispatchContent {

    public static final ResourceLocation COLLECTING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "collecting");

    public static final ResourceLocation TIDYING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "tidying");

    public static final ResourceLocation PACKING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "packing");

    public static final ResourceLocation KEEPING_POST = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "keeping_post");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "dispatch");

    public static final ResourceLocation PORT =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "port");

    public static final ResourceLocation VOCATION =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "dispatching");

    public static final String QUICK_HANDS = "dispatch_quick_hands";

    private static final VocationSpec SPEC =
        new VocationSpec(VOCATION, List.of(
            new Perk(QUICK_HANDS, 4, Perk.Grant.haste(VOCATION, VanillaCreateConfig::dispatchHaste))));

    private static final Workload.Haste HASTE = Workload.Haste.of(VOCATION);

    public static double haste(Worker who) {
        return HASTE.of(who);
    }

    public static void declare(Declaring declaring) {
        var network = PackageNetworks.get();
        Posts.register((level, colony) -> colony.service(ID, DispatchPresence.class)
            .map(state -> state.keeperPosts(level)).orElseGet(List::of));
        InventoryProviders.register(ID, new DispatchInventory());
        SupplySources.install((colony, item) -> colony.service(ID, DispatchPresence.class)
            .map(state -> state.sources(item)).orElse(List.of()));
        BuiltInRegistries.BLOCK.stream().filter(block -> network.isPostbox(block.defaultBlockState()))
                .forEach(block -> Mechanics.station(Mechanics.Station.block(block, PORT, VOCATION, List.of(),
                        List.of(Component.translatable("folkways.mechanics.dispatch")))));
        declaring.vocation(SPEC.ordered(300).grouped(VocationGroup.TRANSPORT));
        declaring.colony(ID, scope -> {
            DispatchPresence state = new DispatchPresence(scope.colony());
            scope.share(state);
            scope.tick(state::tick);
            scope.closed(state::closed);
        });
    }

    public static void enroll(Registering registering) {
        registering.residentLines(ID, colony -> colony.service(ID, DispatchPresence.class).map(value -> value));
        registering.memberPointing(ID, colony -> colony.service(ID, DispatchPresence.class).map(value -> value));
        registering.holding(PORT, colony -> colony.service(ID, DispatchPresence.class).map(HoldingKind.class::cast));
        registering.panel(ID, colony -> colony.service(ID, DispatchPresence.class).map(ColonyPanel.class::cast));
        Operations.register((colony, id) -> colony.service(ID, DispatchPresence.class)
            .flatMap(presence -> presence.operation(id)));
        Operations.listing(colony -> colony.service(ID, DispatchPresence.class)
            .map(DispatchPresence::operations).orElseGet(List::of));
        registering.enrollment(ID, new Enrollment(
            List.of(new Delegation(PORT, new Shape.Block(state -> PackageNetworks.get().isPostbox(state)),
                ResourceLocation.fromNamespaceAndPath("create", "white_postbox"))),
            Schema.none()));
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

    static Optional<DispatchPresence> chainHolderOf(ServerLevel level, BlockPos chain) {
        for (Colony colony : Colonies.all(level.getServer())) {
            Optional<DispatchPresence> dispatch = presenceIn(colony.service(ID, Object.class));
            if (dispatch.isPresent() && dispatch.get().holdsChain(level, chain)) {
                return dispatch;
            }
        }
        return Optional.empty();
    }

    static Optional<DispatchPresence> presenceIn(Optional<?> works) {
        return works.filter(DispatchPresence.class::isInstance).map(DispatchPresence.class::cast);
    }
}
