package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

// What the player said about one container beyond its orders: which goods it takes, and which slots are the
// colony's to use. Slots are counted from one here, as the player counts them.
record Shelf(WorldPos into, Optional<Admitting> admitting, Optional<Span> slots) {

    static final int MAX_SLOT = 999;

    private static final String TAG_INTO = "into";
    private static final String TAG_ONLY = "only";
    private static final String TAG_GOODS = "goods";
    private static final String TAG_FIRST = "first";
    private static final String TAG_LAST = "last";

    record Admitting(boolean only, ItemSpec goods) {

        Admitting {
            Objects.requireNonNull(goods, "goods");
        }

        StoreRule.Admit rule(WorldPos where) {
            return new StoreRule.Admit(where, only, List.of(goods));
        }
    }

    record Span(int first, int last) {

        Span {
            if (first < 1 || last < first || last > MAX_SLOT) {
                throw new IllegalArgumentException("slots " + first + "-" + last);
            }
        }

        static Span between(int one, int other) {
            return new Span(Math.min(one, other), Math.max(one, other));
        }
    }

    Shelf {
        Objects.requireNonNull(into, "into");
        Objects.requireNonNull(admitting, "admitting");
        Objects.requireNonNull(slots, "slots");
    }

    static Shelf none(WorldPos into) {
        return new Shelf(into, Optional.empty(), Optional.empty());
    }

    Shelf taking(Optional<Admitting> next) {
        return new Shelf(into, next, slots);
    }

    Shelf spanning(Optional<Span> next) {
        return new Shelf(into, admitting, next);
    }

    boolean bare() {
        return admitting.isEmpty() && slots.isEmpty();
    }

    boolean admits(ItemSpec spec) {
        return admitting.map(admit -> admit.rule(into).admits(spec)).orElse(true);
    }

    List<StoreRule> rules(WorldPos where) {
        List<StoreRule> rules = new ArrayList<>();
        admitting.ifPresent(admit -> rules.add(admit.rule(where)));
        slots.ifPresent(span -> rules.add(new StoreRule.Slots(where, span.first() - 1, span.last())));
        return rules;
    }

    CompoundTag save() {
        Writer writer = Writer.of().blob(TAG_INTO, into.save());
        admitting.ifPresent(admit -> writer.flag(TAG_ONLY, admit.only()).blob(TAG_GOODS, admit.goods().save()));
        slots.ifPresent(span -> writer.integer(TAG_FIRST, span.first()).integer(TAG_LAST, span.last()));
        return writer.tag();
    }

    static Optional<Shelf> load(Reader reader) {
        Optional<WorldPos> into = reader.child(TAG_INTO).flatMap(WorldPos::load);
        if (into.isEmpty()) {
            return Optional.empty();
        }
        Optional<Admitting> admitting = reader.child(TAG_GOODS).flatMap(ItemSpec::load)
            .map(goods -> new Admitting(reader.flag(TAG_ONLY).orElse(true), goods));
        Optional<Integer> first = reader.integer(TAG_FIRST);
        Optional<Integer> last = reader.integer(TAG_LAST);
        Optional<Span> slots = first.isPresent() && last.isPresent()
            && first.get() >= 1 && last.get() <= MAX_SLOT && first.get() <= last.get()
            ? Optional.of(new Span(first.get(), last.get())) : Optional.empty();
        Shelf shelf = new Shelf(into.get(), admitting, slots);
        return shelf.bare() ? Optional.empty() : Optional.of(shelf);
    }
}
