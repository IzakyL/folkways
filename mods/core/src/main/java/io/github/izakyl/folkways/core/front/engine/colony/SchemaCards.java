package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.front.api.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A settings schema as the parameter elements of a card: a flag, a typed count, a choice, an item list. */
public final class SchemaCards {
    private SchemaCards() {
    }

    public static List<Card.Element> elements(Schema schema) {
        List<Card.Element> elements = new ArrayList<>();
        for (Schema.Setting setting : schema.settings()) {
            Optional<Card.Label> label = Optional.of(setting.nameKey().isEmpty()
                ? new Card.Label(setting.key(), false) : new Card.Label(setting.nameKey(), true));
            elements.add(switch (setting) {
                case Schema.Setting.Flag flag -> new Card.Element.Flag(flag.key(), Optional.of(flag.icon()), label,
                    flag.byDefault());
                case Schema.Setting.Count count -> new Card.Element.Number(count.key(), count.min(), count.max(), 1,
                    Optional.of(count.icon()), label, Optional.of((double) count.byDefault()), true);
                case Schema.Setting.Choice choice -> new Card.Element.Choice(choice.key(),
                    choice.options().stream().map(Object::toString).toList(), List.of(), List.of(),
                    Optional.of(choice.icon()), label, Optional.of(choice.byDefault().toString()), Optional.empty(),
                    Map.of());
                case Schema.Setting.Items items -> new Card.Element.Items(items.key(), Optional.of(items.icon()), label,
                    items.byDefault(), items.single(), items.blocks());
            });
        }
        return elements;
    }
}
