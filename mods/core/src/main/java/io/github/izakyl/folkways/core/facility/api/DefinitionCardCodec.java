package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.core.facility.api.DefinitionCodec.only;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec.Field;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

final class DefinitionCardCodec {

    private DefinitionCardCodec() {
    }

    static Map<String, Object> ui(Card ui) {
        List<Object> card = ui.elements().stream().<Object>map(DefinitionCardCodec::element).toList();
        if (ui.overlays().isEmpty()) {
            return Map.of("card", card);
        }
        return Map.of("card", card, "overlay", ui.overlays().stream().<Object>map(overlay -> switch (overlay) {
            case Card.Overlay.Range range -> Map.of("range", range.reading());
            case Card.Overlay.Path path -> Map.of("path", path.routine());
        }).toList());
    }

    static Card.Overlay overlay(Field field) {
        if (field.has("range") && only(field, "range")) {
            return new Card.Overlay.Range(field.get("range").string());
        }
        if (field.has("path") && only(field, "path")) {
            return new Card.Overlay.Path(field.get("path").string());
        }
        throw field.invalid();
    }

    static Map<String, Object> element(Card.Element element) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (element) {
            case Card.Element.Button button -> {
                data.put("button", button.routine());
                button.label().ifPresent(label -> data.put("label", label.text()));
                button.icon().ifPresent(icon -> data.put("icon", icon.toString()));
                if (!button.enabledWhen().isEmpty()) {
                    data.put("enabled_when", DefinitionCodec.predicateData(button.enabledWhen()));
                }
            }
            case Card.Element.Target target -> {
                data.put("target", target.routine());
                data.put("pick", target.pick().key());
                target.icon().ifPresent(icon -> data.put("icon", icon.toString()));
                target.label().ifPresent(label -> data.put("label", label.text()));
                if (target.defaultCommand()) {
                    data.put("default", true);
                }
                if (!target.enabledWhen().isEmpty()) {
                    data.put("enabled_when", DefinitionCodec.predicateData(target.enabledWhen()));
                }
            }
            case Card.Element.Toggle toggle -> {
                data.put("toggle", toggle.trigger());
                toggle.icon().ifPresent(icon -> data.put("icon", icon.toString()));
            }
            case Card.Element.Choice choice -> {
                data.put("choice", choice.param());
                data.put("options", choice.options());
                if (!choice.labels().isEmpty()) {
                    data.put("labels", choice.labels());
                }
                if (!choice.icons().isEmpty()) {
                    data.put("icons", choice.icons());
                }
                choice.icon().ifPresent(icon -> data.put("icon", icon.toString()));
                choice.label().ifPresent(label -> data.put("label", label.text()));
                choice.initial().ifPresent(initial -> data.put("default", initial));
                choice.run().ifPresent(run -> data.put("run", run));
                if (!choice.enabledWhen().isEmpty()) {
                    data.put("enabled_when", DefinitionCodec.predicateData(choice.enabledWhen()));
                }
            }
            case Card.Element.Number number -> {
                data.put("number", number.param());
                data.put("min", number.min());
                data.put("max", number.max());
                data.put("step", number.step());
                number.icon().ifPresent(icon -> data.put("icon", icon.toString()));
                number.label().ifPresent(label -> data.put("label", label.text()));
                number.initial().ifPresent(initial -> data.put("default", initial));
                if (number.typed()) {
                    data.put("typed", true);
                }
            }
            case Card.Element.Flag flag -> {
                data.put("flag", flag.param());
                flag.icon().ifPresent(icon -> data.put("icon", icon.toString()));
                flag.label().ifPresent(label -> data.put("label", label.text()));
                data.put("default", flag.initial());
            }
            case Card.Element.Items items -> {
                data.put("items", items.param());
                items.icon().ifPresent(icon -> data.put("icon", icon.toString()));
                items.label().ifPresent(label -> data.put("label", label.text()));
                if (!items.initial().isEmpty()) {
                    data.put("default", items.initial().stream().map(ItemFilter::describe).toList());
                }
                if (items.single()) {
                    data.put("single", true);
                }
                if (items.blocks()) {
                    data.put("blocks", true);
                }
            }
            case Card.Element.Lines ignored -> data.put("lines", true);
            case Card.Element.Rows rows -> {
                data.put("rows", rows.source());
                rows.whenEmpty().ifPresent(label -> data.put("empty", label.text()));
            }
            case Card.Element.Heading heading -> data.put("heading", heading.text().text());
            case Card.Element.Note note -> data.put("note", note.text().text());
            case Card.Element.Readout readout -> {
                data.put("readout", readout.reading());
                data.put("as", readout.as().name().toLowerCase(Locale.ROOT));
                readout.label().ifPresent(label -> data.put("label", label.text()));
            }
        }
        return Map.copyOf(data);
    }

    static Card.Element element(Field field) {
        if (field.has("button") && only(field, "button", "icon", "enabled_when", "label")) {
            return new Card.Element.Button(field.get("button").string(), icon(field),
                DefinitionCodec.predicate(field.get("enabled_when", Map.of())), label(field));
        }
        if (field.has("target") && only(field, "target", "pick", "icon", "label", "default", "enabled_when")) {
            Field pick = field.get("pick");
            Card.Pick kind = java.util.Arrays.stream(Card.Pick.values())
                .filter(value -> value.key().equals(pick.string())).findFirst().orElseThrow(pick::invalid);
            return new Card.Element.Target(field.get("target").string(), kind, icon(field), label(field),
                field.get("default", false).bool(), DefinitionCodec.predicate(field.get("enabled_when", Map.of())));
        }
        if (field.has("toggle") && only(field, "toggle", "icon")) {
            return new Card.Element.Toggle(field.get("toggle").string(), icon(field));
        }
        if (field.has("choice") && only(field, "choice", "options", "labels", "icons", "icon", "label", "default",
                "run", "enabled_when")) {
            Optional<String> initial = field.has("default")
                ? Optional.of(field.get("default").string()) : Optional.empty();
            Optional<String> run = field.has("run") ? Optional.of(field.get("run").string()) : Optional.empty();
            return new Card.Element.Choice(field.get("choice").string(), field.get("options").list(Field::string),
                field.get("labels", List.of()).list(Field::string), field.get("icons", List.of()).list(Field::string),
                icon(field), label(field), initial, run, DefinitionCodec.predicate(field.get("enabled_when", Map.of())));
        }
        if (field.has("number") && only(field, "number", "min", "max", "step", "icon", "label", "default", "typed")) {
            return new Card.Element.Number(field.get("number").string(), field.get("min").number(),
                field.get("max").number(), field.get("step").number(), icon(field), label(field),
                field.has("default") ? Optional.of(field.get("default").number()) : Optional.empty(),
                field.get("typed", false).bool());
        }
        if (field.has("flag") && only(field, "flag", "icon", "label", "default")) {
            return new Card.Element.Flag(field.get("flag").string(), icon(field), label(field),
                field.get("default", false).bool());
        }
        if (field.has("items") && only(field, "items", "icon", "label", "default", "single", "blocks")) {
            return new Card.Element.Items(field.get("items").string(), icon(field), label(field),
                field.get("default", List.of()).list(each -> ItemFilter.query(each.string()).orElseThrow(each::invalid)),
                field.get("single", false).bool(), field.get("blocks", false).bool());
        }
        if (field.has("lines") && only(field, "lines")) {
            return new Card.Element.Lines();
        }
        if (field.has("rows") && only(field, "rows", "empty")) {
            return new Card.Element.Rows(field.get("rows").string(), field.has("empty")
                ? Optional.of(new Card.Label(field.get("empty").string(), false)) : Optional.empty());
        }
        if (field.has("heading") && only(field, "heading")) {
            return new Card.Element.Heading(new Card.Label(field.get("heading").string(), false));
        }
        if (field.has("note") && only(field, "note")) {
            return new Card.Element.Note(new Card.Label(field.get("note").string(), false));
        }
        if (field.has("readout") && field.has("as") && only(field, "readout", "as", "label")) {
            Field style = field.get("as");
            Card.Style as;
            try {
                as = Card.Style.valueOf(style.string().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw style.invalid();
            }
            return new Card.Element.Readout(field.get("readout").string(), as, label(field));
        }
        throw field.invalid();
    }

    private static Optional<Card.Label> label(Field field) {
        return field.has("label") ? Optional.of(new Card.Label(field.get("label").string(), false))
            : Optional.empty();
    }

    private static Optional<ResourceLocation> icon(Field field) {
        return field.has("icon") ? Optional.of(field.get("icon").id()) : Optional.empty();
    }
}
