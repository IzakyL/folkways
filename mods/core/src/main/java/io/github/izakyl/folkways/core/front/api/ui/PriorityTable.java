package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.resident.api.Licences;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.VocationGroup;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.resources.ResourceLocation;

/**
 * The columns of a priority table. Each vocation group stands as one column that sets all of its vocations
 * at once, and one group at a time opens into a column per vocation. Vocations that name no group keep their
 * own columns after the groups. Groups only arrange and bulk-edit: every value still belongs to one vocation.
 */
public final class PriorityTable {

    /** A group cell's standing when its vocations do not all stand alike. */
    public static final int MIXED = Integer.MIN_VALUE;
    public static final int GROUP_HEIGHT = 14;
    /** The space between one group's columns and the next, in every row of a priority table. */
    public static final int GROUP_GAP = 4;
    private static final int GROUP_INSET = 1;
    private static final int HEADER_COLOR = 0xFF9A3324;

    /** A run of neighbouring columns in one group; vocations that name no group share one run without a column. */
    public record Run(VocationGroup group, int start, List<Vocation> members) {

        public Run {
            members = List.copyOf(members);
        }

        public boolean grouped() {
            return !group.equals(VocationGroup.OTHER);
        }

        public Component name() {
            return Component.translatable(group.translationKey());
        }
    }

    private final String prefix;
    private final boolean client;
    private final List<Run> runs = runs();
    private final List<List<UIElement>> folded = new ArrayList<>();
    private final List<List<UIElement>> unfolded = new ArrayList<>();
    private final List<UIElement> spans = new ArrayList<>();
    private final List<UIElement> titles = new ArrayList<>();
    private final List<UIElement> rules = new ArrayList<>();
    private final Map<UIElement, int[]> shown = new IdentityHashMap<>();
    private int open = -1;
    private Runnable resized = () -> { };

    /** {@code prefix} names the table's toggles, as {@code prefix.open.<group>}. */
    public PriorityTable(String prefix, boolean client) {
        this.prefix = prefix;
        this.client = client;
        for (int index = 0; index < runs.size(); index++) {
            folded.add(new ArrayList<>());
            unfolded.add(new ArrayList<>());
        }
    }

    public List<Run> columns() {
        return runs;
    }

    /** Runs whenever a group opens or closes, so the page can follow the table's new width. */
    public void onResize(Runnable resized) {
        this.resized = resized;
    }

    /** The group open into its vocations, if any. */
    public Optional<VocationGroup> open() {
        return open < 0 ? Optional.empty() : Optional.of(runs.get(open).group());
    }

    /** Opens a group into its vocations, closing whichever was open; opening the open group closes it. */
    public void toggle(int run) {
        open = open == run || run < 0 || !runs.get(run).grouped() ? -1 : run;
        for (int index = 0; index < runs.size(); index++) {
            boolean opened = index == open;
            folded.get(index).forEach(element -> element.setDisplay(!opened));
            unfolded.get(index).forEach(element -> element.setDisplay(opened));
            if (runs.get(index).grouped()) {
                int width = width(index);
                spans.get(index).layout(layout -> layout.width(width));
                rules.get(index).layout(layout -> layout.width(width - GROUP_INSET * 2));
                if (client) {
                    SignLine.show(titles.get(index), title(index));
                }
            }
        }
        resized.run();
    }

    /** The width of every column on show together, with the gaps between groups. */
    public int width() {
        int width = GROUP_GAP * Math.max(0, runs.size() - 1);
        for (int index = 0; index < runs.size(); index++) {
            width += width(index);
        }
        return width;
    }

    private int width(int run) {
        Run at = runs.get(run);
        if (!at.grouped()) {
            return PriorityCells.WIDTH * at.members().size();
        }
        return PriorityCells.WIDTH * (run == open ? at.members().size() : 1);
    }

    private Sentence title(int run) {
        ResourceLocation group = runs.get(run).group().id();
        return Sentence.of(Sentence.glyph(group.withPath("vocation_group/" + group.getPath())),
            Sentence.glyph(ResourceLocation.fromNamespaceAndPath("folkways_core",
                run == open ? "glyph/unfolded" : "glyph/folded")));
    }

