package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.colony.api.KitRules;
import io.github.izakyl.folkways.core.resident.api.Licence;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.plan.api.PackSpace;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.item.api.Goods;
import java.util.Map;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.ToolPolicy;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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
                       List<ItemStack> cargo, ToolPolicy toolRules, PackSpace space) {

        public Hand {
            licences = List.copyOf(licences);
            tools = tools.stream().map(ItemStack::copy).toList();
            cargo = cargo.stream().map(ItemStack::copy).toList();
        }

        public Hand(Resident who, WorldPos at, int packCells, int packCapacity, double pace,
                    List<Licence> licences, List<ItemStack> tools, Optional<UUID> holding,
                    List<ItemStack> cargo, ToolPolicy toolRules) {
            this(who, at, packCells, packCapacity, pace, licences, tools, holding, cargo, toolRules, null);
        }

        public long capacity(ItemSpec goods) {
            return space == null ? (long) packCells * Math.max(1, Goods.stackSize(goods)) : space.capacity(goods);
        }

        public int load(Map<ItemSpec, Long> goods) {
            return space == null ? PackLoad.cells(goods) : space.load(goods);
        }

        public int delta(Map<ItemSpec, Long> goods) {
            return space == null ? PackLoad.cells(goods) : space.delta(goods);
        }

        public Hand(Resident who, WorldPos at, int packCells, int packCapacity, double pace,
                    List<Licence> licences, List<ItemStack> tools, Optional<UUID> holding, List<ItemStack> cargo) {
            this(who, at, packCells, packCapacity, pace, licences, tools, holding, cargo,
                KitRules.defaults());
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
