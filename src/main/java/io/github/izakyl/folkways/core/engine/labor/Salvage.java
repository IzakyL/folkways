package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.plan.Message;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

final class Salvage {

    static final ResourceLocation DOMAIN =
        ResourceLocation.fromNamespaceAndPath("folkways", "salvage");

    private final UUID id = UUID.randomUUID();

    private final Map<UUID, Lying> down = new LinkedHashMap<>();

    List<UUID> drops() {
        return down.values().stream().map(Lying::drop).toList();
    }

    Optional<Message> dropped(ItemEntity drop) {
        if (!(drop.level() instanceof ServerLevel level) || drop.getItem().isEmpty()) {
            return Optional.empty();
        }
        WorldPos at = WorldSpaces.at(drop);
        BlockPos cell = at.block(level);
        Optional<Stances> stances = Stances.of(level, Reach.workableCells(level, cell));
        if (stances.isEmpty()) {
            return Optional.empty();
        }
        Lying lying = new Lying(drop.getUUID(), at, Goods.specOf(drop.getItem()),
            drop.getItem().getCount(), stances.get());
        down.put(PickUp.nameOf(drop.getUUID()), lying);
        return Optional.of(new Message.Submitted(DOMAIN, Grown.of(lying.pickUp())));
    }

    boolean ended(UUID node) {
        return down.remove(node) != null;
    }

    Optional<Workshop> workshop() {
        return down.isEmpty() ? Optional.empty() : Optional.of(new Frozen(id, List.copyOf(down.values())));
    }

    private record Lying(UUID drop, WorldPos at, ItemSpec what, long count, Stances stances) {

        PickUp pickUp() {
            return new PickUp(drop, at, what, count, stances);
        }
    }

    private record Frozen(UUID key, List<Lying> down) implements Workshop {

        @Override
        public ResourceLocation id() {
            return DOMAIN;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(down.get(0).at());
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) {
                return List.of();
            }
            List<Refinement.Change> answers = new ArrayList<>();
            for (Lying stack : down) {
                if (produce.wanted().admits(stack.what())) {
                    answers.add(Refinement.Change.of(Grown.of(stack.pickUp())));
                }
            }
            return List.copyOf(answers);
        }
    }
}
