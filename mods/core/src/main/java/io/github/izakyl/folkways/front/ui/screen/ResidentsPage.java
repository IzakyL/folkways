package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.participation.Participation;
import io.github.izakyl.folkways.core.api.participation.Participations;
import io.github.izakyl.folkways.core.api.participation.Stake;
import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.effect.perk.PerkPool;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.FolkwaysAttachments;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.resident.body.Licences;
import io.github.izakyl.folkways.core.api.resident.body.WorkPriorities;
import io.github.izakyl.folkways.core.engine.colony.WorkDefaults;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.ui.Bay;
import io.github.izakyl.folkways.front.api.ui.Cue;
import io.github.izakyl.folkways.front.api.ui.Cues;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Inlay;
import io.github.izakyl.folkways.front.api.ui.Nook;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.SignLine;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.authority.OwnedResident;
import io.github.izakyl.folkways.front.engine.colony.Residents;
import io.github.izakyl.folkways.front.ui.panel.Inlays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.appliedenergistics.yoga.YogaJustify;

public final class ResidentsPage implements Draw {

    private static final int ROWS = 24;
    // Every resident is sent, so the search finds those past the rows on show.
    private static final int SENT = 512;
    private static final int UUID_HEAD = 8;

    private static final int NAME_WIDTH = 78;
    private static final int DOING_WIDTH = 112;
    private static final int CELL_WIDTH = 24;

    private static final int ABBREVIATION = 2;

    private static final int ALLOWED_COLOR = 0xFF2B1C12;
    private static final int BARRED_COLOR = 0xFF8A2416;
    private static final int HEADER_COLOR = 0xFF9A3324;

    private static final String ROSTER = "roster";
    private static final String NAME = "name";
    private static final String DOING = "doing";
    private static final String UNASSIGNED = "folkways.resident.unassigned";
    private static final String PRIORITIES = "priorities";

    private static final int BARRED = 0;

    private static final String OPEN = "open";
    private static final String TRACKS = "tracks";
    private static final String TRADE = "trade";
    private static final String LEVEL = "level";
    private static final String MAX = "max";
    private static final String ID = "id";
    private static final String NAMED = "named";

    private final State state = new State();
    private ListTag roster = new ListTag();
    private String query = "";
    private String shown = "";
    private Pane detail;
    private boolean detailShown;
    private UIElement tracks;
    private UIElement doing;
    private Label residentName;
    private UIElement marks;
    private TextField nameField;
    private Button renameButton;
    private final List<Inlay> inlays = new ArrayList<>();

    @Override
    public void windows(Player player, io.github.izakyl.folkways.front.api.ui.Windows desk) {
        detail = desk.add("resident", Component.translatable("folkways.tab.residents"), detail(player));
        if (detail instanceof Window window) { window.preferSize(Rows.CONTENT_WIDTH + 20, 440); }
        detail.onDismiss(() -> state.selected = null);
    }

    @Override
    public UIElement build(Player player) {
        List<Row> rows = new ArrayList<>();
        UIElement table = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthAuto().minWidth(NAME_WIDTH + DOING_WIDTH + Rows.GAP * 2
                    + CELL_WIDTH * Vocations.ids().size()).gapAll(1))
            .addClass("folkways_list");
        table.addChildren(header(player.level().isClientSide()));
        for (int index = 0; index < ROWS; index++) {
            Row row = new Row(player, index);
            rows.add(row);
            table.addChildren(row.element);
        }
        ScrollerView matrix = Rows.fills(Rows.wideBox());
        matrix.addScrollViewChild(table);

        Label empty = Rows.text(Component.translatable("folkways.ui.residents.empty"));
        empty.setDisplay(false);
        Label unmatched = Rows.text(Component.translatable("folkways.ui.residents.unmatched"));
        unmatched.setDisplay(false);
        Runnable refilter = () -> {
            int matched = showRoster(rows, player.registryAccess());
            empty.setDisplay(roster.isEmpty());
            unmatched.setDisplay(!roster.isEmpty() && matched == 0);
        };

        TextField search = new TextField();
        search.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        search.textFieldStyle(style -> style.placeholder(Component.translatable("folkways.picker.search")));
        search.style(style -> style.tooltips(
            Component.translatable("folkways.ui.residents.search"),
            Component.translatable("folkways.ui.residents.search.field"),
            Component.translatable("folkways.ui.residents.search.trade"),
            Component.translatable("folkways.ui.residents.search.logic")));
        search.setTextResponder(text -> {
            query = text;
            refilter.run();
        });
        Tokens.name(search, "folkways.resident.search");

