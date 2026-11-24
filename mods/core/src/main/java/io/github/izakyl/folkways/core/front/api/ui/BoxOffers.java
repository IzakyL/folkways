package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.base.Ledger;
import io.github.izakyl.folkways.core.front.api.Category;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BoxOffers {

    private static final Ledger<Offer> OFFERS =
        Ledger.sealedOnceRead("offered over a marked box", Offer::id);

    private BoxOffers() {
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

    /** Something a drawn shape can be handed to; the category groups it with the book's other drawing tools. */
    public record Offer(ResourceLocation id, String nameKey, ResourceLocation icon, Body body, ResourceLocation category) {

        public Offer(ResourceLocation id, String nameKey, ResourceLocation icon, Body body) {
            this(id, nameKey, icon, body, Category.OTHER.id());
        }

        /** The same offer, grouped under that category. */
        public Offer in(Category category) {
            return new Offer(id, nameKey, icon, body, category.id());
        }

        /** What the offer does the moment it is picked, if it asks nothing first ({@link #acting}). */
        public Optional<Act> act() {
            return body instanceof Acting acting ? Optional.of(acting.act()) : Optional.empty();
        }
    }

    /** A body for an offer with nothing to ask: picked, it acts at once and no page of its own opens. */
    public static Body acting(Act act) {
        return new Acting(act);
    }

    @FunctionalInterface
    public interface Body {

        /** What the offer asks before it is taken, drawn in the page; {@code taking} is told what was decided. */
        UIElement build(BlockPos min, BlockPos max, Taking taking);
    }

    @FunctionalInterface
    public interface Act {

        /** Takes the box at once; the box stays drawn, as it does when a page is backed out of. */
        void take(BlockPos min, BlockPos max);
    }

    private record Acting(Act act) implements Body {

        @Override
        public UIElement build(BlockPos min, BlockPos max, Taking taking) {
            act.take(min, max);
            return new UIElement();
        }
    }
}
