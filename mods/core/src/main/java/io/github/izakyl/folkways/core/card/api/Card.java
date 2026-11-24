package io.github.izakyl.folkways.core.card.api;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * What a facility shows the player: elements bound by name to its routines, triggers, parameters and readings, and
 * what the world shows around it while it is selected. A card is data; the client binds the names to values.
 */
public record Card(List<Element> elements, List<Overlay> overlays) {
    public static final Card EMPTY = new Card(List.of(), List.of());

    public Card {
        elements = List.copyOf(elements);
        overlays = List.copyOf(overlays);
    }

    public Card(List<Element> elements) {
        this(elements, List.of());
    }


    /**
     * Drawn in the world while the facility is selected. A range ring is centred on the facility and sized by a numeric
     * parameter, a numeric reading or a probe's declared range. A path is supplied by a named routine.
     */
    public sealed interface Overlay {
        record Range(String reading) implements Overlay {
            public Range {
                Objects.requireNonNull(reading);
            }
        }

        record Path(String routine) implements Overlay {
            public Path {
                Objects.requireNonNull(routine);
            }
        }
    }

    /**
     * One element of a card. A button or toggle may wear an item as its icon; a button may say, as
     * targets its readings must agree with, when it can be pressed. A readout may carry a label in the player's
     * words.
     */
    public record Label(String text, boolean translated) {

        public Component component() {
            return translated ? Component.translatable(text) : Component.literal(text);
        }
    }

    public sealed interface Element {
        record Button(String routine, Optional<ResourceLocation> icon, Map<String, Value> enabledWhen,
                      Optional<Label> label)
            implements Element {
            public Button {
                Objects.requireNonNull(routine);
                Objects.requireNonNull(icon);
                enabledWhen = Map.copyOf(enabledWhen);
            }

            public Button(String routine, Optional<ResourceLocation> icon, Map<String, Value> enabledWhen) {
                this(routine, icon, enabledWhen, Optional.empty());
            }

            public Button(String routine) {
                this(routine, Optional.empty(), Map.of());
            }
        }

        record Target(String routine, Pick pick, Optional<ResourceLocation> icon, Optional<Label> label,
                      boolean defaultCommand, Map<String, Value> enabledWhen, Map<String, Value> arguments)
            implements Element {
            public Target {
                Objects.requireNonNull(routine);
                Objects.requireNonNull(pick);
                Objects.requireNonNull(icon);
                Objects.requireNonNull(label);
                enabledWhen = Map.copyOf(enabledWhen);
                arguments = Map.copyOf(arguments);
            }

            public Target(String routine, Pick pick, Optional<ResourceLocation> icon, Optional<Label> label,
                          boolean defaultCommand, Map<String, Value> enabledWhen) {
                this(routine, pick, icon, label, defaultCommand, enabledWhen, Map.of());
            }

            public static String statusKey(int index) {
                return "target:" + index;
            }
        }

        record Toggle(String trigger, Optional<ResourceLocation> icon) implements Element {
            public Toggle {
                Objects.requireNonNull(trigger);
                Objects.requireNonNull(icon);
            }

            public Toggle(String trigger) {
                this(trigger, Optional.empty());
            }
        }

        sealed interface Parameter extends Element {
            String param();

            Optional<ResourceLocation> icon();

            boolean accepts(Value value);

            Value adjust(Value value, int direction);

            /** The value the parameter takes while nothing has set it. */
            default Optional<Value> fallback() {
                return Optional.empty();
            }
        }

        /** An on/off parameter. */
        record Flag(String param, Optional<ResourceLocation> icon, Optional<Label> label, boolean initial)
            implements Parameter {
            public Flag {
                Objects.requireNonNull(param);
                Objects.requireNonNull(icon);
                Objects.requireNonNull(label);
            }

            @Override
            public boolean accepts(Value value) {
                return value instanceof Value.Bool;
            }

            @Override
            public Value adjust(Value value, int direction) {
                return Value.of(!((Value.Bool) value).on());
            }

            @Override
            public Optional<Value> fallback() {
                return Optional.of(Value.of(initial));
            }
        }

        /**
         * A list of item rules: items, tags and searches. A single one names exactly one item; a list of blocks offers
         * only what places one.
         */
        record Items(String param, Optional<ResourceLocation> icon, Optional<Label> label, List<ItemFilter> initial,
                     boolean single, boolean blocks) implements Parameter {
            public Items {
                Objects.requireNonNull(param);
                Objects.requireNonNull(icon);
                Objects.requireNonNull(label);
                initial = List.copyOf(initial);
            }

            @Override
            public boolean accepts(Value value) {
                return value instanceof Value.Items items && items.filters().size() <= (single ? 1 : 256)
                    && (!single || items.filters().stream().noneMatch(filter -> filter.tag() || filter.query()));
            }

            @Override
            public Value adjust(Value value, int direction) {
                return value;
            }

            @Override
            public Optional<Value> fallback() {
                return Optional.of(new Value.Items(initial));
            }
        }

