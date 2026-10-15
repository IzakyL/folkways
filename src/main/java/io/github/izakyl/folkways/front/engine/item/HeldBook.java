package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

// A colony book in hand. Bound, it reaches the colony the player acts for; without one chosen it reaches nothing.
public record HeldBook(InteractionHand hand, ItemStack stack, UUID colonyId, Shape.Gesture gesture) {

    public static Optional<HeldBook> bound(Player player) {
        Optional<UUID> colonyId = CurrentColony.id(player);
        return colonyId.isEmpty() ? Optional.empty() : any(player)
            .map(book -> new HeldBook(book.hand(), book.stack(), colonyId.get(), book.gesture()));
    }

    public static Optional<HeldBook> any(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ColonyBookItem) {
                return Optional.of(new HeldBook(hand, stack,
                    CurrentColony.id(player).orElse(null), ColonyBookItem.gesture(stack)));
            }
        }
        return Optional.empty();
    }
}
