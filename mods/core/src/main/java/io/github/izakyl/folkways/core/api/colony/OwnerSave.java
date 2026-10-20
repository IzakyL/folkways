package io.github.izakyl.folkways.core.api.colony;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/**
 * An owner's saved payload. Step n migrates version n to n + 1; unwrapped saves are version zero.
 * Steps only transform their private NBT input, preserve fields they do not own, and never touch game state.
 * The validator rejects malformed current payloads by throwing. Core publishes the result only after every
 * required step and validation succeeds; an unavailable payload must never be replaced by empty defaults.
 */
public record OwnerSave(ResourceLocation owner, List<UnaryOperator<CompoundTag>> migrations,
                        Consumer<CompoundTag> validate) {

    public OwnerSave(ResourceLocation owner, List<UnaryOperator<CompoundTag>> migrations) {
        this(owner, migrations, payload -> { });
    }

    public OwnerSave {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(validate, "validate");
        migrations = List.copyOf(migrations);
        if (migrations.isEmpty()) {
            throw new IllegalArgumentException("an owner save needs its version zero migration");
        }
    }

    public int version() {
        return migrations.size();
    }

    public CompoundTag migrate(int from, CompoundTag payload) {
        if (from < 0 || from > version()) {
            throw new IllegalArgumentException("unsupported " + owner + " save version " + from);
        }
        CompoundTag next = payload.copy();
        for (int version = from; version < version(); version++) {
            next = Objects.requireNonNull(migrations.get(version).apply(next.copy()),
                "migration returned no payload").copy();
        }
        validate.accept(next.copy());
        return next;
    }
}
