package io.github.izakyl.folkways.core.front.engine.item;

import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// A colony book in hand. Bound, it reaches the colony the player acts for; without one chosen it reaches nothing.
public record HeldBook(InteractionHand hand, ItemStack stack, UUID colonyId) {

    public static Optional<HeldBook> bound(Player player) {
        Optional<UUID> colonyId = CurrentColony.id(player);
        return colonyId.isEmpty() ? Optional.empty() : any(player)
            .map(book -> new HeldBook(book.hand(), book.stack(), colonyId.get()));
    }

    public static Optional<HeldBook> any(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ColonyBookItem) {
                return Optional.of(new HeldBook(hand, stack, CurrentColony.id(player).orElse(null)));
            }
        }
        return Optional.empty();
    }
}
