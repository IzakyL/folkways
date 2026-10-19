package io.github.izakyl.folkways.effect;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

// What an effect reaches: everything a resident does, or only the work of one trade.
public record Scope(Optional<ResourceLocation> trade) {

    public static final Scope ANY = new Scope(Optional.empty());

    public static Scope trade(ResourceLocation trade) {
        return new Scope(Optional.of(trade));
    }

    // Whether an effect under this scope bears on a question asked about this trade, or about none.
    public boolean covers(Optional<ResourceLocation> asked) {
        return trade.isEmpty() || trade.equals(asked);
    }
}
