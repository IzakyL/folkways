package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkGesture;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Xp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

// Clears one parcel no collection is waiting on, so strays do not fill the port.
final class StrayNode implements Node {

    static final int RANK = 2;

    private final NodeSpec spec;
    private final Vocation hauling = Vocations.required(Vocations.HAULING);
    private final WorldPos at;
    private final Map<UUID, CollectNode> collecting;

    StrayNode(WorldPos at, Stances stances, int reachTicks, Map<UUID, CollectNode> collecting) {
        this.at = at;
        this.collecting = collecting;
        this.spec = NodeSpec.of(idOf(at), DispatchContent.ID, new WorkSite.AtBlock(at), stances,
                Workload.Once.of(reachTicks, DispatchContent::haste))
            .vocation(hauling)
            .gesture(WorkGesture.SWING)
            .focus(at.cell())
            .doing(DispatchContent.TIDYING)
            .done();
    }

    static boolean stray(Map<UUID, CollectNode> collecting, WorldPos at, PackageNetwork.Parcel parcel) {
        return collecting.values().stream().noneMatch(node -> node.port().equals(at) && node.owns(parcel));
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public boolean ready(ServerLevel level) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return false;
        }
        return PackageNetworks.get().parcelsAt(level, at.block(level)).stream()
            .anyMatch(parcel -> stray(collecting, at, parcel));
    }

    @Override
    public Outcome commit(ServerLevel level, Worker who) {
        PackageNetwork network = PackageNetworks.get();
        if (!network.isPort(level.getBlockState(at.block(level)))) {
            return Outcome.failed(DispatchRefusal.NO_PORT);
        }
        Optional<List<ItemStack>> taken = network.clearOne(level, at.block(level),
            parcel -> stray(collecting, at, parcel));
        if (taken.isEmpty()) {
            return Outcome.failed(DispatchRefusal.NOTHING_ARRIVED);
        }
        return new Outcome.Done(List.of(Rummaged.at(at.block(level))), taken.get(),
            Optional.of(new Xp(hauling, 1)));
    }

    private static UUID idOf(WorldPos at) {
        return UUID.nameUUIDFromBytes(("folkways:dispatch/stray/" + at).getBytes(StandardCharsets.UTF_8));
    }
}
