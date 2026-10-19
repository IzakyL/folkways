package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.resident.body.Licence;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

public record Crew(List<Hand> hands) {

    public Crew {
        hands = List.copyOf(hands);
    }

    public record Hand(Resident who, WorldPos at, int packCells, int packCapacity, double pace,
                       List<Licence> licences, List<ItemStack> tools, Optional<UUID> holding,
                       List<ItemStack> cargo) {

        public Hand {
            licences = List.copyOf(licences);
            tools = tools.stream().map(ItemStack::copy).toList();
            cargo = cargo.stream().map(ItemStack::copy).toList();
        }

        public Hand(Resident who, WorldPos at, int packCells, int packCapacity, double pace,
                    List<Licence> licences, List<ItemStack> tools, Optional<UUID> holding) {
            this(who, at, packCells, packCapacity, pace, licences, tools, holding, List.of());
        }

        public UUID id() {
            return who.id();
        }

        public Optional<Licence> licenceFor(Vocation trade) {
            for (Licence licence : licences) {
                if (licence.vocation().equals(trade)) {
                    return Optional.of(licence);
                }
            }
            return Optional.empty();
        }

        public Optional<Keenness> keennessOf(Vocation trade) {
            return licenceFor(trade).map(Licence::keenness);
        }

        public boolean takes(Optional<Vocation> trade) {
            return trade.isEmpty() || licenceFor(trade.get()).isPresent();
        }
    }
}