        UIElement page = Rows.page().addChildren(defaults(player), Rows.strip().addChildren(search), empty, unmatched, matrix);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> rosterTag(player))
            .onSyncReceived(tag -> {
                roster = tag instanceof CompoundTag data ? data.getList(ROSTER, Tag.TAG_COMPOUND) : new ListTag();
                refilter.run();
            })
            .build()
            .getSyncValue());
        // The client filters, since only it can read names in the player's language; it tells the
        // server whom each row now shows, so a click on a row reaches the resident drawn there.
        var showing = DataBindingBuilder.stringC2S(ids -> state.shown = parseIds(ids)).build();
        showing.setRemoteDataSource(IDataSource.of(ignored -> { }, () -> shown));
        page.addSyncValue(showing.getSyncValue());
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> detailTag(player))
            .onSyncReceived(tag -> showDetail(tag, player.registryAccess()))
            .build()
            .getSyncValue());
        return page;
    }

    private static UIElement defaults(Player player) {
        UIElement section = Rows.page();
        section.layout(layout -> layout.flexShrink(0));
        Button fold = new Button();
        fold.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        Tokens.name(fold, "folkways.resident.defaults.fold");
        UIElement under = Rows.page();
        under.layout(layout -> layout.flexShrink(0));
        under.addChildren(Rows.note(Component.translatable("folkways.ui.residents.defaults.hint")));
        List<ResourceLocation> kinds = ResidentKinds.ids().stream()
            .filter(id -> ResidentKinds.of(id).orElseThrow().workDefaults()).sorted().toList();
        UIElement table = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthAuto()
            .minWidth(NAME_WIDTH + DOING_WIDTH + Rows.GAP * 2 + CELL_WIDTH * Vocations.ids().size()).gapAll(1));
        table.addChildren(header(player.level().isClientSide()));
        for (ResourceLocation kind : kinds) {
            UIElement cells = grid();
            for (ResourceLocation trade : Vocations.ids()) {
                Label cell = priorityCell(trade);
                Tokens.name(cell, "folkways.resident.defaults." + Tokens.of(kind) + "." + Tokens.of(trade));
                cell.addServerEventListener(UIEvents.CLICK, event -> editDefault(player, kind, trade, true, 0));
                cell.addServerEventListener(UIEvents.MOUSE_WHEEL,
                    event -> editDefault(player, kind, trade, false, (int) Math.signum(event.deltaY)));
                cell.addSyncValue(DataBindingBuilder.intValS2C(() -> authority(player)
                        .map(acting -> defaultStanding(acting.colony(), kind, trade)).orElse(BARRED))
                    .onSyncReceived(standing -> showPriority(cell, standing)).build().getSyncValue());
                cells.addChildren(cell);
            }
            table.addChildren(Rows.row().addChildren(fixed(NAME_WIDTH).setText(
                Component.translatable("entity." + kind.getNamespace() + "." + kind.getPath())), fixed(DOING_WIDTH), cells));
        }
        ScrollerView matrix = Rows.wideBox();
        matrix.layout(layout -> layout.height((Rows.ROW_HEIGHT + 1) * (kinds.size() + 1) + 10).flexShrink(0));
        matrix.addScrollViewChild(table);
        under.addChildren(matrix);
        Button reset = new Button().setText(Component.translatable("folkways.ui.residents.defaults.reset"))
            .setOnServerClick(event -> resetDefaults(player));
        reset.layout(layout -> layout.flexShrink(0));
        reset.style(style -> style.tooltips(Component.translatable("folkways.ui.residents.defaults.reset_hint")));
        Tokens.name(reset, "folkways.resident.defaults.reset");
        fold.setText(Component.literal("− ").append(Component.translatable("folkways.ui.residents.defaults.title")));
        fold.setOnClick(event -> {
            boolean open = !under.isDisplayed();
            under.setDisplay(open);
            fold.setText(Component.literal(open ? "− " : "+ ")
                .append(Component.translatable("folkways.ui.residents.defaults.title")));
        });
        section.addChildren(Rows.strip().addChildren(fold, reset), under);
        return section;
    }

    private static int defaultStanding(Colony colony, ResourceLocation kind, ResourceLocation trade) {
        Participation participation = Participations.between(kind, Stake.vocation(trade));
        if (participation == Participation.NONE) {
            return BARRED;
        }
        WorkPriorities.Rule rule = WorkDefaults.of(colony, kind).of(trade);
        return rule.allowed() || participation == Participation.ALWAYS ? rule.priority().number() : -rule.priority().number();
    }

    static void editDefault(Player player, ResourceLocation kind, ResourceLocation trade, boolean toggle, int notches) {
        authority(player).ifPresent(acting -> {
            WorkPriorities.Rule current = WorkDefaults.of(acting.colony(), kind).of(trade);
            int level = Math.clamp(current.priority().number() + Integer.signum(notches), 1, 4);
            WorkDefaults.set(acting.colony(), kind, trade, toggle ? !current.allowed() : current.allowed(), level);
        });
    }

    static void resetDefaults(Player player) {
        authority(player).ifPresent(acting -> ResidentKinds.ids().forEach(kind -> WorkDefaults.reset(acting.colony(), kind)));
    }

    private static Label priorityCell(ResourceLocation trade) {
        Label cell = new Label();
        cell.setText(Component.empty());
        cell.layout(layout -> layout.width(CELL_WIDTH - 6).height(16).marginHorizontal(3).flexShrink(0));
        cell.textStyle(style -> style.textAlignHorizontal(Horizontal.CENTER)
            .textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HIDE));
        cell.addClass("folkways_priority");
        Cues.under(cell, vocationName(trade),
            Cue.of("folkways.keys.allow", Cue.Key.CLICK), Cue.of("folkways.keys.rank", Cue.Key.WHEEL));
        // The wheel ranks the cell; it must not also scroll the table beneath it.
        cell.addEventListener(UIEvents.MOUSE_WHEEL, UIEvent::stopPropagation);
        return cell;
    }

    private static void showPriority(Label cell, int standing) {
        cell.setText(Component.literal(cell(standing)));
        cell.textStyle(style -> style.textColor(standing < 0 ? BARRED_COLOR : ALLOWED_COLOR));
    }

    private UIElement detail(Player player) {
        TextField nameField = new TextField();
        nameField.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        nameField.bind(DataBindingBuilder.stringC2S(text -> state.typed = text).build());
        nameField.textFieldStyle(style -> style.placeholder(Component.translatable("folkways.resident.rename.hint")));
        this.nameField = nameField;

        Button back = new Button()
            .setText(Component.translatable("folkways.action.back"))
            .setOnServerClick(event -> state.selected = null);
        back.layout(layout -> layout.flexShrink(0));
        Tokens.name(back, "folkways.resident.back");
        Button rename = new Button()
            .setText(Component.translatable("folkways.action.rename"))
            .setOnServerClick(event -> rename(player, state));
        rename.layout(layout -> layout.flexShrink(0));
        Tokens.name(rename, "folkways.resident.rename");
        renameButton = rename;

        doing = SignLine.of();
        residentName = Rows.name(Component.empty());
        marks = SignLine.of();
        tracks = Rows.page();
        Tokens.name(tracks, "folkways.resident.vocations");
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(bay(player), tracks));

        UIElement body = Rows.page().addChildren(
            Rows.strip().addChildren(residentName, marks, back, rename),
            nameField,
            Rows.strip().addChildren(Rows.value(Component.translatable("folkways.ui.residents.doing")), doing),
            scroller);
        body.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return body;
    }

    /** A vocation's column is headed by its glyph, or the first letters of its name where it has none. */
    private static UIElement header(boolean client) {
        UIElement line = Rows.row();
        UIElement cells = grid();
        for (ResourceLocation trade : Vocations.ids()) {
            Component full = vocationName(trade);
            UIElement glyph = SignLine.of();
            UIElement cell = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .justifyContent(YogaJustify.CENTER).alignItems(AlignItems.CENTER)
                .width(CELL_WIDTH).height(Rows.ROW_HEIGHT).flexShrink(0));
            cell.addChildren(glyph);
            cell.style(style -> style.tooltips(full));
            if (client) {
                SignLine.show(glyph, Sentence.of(Sentence.glyph(
                    ResourceLocation.fromNamespaceAndPath(trade.getNamespace(), "vocation/" + trade.getPath()),
                    new Notice("folkways.sign.as_is", List.of(Notice.text(abbreviate(full)))))), HEADER_COLOR);
            }
            cells.addChildren(cell);
        }
        line.addChildren(fixed(NAME_WIDTH).setText(Component.translatable("folkways.ui.residents.name")),
            fixed(DOING_WIDTH).setText(Component.translatable("folkways.ui.residents.doing")), cells);
        return line;
    }

    private static UIElement grid() {
        return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER).flexShrink(0).gapAll(0));
    }

    private static Component vocationName(ResourceLocation trade) {
        return Component.translatable(
            "folkways.vocation." + trade.getNamespace() + "." + trade.getPath());
    }

    private static String abbreviate(Component name) {
        String written = name.getString();
        return written.length() <= ABBREVIATION ? written : written.substring(0, ABBREVIATION);
    }

    private UIElement bay(Player player) {
        UIElement filled = Rows.page().setId(Bay.RESIDENT.id().toString());
        for (Inlays.Entry entry : Inlays.in(Bay.RESIDENT)) {
            Inlay inlay = entry.factory().get();
            inlays.add(inlay);
            filled.addChildren(inlay.build(nook(player, entry.owner())));
        }
        return filled;
    }

    private Nook nook(Player player, ResourceLocation owner) {
        return new Nook() {
            @Override
            public int width() {
                return Rows.CONTENT_WIDTH;
            }

            @Override
            public Optional<Body> subject() {
                return selected(player, state);
            }

            @Override
            public CompoundTag kept() {
                return authority(player)
                    .map(acting -> acting.colony().kept(owner))
                    .orElseGet(CompoundTag::new);
            }

            @Override
            public void keep(CompoundTag kept) {
                authority(player).ifPresent(acting -> acting.colony().keep(owner, kept));
            }
        };
    }

    private static final class State {
        private UUID selected;
        private String typed = "";
        private final List<UUID> order = new ArrayList<>();
        // Whom each row shows, as the client last said; until it has, the rows follow the roster.
        private List<UUID> shown;
        private String filled = "";
    }

    private final class Row {
        private final UIElement element = Rows.row();
        private final Label name = fixed(NAME_WIDTH);
        private final UIElement doing = SignLine.of();
        private final List<Label> priorities = new ArrayList<>();

        private Row(Player player, int index) {
            UIElement cells = grid();
            UIElement work = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .alignItems(AlignItems.CENTER).width(DOING_WIDTH).flexShrink(0));
            work.setOverflowVisible(false);
            work.addChildren(doing);
            element.addChildren(name, work, cells);
            name.addServerEventListener(UIEvents.CLICK, event -> select(state, index));
            name.style(style -> style.tooltips(Component.translatable("folkways.ui.residents.open")));
            name.addClass("folkways_link");
            List<ResourceLocation> trades = Vocations.ids();
            for (int slot = 0; slot < trades.size(); slot++) {
                int trade = slot;
                Label cell = priorityCell(trades.get(trade));
                cell.addServerEventListener(UIEvents.CLICK,
                    event -> togglePriority(player, state, index, trade));
                cell.addServerEventListener(UIEvents.MOUSE_WHEEL,
                    event -> rankPriority(player, state, index, trade, (int) Math.signum(event.deltaY)));
                priorities.add(cell);
                cells.addChildren(cell);
            }
        }
    }

    private static Label fixed(int width) {
        Label label = new Label();
        label.setText(Component.empty());
        label.layout(layout -> layout.width(width).flexShrink(0));
        label.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        label.setOverflowVisible(false);
        return label;
    }

    private static List<Body> roster(Player player) {
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return List.of();
        }
        return Residents.of(colony.get(), level.get().getServer());
    }

    private Tag rosterTag(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        state.order.clear();
        for (Body body : roster(player)) {
            if (state.order.size() >= SENT) {
                break;
            }
            state.order.add(body.id());
            CompoundTag row = new CompoundTag();
            row.putString(ID, body.id().toString());
            row.put(NAME, Pages.text(player.registryAccess(), body.mob().getDisplayName()));
            row.put(DOING, told(body).save());
            Licences licences = body.licences();
            List<Vocation> trades = Vocations.all();
            int[] priorities = new int[trades.size()];
            for (int index = 0; index < priorities.length; index++) {
                priorities[index] = standing(licences, trades.get(index));
            }
            row.putIntArray(PRIORITIES, priorities);
            list.add(row);
        }
        tag.put(ROSTER, list);
        return tag;
    }

    private static Sentence told(Body body) {
        return body.doing().map(Sentence::of)
            .orElseGet(() -> Sentence.of(Sentence.word(new Notice(UNASSIGNED, List.of()))));
    }

    private static int standing(Licences licences, Vocation trade) {
        return licences.trade(trade)
            .map(known -> licences.allows(known)
                ? licences.keennessOf(known).number()
                : -licences.keennessOf(known).number())
            .orElse(BARRED);
    }

    private Tag detailTag(Player player) {
        CompoundTag tag = new CompoundTag();
        Optional<Body> body = selected(player, state);
        tag.putBoolean(OPEN, body.isPresent());
        if (body.isEmpty()) {
            return tag;
        }
        tag.putString(ID, state.selected.toString());
        tag.put(NAME, Pages.text(player.registryAccess(), body.get().mob().getDisplayName()));
        tag.putBoolean(NAMED, inlays.stream().anyMatch(inlay -> inlay.names(body.get())));
        tag.put(DOING, told(body.get()).save());
        Sentence marks = Sentence.EMPTY;
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            for (Facing facing : Facing.all(colony.get())) {
                marks = marks.then(facing.nameMarks(body.get().id(), colony.get().view(level.get())));
            }
        }
        tag.put("marks", marks.save());
        ListTag tracks = new ListTag();
        for (ResourceLocation vocation : Vocations.ids()) {
            CompoundTag track = new CompoundTag();
            track.putString(TRADE, vocation.toString());
            track.putInt(LEVEL, body.get().perks().level(vocation));
            track.putInt(MAX, body.get().perks().maxLevel(vocation));
            track.putInt("xp", body.get().perks().xp(vocation));
            track.putInt("next", body.get().perks().nextLevelXp(vocation));
            track.put("perks", perkTag(body.get().perks().ranks(vocation)));
            tracks.add(track);
        }
        tag.put(TRACKS, tracks);
        tag.put("shared", perkTag(body.get().perks().globalRanks()));
        return tag;
    }

    private static ListTag perkTag(Map<String, Integer> ranks) {
        ListTag perks = new ListTag();
        ranks.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag perk = new CompoundTag();
            perk.putString(ID, entry.getKey());
            perk.putInt("rank", entry.getValue());
            perk.putInt(MAX, PerkPool.maxRank(entry.getKey()));
            perks.add(perk);
        });
        return perks;
    }

    @Override
    public boolean titled() {
        return false;
    }

    private static UUID at(State state, int row) {
        List<UUID> rows = state.shown != null ? state.shown : state.order;
        return row >= 0 && row < rows.size() ? rows.get(row) : null;
    }

    private static List<UUID> parseIds(String ids) {
        List<UUID> parsed = new ArrayList<>();
        for (String id : ids.split(",")) {
            if (parsed.size() >= ROWS) {
                break;
            }
            try {
                parsed.add(UUID.fromString(id));
            } catch (IllegalArgumentException notId) {
                // Held as nobody, so the rows after it still line up.
                parsed.add(null);
            }
        }
        return parsed;
    }

    private static void select(State state, int index) {
        UUID clicked = at(state, index);
        state.selected = clicked != null && clicked.equals(state.selected) ? null : clicked;
    }

    private static Optional<Body> selected(Player player, State state) {
        if (state.selected == null) {
            return Optional.empty();
        }
        return owned(player, state.selected).map(OwnedResident::entity);
    }

    private static Optional<OwnedResident> owned(Player player, UUID resident) {
        if (resident == null || !(player instanceof ServerPlayer serverPlayer)) {
            return Optional.empty();
        }
        return authority(player)
            .flatMap(acting -> acting.resident(serverPlayer.server, resident));
    }

    private static Optional<ColonyAuthority> authority(Player player) {
        return player instanceof ServerPlayer serverPlayer
            ? ColonyAuthority.of(serverPlayer, Optional.empty())
            : Optional.empty();
    }

    private static void togglePriority(Player player, State state, int row, int trade) {
        edit(player, state, row, trade, (licences, known) ->
            licences.allow(known, !licences.allows(known)));
    }

    private static void rankPriority(Player player, State state, int row, int trade, int notches) {
        edit(player, state, row, trade, (licences, known) -> {
            int wanted = licences.keennessOf(known).number() + notches;
            Keenness.ofNumber(Math.max(Keenness.FIRST.number(),
                    Math.min(Keenness.FOURTH.number(), wanted)))
                .ifPresent(next -> licences.setKeenness(known, next));
        });
    }

    private static void edit(Player player, State state, int row, int trade,
            BiConsumer<Licences, Licences.Trade> change) {
        UUID resident = at(state, row);
        if (resident == null || trade >= Vocations.ids().size()) {
            return;
        }
        Vocation vocation = Vocations.all().get(trade);
        owned(player, resident).ifPresent(owned -> {
            Body body = owned.entity();
            Licences licences = body.licences();
            licences.trade(vocation).ifPresent(known -> change.accept(licences, known));
            body.mob().setData(FolkwaysAttachments.LICENCES, licences);
        });
    }

    private static void rename(Player player, State state) {
        owned(player, state.selected).ifPresent(owned -> {
            String name = state.typed.trim();
            owned.entity().mob().setCustomName(name.isEmpty() ? null : Component.literal(name));
        });
    }

    // Fills the rows with the residents the search lets through and says how many it let through.
    private int showRoster(List<Row> rows, HolderLookup.Provider registries) {
        Predicate<RosterQuery.Entry> wanted = RosterQuery.parse(query, fields());
        List<ResourceLocation> trades = Vocations.ids();
        List<CompoundTag> list = new ArrayList<>();
        int matched = 0;
        for (int index = 0; index < roster.size(); index++) {
            CompoundTag entry = roster.getCompound(index);
            if (wanted.test(entry(entry, trades, registries))) {
                matched++;
                if (list.size() < rows.size()) {
                    list.add(entry);
                }
            }
        }
        shown = String.join(",", list.stream().map(entry -> entry.getString(ID)).toList());
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (index >= list.size()) {
                row.element.setDisplay(false);
                name(row, Tokens.NONE);
                continue;
            }
            CompoundTag entry = list.get(index);
            row.element.setDisplay(true);
            name(row, entry.getString(ID));
            row.name.setText(Pages.readText(registries, entry.get(NAME)));
            SignLine.show(row.doing, Sentence.load(entry.getList(DOING, Tag.TAG_COMPOUND)));
            int[] priorities = entry.getIntArray(PRIORITIES);
            for (int trade = 0; trade < row.priorities.size(); trade++) {
                int standing = trade < priorities.length ? priorities[trade] : BARRED;
                Label cell = row.priorities.get(trade);
                showPriority(cell, standing);
            }
        }
        return matched;
    }

    // A field answers to its own word and to its column heading in the player's language.
    private static RosterQuery.Fields fields() {
        return new RosterQuery.Fields(
            List.of("name", Component.translatable("folkways.ui.residents.name").getString()),
            List.of("doing", "work", Component.translatable("folkways.ui.residents.doing").getString()));
    }

    private static RosterQuery.Entry entry(CompoundTag entry, List<ResourceLocation> trades,
            HolderLookup.Provider registries) {
        int[] priorities = entry.getIntArray(PRIORITIES);
        List<RosterQuery.Trade> known = new ArrayList<>();
        for (int trade = 0; trade < trades.size(); trade++) {
            ResourceLocation id = trades.get(trade);
            known.add(new RosterQuery.Trade(
                List.of(id.getPath(), id.toString(), vocationName(id).getString()),
                trade < priorities.length ? priorities[trade] : BARRED));
        }
        return new RosterQuery.Entry(Pages.readText(registries, entry.get(NAME)).getString(),
            plain(Sentence.load(entry.getList(DOING, Tag.TAG_COMPOUND))), known);
    }

    // A sentence as words, for the search: its notices and wares by name, its glyphs by what they stand for.
    private static String plain(Sentence sentence) {
        List<String> words = new ArrayList<>();
        for (Sentence.Token token : sentence.tokens()) {
            switch (token) {
                case Sentence.Token.Word word -> words.add(word.notice().component().getString());
                case Sentence.Token.Ware ware -> words.add(
                    BuiltInRegistries.ITEM.get(ware.item()).getDescription().getString());
                case Sentence.Token.Glyph glyph -> glyph.otherwise()
                    .ifPresent(notice -> words.add(notice.component().getString()));
                default -> {
                }
            }
        }
        return String.join(" ", words);
    }

    private static void name(Row row, String resident) {
        String who = resident.length() < UUID_HEAD ? Tokens.NONE : resident.substring(0, UUID_HEAD);
        Tokens.name(row.element, who.isEmpty() ? Tokens.NONE : "folkways.resident.row." + who);
        Tokens.name(row.name, who.isEmpty() ? Tokens.NONE : "folkways.resident.name." + who);
        List<ResourceLocation> trades = Vocations.ids();
        for (int trade = 0; trade < row.priorities.size(); trade++) {
            Tokens.name(row.priorities.get(trade), who.isEmpty() || trade >= trades.size()
                ? Tokens.NONE
                : "folkways.resident.vocation." + Tokens.of(trades.get(trade)) + "." + who);
        }
    }

    private static String cell(int standing) {
        if (standing == BARRED) {
            return "";
        }
        return standing > 0 ? Integer.toString(standing) : "–";
    }

    private void showDetail(Tag tag, HolderLookup.Provider registries) {
        CompoundTag compound = tag instanceof CompoundTag known ? known : new CompoundTag();
        boolean open = compound.getBoolean(OPEN);
        if (detail != null && open != detailShown) {
            detailShown = open;
            if (open) {
                detail.open();
            } else {
                detail.close();
            }
        }
        if (!open) {
            state.filled = "";
            return;
        }
        String showing = compound.getString(ID);
        residentName.setText(Pages.readText(registries, compound.get(NAME)));
        SignLine.show(marks, Sentence.load(compound.getList("marks", Tag.TAG_COMPOUND)));
        boolean ownRename = !compound.getBoolean(NAMED);
        if (nameField != null) {
            nameField.setDisplay(ownRename);
        }
        if (renameButton != null) {
            renameButton.setDisplay(ownRename);
        }
        if (nameField != null && !showing.isEmpty() && !showing.equals(state.filled)) {
            nameField.setText(Pages.readText(registries, compound.get(NAME)).getString());
            state.filled = showing;
        }
        if (detail != null) {
            detail.title(Pages.readText(registries, compound.get(NAME)));
        }
        if (doing != null) {
            SignLine.show(doing, Sentence.load(compound.getList(DOING, Tag.TAG_COMPOUND)));
        }
        if (tracks == null) {
            return;
        }
        tracks.clearAllChildren();
        tracks.addChildren(Rows.heading(Component.translatable("folkways.resident.vocations")));
        ListTag lines = compound.getList(TRACKS, Tag.TAG_COMPOUND);
        for (int index = 0; index < lines.size(); index++) {
            CompoundTag line = lines.getCompound(index);
            ResourceLocation trade = ResourceLocation.parse(line.getString(TRADE));
            if (line.getInt(LEVEL) == 0) {
                continue;
            }
            UIElement group = Rows.page();
            group.addChildren(Rows.strip().addChildren(Rows.name(vocationName(trade)),
                Rows.value(Component.translatable("folkways.resident.level", line.getInt(LEVEL), line.getInt(MAX)))));
            int xp = line.getInt("xp"), next = line.getInt("next");
            UIElement meter = Rows.meter();
            Rows.meterFill(meter, next > 0 ? 100f * xp / next : 100, 0xFF7F943F);
            group.addChildren(Rows.strip().addChildren(meter, Rows.value(next > 0
                ? Component.literal(xp + "/" + next) : Component.translatable("folkways.resident.max_level"))));
            showPerks(group, line.getList("perks", Tag.TAG_COMPOUND));
            tracks.addChildren(group);
        }
        ListTag shared = compound.getList("shared", Tag.TAG_COMPOUND);
        if (!shared.isEmpty()) {
            tracks.addChildren(Rows.group(Component.translatable("folkways.resident.shared")));
            showPerks(tracks, shared);
        }
    }

    private static void showPerks(UIElement group, ListTag perks) {
        UIElement line = Rows.strip();
        line.layout(layout -> layout.flexWrap(FlexWrap.WRAP).alignItems(AlignItems.FLEX_START).gapAll(Rows.GAP));
        for (int index = 0; index < perks.size(); index++) {
            CompoundTag perk = perks.getCompound(index);
            String id = perk.getString(ID);
            int max = Math.max(0, perk.getInt(MAX));
            int rank = Math.clamp(perk.getInt("rank"), 0, max);
            Label name = Rows.text(Component.translatable("folkways.perk.ranked",
                Component.translatable("folkways.perk." + id), "●".repeat(rank) + "○".repeat(max - rank)));
            name.layout(layout -> layout.widthPercent(48));
            name.style(style -> style.tooltips(Component.translatable("folkways.perk." + id + ".desc")));
            Tokens.name(name, "folkways.resident.perk." + id);
            line.addChildren(name);
        }
        group.addChildren(line);
    }
}
