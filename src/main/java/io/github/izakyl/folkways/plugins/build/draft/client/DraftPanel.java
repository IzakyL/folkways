package io.github.izakyl.folkways.plugins.build.draft.client;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.build.SiteNameField;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Face;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class DraftPanel {

    public static final String TOKEN = "folkways.draft";

    private static final int LIST_WIDTH = 104;
    private static final int MARK_WIDTH = 2;
    private static final int MARK_ON = 0xFFE0BC76;
    private static final int CONTROL_MIN = 28;
    private static final int NUMBER_WIDTH = 34;
    private static final int MOST_ICONS = 6;

    /** Where a locally drawn site goes: its file, the corner it stands from, and the name it was given. */
    @FunctionalInterface
    interface HandOff {
        void send(CompoundTag file, BlockPos corner, String siteName);
    }

    private final Hint hint;
    private final HandOff handOff;
    private final List<Pattern> patterns;

    private final List<Button> patternRows = new ArrayList<>();
    private final List<UIElement> patternMarks = new ArrayList<>();
    private final UIElement knobTable = Rows.table();
    private final Label refusal = Rows.text(Component.empty());
    private final Label empty = Rows.text(Component.empty());
    private final SiteNameField name = new SiteNameField();
    private Button confirm;

    private Pattern chosen;
    private KnobValues values;

    private DraftPanel(Hint hint, HandOff handOff) {
        this.hint = hint;
        this.handOff = handOff;
        this.patterns = LocalPatterns.available().stream().filter(pattern -> pattern.accepts(hint)).toList();
    }

    /** The patterns that fit what was drawn and the chosen one's knobs; {@code done} is run once one is commissioned. */
    static UIElement body(Hint hint, HandOff handOff, Runnable done) {
        return new DraftPanel(hint, handOff).build(done);
    }

    private UIElement build(Runnable done) {
        // The patterns stand in a column of their own, and the chosen one's knobs beside it.
        UIElement table = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        for (Pattern pattern : patterns) {
            UIElement mark = new UIElement()
                .layout(layout -> layout.width(MARK_WIDTH).heightPercent(100).flexShrink(0))
                .style(style -> style.background(new ColorRectTexture(MARK_ON)));
            mark.setDisplay(false);
            Button row = new Button().noText();
            row.layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).height(Rows.ROW_HEIGHT).flexShrink(0)
                .alignItems(AlignItems.CENTER).gapAll(Rows.GAP - 1).paddingAll(1));
            row.addClass("folkways_nav");
            row.setOverflowVisible(false);
            row.addChildren(mark, Rows.name(nameOf(pattern)));
            row.setOnClick(event -> choose(pattern));
            Tokens.name(row, TOKEN + ".pattern." + Tokens.of(pattern.id()));
            patternRows.add(row);
            patternMarks.add(mark);
            table.addChildren(row);
        }
        ScrollerView list = Rows.box();
        list.layout(layout -> layout.width(LIST_WIDTH).flexShrink(0).minHeight(0));
        list.addScrollViewChild(table);
        list.setDisplay(!patterns.isEmpty());
        empty.setText(Component.translatable(TOKEN + ".none"));
        empty.setDisplay(patterns.isEmpty());

        refusal.setDisplay(false);

        confirm = new Button()
            .setText(Component.translatable("folkways.action.confirm"))
            .setOnClick(event -> raise(done));
        confirm.setDisplay(false);
        Tokens.name(confirm, TOKEN + ".confirm");

        UIElement stack = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(5))
            .addChildren(empty, knobTable, refusal);

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(stack);

        UIElement page = Rows.page().addChildren(scroller, name.row(), Rows.actions(confirm));
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
        if (patterns.isEmpty()) {
            refresh();
        } else {
            // The first is chosen to begin with, so the knobs' half never opens empty.
            choose(patterns.get(0));
        }
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(list, page);
    }

    // A pattern's own words come first; the game's language files name the ones that bring none.
    private static Component nameOf(Pattern pattern) {
        return pattern.face().word(language(), Face.TITLE).<Component>map(Component::literal)
            .orElseGet(() -> Component.translatableWithFallback(
                "folkways.pattern." + pattern.id().getNamespace() + "." + pattern.id().getPath(),
                pattern.id().getPath()));
    }

    private static String language() {
        return Minecraft.getInstance().getLanguageManager().getSelected();
    }

    private Component worded(String key, Component otherwise) {
        return chosen.face().word(language(), key).<Component>map(Component::literal).orElse(otherwise);
    }

    private void choose(Pattern pattern) {
        chosen = pattern;
        values = KnobValues.of(pattern.knobs());
        knobTable.clearAllChildren();
        for (Schema.Setting setting : pattern.knobs().settings()) {
            knobTable.addChildren(new Knob(setting).row);
        }
        refusal.setDisplay(false);
        refresh();
    }

    private void refresh() {
        for (int index = 0; index < patternRows.size(); index++) {
            Pattern pattern = patterns.get(index);
            Button row = patternRows.get(index);
            patternMarks.get(index).setDisplay(pattern.equals(chosen));
            if (pattern.equals(chosen)) {
                row.addClass("folkways_selected");
            } else {
                row.removeClass("folkways_selected");
            }
        }
        confirm.setDisplay(chosen != null);
        name.row().setDisplay(chosen != null);
    }

    private void raise(Runnable done) {
        if (chosen == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        World world = minecraft.level == null ? World.NONE : World.of(minecraft.level);
        Direction front = minecraft.player == null ? Direction.NORTH : minecraft.player.getDirection().getOpposite();
        Drawn drawn = Patterns.commission(chosen,
            new Commission(hint, values, world, front, Patterns.seedOf(chosen, hint)));
        if (drawn instanceof Drawn.Refused refused) {
            refusal.setText(Component.translatable(refused.why().translationKey(),
                Notice.text(refused.detail())));
            refusal.setDisplay(true);
            return;
        }
        if (!LocalPatterns.local(chosen)) {
            DraftOffer.commission(chosen, hint, Chosen.of(chosen.knobs(), values), front, name.typed());
            done.run();
            return;
        }
        if (chosen.grows()) {
            refusal.setText(Component.translatable(TOKEN + ".grow.local"));
            refusal.setDisplay(true);
            return;
        }
        Drawn.Ready ready = (Drawn.Ready) drawn;
        CompoundTag file = DraftTemplate.of(ready.draft(), SharedConstants.getCurrentVersion().getDataVersion()
            .getVersion());
        if (!DraftOffer.fits(file)) {
            refusal.setText(Component.translatable(TOKEN + ".too_big_to_send"));
            refusal.setDisplay(true);
            return;
        }
        handOff.send(file, ready.corner(), name.typed());
        done.run();
    }

    private static ItemStack stackOf(ResourceLocation item) {
        return BuiltInRegistries.ITEM.getOptional(item).map(ItemStack::new).orElse(ItemStack.EMPTY);
    }

    private static ItemFilter filterOf(Face.Option option) {
        String key = option.key();
        return key.startsWith("#")
            ? ItemFilter.tag(ResourceLocation.parse(key.substring(1))) : ItemFilter.item(ResourceLocation.parse(key));
    }

    private static Component naming(ItemFilter filter) {
        if (filter.tag()) {
            return Component.literal("#" + filter.id().getPath());
        }
        ItemStack stack = FilterStacks.of(filter);
        return stack.isEmpty() ? Component.literal(filter.describe()) : stack.getHoverName();
    }

    private final class Knob {

        private final UIElement row = Rows.row();

        private Knob(Schema.Setting setting) {
            UIElement control = switch (setting) {
                case Schema.Setting.Flag flag -> box(flag);
                case Schema.Setting.Count count -> number(count);
                case Schema.Setting.Choice choice -> one(choice);
                case Schema.Setting.Items items -> any(items);
            };
            Tokens.name(control, TOKEN + ".knob." + setting.key());
            ItemStack icon = stackOf(setting.icon());
            if (!icon.isEmpty()) {
                row.addChildren(Rows.icon(icon));
            }
            row.addChildren(Rows.name(worded(setting.key(), setting.name())));
            if (setting instanceof Schema.Setting.Count count) {
                Label range = Rows.value(Component.translatable(TOKEN + ".range", count.min(), count.max()));
                range.textStyle(style -> style.textColor(Rows.QUIET));
                row.addChildren(range);
            }
            row.addChildren(control);
        }

        private UIElement box(Schema.Setting.Flag flag) {
            Toggle box = new Toggle().noText();
            box.setOn(values.flag(flag.key()), false);
            box.setOnToggleChanged(on -> values.set(flag.key(), on));
            box.layout(layout -> layout.flexShrink(0));
            return box;
        }

        // What is typed is held as it is typed, so nothing waits on the field losing focus before Build.
        private UIElement number(Schema.Setting.Count count) {
            TextField field = new TextField();
            field.setNumbersOnlyInt(count.min(), count.max());
            field.setValue(Integer.toString(values.count(count.key())), false);
            field.setTextResponder(typed -> {
                try {
                    values.set(count, Integer.parseInt(typed.trim()));
                } catch (NumberFormatException unfinished) {
                    // Half-typed: the last whole number stands.
                }
            });
            field.layout(layout -> layout.width(NUMBER_WIDTH).flexShrink(0));
            return field;
        }

        private UIElement one(Schema.Setting.Choice choice) {
            List<ChoicePicker.Row> rows = new ArrayList<>();
            List<Face.Option> offered = chosen.face().offered(choice.key());
            for (int at = 0; at < choice.options().size(); at++) {
                ResourceLocation id = choice.options().get(at);
                Face.Option option = at < offered.size() ? offered.get(at) : null;
                ItemStack icon = option == null ? ItemStack.EMPTY : option.icon().map(DraftPanel::stackOf)
                    .orElse(ItemStack.EMPTY);
                Component label = option == null ? ChoicePicker.labelOf(id)
                    : worded(Face.wordOf(choice.key(), option), ChoicePicker.labelOf(id));
                rows.add(new ChoicePicker.Row(id.toString(), label, icon.isEmpty() ? ChoicePicker.iconOf(id) : icon));
            }
            Button press = new Button().noText();
            press.layout(layout -> layout.widthAuto().minWidth(CONTROL_MIN).flexShrink(1));
            Runnable show = () -> {
                String held = values.choice(choice.key()).toString();
                wear(press, rows.stream().filter(each -> each.key().equals(held)).toList());
            };
            press.setOnClick(event -> ChoicePicker.open(press, worded(choice.key(), choice.name()).getString(), rows,
                values.choice(choice.key()).toString(), picked -> {
                    values.choose(choice, ResourceLocation.parse(picked));
                    show.run();
                }));
            show.run();
            return press;
        }

        // Options this game has not loaded the block of are left out, as a pack may offer another mod's.
        private UIElement any(Schema.Setting.Items items) {
            List<ChoicePicker.Row> rows = new ArrayList<>();
            Map<String, ItemFilter> filters = new LinkedHashMap<>();
            for (Face.Option option : chosen.face().offered(items.key())) {
                ItemFilter filter = filterOf(option);
                if (!filter.tag() && !BuiltInRegistries.ITEM.containsKey(filter.id())) {
                    continue;
                }
                ItemStack icon = option.icon().map(DraftPanel::stackOf).orElse(ItemStack.EMPTY);
                rows.add(new ChoicePicker.Row(option.key(), worded(Face.wordOf(items.key(), option), naming(filter)),
                    icon.isEmpty() ? FilterStacks.of(filter) : icon));
                filters.put(option.key(), filter);
            }
            Button press = new Button().noText();
            press.layout(layout -> layout.widthAuto().minWidth(CONTROL_MIN).flexShrink(1));
            Runnable show = () -> {
                List<ItemFilter> held = values.filtersOf(items.key());
                wear(press, rows.stream().filter(each -> held.contains(filters.get(each.key()))).toList());
            };
            press.setOnClick(event -> {
                List<ItemFilter> held = values.filtersOf(items.key());
                Set<String> ticked = new LinkedHashSet<>();
                filters.forEach((key, filter) -> {
                    if (held.contains(filter)) {
                        ticked.add(key);
                    }
                });
                ChoicePicker.openMany(press, worded(items.key(), items.name()).getString(), rows, ticked, picked -> {
                    values.hold(items.key(), picked.stream().map(filters::get).toList());
                    show.run();
                });
            });
            show.run();
            return press;
        }

        // One thing held reads as its icon and name; several as their icons in a row; none as "empty".
        private void wear(Button press, List<ChoicePicker.Row> held) {
            press.clearAllChildren();
            if (held.isEmpty()) {
                press.addChildren(Rows.value(Component.translatable(TOKEN + ".empty")));
                return;
            }
            for (ChoicePicker.Row each : held.subList(0, Math.min(held.size(), MOST_ICONS))) {
                if (!each.icon().isEmpty()) {
                    press.addChildren(Rows.icon(each.icon()));
                }
            }
            if (held.size() == 1) {
                press.addChildren(Rows.value(held.get(0).label()));
            } else if (held.size() > MOST_ICONS) {
                press.addChildren(Rows.value(Component.literal("+" + (held.size() - MOST_ICONS))));
            }
        }
    }
}