        /**
         * One value out of a declared list, shown as a row of options. The choice is saved as the facility parameter
         * {@code param}, which routines can reference; with {@code run}, choosing also runs that manual routine with
         * the chosen value as its argument named {@code param}. Each option may have a label in the player's words
         * and an item icon ({@code icons} entries are item IDs, or empty for none). {@code initial} is the option the
         * parameter starts at when the facility does not set it, the first option by default.
         */
        record Choice(String param, List<String> options, List<String> labels, List<String> icons,
                      Optional<ResourceLocation> icon, Optional<Label> label, Optional<String> initial,
                      Optional<String> run, Map<String, Value> enabledWhen) implements Parameter {
            public Choice {
                Objects.requireNonNull(param);
                options = List.copyOf(options);
                labels = List.copyOf(labels);
                icons = List.copyOf(icons);
                Objects.requireNonNull(icon);
                Objects.requireNonNull(label);
                Objects.requireNonNull(initial);
                Objects.requireNonNull(run);
                enabledWhen = Map.copyOf(enabledWhen);
            }

            public Choice(String param, List<String> options, List<String> labels, Optional<ResourceLocation> icon) {
                this(param, options, labels, List.of(), icon, Optional.empty(), Optional.empty(), Optional.empty(),
                    Map.of());
            }

            /** The value the parameter takes when the facility leaves it unset. */
            @Override
            public Optional<Value> fallback() {
                return initial.or(() -> options.stream().findFirst()).map(Value.Text::new);
            }

            /** The option's label, or the option itself when it has none. */
            public String label(int option) {
                return labels.isEmpty() ? options.get(option) : labels.get(option);
            }

            /** The option's icon, if it names one. */
            public Optional<ResourceLocation> icon(int option) {
                return icons.isEmpty() || icons.get(option).isEmpty() ? Optional.empty()
                    : Optional.ofNullable(ResourceLocation.tryParse(icons.get(option)));
            }

            public static String statusKey(int index) {
                return "choice:" + index;
            }

            @Override
            public boolean accepts(Value value) {
                return value instanceof Value.Text text && options.contains(text.value());
            }

            @Override
            public Value adjust(Value value, int direction) {
                int current = options.indexOf(((Value.Text) value).value());
                return new Value.Text(options.get(Math.floorMod(current + direction, options.size())));
            }
        }

        /** A number stepped between bounds; {@code typed} also lets the player type it. */
        record Number(String param, double min, double max, double step, Optional<ResourceLocation> icon,
                      Optional<Label> label, Optional<Double> initial, boolean typed) implements Parameter {
            public Number {
                Objects.requireNonNull(param);
                Objects.requireNonNull(icon);
                Objects.requireNonNull(label);
                Objects.requireNonNull(initial);
            }

            public Number(String param, double min, double max, double step, Optional<ResourceLocation> icon,
                          Optional<Label> label) {
                this(param, min, max, step, icon, label, Optional.empty(), false);
            }

            @Override
            public Optional<Value> fallback() {
                return initial.map(Value::of);
            }

            @Override
            public boolean accepts(Value value) {
                if (!(value instanceof Value.Real real) || real.value() < min || real.value() > max
                    || step <= 0) {
                    return false;
                }
                return real.value() == max || decimal(real.value()).subtract(decimal(min))
                    .remainder(decimal(step)).signum() == 0;
            }

            @Override
            public Value adjust(Value value, int direction) {
                var start = decimal(min);
                var stride = decimal(step);
                var current = decimal(((Value.Real) value).value());
                var index = current.subtract(start).divide(stride, 0, direction > 0
                    ? java.math.RoundingMode.FLOOR : java.math.RoundingMode.CEILING);
                return Value.of(start.add(stride.multiply(index.add(decimal(direction))))
                    .max(start).min(decimal(max)).doubleValue());
            }

            private static java.math.BigDecimal decimal(double value) {
                return java.math.BigDecimal.valueOf(value);
            }
        }

        /** The facility's status lines, as the hover card over it shows them. */
        record Lines() implements Element {
        }

        /** A list the facility supplies under {@code source}, one {@link Row} each, or {@code whenEmpty}. */
        record Rows(String source, Optional<Label> whenEmpty) implements Element {
            public Rows {
                Objects.requireNonNull(source);
                Objects.requireNonNull(whenEmpty);
            }
        }

