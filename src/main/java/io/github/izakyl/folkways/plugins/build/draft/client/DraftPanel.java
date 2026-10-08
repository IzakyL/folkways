package io.github.izakyl.folkways.plugins.build.draft.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.ItemChoice;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.LonePanel;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.build.SiteNameField;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Commission;
import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Drawn;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import io.github.izakyl.folkways.plugins.build.draft.World;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class DraftPanel {

    public static final String TOKEN = "folkways.draft";

    private static final String ID = "draft";

    private static final float WIDTH = 300f;
    private static final float HEIGHT = 260f;

    private static final int CONTROL_MIN = 28;

    /** Where a locally drawn site goes: its file, the corner it stands from, and the name it was given. */
    @FunctionalInterface
    interface HandOff {
        void send(CompoundTag file, BlockPos corner, String siteName);
    }

    private final Hint hint;
    private final HandOff handOff;
    private final List<Pattern> patterns;

    private final List<Button> patternRows = new ArrayList<>();
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

    static void open(Hint hint, HandOff handOff) {
        DraftPanel panel = new DraftPanel(hint, handOff);
        LonePanel.open(ID, Component.translatable(TOKEN), WIDTH, HEIGHT, panel::build);
    }

    private UIElement build(Pane pane) {
        UIElement table = Rows.strip();
        table.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(Rows.GAP));
        for (Pattern pattern : patterns) {
            Button row = new Button().setText(nameOf(pattern));
            row.layout(layout -> layout.flexShrink(0));
            row.setOnClick(event -> choose(pattern));
            Tokens.name(row, TOKEN + ".pattern." + Tokens.of(pattern.id()));
            patternRows.add(row);
            table.addChildren(row);
        }
        empty.setText(Component.translatable(TOKEN + ".none"));
        empty.setDisplay(patterns.isEmpty());

        refusal.setDisplay(false);

        confirm = new Button()
            .setText(Component.translatable("folkways.action.confirm"))
            .setOnClick(event -> raise(pane));
        confirm.setDisplay(false);
        Tokens.name(confirm, TOKEN + ".confirm");

        UIElement stack = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).gapAll(5))
            .addChildren(empty, table, knobTable, refusal);

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(stack);

        refresh();
        UIElement page = Rows.page().addChildren(scroller, name.row(), Rows.actions(confirm));
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return page;
    }

    private static Component nameOf(Pattern pattern) {
        return Component.translatableWithFallback(
            "folkways.pattern." + pattern.id().getNamespace() + "." + pattern.id().getPath(), pattern.id().getPath());
    }

    private void choose(Pattern pattern) {
        chosen = pattern;
        values = KnobValues.of(pattern.knobs());
        knobTable.clearAllChildren();
        for (Schema.Setting setting : pattern.knobs().settings()) {
            Knob knob = new Knob();
            knob.show(setting);
            knobTable.addChildren(knob.row);
        }
        refusal.setDisplay(false);
        refresh();
    }

    private void refresh() {
        for (int index = 0; index < patternRows.size(); index++) {
            Pattern pattern = patterns.get(index);
            Button row = patternRows.get(index);
            row.setText(pattern.equals(chosen)
                ? Component.literal("> ").append(nameOf(pattern))
                : nameOf(pattern));
            if (pattern.equals(chosen)) {
                row.addClass("folkways_chosen");
            } else {
                row.removeClass("folkways_chosen");
            }
        }
        confirm.setDisplay(chosen != null);
        name.row().setDisplay(chosen != null);
    }

    private void raise(Pane pane) {
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
            pane.close();
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
        pane.close();
    }

    private String describe(String key) {
        List<ItemFilter> held = values.filtersOf(key);
        if (held.isEmpty()) {
            return Component.translatable(TOKEN + ".empty").getString();
        }
        String first = naming(held.get(0));
        return held.size() == 1 ? first : first + " +" + (held.size() - 1);
    }

    private static String naming(ItemFilter filter) {
        if (filter.tag()) {
            return "#" + filter.id().getPath();
        }
        ItemStack stack = FilterStacks.of(filter);
        return stack.isEmpty() ? filter.describe() : stack.getHoverName().getString();
    }

    private final class Knob {

        private final UIElement row = Rows.row();
        private final Label name = Rows.name(Component.empty());
        private final Label value = Rows.value(Component.empty());
        private final Button press = new Button().noText();

        private Schema.Setting held;

        private Knob() {
            press.layout(layout -> layout.widthAuto().minWidth(CONTROL_MIN).flexShrink(1));
            press.addChildren(value);
            press.setOnClick(event -> turn());
            row.addChildren(name, press);
            row.setDisplay(false);
        }

        private void show(Schema.Setting setting) {
            held = setting;
            row.setDisplay(setting != null);
            if (setting == null) {
                return;
            }
            Tokens.name(press, TOKEN + ".knob." + setting.key());
            name.setText(setting.name());
            value.setText(reading(setting));
        }

        private void turn() {
            Schema.Setting setting = held;
            if (setting == null) {
                return;
            }
            switch (setting) {
                case Schema.Setting.Flag flag -> values.toggle(flag.key());
                case Schema.Setting.Count count -> values.step(count);
                case Schema.Setting.Choice choice -> {
                    ChoicePicker.open(press, choice, values.choice(choice.key()), picked -> {
                        values.choose(choice, picked);
                        value.setText(reading(choice));
                    });
                    return;
                }
                case Schema.Setting.Items items -> {
                    ItemChoice.open(Minecraft.getInstance().screen, picked -> {
                        values.put(items.key(), picked);
                        value.setText(reading(items));
                    });
                    return;
                }
            }
            value.setText(reading(setting));
        }

        private Component reading(Schema.Setting setting) {
            return switch (setting) {
                case Schema.Setting.Flag flag -> Component.translatable(
                    values.flag(flag.key()) ? "folkways.action.on" : "folkways.action.off");
                case Schema.Setting.Count count ->
                    Component.literal(Integer.toString(values.count(count.key())));
                case Schema.Setting.Choice choice ->
                    ChoicePicker.labelOf(values.choice(choice.key()));
                case Schema.Setting.Items items -> Component.literal(describe(items.key()));
            };
        }
    }
}
