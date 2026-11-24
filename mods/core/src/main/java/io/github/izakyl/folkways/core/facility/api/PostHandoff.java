package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/** Brings ordinary planned supplies to a resident who must stay at a post. */
record PostHandoff(NodeSpec spec, Posts posts, Post post, Worker recipient) implements Node {

    static Optional<PostHandoff> to(ServerLevel level, ResourceLocation owner, Posts posts, Post post,
            Worker recipient, Need need) {
        var at = WorldSpaces.at(recipient.body());
        return Stances.of(level, Reach.workableCells(level, at.block(level))).map(stance ->
            new PostHandoff(NodeSpec.of(UUID.randomUUID(), owner, new WorkSite.AtBlock(at), stance,
                Workload.Once.of(0)).needs(need).done(), posts, post, recipient));
    }

    @Override
    public boolean admits(ServerLevel level, Mob body) {
        return recipient.body() != body;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker courier) {
        if (posts.occupant(level, post).filter(worker -> worker.body() == recipient.body()).isEmpty()) {
            return Outcome.failed(FacilityRefusal.POST_UNAVAILABLE);
        }
        if (!PostTransfer.fits(recipient.pack(), courier.supplied())) {
            return Outcome.failed(FacilityRefusal.BUSY);
        }
        List<ItemStack> unused = new ArrayList<>();
        for (ItemStack stack : courier.supplied()) {
            ItemStack left = Containers.insert(recipient.pack(), stack);
            if (!left.isEmpty()) {
                unused.add(left);
            }
        }
        InventoryFeedback.resident(recipient.body());
        return new Outcome.Done(List.of(), List.of(), Optional.empty(), unused);
    }
}