        /**
         * A heading that groups the elements after it, up to the next heading, in a section of their own. A section
         * gives each setting a line of its own, or with {@link Layout#ROW} lays its controls side by side: switches as
         * buttons, the other settings named above their controls, commands among them.
         */
        record Heading(Label text, Layout layout) implements Element {
            public Heading {
                Objects.requireNonNull(text);
                Objects.requireNonNull(layout);
            }

            public Heading(Label text) {
                this(text, Layout.LINES);
            }
        }

        /** A line of explanation. */
        record Note(Label text) implements Element {
            public Note {
                Objects.requireNonNull(text);
            }
        }

        record Readout(String reading, Style as, Optional<Label> label) implements Element {
            public Readout {
                Objects.requireNonNull(reading);
                Objects.requireNonNull(as);
                Objects.requireNonNull(label);
            }

            public Readout(String reading, Style as) {
                this(reading, as, Optional.empty());
            }
        }
    }

    public enum Pick {
        ENTITY("entity"), POS("pos"), ENTITY_OR_POS("entity|pos"), ITEM("item"), BLOCK("block"),
        GROUND("ground"), ENTITY_OR_BLOCK("entity|block"), SURFACE_POSITION("surface_pos"),
        /** Two blocks picked as opposite corners of a box, sent as its lowest and highest cell. */
        BOX("box");

        private final String key;

        Pick(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public boolean acceptsEntity(boolean item) {
            return switch (this) {
                case ITEM -> item;
                case ENTITY, ENTITY_OR_POS, ENTITY_OR_BLOCK -> true;
                default -> false;
            };
        }

        public boolean acceptsPosition() {
            return this != ENTITY && this != ITEM && this != BOX;
        }

        /** How many coordinates a picked place carries: a block's three, or a box's six. */
        public int coordinates() {
            return this == BOX ? 6 : 3;
        }
    }

    public enum Style {
        TEXT, LIGHT, PROGRESS, SLOTS
    }

    /** How a section lays out what it groups: a line each, or side by side. */
    public enum Layout {
        LINES, ROW
    }

    /** The parameter elements on the card. */
    public List<Element.Parameter> parameters() {
        return elements.stream().filter(Element.Parameter.class::isInstance).map(Element.Parameter.class::cast).toList();
    }

    public Optional<Element.Parameter> parameter(String param) {
        return parameters().stream().filter(each -> each.param().equals(param)).findFirst();
    }

    /** The values set, with every parameter left unset at its card's fallback. */
    public Map<String, Value> withFallbacks(Map<String, Value> values) {
        Map<String, Value> filled = null;
        for (Element.Parameter parameter : parameters()) {
            if (!values.containsKey(parameter.param()) && (filled == null || !filled.containsKey(parameter.param()))) {
                var fallback = parameter.fallback();
                if (fallback.isPresent()) {
                    if (filled == null) {
                        filled = new java.util.LinkedHashMap<>(values);
                    }
                    filled.put(parameter.param(), fallback.get());
                }
            }
        }
        return filled == null ? values : Map.copyOf(filled);
    }

    /**
     * One entry of a {@link Element.Rows} list: what it is, what it says about itself, where it stands in the world
     * (pressing the row shows the place) and up to four acts.
     */
    public record Row(ItemStack icon, Line title, List<Line> details, Optional<BlockPos> at, List<Act> acts) {
        public static final int MOST_ACTS = 4;

        public Row {
            icon = icon.copy();
            Objects.requireNonNull(title);
            details = List.copyOf(details);
            Objects.requireNonNull(at);
            acts = List.copyOf(acts);
            if (acts.size() > MOST_ACTS) {
                throw new IllegalArgumentException("a row holds at most " + MOST_ACTS + " acts");
            }
        }

        public static Row of(ItemStack icon, Line title, List<Line> details) {
            return new Row(icon, title, details, Optional.empty(), List.of());
        }

        public Row at(BlockPos where) {
            return new Row(icon, title, details, Optional.of(where.immutable()), acts);
        }

        public Row with(Act act) {
            var more = new java.util.ArrayList<>(acts);
            more.add(act);
            return new Row(icon, title, details, at, more);
        }
    }

    /** What pressing a row's button does. */
    public sealed interface Act {
        Label label();

        /** Runs one of the facility's routines with these arguments, as a manual order. */
        record Run(String routine, Map<String, Value> arguments, Label label, Optional<ResourceLocation> icon)
            implements Act {
            public Run {
                Objects.requireNonNull(routine);
                arguments = Map.copyOf(arguments);
                Objects.requireNonNull(label);
                Objects.requireNonNull(icon);
            }
        }

        /** Opens another facility's card. */
        record Open(UUID facility, Label label) implements Act {
            public Open {
                Objects.requireNonNull(facility);
                Objects.requireNonNull(label);
            }
        }
    }
}
