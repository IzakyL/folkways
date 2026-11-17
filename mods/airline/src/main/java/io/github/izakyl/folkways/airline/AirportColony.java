package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.core.front.api.panel.PageKind;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class AirportColony implements Facing {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, "airports");
    static final ResourceLocation TOWER = ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, "airport_tower");
    static final PageKind PAGE = PageKind.boarded(ID, "folkways.airline.airport.title", TOWER).inNavigation();

    static void enroll(Registering registering) {
        registering.enrollment(ID, new Enrollment(List.of(new Delegation(TOWER, new Shape.Block(
            (level, pos) -> level.getBlockState(pos).is(AirlineContent.AIRPORT_TOWER.get()), (level, pos) -> pos, false), TOWER)),
            Schema.none(), List.of(PAGE)));
        registering.facing(ID, colony -> Optional.of(new AirportColony()));
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView colony) {
        if (!page.equals(ID)) {
            return Optional.empty();
        }
        return Optional.of(Board.of(colony.blocks(TOWER).stream().map(pos -> Airports.at(colony.level(), pos)
            .map(Airport::tower).orElse(pos)).distinct().sorted(BlockPos::compareTo)
            .map(pos -> Board.Row.of(AirlineContent.AIRPORT_TOWER.toStack(),
                Airports.at(colony.level(), pos).map(AirportColony::title).orElseGet(Component::empty),
                Component.literal(pos.toShortString()))).toList()));
    }

    @Override
    public Optional<Board> boardAt(ResourceLocation page, BlockPos at, ColonyView colony) {
        return page.equals(ID) && colony.blocks(TOWER).contains(at)
            ? Optional.of(Board.of(List.of())) : Optional.empty();
    }

    static Component title(Airport airport) {
        return airport.name().isBlank() ? Component.translatable("folkways.airline.airport.unnamed")
            : Component.literal(airport.name());
    }
}
