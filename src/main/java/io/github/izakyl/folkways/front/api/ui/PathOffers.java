package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.core.api.Ledger;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class PathOffers {

    private static final Ledger<Offer> OFFERS =
        Ledger.sealedOnceRead("offered over a marked path", Offer::id);

    private PathOffers() {
    }

    public static void register(Offer offer) {
        OFFERS.claim(offer);
    }

    public static List<Offer> all() {
        return OFFERS.all();
    }

    public static Optional<Offer> of(ResourceLocation id) {
        return OFFERS.of(id);
    }

    public record Offer(ResourceLocation id, String nameKey, ResourceLocation icon, Taker take) {
    }

    @FunctionalInterface
    public interface Taker {

        void take(List<BlockPos> points);
    }
}
