package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Cadence;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.Placard;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.api.notice.Notice;
import io.github.izakyl.folkways.core.front.api.notice.Sentence;
import io.github.izakyl.folkways.core.front.api.panel.Board;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Joint;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import io.github.izakyl.folkways.vanilla.build.draft.Templates;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

final class BuildPresence implements Facing {

    // How often the board's count of what an order still owes is read again, while its cells are being met.
    private static final int TALLY_TICKS = 20;

    private static final int TOLD_WARES = 4;

    static final int RETRY_TICKS = 200;

    private static final String TAG_BOOK = "book";

    private static final String TAG_TEMPORARY = "temporary";

    private static final String TAG_ORDER = "order";

    private static final String TAG_CANCELLED = "cancelled";

    private static final String TAG_DRAFTED = "drafted";

    private static final String TAG_MET = "met";

    private static final String TAG_CELLS = "cells";

    private static final String TAG_NUMBERED = "numbered";

    private final Colony colony;

    private final BuildSite.Asks asks;
    private final MinecraftServer server;
    private final BlueprintBook book = new BlueprintBook();
    private final Map<UUID, TemporaryScaffolds> temporary = new LinkedHashMap<>();
    private final Set<UUID> cancelled = new LinkedHashSet<>();
    private final Map<UUID, Carving.Cache> caches = new LinkedHashMap<>();
    private final Map<UUID, BuildSite> sites = new LinkedHashMap<>();
    private final Map<UUID, BuildSite> opening = new LinkedHashMap<>();
    private record Preparation(Blueprint source, CompletableFuture<Blueprint> future) {
    }

    private final Map<UUID, Preparation> preparing = new LinkedHashMap<>();
    private final Map<UUID, Component> payloadFailures = new LinkedHashMap<>();
    private final Map<UUID, Long> payloadRetryAt = new LinkedHashMap<>();
    private final Map<UUID, Drafted> drafted = new LinkedHashMap<>();
    private final Map<UUID, Map<ItemSpec, Long>> owed = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockPos>> met = new LinkedHashMap<>();
    private final Map<UUID, Set<Joint>> joined = new LinkedHashMap<>();
    private final Set<UUID> untallied = new LinkedHashSet<>();
    // How many sites have been numbered for want of a name, so the next unnamed one is #numbered+1.
    private int numbered;
    private boolean unkept;
    // When sites are tallied within each period, spread against every other cron; see Cadence.
    private final Cadence.Cron tallying;

    BuildPresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.tallying = Cadence.claim(colony, TALLY_TICKS);
        this.asks = new BuildSite.Asks() {
            @Override
            public Optional<Colony> colony() {
                return Optional.of(colony);
            }

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
        book.reportChangesTo(this::keep);
        Reader saved = Reader.of(colony.kept(BuildContent.ID));
        if (saved.keys().isEmpty()) {
            return;
        }
        book.loadInto(saved.child(TAG_BOOK).orElseThrow(), server.registryAccess());
        numbered = Math.max(0, saved.integer(TAG_NUMBERED).orElseThrow());
        for (Reader entry : saved.requiredChildren(TAG_TEMPORARY)) {
            UUID order = entry.uuid(TAG_ORDER).orElseThrow();
            scaffoldsOf(order).load(entry);
            if (entry.flag(TAG_CANCELLED).orElseThrow()) {
                cancelled.add(order);
            }
        }
        for (Reader entry : saved.requiredChildren(TAG_DRAFTED)) {
            Drafted.load(entry).ifPresent(one -> drafted.put(one.order(), one));
        }
        for (Reader entry : saved.requiredChildren(TAG_MET)) {
            UUID order = entry.uuid(TAG_ORDER).orElseThrow();
            met.put(order, cells(entry.intArray(TAG_CELLS).orElseThrow()));
            Set<Joint> metJoints = new LinkedHashSet<>();
            for (Reader joint : entry.requiredChildren("joints")) {
                metJoints.add(new Joint(joint.blockPos("from").orElseThrow(), joint.blockPos("to").orElseThrow()));
            }
            joined.put(order, metJoints);
        }
        for (Reader entry : saved.requiredChildren("carvingCaches")) {
            cacheOf(entry.uuid(TAG_ORDER).orElseThrow()).load(entry);
        }
    }