    /**
     * The band above the column headers: each group's icon and disclosure mark head its columns, underlined
     * so the run reads as one. {@code leading} is the width of whatever stands before the first column.
     */
    public UIElement band(float leading) {
        UIElement band = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.FLEX_END).height(GROUP_HEIGHT).flexShrink(0).gapAll(0));
        band.addClass("folkways_vocation_groups");
        if (leading > 0) {
            band.addChildren(new UIElement().layout(layout -> layout.width(leading).flexShrink(0)));
        }
        for (int index = 0; index < runs.size(); index++) {
            if (index > 0) {
                band.addChildren(gap());
            }
            band.addChildren(span(index));
        }
        return band;
    }

    private UIElement span(int index) {
        Run run = runs.get(index);
        int width = width(index);
        UIElement span = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.CENTER).width(width).height(GROUP_HEIGHT).flexShrink(0));
        spans.add(span);
        UIElement label = SignLine.of();
        titles.add(label);
        UIElement rule = new UIElement();
        rules.add(rule);
        if (!run.grouped()) {
            return span;
        }
        if (client) {
            SignLine.show(label, title(index));
        }
        label.layout(layout -> layout.height(GROUP_HEIGHT - 1).flexShrink(0));
        rule.layout(layout -> layout.width(width - GROUP_INSET * 2).height(1).flexShrink(0))
            .style(style -> style.background(new ColorRectTexture(HEADER_COLOR)));
        span.addChildren(label, rule);
        toggles(span, index);
        Tokens.name(span, prefix + ".open." + Tokens.of(run.group().id()));
        return span;
    }

    // Opening and closing is the viewer's own arrangement, so it stays on the client.
    private void toggles(UIElement element, int index) {
        element.addClass("folkways_link");
        element.addEventListener(UIEvents.MOUSE_DOWN, event -> {
            toggle(index);
            event.stopPropagation();
        });
        element.addEventListener(UIEvents.HOVER_TOOLTIPS, event -> event.hoverTooltips = HoverTooltips.empty()
            .append(runs.get(index).name())
            .append(Component.translatable("folkways.vocation_group.count", runs.get(index).members().size()))
            .append(members(runs.get(index)))
            .tooltipComponent(new Cues(List.of(Cue.of(index == open ? "folkways.keys.fold" : "folkways.keys.unfold",
                Cue.Key.CLICK)), true)));
    }

    private static Component members(Run run) {
        List<Component> names = run.members().stream().map(vocation -> PriorityCells.vocationName(vocation.id())).toList();
        return names.isEmpty() ? Component.empty()
            : Component.translatable("folkways.vocation_group.holds",
                ComponentUtils.formatList(names, Component.translatable("folkways.vocation_group.separator")))
                .withColor(Rows.QUIET);
    }

    /** Only open groups need a second header line, showing each vocation's own icon. */
    public UIElement header() {
        return row(trade -> PriorityCells.header(Vocations.ids().get(trade), client), index ->
            new UIElement().layout(layout -> layout.width(PriorityCells.WIDTH).height(0).flexShrink(0)));
    }

    /**
     * One line of the table: a cell per vocation from {@code trade}, given its column, and a cell per group from
     * {@code group}, given its run. Only the cells of the arrangement on show are displayed.
     */
    public UIElement row(IntFunction<UIElement> trade, IntFunction<UIElement> group) {
        UIElement cells = PriorityCells.row();
        for (int index = 0; index < runs.size(); index++) {
            if (index > 0) {
                cells.addChildren(gap());
            }
            Run run = runs.get(index);
            if (run.grouped()) {
                UIElement summary = group.apply(index);
                summary.setDisplay(index != open);
                folded.get(index).add(summary);
                cells.addChildren(summary);
            }
            for (int slot = 0; slot < run.members().size(); slot++) {
                UIElement cell = trade.apply(run.start() + slot);
                if (run.grouped()) {
                    cell.setDisplay(index == open);
                    unfolded.get(index).add(cell);
                }
                cells.addChildren(cell);
            }
        }
        return cells;
    }

    /** A cell setting every vocation of a group at once; its tooltip lists how each of them stands. */
    public Label groupCell(int index) {
        Run run = runs.get(index);
        Label cell = new Label();
        cell.setText(Component.empty());
        float margin = 3;
        cell.layout(layout -> layout.width(PriorityCells.WIDTH - 6).height(16).marginHorizontal(margin).flexShrink(0));
        cell.textStyle(style -> style.textAlignHorizontal(Horizontal.CENTER)
            .textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HIDE));
        cell.addClass("folkways_priority");
        cell.addEventListener(UIEvents.HOVER_TOOLTIPS, event -> {
            HoverTooltips tips = HoverTooltips.empty().append(run.name());
            int[] standings = shown.getOrDefault(cell, new int[0]);
            for (int slot = 0; slot < run.members().size() && slot < standings.length; slot++) {
                if (standings[slot] != 0) {
                    tips = tips.append(Component.translatable("folkways.vocation_group.member",
                        PriorityCells.vocationName(run.members().get(slot).id()), PriorityCells.word(standings[slot])));
                }
            }
            if (summary(standings) == MIXED) {
                tips = tips.append(Component.translatable("folkways.vocation_group.mixed").withColor(Rows.QUIET));
            }
            event.hoverTooltips = tips.tooltipComponent(new Cues(List.of(
                Cue.of("folkways.keys.allow_all", Cue.Key.CLICK), Cue.of("folkways.keys.rank_all", Cue.Key.WHEEL)), true));
        });
        cell.addEventListener(UIEvents.MOUSE_WHEEL, UIEvent::stopPropagation);
        return cell;
    }

    /** Shows a group cell for its vocations' standings, in the run's order. */
    public void show(UIElement cell, int[] standings) {
        shown.put(cell, standings.clone());
        if (cell instanceof Label label) {
            PriorityCells.show(label, summary(standings));
        }
    }

    /** The standings of a run's vocations, picked from a whole line's standings in column order. */
    public static int[] of(Run run, int[] line) {
        int[] picked = new int[run.members().size()];
        for (int slot = 0; slot < picked.length; slot++) {
            int column = run.start() + slot;
            picked[slot] = column < line.length ? line[column] : 0;
        }
        return picked;
    }

    private UIElement gap() {
        return new UIElement().layout(layout -> layout.width(GROUP_GAP).height(1).flexShrink(0));
    }

    /** Groups in column order; each holds one contiguous run of the vocation columns. */
    public static List<Run> runs() {
        List<Run> runs = new ArrayList<>();
        List<Vocation> all = Vocations.all();
        int start = 0;
        for (int index = 1; index <= all.size(); index++) {
            if (index == all.size() || !all.get(index).group().equals(all.get(start).group())) {
                runs.add(new Run(all.get(start).group(), start, all.subList(start, index)));
                start = index;
            }
        }
        return runs;
    }

    /**
     * How a group stands: as its vocations do when they all stand alike, else {@link #MIXED}. Vocations not taken
     * up (standing 0) do not count; a group none of whose vocations are taken up stands at 0.
     */
    public static int summary(int[] standings) {
        int seen = 0;
        for (int standing : standings) {
            if (standing == 0) {
                continue;
            }
            if (seen != 0 && seen != standing) {
                return MIXED;
            }
            seen = standing;
        }
        return seen;
    }

    /** A click on a group allows every vocation unless all are already allowed; then it forbids them all. */
    public static boolean allowAll(List<Boolean> allowed) {
        return !allowed.stream().allMatch(Boolean::booleanValue);
    }

    /**
     * The one priority a wheel notch gives every vocation in a group: one step from the priority most of them
     * share (the keener on a tie), so mixed priorities line up at once.
     */
    public static int rankAll(List<Integer> priorities, int notches) {
        int[] counts = new int[Keenness.values().length + 1];
        int common = Keenness.DEFAULT.number();
        for (int priority : priorities) {
            counts[Math.clamp(priority, Keenness.FIRST.number(), Keenness.FOURTH.number())]++;
        }
        for (int priority = Keenness.FOURTH.number(); priority >= Keenness.FIRST.number(); priority--) {
            if (counts[priority] > 0 && counts[priority] >= counts[common]) {
                common = priority;
            }
        }
        return Math.clamp(common + Integer.signum(notches), Keenness.FIRST.number(), Keenness.FOURTH.number());
    }

    /** Allows or forbids, as {@link #allowAll} decides, every vocation of the group the resident takes up. */
    public static void toggle(Licences licences, List<Vocation> members) {
        List<Licences.Trade> known = members.stream().map(licences::trade).flatMap(Optional::stream).toList();
        boolean allow = allowAll(known.stream().map(licences::allows).toList());
        known.forEach(trade -> licences.allow(trade, allow));
    }

    /** Gives every vocation of the group the resident takes up the priority {@link #rankAll} decides. */
    public static void rank(Licences licences, List<Vocation> members, int notches) {
        List<Licences.Trade> known = members.stream().map(licences::trade).flatMap(Optional::stream).toList();
        if (known.isEmpty()) {
            return;
        }
        int wanted = rankAll(known.stream().map(trade -> licences.keennessOf(trade).number()).toList(), notches);
        Keenness.ofNumber(wanted).ifPresent(keenness -> known.forEach(trade -> licences.setKeenness(trade, keenness)));
    }
}
