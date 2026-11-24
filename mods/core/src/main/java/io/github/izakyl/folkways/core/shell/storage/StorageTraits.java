package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.facility.api.control.TransformHint;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Native reach and write effects of a storage adapter. */
public record StorageTraits(Optional<Component> unavailable, List<TransformHint> transforms) {

    public static final StorageTraits PLAIN = new StorageTraits(Optional.empty(), List.of());

    public static StorageTraits unavailable(String reason) {
        return new StorageTraits(Optional.of(message(reason)), List.of());
    }

    public static StorageTraits voiding(ItemStack filter) {
        return transform(new TransformHint.Discard(prototype(filter), TransformHint.Discard.Mode.UPGRADE));
    }

    public static StorageTraits source(ItemStack item) {
        return transform(new TransformHint.Source(prototype(item)));
    }

    public static StorageTraits compacting(ItemStack from, ItemStack to) {
        return transform(new TransformHint.Convert(from.copyWithCount(1), to.copyWithCount(1)));
    }

    public static StorageTraits compacting() {
        return transform(new TransformHint.Compacting());
    }

    public static StorageTraits conversion() {
        return transform(new TransformHint.Conversion());
    }

    public static StorageTraits upgrade(ItemStack upgrade) {
        return transform(new TransformHint.UnknownUpgrade(upgrade.copy()));
    }

    private static StorageTraits transform(TransformHint hint) {
        return new StorageTraits(Optional.empty(), List.of(hint));
    }

    private static Optional<ItemStack> prototype(ItemStack stack) {
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.copyWithCount(1));
    }

    private static Component message(String reason) {
        return Component.translatable("folkways.storage.unusable." + reason);
    }
}
