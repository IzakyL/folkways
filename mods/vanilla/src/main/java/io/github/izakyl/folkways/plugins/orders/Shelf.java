package io.github.izakyl.folkways.plugins.orders;

import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemQuery;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

// Container access and incoming goods filters, independent of delivery orders.
record Shelf(WorldPos into, Optional<Admitting> admitting, boolean take, boolean store) {

    private static final String TAG_INTO = "into";
    private static final String TAG_ONLY = "only";
    private static final String TAG_GOODS = "goods";

    record Admitting(boolean only, List<ItemFilter> filters) {
        Admitting {
            filters = List.copyOf(filters);
        }

        Admitting(boolean only, ItemSpec goods) {
            this(only, filtersOf(goods));
        }

        private static List<ItemFilter> filtersOf(ItemSpec goods) {
            if (!goods.anyOf().isEmpty()) {
                return goods.anyOf().stream().flatMap(spec -> filtersOf(spec).stream()).toList();
            }
            return List.of(goods.item().map(ItemFilter::item)
                .orElseGet(() -> ItemFilter.tag(goods.tag().orElseThrow().location())));
        }

        Optional<ItemSpec> goods() {
            List<ItemSpec> allowed = new ArrayList<>();
            for (ItemFilter filter : filters) {
                switch (filter.kind()) {
                    case ITEM -> allowed.add(ItemSpec.of(filter.id()));
                    case TAG -> allowed.add(ItemSpec.of(TagKey.create(Registries.ITEM, filter.id())));
                    case QUERY -> ItemQuery.parse(filter.text()).ifPresent(query -> query.items()
                        .forEach(item -> allowed.add(ItemSpec.of(BuiltInRegistries.ITEM.getKey(item)))));
                }
            }
            return allowed.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(allowed));
        }

        StoreRule.Admit rule(WorldPos where) {
            return new StoreRule.Admit(where, only, goods().map(List::of).orElseGet(List::of));
        }
    }

    Shelf {
        Objects.requireNonNull(into, "into");
        Objects.requireNonNull(admitting, "admitting");
    }

    static Shelf none(WorldPos into) {
        return new Shelf(into, Optional.empty(), true, true);
    }

    Shelf taking(Optional<Admitting> next) {
        return new Shelf(into, next, take, store);
    }

    Shelf toggle(String mode) {
        return switch (mode) {
            case "take" -> new Shelf(into, admitting, !take, store);
            case "store" -> new Shelf(into, admitting, take, !store);
            default -> throw new IllegalArgumentException(mode);
        };
    }

    boolean bare() {
        return admitting.isEmpty() && take && store;
    }

    boolean admits(ItemSpec spec) {
        return admitting.map(admit -> admit.rule(into).admits(spec)).orElse(true);
    }

    List<StoreRule> rules(WorldPos where) {
        List<StoreRule> rules = new ArrayList<>();
        rules.add(new StoreRule.Access(where, take, store));
        admitting.ifPresent(admit -> rules.add(admit.rule(where)));
        return rules;
    }

    CompoundTag save() {
        Writer writer = Writer.of().blob(TAG_INTO, into.save())
            .flag("take", take).flag("store", store);
        admitting.ifPresent(admit -> writer.flag(TAG_ONLY, admit.only()).children("filters", admit.filters(),
            filter -> Writer.of().string("rule", filter.write()).tag()));
        return writer.tag();
    }

    static Optional<Shelf> load(Reader reader) {
        Optional<WorldPos> into = reader.child(TAG_INTO).flatMap(WorldPos::load);
        if (into.isEmpty()) {
            return Optional.empty();
        }
        Optional<Admitting> admitting = reader.keys().contains("filters")
            ? Optional.of(new Admitting(reader.flag(TAG_ONLY).orElse(false), reader.children("filters").stream()
                .flatMap(saved -> saved.string("rule").flatMap(ItemFilter::read).stream()).toList()))
            : reader.child(TAG_GOODS).flatMap(ItemSpec::load)
                .map(goods -> new Admitting(reader.flag(TAG_ONLY).orElse(true), goods));
        Shelf shelf = new Shelf(into.get(), admitting, reader.flag("take").orElse(true),
            reader.flag("store").orElse(true));
        return shelf.bare() ? Optional.empty() : Optional.of(shelf);
    }
}
