package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Enrollment;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.Registering;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

final class AirportColony {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, "airports");
    static final ResourceLocation TOWER = ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, "airport_tower");
    static void enroll(Registering registering) {
        Posts.register(TowerDuty::posts);
        registering.enrollment(ID, new Enrollment(List.of(new Delegation(TOWER, new Shape.Block(
            (level, pos) -> level.getBlockState(pos).is(AirlineContent.AIRPORT_TOWER.get()), (level, pos) -> pos, false), TOWER)),
            Schema.none()));
        registering.holding(TOWER, colony -> Optional.of(new HoldingKind() {
            @Override
            public List<Line> lines(Held held, ColonyView view) {
                return Airports.at(view.level(), held.site().block(view.level()))
                    .map(airport -> List.of(Line.text(title(airport)))).orElseGet(List::of);
            }
        }));
    }

    static Component title(Airport airport) {
        return airport.name().isBlank() ? Component.translatable("folkways.airline.airport.unnamed")
            : Component.literal(airport.name());
    }
}
