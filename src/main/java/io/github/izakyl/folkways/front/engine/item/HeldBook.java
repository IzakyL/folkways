package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.front.api.Shape;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public record HeldBook(InteractionHand hand, ItemStack stack, UUID colonyId, Shape.Gesture gesture) {

    public static Optional<HeldBook> bound(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof ColonyBookItem)) {
                continue;
            }
            Optional<UUID> colonyId = ColonyBookItem.boundColonyId(stack);
            if (colonyId.isPresent()) {
                return Optional.of(new HeldBook(hand, stack, colonyId.get(),
                    ColonyBookItem.gesture(stack)));
            }
        }
        return Optional.empty();
    }

    public static Optional<HeldBook> any(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ColonyBookItem) {
                return Optional.of(new HeldBook(hand, stack,
                    ColonyBookItem.boundColonyId(stack).orElse(null), ColonyBookItem.gesture(stack)));
            }
        }
        return Optional.empty();
    }
}
