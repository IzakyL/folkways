package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Ghost;
import io.github.izakyl.folkways.front.api.Placard;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.plugins.build.draft.DraftRefusal;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Round;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

final class BuildPresence implements Facing {

    // How often the board's count of what an order still owes is read again, while its cells are being met.
    private static final int TALLY_TICKS = 20;

    private static final int TOLD_WARES = 4;

    private static final String TAG_BOOK = "book";

    private static final String TAG_TEMPORARY = "temporary";

    private static final String TAG_ORDER = "order";

    private static final String TAG_CANCELLED = "cancelled";

    private static final String TAG_GROWING = "growing";

    private static final String TAG_DRAFTED = "drafted";

    private static final String TAG_MET = "met";

    private static final String TAG_CELLS = "cells";

    private static final String TAG_NUMBERED = "numbered";

    static final int RETRY_TICKS = 200;

    private final Colony colony;

    private final BuildSite.Asks asks;
    private final MinecraftServer server;
    private final BlueprintBook book = new BlueprintBook();
    private final Map<UUID, TemporaryScaffolds> temporary = new LinkedHashMap<>();
    private final Set<UUID> cancelled = new LinkedHashSet<>();
    private final Map<UUID, BuildSite> sites = new LinkedHashMap<>();
    private final Map<UUID, Growing> growing = new LinkedHashMap<>();
    private final Map<UUID, Drafted> drafted = new LinkedHashMap<>();
    private final Map<UUID, Map<ResourceLocation, Long>> owed = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockPos>> met = new LinkedHashMap<>();
    private final Set<UUID> untallied = new LinkedHashSet<>();
    // How many sites have been numbered for want of a name, so the next unnamed one is #numbered+1.
    private int numbered;
    private boolean unkept;
    private int sinceTally;

    BuildPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.asks = new BuildSite.Asks() {
            @Override
            public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
                colony.submit(BuildContent.ID, dimension, work, ended);
            }

