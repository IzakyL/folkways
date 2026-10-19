package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Stall;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

// The colony's work standing still, told on the cards and pages of the place it stands in: a zone, a block, a site.
public final class ColonyStalls {

    // A card tells only so many; past that, more work standing still for other reasons says nothing new.
    private static final int MOST = 3;

    private ColonyStalls() {
    }

    // On a card, goods that were missing or had nowhere to go are told in pictures, as a resident's balk is: "Missing:
    // [sowing] ✗ wheat seeds"; any other stall in its own words.
    public static List<Line> lines(Colony colony, ServerLevel level, Predicate<BlockPos> inside) {
        Set<Line> said = new LinkedHashSet<>();
        for (Stall stall : colony.stallsIn(level.dimension())) {
            if (said.size() >= MOST) {
                break;
            }
            if (stall.where().in(level) && inside.test(stall.where().block(level))) {
                said.add(pictured(stall).orElseGet(() -> Line.blocked(notice(stall))));
            }
        }
        return new ArrayList<>(said);
    }

    private static Optional<Line> pictured(Stall stall) {
        Optional<ResourceLocation> item = stall.goods().flatMap(Doings::item)
            .filter(BuiltInRegistries.ITEM::containsKey);
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return switch (stall.why().shortfall()) {
            case LACKING -> Optional.of(Line.blocked(Notice.of(LookNotice.LACKING), goods(stall, item.get(), "lacks")));
            case NO_ROOM -> Optional.of(Line.blocked(Notice.of(LookNotice.NO_ROOM), goods(stall, item.get(), "refuses")));
            case NONE -> Optional.empty();
        };
    }

    private static Sentence goods(Stall stall, ResourceLocation item, String mark) {
        return Sentence.of(Sentence.doing(stall.doing()))
            .then(Sentence.wares(Sentence.glyph(mark), List.of(new Sentence.Token.Ware(item, 0L))));
    }

    // Filled with the goods the work went short of, else with what the work is done to, as a resident's balk is.
    static Notice notice(Stall stall) {
        Notice.Arg filled = stall.goods().map(ColonyStalls::named)
            .orElseGet(() -> stall.about().map(Notice::named).orElseGet(() -> Notice.text("")));
        return Notice.of(stall.why(), filled);
    }

    private static Notice.Arg named(ItemSpec goods) {
        return Doings.item(goods)
            .map(id -> Notice.named(BuiltInRegistries.ITEM.get(id).getDescriptionId()))
            .orElseGet(() -> Notice.text(goods.describe()));
    }
}
