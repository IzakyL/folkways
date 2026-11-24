package io.github.izakyl.folkways.vanilla.cookery;

import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.shell.cookery.api.Appliances;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;

/** Physical operating positions for kitchen appliances. */
public final class KitchenStation {
    private KitchenStation() {
    }

    // The appliance's control at its anchor, if it is one Core adapts.
    public static Optional<Port<ItemStack>> control(ServerLevel level, WorldPos at) {
        return Ports.at(level, at, ResourceKind.ITEM, Appliances.INPUT);
    }

    // A counter-top appliance is worked from the handle's adjacent column. The same
    // column also supports appliances placed directly on the floor; never borrow another side.
    public static Optional<Stances> operating(ServerLevel level, WorldPos at, Direction side) {
        return Stances.of(Appliances.footings(level, at, side));
    }

    // A multi-block appliance may operate above its registered anchor. Publish all candidate
    // footings, then retain only those that reach every physical point needed by this batch.
    public static Optional<Stances> around(ServerLevel level, List<WorldPos> points) {
        return Stances.of(points.stream().filter(point -> point.in(level))
            .flatMap(point -> Reach.workableCells(level, point.block(level)).stream()
                .map(cell -> WorldPos.of(level, cell))).toList());
    }

    public static Optional<Stances> reaching(ServerLevel level, Stances offered, List<WorldPos> points) {
        return StationApproach.reaching(level, offered, points);
    }

    public static Optional<Stances> calculateReaching(ServerLevel level, Stances offered, List<WorldPos> points) {
        if (points.isEmpty() || points.stream().anyMatch(point -> !point.in(level))) { return Optional.empty(); }
        Stances candidates = offered instanceof Stances.Cells ? offered : around(level, points).orElse(null);
        if (candidates == null) { return Optional.empty(); }
        return Stances.of(candidates.cells().stream().filter(footing ->
            points.stream().allMatch(point -> point.realm().equals(footing.realm()))
                && WorldSpaces.storage(level, footing).filter(cell -> Footing.withFeetAt(level, cell)
                    && points.stream().allMatch(point -> Reach.withinReach(cell, point.block(level))
                        && Reach.inSight(level, cell, point.block(level)))).isPresent()).toList());
    }

}