            @Override
            public void withdraw(UUID node) {
                colony.withdraw(BuildContent.ID, node);
            }
        };
        this.server = server;
        Reader saved = Reader.of(colony.kept(BuildContent.ID));
        saved.child(TAG_BOOK).ifPresent(bag -> book.loadInto(bag, server.registryAccess()));
        for (Reader entry : saved.children(TAG_TEMPORARY)) {
            entry.uuid(TAG_ORDER).ifPresent(order -> {
                scaffoldsOf(order).load(entry);
                if (entry.flag(TAG_CANCELLED).orElse(false)) {
                    cancelled.add(order);
                }
            });
        }
        for (Reader entry : saved.children(TAG_GROWING)) {
            Growing.load(entry).ifPresent(one -> growing.put(one.id(), one));
        }
        for (Reader entry : saved.children(TAG_DRAFTED)) {
            Drafted.load(entry).ifPresent(one -> drafted.put(one.order(), one));
        }
        for (Reader entry : saved.children(TAG_MET)) {
            entry.uuid(TAG_ORDER).ifPresent(order -> met.put(order, cells(entry.intArray(TAG_CELLS).orElse(new int[0]))));
        }
        numbered = Math.max(0, saved.integer(TAG_NUMBERED).orElse(0));
        nameUnnamed();
        book.reportChangesTo(this::keep);
    }

    // Sites kept from before they were named are numbered now; an order a growing site placed shares its name.
    private void nameUnnamed() {
        for (Growing one : List.copyOf(growing.values())) {
            if (one.name().isEmpty()) {
                growing.put(one.id(), one.named(SiteNames.numbered(++numbered)));
            }
        }
        for (BlueprintBuildOrder order : book.allBuildOrders()) {
            if (order.name().isEmpty()) {
                book.rename(order.id(), growing.values().stream()
                    .filter(one -> one.order().filter(order.id()::equals).isPresent())
                    .map(Growing::name)
                    .findFirst()
                    .orElseGet(() -> SiteNames.numbered(++numbered)));
            }
        }
    }

    // The name the player typed, or the next number when they typed none; the number is spent once the site is filed.
    private String siteName(String typed) {
        String kept = SiteNames.typed(typed);
        return kept.isEmpty() ? SiteNames.numbered(numbered + 1) : kept;
    }

    private void spend(String typed) {
        if (SiteNames.typed(typed).isEmpty()) {
            numbered++;
        }
    }

    private void keep() {
        CompoundTag saved = Writer.of()
            .blob(TAG_BOOK, book.save(server.registryAccess()))
            .integer(TAG_NUMBERED, numbered)
            .children(TAG_TEMPORARY, temporary.entrySet(), entry -> Writer.of(entry.getValue().save())
                .uuid(TAG_ORDER, entry.getKey())
                .flag(TAG_CANCELLED, cancelled.contains(entry.getKey()))
                .tag())
            .children(TAG_GROWING, growing.values(), Growing::save)
            .children(TAG_DRAFTED, drafted.values(), Drafted::save)
            .children(TAG_MET, metNow().entrySet(), entry -> Writer.of()
                .uuid(TAG_ORDER, entry.getKey())
                .intArray(TAG_CELLS, packed(entry.getValue()))
                .tag())
            .tag();
        colony.keep(BuildContent.ID, saved);
    }

    // The cells each order has met, as the realm holds them: from its site while it has one, else as last kept.
    private Map<UUID, Set<BlockPos>> metNow() {
        Map<UUID, Set<BlockPos>> now = new LinkedHashMap<>(met);
        sites.forEach((order, site) -> now.put(order, site.met()));
        now.keySet().removeIf(order -> !filed(order));
        now.values().removeIf(Set::isEmpty);
        return now;
    }

    private static int[] packed(Set<BlockPos> cells) {
        int[] packed = new int[cells.size() * 3];
        int at = 0;
        for (BlockPos cell : cells) {
            packed[at++] = cell.getX();
            packed[at++] = cell.getY();
            packed[at++] = cell.getZ();
        }
        return packed;
    }

    private static Set<BlockPos> cells(int[] packed) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int at = 0; at + 2 < packed.length; at += 3) {
            cells.add(new BlockPos(packed[at], packed[at + 1], packed[at + 2]));
        }
        return cells;
    }

    /** Files the blueprint as a site called what the player typed, or numbered when they typed nothing. */
    Optional<BlueprintBuildOrder> file(ServerLevel level, Blueprint blueprint, BlockPos anchor, String typed,
            Optional<UUID> playerId, String playerName) {
        Optional<BlueprintBuildOrder> filed = place(level, blueprint, anchor, siteName(typed), playerId, playerName);
        filed.ifPresent(order -> {
            spend(typed);
            keep();
        });
        return filed;
    }

    Optional<BlueprintBuildOrder> file(ServerLevel level, Blueprint blueprint, BlockPos anchor, String typed,
            Optional<UUID> playerId, String playerName, ResourceLocation pattern, Hint hint) {
        Optional<BlueprintBuildOrder> filed = place(level, blueprint, anchor, siteName(typed), playerId, playerName);
        filed.ifPresent(order -> {
            drafted.put(order.id(), new Drafted(order.id(), pattern, hint));
            spend(typed);
            keep();
        });
        return filed;
    }

    private Optional<BlueprintBuildOrder> place(ServerLevel level, Blueprint blueprint, BlockPos anchor, String name,
            Optional<UUID> playerId, String playerName) {
        book.add(blueprint);
        return book.place(level, blueprint.id(), name, anchor, playerId, playerName, level.getGameTime());
    }

    /** Begins a growing site; every round it places carries the name it was given, or the number it was dealt. */
    Optional<Component> grow(ServerLevel level, Growing begun) {
        Growing fresh = begun.named(siteName(begun.name()));
        Growing next = advance(level, fresh);
        if (next == null) {
            return Optional.of(Component.translatable(DraftRefusal.NOTHING_DRAWN.translationKey(), ""));
        }
        if (next.order().isEmpty()) {
            return Optional.of(Component.translatable(next.stuck(), next.stuckDetail()));
        }
        growing.put(next.id(), next);
        spend(begun.name());
        keep();
        return Optional.empty();
    }

    private Growing advance(ServerLevel level, Growing one) {
        long retry = level.getGameTime() + RETRY_TICKS;
        return switch (Rounds.draw(level, one)) {
            case Round.Ended ended -> null;
            case Round.Stuck stuck -> one.stuck(stuck.refused().why().translationKey(), stuck.refused().detail(), retry);
            case Round.Grew grew -> {
                Optional<Blueprint> blueprint = Rounds.blueprintOf(level, one, grew.ready().draft());
                if (blueprint.isEmpty()) {
                    yield one.stuck(DraftRefusal.SITE_TOO_LARGE.translationKey(), "", retry);
                }
                Optional<BlueprintBuildOrder> order = place(level, blueprint.get(), grew.ready().corner(),
                    one.name(), one.playerId(), one.playerName());
                if (order.isEmpty()) {
                    yield one.stuck("folkways.blueprint.too_many", Integer.toString(BlueprintBook.MAX_BUILD_ORDERS),
                        retry);
                }
                yield one.building(order.get().id(),
                    one.growth().next(grew.kept(), grew.ready().corner(), grew.ready().draft(), one.hint().anchor()),
                    grew.last());
            }
        };
    }

    private void tend(ServerLevel level) {
        boolean changed = false;
        for (Growing one : List.copyOf(growing.values())) {
            if (!one.anchor().in(level) || one.order().filter(this::filed).isPresent()) {
                continue;
            }
            if (one.order().isPresent()) {
                if (one.last()) {
                    growing.remove(one.id());
                    changed = true;
                    continue;
                }
                one = one.waiting();
                growing.put(one.id(), one);
                changed = true;
            }
            if (level.getGameTime() < one.retryAt()) {
                continue;
            }
            Growing next = advance(level, one);
            if (next == null) {
                growing.remove(one.id());
            } else {
                growing.put(next.id(), next);
            }
            changed = true;
        }
        if (changed) {
            keep();
        }
    }

    Optional<String> growingName(UUID id) {
        return Optional.ofNullable(growing.get(id)).map(Growing::name);
    }

    private void stopGrowing(UUID order) {
        growing.values().removeIf(one -> one.order().filter(order::equals).isPresent());
    }

    @Override
    public Attempt act(ResourceLocation page, String actionKey, Optional<BlockPos> at, ColonyView view) {
        if (!page.equals(BuildContent.PAGE.id())) {
            return Attempt.refused(BuildRefusal.NO_SUCH_ORDER);
        }
        Optional<UUID> stopped = BuildAct.stopped(actionKey);
        if (stopped.isPresent()) {
            Growing one = growing.remove(stopped.get());
            if (one == null) {
                return Attempt.refused(BuildRefusal.NO_SUCH_ORDER);
            }
            one.order().filter(this::filed).ifPresent(this::cancel);
            keep();
            return Attempt.went();
        }
        Optional<UUID> order = BuildAct.cancelled(actionKey);
        if (order.isEmpty() || !filed(order.get())) {
            return Attempt.refused(BuildRefusal.NO_SUCH_ORDER);
        }
        stopGrowing(order.get());
        cancel(order.get());
        keep();
        return Attempt.went();
    }

    // An order called off keeps its site only to take down what scaffolding it left; its work is withdrawn now.
    private void cancel(UUID order) {
        cancelled.add(order);
        scaffoldsOf(order);
        met.remove(order);
        BuildSite site = sites.remove(order);
        if (site != null) {
            site.close();
        }
    }

    public void tick(MinecraftServer server) {
        settle(server);
        if (++sinceTally >= TALLY_TICKS) {
            sinceTally = 0;
            tally(server);
        }
        if (unkept) {
            unkept = false;
            keep();
        }
    }

    /**
     * Brings every order up to what has been heard: a site opened, and its work asked for, for an order that has
     * none yet; what each site heard taken in; an order whose every cell is met finished; growth drawn on. Nothing
     * here looks at an order's cells again unless something was heard of them.
     */
    void settle(MinecraftServer server) {
        Set<UUID> standing = new HashSet<>();
        boolean opened = false;
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            for (BlueprintBuildOrder order : List.copyOf(book.allBuildOrders())) {
                if (!order.anchor().in(level)) {
                    continue;
                }
                standing.add(order.id());
                BuildSite site = sites.get(order.id());
                if (site == null) {
                    // Planning an order is the one sizeable thing done here: one a tick, so a load with many
                    // orders standing spreads them out.
                    if (opened) {
                        continue;
                    }
                    site = open(level, order);
                    if (site == null) {
                        continue;
                    }
                    opened = true;
                }
                if (site.tick(level)) {
                    untallied.add(order.id());
                    unkept = true;
                }
                if (site.complete()) {
                    finish(order.id());
                }
            }
            tend(level);
        }
        for (UUID order : List.copyOf(sites.keySet())) {
            if (!standing.contains(order) && !filed(order)) {
                retire(order);
            }
        }
    }

    // An order is planned once, so only when all the ground it covers is loaded: planned before, the cells not yet
    // loaded would stay refused for good. Until then it is tried again each tick.
    @Nullable
    private BuildSite open(ServerLevel level, BlueprintBuildOrder order) {
        Optional<Blueprint> drawing = book.blueprint(order.blueprintId());
        BuildTarget target = cancelled.contains(order.id()) || drawing.isEmpty()
            ? new BuildTarget(order.anchor().realm(), Map.of())
            : BuildTarget.of(order, drawing.get()).in(level);
        if (!loaded(level, target)) {
            return null;
        }
        BuildSite site = new BuildSite(order.id(), asks, target, scaffoldsOf(order.id()));
        sites.put(order.id(), site);
        site.start(level, met.getOrDefault(order.id(), Set.of()));
        untallied.add(order.id());
        tallyOne(level, order, site);
        return site;
    }

    private static boolean loaded(ServerLevel level, BuildTarget target) {
        Set<Long> chunks = new HashSet<>();
        for (BlockPos cell : target.cells().keySet()) {
            if (chunks.add(ChunkPos.asLong(cell)) && !level.hasChunkAt(cell)) {
                return false;
            }
        }
        return true;
    }

    // The board's reading of each order its residents have been at lately: how far along, and what it still owes.
    private void tally(MinecraftServer server) {
        if (untallied.isEmpty()) {
            return;
        }
        for (ColonyView view : colony.views(server)) {
            for (BlueprintBuildOrder order : book.allBuildOrders()) {
                BuildSite site = sites.get(order.id());
                if (site != null && untallied.contains(order.id()) && order.anchor().in(view.level())) {
                    tallyOne(view.level(), order, site);
                }
            }
        }
        untallied.clear();
    }

    private void tallyOne(ServerLevel level, BlueprintBuildOrder order, BuildSite site) {
        Optional<Blueprint> drawing = book.blueprint(order.blueprintId());
        site.progress(drawing.flatMap(blueprint -> BuildOrderView.of(level, order, blueprint)));
        if (cancelled.contains(order.id()) || drawing.isEmpty()) {
            owed.remove(order.id());
        } else {
            owed.put(order.id(), BuildOrderView.owed(level, order, drawing.get()));
        }
    }

    private TemporaryScaffolds scaffoldsOf(UUID order) {
        return temporary.computeIfAbsent(order, id -> new TemporaryScaffolds(this::keep));
    }

    boolean filed(UUID order) {
        return book.allBuildOrders().stream().anyMatch(found -> found.id().equals(order));
    }

    // What an order's site has refused so far and why; read by the live fuzzers (tests/fuzz) as the board is.
    Map<BlockPos, BuildRefusal> refusals(UUID order) {
        BuildSite site = sites.get(order);
        return site == null ? Map.of() : site.refusals();
    }

    // The pieces of an order's work still out; read by the live fuzzers (tests/fuzz) to say what an order that never
    // finished was waiting on.
    List<BuildSite.Piece> pieces(UUID order) {
        BuildSite site = sites.get(order);
        return site == null ? List.of() : site.pieces();
    }

    // The site an order is built by, once it has one; read by the game tests.
    Optional<BuildSite> site(UUID order) {
        return Optional.ofNullable(sites.get(order));
    }

    private void finish(UUID order) {
        temporary.remove(order);
        cancelled.remove(order);
        book.removeBuildOrder(order);
        retire(order);
        keep();
    }

    private void retire(UUID order) {
        owed.remove(order);
        met.remove(order);
        untallied.remove(order);
        if (drafted.remove(order) != null) {
            keep();
        }
        BuildSite site = sites.remove(order);
        if (site != null) {
            site.close();
        }
    }

    public void closed(Closing why) {
        sites.values().forEach(BuildSite::forget);
        sites.clear();
    }

    @Override
    public List<Ghost> ghosts(ColonyView view) {
        ServerLevel level = view.level();
        List<Ghost> owed = new ArrayList<>();
        for (BlueprintBuildOrder order : book.allBuildOrders()) {
            if (cancelled.contains(order.id()) || !order.anchor().in(level)) {
                continue;
            }
            book.blueprint(order.blueprintId())
                .ifPresent(drawing -> owed.addAll(BuildOrderView.pending(level, order, drawing)));
        }
        return List.copyOf(owed);
    }

    @Override
    public List<Placard> placards(ColonyView view) {
        ServerLevel level = view.level();
        List<Placard> placards = new ArrayList<>();
        Map<UUID, Growing> grownOrders = new LinkedHashMap<>();
        for (Growing one : growing.values()) {
            if (!one.anchor().in(level)) {
                continue;
            }
            Optional<UUID> order = one.order().filter(this::filed);
            order.ifPresent(id -> grownOrders.put(id, one));
            if (order.isEmpty()) {
                placards.add(new Placard(one.id(), BuildPlacards.outline(one.hint()), BuildPlacards.waiting(one)));
            }
        }
        for (BlueprintBuildOrder order : book.allBuildOrders()) {
            if (cancelled.contains(order.id()) || !order.anchor().in(level)) {
                continue;
            }
            BuildSite site = sites.get(order.id());
            Optional<BuildOrderView> seen = site == null ? Optional.empty() : site.progress();
            Growing grown = grownOrders.get(order.id());
            Drafted drawn = drafted.get(order.id());
            Optional<Placard.Outline> outline = grown != null
                ? Optional.of(BuildPlacards.outline(grown.hint()))
                : drawn != null
                    ? Optional.of(BuildPlacards.outline(drawn.hint()))
                    : seen.map(seenView -> new Placard.Outline.Box(seenView.min(), seenView.max()));
            if (outline.isEmpty()) {
                continue;
            }
            placards.add(new Placard(grown != null ? grown.id() : order.id(), outline.get(),
                BuildPlacards.building(Line.literal(order.name()), seen, owed.getOrDefault(order.id(), Map.of()))));
        }
        return List.copyOf(placards);
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(BuildContent.PAGE.id())) {
            return Optional.empty();
        }
        List<Board.Row> rows = new ArrayList<>();
        for (BlueprintBuildOrder order : book.allBuildOrders()) {
            rows.add(rowFor(order));
        }
        for (Growing one : growing.values()) {
            if (one.order().filter(this::filed).isEmpty()) {
                rows.add(rowFor(one));
            }
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.build.open",
                Component.literal(Integer.toString(rows.size())))),
            rows,
            Optional.of(Component.translatable("folkways.page.build.empty"))));
    }

    private Board.Row rowFor(BlueprintBuildOrder order) {
        BuildSite site = sites.get(order.id());
        Board.Row row = new Board.Row(new ItemStack(Items.BRICKS),
            Component.literal(order.name()),
            cancelled.contains(order.id())
                ? Component.translatable("folkways.page.build.cleanup")
                : detailOf(site),
            List.of(new Board.Act.Ping(order.anchor().cell()),
                new Board.Act.Do(BuildAct.cancelling(order.id()), "folkways.action.delete",
                    ResourceLocation.withDefaultNamespace("barrier"))));
        return cancelled.contains(order.id()) || site == null || site.progress().isEmpty()
            ? row
            : row.told(toldOf(site, owed.getOrDefault(order.id(), Map.of())));
    }

    private static Board.Row rowFor(Growing one) {
        Component detail = one.stuck().isEmpty()
            ? Component.translatable("folkways.page.build.growing", one.growth().round() + 1)
            : Component.translatable("folkways.page.build.growing.stuck", one.growth().round() + 1,
                Component.translatable(one.stuck(), one.stuckDetail()));
        return new Board.Row(new ItemStack(Items.IRON_PICKAXE),
            Component.literal(one.name()),
            detail,
            List.of(new Board.Act.Ping(one.anchor().cell()),
                new Board.Act.Do(BuildAct.stopping(one.id()), "folkways.action.delete",
                    ResourceLocation.withDefaultNamespace("barrier"))));
    }

    // The building glyph and the first of what is owed, or how far it has come when nothing is; then how many
    // cells nothing can be done about.
    private static Sentence toldOf(BuildSite site, Map<ResourceLocation, Long> owed) {
        BuildOrderView seen = site.progress().orElseThrow();
        Sentence told = owed.isEmpty()
            ? Sentence.of(Sentence.doing(BuildContent.BUILDING), Sentence.word(new Notice(
                "folkways.page.build.progress", List.of(Notice.count(seen.placed()), Notice.count(seen.total())))))
            : BuildPlacards.needs(owed, TOLD_WARES);
        int refused = site.refusals().size();
        return refused == 0 ? told : told.then(Sentence.glyph("refuses"),
            Sentence.word(Notice.count(refused)));
    }

    private static Component detailOf(BuildSite site) {
        if (site == null || site.progress().isEmpty()) {
            return Component.translatable("folkways.page.build.unseen");
        }
        BuildOrderView seen = site.progress().get();
        int refused = site.refusals().size();
        if (refused == 0) {
            return Component.translatable("folkways.page.build.progress", seen.placed(), seen.total());
        }
        return Component.translatable("folkways.page.build.refused", seen.placed(), seen.total(),
            refused);
    }
}
