package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.base.Ledger;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public final class OwnerSaves {

    private static final Ledger<OwnerSave> SAVES = new Ledger<>("an owner save", OwnerSave::owner);

    private OwnerSaves() {
    }

    public static void register(OwnerSave save) {
        SAVES.claim(save);
    }

    public static Optional<OwnerSave> of(ResourceLocation owner) {
        return SAVES.of(owner);
    }
}