    boolean buildingAt(ServerLevel level, WorldPos at) {
        return sites.values().stream().anyMatch(site -> site.target().realm().equals(at.realm())
            && site.target().in(level).cells().containsKey(at.block(level)));
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
            .children("carvingCaches", caches.entrySet(), entry -> Writer.of(entry.getValue().save())
                .uuid(TAG_ORDER, entry.getKey()).tag())
            .children(TAG_TEMPORARY, temporary.entrySet(), entry -> Writer.of(entry.getValue().save())
                .uuid(TAG_ORDER, entry.getKey())
                .flag(TAG_CANCELLED, cancelled.contains(entry.getKey()))
                .tag())
            .children(TAG_DRAFTED, drafted.values(), Drafted::save)
            .children(TAG_MET, metNow().entrySet(), entry -> Writer.of()
                .uuid(TAG_ORDER, entry.getKey())
                .intArray(TAG_CELLS, packed(entry.getValue()))
                .children("joints", joinedNow(entry.getKey()), joint -> Writer.of()
                    .blockPos("from", joint.from()).blockPos("to", joint.to()).tag())
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

    private Set<Joint> joinedNow(UUID order) {
        BuildSite site = sites.get(order);
        return site == null ? joined.getOrDefault(order, Set.of()) : site.joined();
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

    Optional<BlueprintBuildOrder> fileOnce(ServerLevel level, Blueprint blueprint, BlockPos anchor) {
        if (!blueprint.confirmed() || book.full()) {
            return Optional.empty();
        }
        book.singleUse(blueprint.id());
        book.addConfirmed(blueprint);
        Optional<BlueprintBuildOrder> filed = book.place(level, blueprint.id(), siteName(""), anchor,
            Optional.empty(), "", level.getGameTime());
        filed.ifPresent(order -> {
            spend("");
            keep();
        });
        return filed;
    }

    Optional<BlueprintBuildOrder> file(ServerLevel level, Blueprint blueprint, BlockPos anchor, String typed,
            Optional<UUID> playerId, String playerName, ResourceLocation pattern, Hint hint, CompoundTag settings,
            Direction facing) {
        Optional<BlueprintBuildOrder> filed = place(level, blueprint, anchor, siteName(typed), playerId, playerName);
        filed.ifPresent(order -> {
            drafted.put(order.id(), new Drafted(order.id(), pattern, hint, settings.copy(), facing));
            spend(typed);
            keep();
        });
        return filed;
    }

    private Optional<BlueprintBuildOrder> place(ServerLevel level, Blueprint blueprint, BlockPos anchor, String name,
            Optional<UUID> playerId, String playerName) {
        book.add(level, blueprint);
        return book.place(level, blueprint.id(), name, anchor, playerId, playerName, level.getGameTime());
    }

    @Override
    public Attempt act(ResourceLocation page, String actionKey, Optional<BlockPos> at, ColonyView view) {
        if (!page.equals(BuildContent.PAGE.id())) {
            return Attempt.refused(BuildRefusal.NO_SUCH_ORDER);
        }
        Optional<UUID> order = BuildAct.cancelled(actionKey);
        if (order.isEmpty() || !filed(order.get())) {
            return Attempt.refused(BuildRefusal.NO_SUCH_ORDER);
        }
        cancel(order.get());
        keep();
        return Attempt.went();
    }

    // An order called off keeps its site only to take down what scaffolding it left; its work is withdrawn now.
    private void cancel(UUID order) {
        refund(order);
        cancelled.add(order);
        BuildSite staged = opening.remove(order);
        if (staged != null) {
            staged.close();
        }
        scaffoldsOf(order);
        met.remove(order);
        joined.remove(order);
        BuildSite site = sites.remove(order);
        if (site != null) {
            site.close();
        }
    }

    public void tick(MinecraftServer server) {
        settle(server);
        if (tallying.due(server)) {
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
                    opened = true;
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
        if (!cancelled.contains(order.id()) && level.getGameTime() < payloadRetryAt.getOrDefault(order.id(), 0L)) {
            return null;
        }
        try {
            Blueprint source = book.blueprint(order.blueprintId()).orElse(null);
            if (source != null && !cancelled.contains(order.id())) {
                Preparation preparation = preparing.get(order.blueprintId());
                if (preparation == null || preparation.source() != source) {
                    preparation = new Preparation(source, source.prepare());
                    preparing.put(order.blueprintId(), preparation);
                }
                var future = preparation.future();
                if (!future.isDone()) {
                    return null;
                }
                try {
                    Blueprint ready = future.join();
                    book.prepared(source, ready);
                    preparing.put(order.blueprintId(), new Preparation(ready,
                        CompletableFuture.completedFuture(ready)));
                } catch (CompletionException failed) {
                    if (failed.getCause() instanceof StructureCompat.Failure invalid) {
                        throw invalid;
                    }
                    throw StructureCompat.invalid();
                }
            }
            BuildSite opened = openCompatible(level, order);
            payloadFailures.remove(order.id());
            payloadRetryAt.remove(order.id());
            return opened;
        } catch (StructureCompat.Failure failed) {
            BuildSite staged = opening.remove(order.id());
            if (staged != null) {
                staged.close();
            }
            preparing.remove(order.blueprintId());
            BuildSite partial = sites.remove(order.id());
            if (partial != null) {
                partial.close();
            }
            untallied.remove(order.id());
            owed.remove(order.id());
            payloadFailures.put(order.id(), failed.component());
            payloadRetryAt.put(order.id(), level.getGameTime() + RETRY_TICKS);
            return null;
        }
    }

    @Nullable
    private BuildSite openCompatible(ServerLevel level, BlueprintBuildOrder order) {
        try (var processing = StructureCompat.processing()) {
            BuildSite site = opening.get(order.id());
            if (site == null) {
                Optional<Blueprint> drawing = cancelled.contains(order.id())
                    ? Optional.empty() : book.blueprint(order.blueprintId());
                BuildTarget target = cancelled.contains(order.id()) || drawing.isEmpty()
                    ? new BuildTarget(order.anchor().realm(), Map.of())
                    : BuildTarget.of(order, drawing.get()).in(level);
                if (!loaded(level, target)) {
                    return null;
                }
                site = new BuildSite(order.id(), asks, target, scaffoldsOf(order.id()));
                site.cache = cacheOf(order.id());
                site.begin(level, met.getOrDefault(order.id(), Set.of()), joined.getOrDefault(order.id(), Set.of()));
                opening.put(order.id(), site);
            }
            if (!loaded(level, site.target())) {
                return null;
            }
            // Small orders finish in this call; large ones retain their staged opener for the next tick.
            long until = System.nanoTime() + 8_000_000L;
            while (!site.advanceStart(level)) {
                if (System.nanoTime() >= until) {
                    return null;
                }
            }
            opening.remove(order.id());
            sites.put(order.id(), site);
            untallied.add(order.id());
            tallyOne(level, order, site);
            return site;
        }
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
        site.progress(BuildOrderView.of(order.id(), site.target(), site.metCells(), site.metJoints()));
        if (cancelled.contains(order.id()) || drawing.isEmpty()) {
            owed.remove(order.id());
        } else {
            owed.put(order.id(), site.owed());
        }
    }

    private TemporaryScaffolds scaffoldsOf(UUID order) {
        return temporary.computeIfAbsent(order, id -> new TemporaryScaffolds(this::keep));
    }

    boolean filed(UUID order) {
        return book.allBuildOrders().stream().anyMatch(found -> found.id().equals(order));
    }

    // The pieces of an order's work still out; read by the game tests.
    List<BuildSite.Piece> pieces(UUID order) {
        BuildSite site = sites.get(order);
        return site == null ? List.of() : site.pieces();
    }

    // The site an order is built by, once it has one; read by the game tests.
    Optional<BuildSite> site(UUID order) {
        return Optional.ofNullable(sites.get(order));
    }

    private Carving.Cache cacheOf(UUID order) {
        return caches.computeIfAbsent(order, id -> new Carving.Cache(() -> unkept = true));
    }

    private void refund(UUID order) {
        Carving.Cache cache = caches.get(order);
        if (cache == null) {
            return;
        }
        BlueprintBuildOrder filed = book.allBuildOrders().stream().filter(one -> one.id().equals(order))
            .findFirst().orElse(null);
        if (filed == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (!filed.anchor().in(level)) {
                continue;
            }
            BlockPos at = filed.anchor().block(level);
            level.getChunk(at);
            for (var amount : cache.drain()) {
                long left = amount.count();
                while (left > 0) {
                    ItemStack stack = Goods.display(amount.spec());
                    int count = (int) Math.min(left, stack.getMaxStackSize());
                    stack.setCount(count);
                    level.addFreshEntity(new ItemEntity(level,
                        at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, stack));
                    left -= count;
                }
            }
            caches.remove(order);
            return;
        }
    }

    private void finish(UUID order) {
        refund(order);
        temporary.remove(order);
        cancelled.remove(order);
        book.removeBuildOrder(order);
        retire(order);
        keep();
    }

    private void retire(UUID order) {
        BuildSite staged = opening.remove(order);
        if (staged != null) {
            staged.close();
        }
        payloadFailures.remove(order);
        payloadRetryAt.remove(order);
        owed.remove(order);
        met.remove(order);
        joined.remove(order);
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
        opening.values().forEach(BuildSite::forget);
        opening.clear();
        preparing.clear();
        sites.values().forEach(BuildSite::forget);
        sites.clear();
    }

    @Override
    public List<Ghost> ghosts(ColonyView view) {
        return ghosts(view, 2048, BlockPos.ZERO);
    }

    @Override
    public List<Ghost> ghosts(ColonyView view, int most, BlockPos near) {
        ServerLevel level = view.level();
        List<Ghost> owed = new ArrayList<>();
        int payloadBytes = 256 * 1024;
        try (var processing = StructureCompat.processing()) {
            for (BlueprintBuildOrder order : book.allBuildOrders()) {
                if (owed.size() >= most) {
                    break;
                }
                if (cancelled.contains(order.id()) || !order.anchor().in(level)) {
                    continue;
                }
                BuildSite site = sites.get(order.id());
                if (site != null) {
                    Drafted recipe = drafted.get(order.id());
                    if (recipe != null && Patterns.find(recipe.pattern()).isPresent()) {
                        owed.addAll(BuildOrderView.recipePending(level, site.target(), site.metCells(),
                            site.metJoints(), most - owed.size(), near, order.id()));
                        continue;
                    }
                    var nearby = BuildOrderView.pending(level, site.target(), site.metCells(), site.metJoints(),
                        most - owed.size(), near, payloadBytes);
                    for (Ghost ghost : nearby) {
                        payloadBytes -= ghost.payload().bytes();
                    }
                    owed.addAll(nearby);
                }
            }
        } catch (StructureCompat.Failure limited) {
            // A preview never restarts the budget to finish farther-away orders.
        }
        return List.copyOf(owed);
    }

    @Override
    public List<Ghost> payloadGhosts(ColonyView view, Set<UUID> orders, int most, BlockPos near) {
        List<Ghost> ghosts = new ArrayList<>();
        int bytes = StructurePayload.MAX_PREVIEW_BYTES;
        try (var processing = StructureCompat.processing()) {
            for (BlueprintBuildOrder order : book.allBuildOrders()) {
                BuildSite site = sites.get(order.id());
                Drafted recipe = drafted.get(order.id());
                if (!orders.contains(order.id()) || recipe == null || Patterns.find(recipe.pattern()).isEmpty()
                        || cancelled.contains(order.id()) || site == null
                        || !order.anchor().in(view.level()) || ghosts.size() >= most) {
                    continue;
                }
                var nearby = BuildOrderView.pending(view.level(), site.target(), site.metCells(), site.metJoints(),
                    most - ghosts.size(), near, bytes);
                for (Ghost ghost : nearby) {
                    bytes -= ghost.payload().bytes();
                }
                ghosts.addAll(nearby);
            }
        } catch (StructureCompat.Failure limited) {
            // Keep the same payload and processing budget as the ordinary preview.
        }
        return List.copyOf(ghosts);
    }

    @Override
    public List<CompoundTag> previewRecipes(ColonyView view) {
        return book.allBuildOrders().stream()
            .filter(order -> !cancelled.contains(order.id()) && order.anchor().in(view.level()))
            .map(order -> drafted.get(order.id()))
            .filter(recipe -> recipe != null && Patterns.find(recipe.pattern()).isPresent())
            .map(Drafted::save).toList();
    }

    @Override
    public List<Placard> placards(ColonyView view) {
        ServerLevel level = view.level();
        List<Placard> placards = new ArrayList<>();
        for (BlueprintBuildOrder order : book.allBuildOrders()) {
            if (cancelled.contains(order.id()) || !order.anchor().in(level)) {
                continue;
            }
            BuildSite site = sites.get(order.id());
            Optional<BuildOrderView> seen = site == null ? Optional.empty() : site.progress();
            Drafted drawn = drafted.get(order.id());
            Optional<Placard.Outline> outline = drawn != null
                ? Optional.of(BuildPlacards.outline(drawn.hint()))
                : seen.map(seenView -> new Placard.Outline.Box(seenView.min(), seenView.max()));
            if (outline.isEmpty()) {
                continue;
            }
            placards.add(
                new Placard(
                order.id(),
                outline.get(),
                BuildPlacards.building(
                Line.literal(order.name()),
                seen,
                owed.getOrDefault(order.id(), Map.of()),
                level.registryAccess())));
        }
        return List.copyOf(placards);
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(BuildContent.PAGE.id())) {
            return Optional.empty();
        }
        List<Board.Row> standing = new ArrayList<>();
        for (BlueprintBuildOrder order : book.allBuildOrders()) {
            standing.add(rowFor(order, view.level().registryAccess()));
        }
        // The blueprints there are to set down lead the page, and the build orders standing follow them.
        List<Board.Row> rows = new ArrayList<>();
        rows.add(new Board.Row(new ItemStack(BuiltInRegistries.ITEM.get(BuildContent.BLUEPRINTS.icon())),
            Component.translatable(BuildContent.BLUEPRINTS.nameKey()),
            Component.translatable("folkways.blueprints.shelved", Templates.blueprints().size()),
            List.of(new Board.Act.Open(BuildContent.BLUEPRINTS.id()))));
        rows.add(Board.Row.heading(Component.translatable("folkways.page.build.orders")));
        if (standing.isEmpty()) {
            rows.add(Board.Row.of(ItemStack.EMPTY, Component.translatable("folkways.page.build.empty"),
                Component.empty()));
        }
        rows.addAll(standing);
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.build.open",
                Component.literal(Integer.toString(standing.size())))),
            rows,
            Optional.of(Component.translatable("folkways.page.build.empty"))));
    }

    private Board.Row rowFor(BlueprintBuildOrder order, HolderLookup.Provider registries) {
        BuildSite site = sites.get(order.id());
        Board.Row row = new Board.Row(new ItemStack(Items.BRICKS),
            Component.literal(order.name()),
            cancelled.contains(order.id())
                ? Component.translatable("folkways.page.build.cleanup")
                : payloadFailures.getOrDefault(order.id(), detailOf(site)),
            List.of(new Board.Act.Ping(order.anchor().cell()),
                new Board.Act.Do(BuildAct.cancelling(order.id()), "folkways.action.delete",
                    ResourceLocation.withDefaultNamespace("barrier"))));
        return cancelled.contains(order.id()) || site == null || site.progress().isEmpty()
            ? row
            : row.told(toldOf(site, owed.getOrDefault(order.id(), Map.of()), registries));
    }

    // The building glyph and the first of what is owed, or how far it has come when nothing is;
    // then how many
    // cells nothing can be done about.
    private static Sentence toldOf(BuildSite site, Map<ItemSpec, Long> owed, HolderLookup.Provider registries) {
        BuildOrderView seen = site.progress().orElseThrow();
        Sentence told =
            owed.isEmpty()
            ? Sentence.of(
            Sentence.doing(BuildContent.BUILDING),
            Sentence.word(
            new Notice(
            "folkways.page.build.progress",
            List.of(Notice.count(seen.placed()), Notice.count(seen.total())))))
            : BuildPlacards.needs(owed, TOLD_WARES, registries);
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
        return Component.translatable("folkways.page.build.refused", seen.placed(), seen.total(), refused)
            .append(": ").append(Component.translatable(site.refusals().values().iterator().next().translationKey()));
    }
}
