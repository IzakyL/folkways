package io.github.izakyl.folkways.core.card.client;

import io.github.izakyl.folkways.core.card.api.notice.Notice;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.net.CardOrders;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.core.front.api.ui.ItemDrops;
import io.github.izakyl.folkways.core.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Segments;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Slot;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * One facility's card on the page, drawn from its elements in card order and the live status the server last sent:
 * commands side by side, settings a line each, readings in a table, lists as rows. Every press goes through
 * {@link ClientCards#press}; a refused one says why under the state line. Labels and icons are the card's own,
 * everything else comes from the locale.
 */
public final class CardView {
    /** The prefix of every element id on a card. */
    public static final String ID = "folkways.card.";
    // The words the command view's facility card has always used; the card shares them.
    static final String KEY = "folkways.rts.facility.";
    static final String ERROR = KEY + "error.";
    private static final ItemStack BUTTON = new ItemStack(Items.REDSTONE_TORCH);
    private static final ItemStack TOGGLE = new ItemStack(Items.CLOCK);
    private static final int GOOD = 0xFF557A46;
    private static final int BAD = 0xFF9A3324;
    private static final int BUSY = 0xFFB07A1E;
    private static final int SWITCHED_ON = 0x3D6429;
    private static final int SWITCHED_OFF = 0x7D7466;
    private static final int GAP = 2;
    private static final float MIN_WIDTH = 164;
    private static final float MAX_ROW = 320;
    // A section's frame: its padding on either side.
    private static final float FRAME = 8;
    private static final float ROWS_WIDTH = 220;
    private static final int FIGURE = 34;
    // A choice with more options than this offers them in a picker rather than side by side.
    private static final long REFUSAL_MS = 6000;
    private static final Map<UUID, Refusal> REFUSED = new ConcurrentHashMap<>();

    /** What the window showing a card does for it. */
    public interface Host {
        /** The hotkey of a command slot, counted from zero, or null when this window gives commands no keys. */
        @Nullable
        default KeyMapping key(int slot) {
            return null;
        }

        /** Whether a target can be picked in the world from this window. */
        default boolean picks() {
            return false;
        }

        /** Starts picking a target in the world for the card's element at {@code index}. */
        default void pick(int index, Card.Element.Target target) {
        }

        /** Shows a place in the world. */
        void ping(BlockPos at);

        /** Opens another facility's card. */
        default void open(UUID colony, UUID facility) {
            CardWindow.open(colony, facility);
        }

        /** Where a picker the card asks for opens; {@code asking} is the element that asked. */
        default UIElement desk(UIElement asking) {
            return asking;
        }

        /** Remembers the picker the card opened, so the window can close it first. */
        default void popup(Dialog dialog) {
        }

        /** Hears a refused press, besides the card saying so. */
        default void refused(Component reason) {
        }

        /** Whether the card says what the facility is running; a bodiless card on a book page runs nothing. */
        default boolean states() {
            return true;
        }

        /** Whether the card scrolls in its own body, or lies in a page that scrolls it (see {@link #card}). */
        default boolean scrolls() {
            return true;
        }
    }

    private record Refusal(Component text, long until) {
    }

    private final CardStatus status;
    private final Host host;
    private final Map<String, Value> values;
    private final Map<Integer, Runnable> slots = new HashMap<>();
    private final List<NumberInput> numbers = new ArrayList<>();
    private UIElement page = Rows.page();
    private ScrollerView body = Rows.fills(Rows.box());
    private float nameWidth;
    private float widest = MIN_WIDTH;
    private float estimate;
    private Runnable[] refusalTick = new Runnable[1];
    private UIElement strip;
    private UIElement table;
    // Where the next element goes: the page, or the body of the section a heading opened.
    private UIElement into;
    // The sections, side by side while they fit.
    private UIElement shelf;
    private UIElement section;
    // Whether the open section lays its controls side by side, and how long their line has grown.
    private boolean side;
    private float reach;

    private CardView(CardStatus status, Host host) {
        this.status = status;
        this.host = host;
        this.values = status.card().withFallbacks(status.parameters());
    }

    /** The card drawn for this status, in a scroll view named as the card's body. */
    public static CardView of(CardStatus status, Host host) {
        CardView view = new CardView(status, host);
        view.build();
        Runnable[] tick = view.refusalTick;
        view.page.addEventListener(UIEvents.TICK, event -> tick[0].run());
        return view;
    }

    /** A card with nothing to draw yet: the words say why. */
    public static ScrollerView waiting(Component words) {
        ScrollerView body = Rows.fills(Rows.box());
        body.addScrollViewChild(Rows.page().addChildren(Rows.note(words)));
        Tokens.name(body, ID + "waiting");
        return body;
    }

    public ScrollerView body() {
        return body;
    }

    /** The card itself, for a host that does not let it scroll on its own. */
    public UIElement card() {
        return page;
    }

    /** Redraws from new status, keeping a focused number field attached while its setting still exists. */
    public CardView refresh(CardStatus latest) {
        if (status == latest) {
            return this;
        }
        return redraw(latest);
    }

    /** Redraws even when only the host's display inputs have changed. */
    public CardView redraw(CardStatus latest) {
        CardView next = CardView.of(latest, host);
        NumberInput editing = numbers.stream().filter(input -> input.field().isFocused()).findFirst().orElse(null);
        boolean kept = false;
        if (editing != null && status.facility().equals(latest.facility()) && status.colony().equals(latest.colony())) {
            for (int i = 0; i < next.numbers.size(); i++) {
                NumberInput fresh = next.numbers.get(i);
                if (fresh.number().equals(editing.number())) {
                    // The field being typed in stays, and so does every element it lies in up to the page; each
                    // takes the fresh one's children around it.
                    List<UIElement[]> path = new ArrayList<>();
                    UIElement old = editing.field();
                    UIElement drawn = fresh.field();
                    while (old != null && drawn != null && old != page) {
                        path.add(new UIElement[] {old.getParent(), drawn.getParent(), old, drawn});
                        old = old.getParent();
                        drawn = drawn.getParent();
                    }
                    if (old == page && drawn == next.page) {
                        if (status != latest) {
                            editing.refreshFrom(fresh);
                        }
                        path.forEach(step -> replaceChildren(step[0], step[1], step[2], step[3]));
                        next.numbers.set(i, editing);
                        kept = true;
                    }
                    break;
                }
            }
        }
        if (!kept) {
            replaceChildren(page, next.page, null, null);
        }
        refusalTick[0] = next.refusalTick[0];
        next.refusalTick = refusalTick;
        next.page = page;
        next.body = body;
        return next;
    }

    private static void replaceChildren(UIElement target, UIElement source, UIElement kept, UIElement replacement) {
        for (UIElement child : target.getSafeChildren()) {
            if (child != kept) {
                target.removeChild(child);
            }
        }
        int index = 0;
        for (UIElement child : source.getSafeChildren()) {
            if (child != replacement) {
                target.addChildAt(child, index);
            }
            index++;
        }
    }

    public UUID facility() {
        return status.facility();
    }

    /** Presses the command in this hotkey slot; false when the card has none there or it cannot be pressed now. */
    public boolean press(int slot) {
        Runnable action = slots.get(slot);
        if (action == null) {
            return false;
        }
        action.run();
        return true;
    }

    /** How wide the card would like to be: its widest line, within reason. */
    public float width() {
        return Math.min(MAX_ROW, widest);
    }

    /** How tall the card is once laid out, or as near as it can tell before. */
    public float height() {
        float laid = page.getSizeHeight();
        return laid > 0 ? laid + 4 : estimate;
    }

    // ---------------------------------------------------------------- building

    private void build() {
        Tokens.name(body, ID + "body");
        Tokens.name(page, ID + "card");
        if (host.states()) {
            page.addChildren(state());
        }
        page.addChildren(refusal());
        into = page;
        List<Card.Element> elements = status.card().elements();
        // Settings a line each share a column for their names; those laid side by side wear theirs above.
        boolean sideBySide = false;
        for (Card.Element element : elements) {
            if (element instanceof Card.Element.Heading heading) {
                sideBySide = heading.layout() == Card.Layout.ROW;
            } else if (!sideBySide && (element instanceof Card.Element.Choice || element instanceof Card.Element.Number
                || element instanceof Card.Element.Flag || element instanceof Card.Element.Items)) {
                nameWidth = Math.max(nameWidth, Name.width(icon(((Card.Element.Parameter) element).icon(), ItemStack.EMPTY),
                    nameOf(element), element instanceof Card.Element.Choice ? "WW" : ""));
            }
        }
        int slot = 0;
        for (int index = 0; index < elements.size(); index++) {
            Card.Element element = elements.get(index);
            if (!(element instanceof Card.Element.Button || element instanceof Card.Element.Target
                || element instanceof Card.Element.Toggle)) {
                strip = null;
            }
            if (!(element instanceof Card.Element.Readout)) {
                table = null;
            }
            switch (element) {
                case Card.Element.Button button -> command(button(button, index, slot++));
                case Card.Element.Target target -> command(target(target, index, slot++));
                case Card.Element.Toggle toggle -> command(toggle(toggle, index, slot++));
                case Card.Element.Choice choice -> choice(choice, index, slot++);
                case Card.Element.Number number -> {
                    number(number, index, slot);
                    slot += 2;
                }
                case Card.Element.Flag flag -> flag(flag, index);
                case Card.Element.Items items -> items(items, index);
                case Card.Element.Readout readout -> readout(readout);
                case Card.Element.Lines ignored -> add(lines());
                case Card.Element.Rows rows -> add(rows(rows, index));
                case Card.Element.Heading heading -> section(heading, index);
                case Card.Element.Note note -> {
                    Label words = Rows.note(note.text().component());
                    Tokens.name(words, ID + "note." + index);
                    add(words);
                    grow(12 + 5);
                }
            }
        }
        close();
        if (elements.isEmpty()) {
            page.addChildren(Rows.note(Component.translatable(KEY + "card.none")));
            grow(12 + 5);
        }
        grow(12);
        if (host.scrolls()) {
            body.addScrollViewChild(page);
        }
    }

    /**
     * A command joins the strip of commands before it, or starts one; strips wrap when the card is narrow. In a
     * section laid side by side it takes its place among the settings.
     */
    private void command(CommandButton press) {
        if (side) {
            flow(press, press.width());
            return;
        }
        if (strip == null) {
            strip = Rows.strip();
            strip.layout(layout -> layout.gapAll(GAP).flexWrap(FlexWrap.WRAP).flexShrink(0));
            strip.addClass("folkways_card_commands");
            add(strip);
            grow(CommandButton.SIZE + 5);
        }
        float line = strip.getChildren().stream().map(CommandButton.class::cast)
            .reduce(0f, (sum, each) -> sum + each.width() + GAP, Float::sum);
        float within = line % MAX_ROW;
        if (within > 0 && within + press.width() > MAX_ROW) {
            grow(CommandButton.SIZE + GAP);
        }
        widest = Math.max(widest, Math.min(MAX_ROW, line + press.width()));
        strip.addChildren(press);
    }

    // ---------------------------------------------------------------- sections

    private void add(UIElement element) {
        into.addChildren(element);
    }

    /**
     * A heading opens a section, framed on the page, that holds what follows up to the next heading. Sections laid
     * side by side stand beside each other while the card is wide enough; one of lines spans the card.
     */
    private void section(Card.Element.Heading heading, int index) {
        close();
        side = heading.layout() == Card.Layout.ROW;
        if (!side) {
            // A section of lines spans the page on its own: in a wrapping shelf its width would be indefinite and
            // its lines would collapse.
            shelf = null;
        } else if (shelf == null) {
            shelf = new UIElement();
            shelf.layout(layout -> layout.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).widthPercent(100)
                .gapAll(GAP).flexShrink(0));
            Tokens.name(shelf, ID + "sections");
            page.addChildren(shelf);
        }
        section = new UIElement();
        section.addClass("folkways_card");
        section.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).paddingAll(FRAME / 2).gapAll(2)
            .flexShrink(side ? 1 : 0).minWidth(0));
        if (side) {
            section.layout(layout -> layout.flexGrow(1));
        } else {
            section.layout(layout -> layout.widthPercent(100));
        }
        Tokens.name(section, ID + "section." + index);
        UIElement title = Rows.heading(heading.text().component());
        title.layout(layout -> layout.height(12));
        Tokens.name(title, ID + "heading." + index);
        section.addChildren(title);
        (side ? shelf : page).addChildren(section);
        if (side) {
            UIElement flow = new UIElement();
            flow.layout(layout -> layout.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).widthPercent(100)
                .alignItems(AlignItems.FLEX_END).gapAll(GAP));
            section.addChildren(flow);
            into = flow;
        } else {
            into = section;
        }
        reach = 0;
        grow(12 + 2 + FRAME + GAP);
    }

    /** Ends the open section: one laid side by side asks for the width of its line, to stand beside the next. */
    private void close() {
        if (section != null && side) {
            float wanted = Math.min(MAX_ROW, reach + FRAME);
            section.layout(layout -> layout.width(wanted));
            widest = Math.max(widest, wanted);
            grow((float) Math.ceil(reach / (MAX_ROW - FRAME)) * (34 + GAP));
        }
        section = null;
        strip = null;
        table = null;
    }

    /** Lays a control in the open section's line. */
    private void flow(UIElement control, float width) {
        into.addChildren(control);
        reach += (reach > 0 ? GAP : 0) + width;
    }

    /**
     * Lays a setting: on a line of its own, its name in the card's shared column and its controls at the far end;
     * in a section laid side by side, its name above its controls.
     */
    private void lay(String id, Name name, float width, float height, UIElement... controls) {
        if (side) {
            UIElement field = new UIElement();
            field.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).gapAll(1).flexShrink(0));
            UIElement under = new UIElement();
            under.layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(GAP));
            under.addChildren(controls);
            field.addChildren(name, under);
            Tokens.name(field, id);
            flow(field, Math.max(name.width(), width));
            return;
        }
        UIElement row = setting(id, name);
        row.addChildren(Rows.spacer());
        row.addChildren(controls);
        add(row);
        widest = Math.max(widest, Math.min(MAX_ROW, nameWidth + width + 3 * GAP + 4 + FRAME));
        grow(Math.max(height, 12) + 4 + 5);
    }

    // ---------------------------------------------------------------- state

    /** What the facility is doing: the routine it is running, or how its last run ended. */
    private UIElement state() {
        UIElement line = Rows.strip();
        line.layout(layout -> layout.gapAll(4).height(12).flexShrink(0));
        Tokens.name(line, ID + "state");
        int color;
        Component text;
        Optional<Operating.Recent> recent = status.recent();
        if (!status.running().isEmpty()) {
            color = BUSY;
            text = Component.translatable(KEY + "running", Component.literal(String.join(", ", status.running())));
        } else if (recent.isPresent()) {
            color = recent.get().ending().equals("done") ? GOOD : BAD;
            text = Component.translatable(KEY + "last", Component.literal(recent.get().routine()), ending(recent.get()));
        } else {
            color = Rows.QUIET;
            text = Component.translatable(KEY + "idle");
        }
        Label label = Rows.value(text);
        label.textStyle(style -> style.textColor(color));
        line.addChildren(new Lamp(color), label);
        grow(12 + 5);
        return line;
    }

    /** Why the last press on this card was refused, for a few seconds after. */
    private UIElement refusal() {
        Label line = Rows.text(Component.empty());
        line.textStyle(style -> style.textColor(BAD));
        Tokens.name(line, ID + "refusal");
        UUID facility = status.facility();
        Refusal[] drawn = {null};
        Runnable shown = () -> {
            Refusal refusal = REFUSED.get(facility);
            boolean showing = refusal != null && Util.getMillis() < refusal.until();
            if (!showing && refusal != null) {
                REFUSED.remove(facility, refusal);
            }
            if (showing != line.isDisplayed()) {
                line.setDisplay(showing);
            }
            if (showing && refusal != drawn[0]) {
                drawn[0] = refusal;
                line.setText(refusal.text());
            }
        };
        shown.run();
        // A hidden element does not tick, so the card itself ticks the refusal line.
        refusalTick[0] = shown;
        return line;
    }

    private static Component ending(Operating.Recent recent) {
        var ending = Component.translatable(KEY + "ending." + switch (recent.ending()) {
            case "done", "failed", "dropped", "revoked", "refused" -> recent.ending();
            default -> "other";
        });
        recent.reason().ifPresent(reason -> ending.append(" · ").append(reason.startsWith("folkways.")
            ? Component.translatable(reason) : Component.literal(reason)));
        return ending;
    }

    // ---------------------------------------------------------------- presses

    private void send(CardOrders.Press press) {
        UUID facility = status.facility();
        ClientCards.press(status.colony(), facility, press, refused -> {
            if (refused.isEmpty()) {
                REFUSED.remove(facility);
                return;
            }
            refusal(refused.get()).ifPresent(text -> {
                REFUSED.put(facility, new Refusal(text, Util.getMillis() + REFUSAL_MS));
                host.refused(text);
            });
        });
    }

    /** What a refusal key says to the player; a stale press, overtaken by a newer one, says nothing. */
    static Optional<Component> refusal(Notice notice) {
        String key = notice.key();
        if (key.equals("stale")) {
            return Optional.empty();
        }
        if (key.startsWith(CardOrders.REASON)) {
            return Optional.of(notice.component());
        }
        String known = switch (key) {
            case "missing", "hidden", "manual", "unloaded", "refused", "background", "changed", "pick", "parameter" ->
                key;
            default -> key.startsWith("disabled.") ? key : "invalid";
        };
        return Optional.of(Component.translatable(ERROR + known));
    }

    private Optional<String> disabled(String key) {
        return Optional.ofNullable(status.disabled().get(key));
    }

    private static Component why(String reason) {
        return Component.translatable(KEY + "disabled." + reason).withStyle(ChatFormatting.RED);
    }

    private void act(CommandButton press, int slot, Runnable action) {
        if (press.isActive()) {
            slots.put(slot, action);
        }
        press.setOnClick(event -> {
            if (event.button == 0 && press.isActive()) {
                action.run();
            }
        });
    }

    // ---------------------------------------------------------------- commands

    private CommandButton button(Card.Element.Button button, int index, int slot) {
        String routine = button.routine();
        KeyMapping key = host.key(slot);
        Component label = label(button.label(), routine);
        CommandButton press = new CommandButton(icon(button.icon(), BUTTON)).key(key).label(label).paper();
        Tokens.name(press, ID + "button." + routine);
        Optional<String> disabled = disabled(routine);
        press.setActive(disabled.isEmpty());
        press.style(style -> style.tooltips(named(key, label),
            disabled.map(CardView::why).orElseGet(() -> Component.translatable(KEY + "button.tip"))));
        act(press, slot, () -> send(new CardOrders.Press.Run(index, routine)));
        return press;
    }

    private CommandButton target(Card.Element.Target target, int index, int slot) {
        KeyMapping key = host.key(slot);
        Component label = label(target.label(), target.routine());
        CommandButton press = new CommandButton(icon(target.icon(), BUTTON)).key(key).label(label)
            .byDefault(target.defaultCommand() && host.picks()).paper();
        Tokens.name(press, ID + "target." + target.routine());
        Optional<String> disabled = disabled(Card.Element.Target.statusKey(index));
        press.setActive(disabled.isEmpty() && host.picks());
        Component tip = disabled.map(CardView::why).orElseGet(() -> host.picks()
            ? Component.translatable(KEY + (target.defaultCommand() ? "target.default" : "target.tip"))
            : Component.translatable(ID + "target.elsewhere").withStyle(ChatFormatting.GRAY));
        press.style(style -> style.tooltips(named(key, label), tip));
        act(press, slot, () -> host.pick(index, target));
        return press;
    }

    private CommandButton toggle(Card.Element.Toggle toggle, int index, int slot) {
        String trigger = toggle.trigger();
        KeyMapping key = host.key(slot);
        boolean on = status.triggers().getOrDefault(trigger, false);
        CommandButton press = new CommandButton(icon(toggle.icon(), TOGGLE)).key(key)
            .label(Component.literal(trigger)).lamp(on).paper();
        Tokens.name(press, ID + "toggle." + trigger);
        if (on) {
            press.addClass("folkways_chosen");
        }
        press.style(style -> style.tooltips(named(key, Component.literal(trigger)),
            Component.translatable(on ? KEY + "toggle.on" : KEY + "toggle.off")
                .withStyle(on ? ChatFormatting.DARK_GREEN : ChatFormatting.GRAY),
            Component.translatable(KEY + "toggle.tip")));
        act(press, slot, () -> send(new CardOrders.Press.Toggle(index, trigger, !on)));
        return press;
    }

    // ---------------------------------------------------------------- settings

    /** A setting's line: its name in the shared column, then its control. */
    private UIElement setting(String id, Name name) {
        UIElement line = new UIElement();
        line.addClass("folkways_row");
        line.layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).flexWrap(FlexWrap.WRAP)
            .alignItems(AlignItems.CENTER).gapAll(GAP).paddingAll(2).flexShrink(0));
        Tokens.name(line, id);
        float shared = nameWidth;
        name.layout(layout -> layout.width(shared));
        line.addChildren(name);
        return line;
    }

    /**
     * A choice: its name with the hotkey that steps to the next option, then its options butted together as one row of
     * tags with the chosen one pressed in and ticked. A list too long for that is one button that opens a picker instead.
     */
    private void choice(Card.Element.Choice choice, int index, int slot) {
        KeyMapping key = host.key(slot);
        Value value = values.get(choice.param());
        int chosen = value instanceof Value.Text text ? choice.options().indexOf(text.value()) : -1;
        Optional<String> disabled = disabled(Card.Element.Choice.statusKey(index));
        Component title = label(choice.label(), choice.param());
        Name name = new Name(icon(choice.icon(), ItemStack.EMPTY), title, key);
        boolean icons = IntStream.range(0, choice.options().size())
            .anyMatch(option -> !optionIcon(choice, option).isEmpty());
        UIElement set = new UIElement();
        set.layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER).gapAll(0)
            .flexShrink(0));
        float width = 0;
        for (int option = 0; option < choice.options().size(); option++) {
            int picked = option;
            Component words = optionLabel(choice, option);
            boolean selected = option == chosen;
            Chip chip = new Chip(optionIcon(choice, option), words, icons, selected);
            Tokens.name(chip, ID + "choice." + choice.param() + "." + choice.options().get(option));
            chip.setActive(disabled.isEmpty());
            Component tip = disabled.map(CardView::why).orElseGet(() -> Component.translatable(selected
                ? KEY + "choice.current" : choice.run().isPresent() ? KEY + "choice.run" : KEY + "choice.tip"));
            chip.style(style -> style.tooltips(named(key, title).copy().append(Component.literal(" · ")
                .withStyle(ChatFormatting.GRAY)).append(words), tip));
            chip.setOnClick(event -> {
                if (event.button == 0 && chip.isActive() && (picked != chosen || choice.run().isPresent())) {
                    send(new CardOrders.Press.Choose(index, choice.param(), picked));
                }
            });
            set.addChildren(chip);
            width += chip.width();
        }
        // What does not fit beside the name, or above in a section laid side by side, is picked from a list.
        float room = MAX_ROW - FRAME - (side ? 0 : nameWidth + 3 * GAP + 4 + FRAME);
        if (choice.options().size() > Segments.MOST || width > room) {
            lay(ID + "choice." + choice.param(), name, 120, 20, opener(choice, index, chosen, disabled, title));
        } else {
            lay(ID + "choice." + choice.param(), name, Math.max(0, width), Chip.height(icons), set);
        }
        if (disabled.isEmpty() && !choice.options().isEmpty()) {
            int next = Math.floorMod(chosen + 1, choice.options().size());
            slots.put(slot, () -> send(new CardOrders.Press.Choose(index, choice.param(), next)));
        }
    }

    /** A long choice's button: what is chosen, worn as its icon and name; a click opens the options. */
    private Button opener(Card.Element.Choice choice, int index, int chosen, Optional<String> disabled,
                          Component title) {
        Button press = Rows.wearing();
        press.layout(layout -> layout.widthAuto().minWidth(80).maxWidth(160).height(20).flexShrink(1));
        if (chosen >= 0) {
            press.addChildren(Rows.icon(optionIcon(choice, chosen)), Rows.worn(optionLabel(choice, chosen)));
        } else {
            press.addChildren(Rows.worn(Rows.placeholder(Component.translatable(ID + "choice.none"))));
        }
        Tokens.name(press, ID + "choice." + choice.param() + ".open");
        press.setActive(disabled.isEmpty());
        press.style(style -> style.tooltips(title, disabled.map(CardView::why)
            .orElseGet(() -> Component.translatable(ID + "choice.open"))));
        press.setOnClick(event -> {
            if (event.button != 0 || !press.isActive()) {
                return;
            }
            List<Picker.Row> rows = new ArrayList<>();
            for (int option = 0; option < choice.options().size(); option++) {
                rows.add(new Picker.Row(choice.options().get(option), optionLabel(choice, option),
                    optionIcon(choice, option)));
            }
            host.popup(Picker.open(press, title, rows,
                chosen >= 0 ? choice.options().get(chosen) : null, picked -> {
                    int option = choice.options().indexOf(picked);
                    if (option >= 0 && (option != chosen || choice.run().isPresent())) {
                        send(new CardOrders.Press.Choose(index, choice.param(), option));
                    }
                }));
        });
        return press;
    }

    /**
     * An option's words: its label, or for an unlabelled option that names a game object, that object's name.
     */
    static Component optionLabel(Card.Element.Choice choice, int option) {
        if (choice.labels().isEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(choice.options().get(option));
            if (id != null) {
                return Picker.labelOf(id);
            }
        }
        return Component.literal(choice.label(option));
    }

    private static ItemStack optionIcon(Card.Element.Choice choice, int option) {
        Optional<ItemStack> named = choice.icon(option).flatMap(BuiltInRegistries.ITEM::getOptional)
            .filter(item -> item != Items.AIR).map(ItemStack::new);
        if (named.isPresent() || !choice.labels().isEmpty() || !choice.icons().isEmpty()) {
            return named.orElse(ItemStack.EMPTY);
        }
        ResourceLocation id = ResourceLocation.tryParse(choice.options().get(option));
        return id == null ? ItemStack.EMPTY : Picker.iconOf(id);
    }

    /** A number between its bounds: a step down, the value (typed, when the card allows), a step up. */
    private void number(Card.Element.Number number, int index, int slot) {
        String param = number.param();
        Value value = values.get(param);
        Component title = label(number.label(), param);
        Name name = new Name(icon(number.icon(), ItemStack.EMPTY), title, null);
        CommandButton down = stepper(number, index, slot + 1, -1, value, title);
        CommandButton up = stepper(number, index, slot, 1, value, title);
        UIElement shown;
        Component amount = value instanceof Value.Real real ? Component.literal(figure(real.value()))
            : Component.literal("–");
        if (number.typed()) {
            NumberInput input = new NumberInput(number, amount.getString(), text -> {
                try {
                    double typed = Double.parseDouble(text.trim());
                    if (!(value instanceof Value.Real real) || real.value() != typed) {
                        send(new CardOrders.Press.Set(index, param, Value.of(typed)));
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // An empty or half-typed field leaves the value as it was.
                }
                return false;
            });
            numbers.add(input);
            TextField field = input.field();
            field.setNumbersOnlyDouble(number.min(), number.max());
            field.textFieldStyle(style -> style.placeholder(Component.empty()));
            field.setText(amount.getString(), false);
            field.layout(layout -> layout.width(FIGURE + 6).height(16).flexShrink(0));
            field.style(style -> style.tooltips(title, Component.translatable(ID + "number.typed",
                figure(number.min()), figure(number.max()))));
            shown = field;
            Tokens.name(field, ID + "number." + param + ".field");
        } else {
            Label figure = Rows.value(amount);
            figure.layout(layout -> layout.width(FIGURE).flexShrink(0));
            figure.textStyle(style -> style.textAlignHorizontal(
                com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal.CENTER).adaptiveWidth(false));
            Tokens.name(figure, ID + "number." + param + ".value");
            shown = figure;
        }
        lay(ID + "number." + param, name, 2 * CommandButton.SIZE + FIGURE + 6 + 2 * GAP, CommandButton.SIZE,
            down, shown, up);
    }

    private CommandButton stepper(Card.Element.Number number, int index, int slot, int direction, Value value,
                                  Component title) {
        KeyMapping key = host.key(slot);
        CommandButton press = new CommandButton(ItemStack.EMPTY).glyph(direction > 0 ? "+" : "−").key(key).paper();
        Tokens.name(press, ID + "number." + number.param() + (direction > 0 ? ".increase" : ".decrease"));
        press.setActive(value != null && number.accepts(value) && !number.adjust(value, direction).equals(value));
        press.style(style -> style.tooltips(named(key, title),
            Component.translatable(KEY + (direction > 0 ? "number.increase" : "number.decrease"))));
        act(press, slot, () -> send(new CardOrders.Press.Step(index, number.param(), direction)));
        return press;
    }

    /**
     * An on/off setting: its name, then a box ticked while it is on. In a section laid side by side it is a tag of its
     * own, ticked or crossed, that switches it.
     */
    private void flag(Card.Element.Flag flag, int index) {
        String param = flag.param();
        boolean on = values.get(param) instanceof Value.Bool bool && bool.on();
        Component title = label(flag.label(), param);
        Component tip = Component.translatable(on ? KEY + "value.on" : KEY + "value.off");
        if (side) {
            ItemStack worn = icon(flag.icon(), ItemStack.EMPTY);
            Component words = Component.literal(on ? "✓ " : "× ").append(title).withColor(on ? SWITCHED_ON : SWITCHED_OFF);
            Button press = Rows.wearing();
            press.layout(layout -> layout.widthAuto().height(CommandButton.SIZE).flexShrink(0).gapAll(2)
                .paddingHorizontal(4));
            if (!worn.isEmpty()) {
                press.addChildren(Rows.icon(worn));
            }
            press.addChildren(Rows.worn(words));
            press.style(style -> style.tooltips(title, tip));
            press.setOnClick(event -> {
                if (event.button == 0) {
                    send(new CardOrders.Press.Set(index, param, Value.of(!on)));
                }
            });
            Tokens.name(press, ID + "flag." + param);
            flow(press, 8 + (worn.isEmpty() ? 0 : 18) + Minecraft.getInstance().font.width(words));
            return;
        }
        Toggle box = new Toggle().noText();
        box.layout(layout -> layout.flexShrink(0));
        box.setOn(on, false);
        box.setOnToggleChanged(next -> {
            if (next != on) {
                send(new CardOrders.Press.Set(index, param, Value.of(next)));
            }
        });
        box.style(style -> style.tooltips(title, tip));
        Tokens.name(box, ID + "flag." + param);
        lay(ID + "flag." + param + ".line", new Name(icon(flag.icon(), ItemStack.EMPTY), title, null), 16, 16, box);
    }

    /** A list of item rules: the first one's icon and what they come to; a click opens the item picker on them. */
    private void items(Card.Element.Items items, int index) {
        String param = items.param();
        List<ItemFilter> held = values.get(param) instanceof Value.Items list ? list.filters() : List.of();
        Component title = label(items.label(), param);
        Name name = new Name(icon(items.icon(), ItemStack.EMPTY), title, null);
        Button press = Rows.wearing();
        press.layout(layout -> layout.widthAuto().minWidth(80).maxWidth(160).height(20).flexShrink(1));
        Component summary = held.isEmpty() ? Rows.placeholder(Component.translatable(ID + "items.none"))
            : held.size() == 1 ? ItemPicker.labelOf(held.getFirst())
            : Component.translatable(ID + "items.more", ItemPicker.labelOf(held.getFirst()), held.size() - 1);
        if (!held.isEmpty()) {
            press.addChildren(Rows.icon(ItemPicker.iconOf(held.getFirst())));
        }
        Label words = Rows.worn(summary);
        words.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        press.addChildren(words);
        Tokens.name(press, ID + "items." + param);
        List<Component> tips = new ArrayList<>();
        tips.add(title);
        held.stream().limit(8).map(ItemPicker::labelOf)
            .forEach(each -> tips.add(each.copy().withStyle(ChatFormatting.GRAY)));
        tips.add(Component.translatable(ID + "items.tip"));
        press.style(style -> style.tooltips(tips.toArray(Component[]::new)));
        press.setOnClick(event -> {
            if (event.button != 0) {
                return;
            }
            UIElement desk = host.desk(press);
            if (items.single()) {
                ResourceLocation current = held.stream().filter(filter -> filter.kind() == ItemFilter.Kind.ITEM)
                    .map(ItemFilter::id).findFirst().orElse(null);
                host.popup(ItemPicker.one(desk, title, current, items.blocks(), id ->
                    send(new CardOrders.Press.Set(index, param, new Value.Items(List.of(ItemFilter.item(id)))))));
            } else {
                host.popup(ItemPicker.many(desk, title, held, items.blocks(), filters ->
                    send(new CardOrders.Press.Set(index, param, new Value.Items(filters)))));
            }
        });
        // An item dragged in from a recipe viewer is taken as the picker would take it: one item names it alone;
        // a list adds the item, or the tag or search a dragged rule stands for.
        ItemDrops.on(press, new ItemDrops.Drop() {
            @Override
            public boolean accepts(ItemStack stack) {
                return dropped(stack).isPresent();
            }

            @Override
            public void take(ItemStack stack) {
                dropped(stack).ifPresent(filter -> {
                    List<ItemFilter> next = new ArrayList<>(items.single() ? List.of() : held);
                    if (!next.contains(filter)) {
                        next.add(filter);
                    }
                    Value value = new Value.Items(next);
                    if (items.accepts(value)) {
                        send(new CardOrders.Press.Set(index, param, value));
                    }
                });
            }

            private Optional<ItemFilter> dropped(ItemStack stack) {
                if (stack.isEmpty()) {
                    return Optional.empty();
                }
                if (items.single()) {
                    return Optional.of(stack.getItem()).filter(item -> !items.blocks() || item instanceof BlockItem)
                        .map(item -> ItemFilter.item(BuiltInRegistries.ITEM.getKey(item)));
                }
                return FilterStacks.parse(stack).filter(filter -> !items.blocks() || filter.kind() != ItemFilter.Kind.ITEM
                    || BuiltInRegistries.ITEM.get(filter.id()) instanceof BlockItem);
            }
        });
        lay(ID + "items." + param + ".line", name, 120, 20, press);
    }

    // ---------------------------------------------------------------- what the facility tells

    private void readout(Card.Element.Readout readout) {
        if (table == null) {
            table = Rows.table();
            add(table);
        }
        Optional<CardStatus.Reading> reading = status.readings().stream()
            .filter(each -> each.name().equals(readout.reading())).findFirst();
        table.addChildren(readout(readout, reading));
        grow(Rows.ROW_HEIGHT + 1);
    }

    private UIElement readout(Card.Element.Readout readout, Optional<CardStatus.Reading> reading) {
        UIElement line = Rows.row();
        Tokens.name(line, ID + "readout." + readout.reading());
        line.addChildren(Rows.name(label(readout.label(), readout.reading())));
        Optional<Value> value = reading.flatMap(CardStatus.Reading::value);
        if (value.isEmpty()) {
            Label none = Rows.value(Component.translatable(KEY + "value.none"));
            none.textStyle(style -> style.textColor(Rows.QUIET));
            return line.addChildren(none);
        }
        switch (readout.as()) {
            case LIGHT -> {
                boolean on = lit(value.get());
                Label words = Rows.value(Component.translatable(on ? KEY + "value.on" : KEY + "value.off"));
                words.textStyle(style -> style.textColor(on ? GOOD : Rows.QUIET));
                line.addChildren(new Lamp(on ? 0xFF6FCF5A : 0xFF5A4E40), words);
            }
            case PROGRESS -> {
                double[] range = reading.flatMap(CardStatus.Reading::range).orElse(new double[] {0, 1});
                double fraction = fraction(value.get(), range);
                UIElement meter = Rows.meter();
                Rows.meterFill(meter, (float) (fraction * 100), GOOD);
                meter.layout(layout -> layout.flexGrow(0).width(60));
                line.addChildren(meter, Rows.value(Component.literal(Math.round(fraction * 100) + "%")));
            }
            case SLOTS -> {
                List<ItemStack> stacks = stacks(value.get());
                if (stacks.isEmpty()) {
                    line.addChildren(Rows.value(text(value.get())));
                } else {
                    for (ItemStack stack : stacks.subList(0, Math.min(6, stacks.size()))) {
                        line.addChildren(new Slot(stack));
                    }
                }
            }
            case TEXT -> line.addChildren(Rows.value(text(value.get())));
        }
        return line;
    }

    /** The facility's status lines, as its hover card tells them. */
    private UIElement lines() {
        UIElement told = new UIElement();
        told.addClass("folkways_card");
        told.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingAll(4).gapAll(2)
            .flexShrink(0));
        Tokens.name(told, ID + "lines");
        for (Line line : status.lines()) {
            CardLines.of(line, Rows.INK).forEach(told::addChildren);
        }
        if (status.lines().isEmpty()) {
            told.addChildren(Rows.note(Component.translatable(KEY + "value.none")));
        }
        grow(Math.max(1, status.lines().size()) * 14 + 8 + 5);
        return told;
    }

    /** A list the facility supplies: a compact row each, which shows its place when pressed, with its acts. */
    private UIElement rows(Card.Element.Rows list, int index) {
        String source = list.source();
        List<Card.Row> rows = status.rows().getOrDefault(source, List.of());
        UIElement shown = Rows.table();
        Tokens.name(shown, ID + "rows." + source);
        if (rows.isEmpty()) {
            list.whenEmpty().ifPresent(words -> {
                shown.addChildren(Rows.note(words.component()));
                grow(12 + 5);
            });
            return shown;
        }
        for (int at = 0; at < rows.size(); at++) {
            shown.addChildren(row(source, index, at, rows.get(at)));
        }
        widest = Math.max(widest, ROWS_WIDTH);
        return shown;
    }

    private UIElement row(String source, int index, int at, Card.Row row) {
        UIElement line = new UIElement();
        line.addClass("folkways_row");
        line.layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).alignItems(AlignItems.CENTER)
            .gapAll(4).paddingAll(2).minHeight(20).flexShrink(0));
        String id = ID + "rows." + source + "." + at;
        Tokens.name(line, id);
        if (!row.icon().isEmpty()) {
            line.addChildren(Rows.icon(row.icon()));
        }
        UIElement words = new UIElement();
        words.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).flexGrow(1).flexShrink(1).minWidth(0)
            .gapAll(1));
        CardLines.of(row.title(), Rows.INK).forEach(words::addChildren);
        row.details().forEach(detail -> CardLines.of(detail, Rows.QUIET).forEach(words::addChildren));
        line.addChildren(words);
        for (int act = 0; act < row.acts().size(); act++) {
            line.addChildren(act(id, index, source, at, act, row.acts().get(act)));
        }
        row.at().ifPresent(where -> {
            line.style(style -> style.tooltips(CardLines.words(row.title()),
                Component.translatable(ID + "rows.ping").withStyle(ChatFormatting.GRAY)));
            line.addEventListener(UIEvents.CLICK, event -> {
                if (event.button == 0 && !(event.target instanceof Button)) {
                    host.ping(where);
                }
            });
        });
        grow(Math.max(20, 12 * (1 + row.details().size())) + 4 + 1);
        return line;
    }

    private CommandButton act(String row, int index, String source, int at, int number, Card.Act act) {
        ItemStack icon = act instanceof Card.Act.Run run ? icon(run.icon(), ItemStack.EMPTY) : ItemStack.EMPTY;
        Component label = act.label().component();
        CommandButton press = new CommandButton(icon).paper();
        if (icon.isEmpty()) {
            press.label(label);
        }
        Tokens.name(press, row + ".act." + number);
        press.style(style -> style.tooltips(label));
        press.setOnClick(event -> {
            if (event.button != 0) {
                return;
            }
            event.stopPropagation();
            switch (act) {
                case Card.Act.Run ignored -> send(new CardOrders.Press.Act(index, source, at, number));
                case Card.Act.Open open -> host.open(status.colony(), open.facility());
            }
        });
        return press;
    }

    // ---------------------------------------------------------------- values

    private static boolean lit(Value value) {
        return switch (value) {
            case Value.Text text -> !text.value().isEmpty();
            case Value.Bool bool -> bool.on();
            case Value.Real real -> real.value() != 0;
            case Value.Vector vector -> vector.values().stream().anyMatch(each -> each != 0);
            case Value.Sequence sequence -> !sequence.values().isEmpty();
            case Value.Pulse ignored -> false;
            case Value.Items items -> !items.filters().isEmpty();
            case Value.Data data -> !data.tag().isEmpty();
        };
    }

    private static double fraction(Value value, double[] range) {
        double span = range[1] - range[0];
        double at = switch (value) {
            case Value.Bool bool -> bool.on() ? range[1] : range[0];
            case Value.Real real -> real.value();
            default -> range[0];
        };
        return span <= 0 ? 0 : Math.max(0, Math.min(1, (at - range[0]) / span));
    }

    /** A reading in words. */
    public static Component text(Value value) {
        return switch (value) {
            case Value.Text text -> Component.literal(text.value());
            case Value.Bool bool -> Component.translatable(bool.on() ? KEY + "value.on" : KEY + "value.off");
            case Value.Real real -> Component.literal(number(real.value()));
            case Value.Vector vector -> Component.literal(vector.values().stream().map(CardView::number)
                .collect(Collectors.joining(", ", "(", ")")));
            case Value.Sequence sequence -> Component.literal(sequence.values().toString());
            case Value.Pulse ignored -> Component.translatable(KEY + "value.pulse");
            case Value.Items items -> Component.literal(String.join(", ",
                items.filters().stream().map(ItemFilter::describe).toList()));
            case Value.Data data -> {
                String snbt = data.tag().toString();
                yield Component.literal(snbt.length() > 40 ? snbt.substring(0, 39) + "…" : snbt);
            }
        };
    }

    private static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e9 ? Long.toString((long) value)
            : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String figure(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** Item stacks from a container's data: an `Items` list, as block entities save theirs. */
    private static List<ItemStack> stacks(Value value) {
        if (!(value instanceof Value.Data data) || Minecraft.getInstance().level == null) {
            return List.of();
        }
        CompoundTag tag = data.tag();
        List<ItemStack> stacks = new ArrayList<>();
        for (Tag item : tag.getList("Items", Tag.TAG_COMPOUND)) {
            ItemStack.parse(Minecraft.getInstance().level.registryAccess(), item).filter(stack -> !stack.isEmpty())
                .ifPresent(stacks::add);
        }
        return stacks;
    }

    // ---------------------------------------------------------------- shared

    private static Component label(Optional<Card.Label> label, String fallback) {
        return label.map(Card.Label::component).orElseGet(() -> Component.literal(fallback));
    }

    private static Component nameOf(Card.Element element) {
        return switch (element) {
            case Card.Element.Choice choice -> label(choice.label(), choice.param());
            case Card.Element.Number number -> label(number.label(), number.param());
            case Card.Element.Flag flag -> label(flag.label(), flag.param());
            case Card.Element.Items items -> label(items.label(), items.param());
            default -> Component.empty();
        };
    }

    /** The item the card names for an element, or the element's own default when it names none or none known. */
    private static ItemStack icon(Optional<ResourceLocation> id, ItemStack fallback) {
        return id.flatMap(BuiltInRegistries.ITEM::getOptional).filter(item -> item != Items.AIR).map(ItemStack::new)
            .orElse(fallback);
    }

    /** The control's name with its hotkey, as the title line of its tooltip. */
    private static Component named(@Nullable KeyMapping key, Component title) {
        return key == null || key.isUnbound() ? title : title.copy().append(Component.literal(" [")
            .append(key.getTranslatedKeyMessage()).append("]").withStyle(ChatFormatting.GRAY));
    }

    private void grow(float by) {
        estimate += by;
    }


}
