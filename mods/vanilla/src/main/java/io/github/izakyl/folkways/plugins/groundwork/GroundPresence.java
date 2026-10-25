package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.plugins.build.Blueprint;
import io.github.izakyl.folkways.plugins.build.BlueprintBlock;
import io.github.izakyl.folkways.plugins.build.BlueprintVolume;
import io.github.izakyl.folkways.plugins.build.BuildOrders;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

final class GroundPresence implements Facing {

    private static final String TAG_MADE = "made";
    private static final int FORAGE_VERSION = 2;

    // Targets are discovered once; later sweeps only update access to the remaining work.
    private final class Site {

        private final UUID zone;
        private final Map<String, Set<String>> targets = new LinkedHashMap<>();
        private boolean discovering;
        private boolean rediscoverForage;
        private boolean loaded;
        private String configuration;
        private final Map<String, Chores> works = new LinkedHashMap<>();
        private Optional<GroundRefusal> refusal = Optional.empty();
        private Weeding weeding = new Weeding(false, false);

        Site(UUID zone) {
            this.zone = zone;
        }

        void scan(ServerLevel level, ZoneView view) {
            Settings settings = view.settings();
            boolean grass = settings.flag(GroundworkContent.WEED);
            boolean flowers = settings.flag(GroundworkContent.FLOWERS);
            if (!weeding.equals(new Weeding(grass, flowers))) {
                works.remove(GroundworkContent.WEED);
                weeding = new Weeding(grass, flowers);
            }
            Survey survey = new Survey(level, view.cells());
            if (discovering) {
                survey.discovering();
            }
            Predicate<BlockPos> member = cell -> colony.holdingAt(WorldPos.of(level, cell)).isPresent();
            Predicate<BlockPos> spared = member;
            // Trees and fallen logs claim their decorations before forage and weeding.
            Set<BlockPos> claimed = new HashSet<>();
            if (settings.flag(GroundworkContent.FELL)) {
                List<Job> trees = new ArrayList<>(survey.fell());
                trees.forEach(tree -> {
                    claimed.addAll(tree.cells());
                    claimed.addAll(tree.canopy());
                });
                List<Job> fallen = survey.fallen(member, state -> false, claimed);
                fallen.forEach(log -> {
                    claimed.addAll(log.cells());
                    claimed.addAll(log.canopy());
                });
                trees.addAll(fallen);
                settle(GroundworkContent.FELL, (at, chore) -> chore.job().key().startsWith("fallen:")
                    ? new BreakNode(at, chore, BreakNode.Way.FALLEN, at.getBlockState(chore.job().cell()),
                        FallenLogs::horizontal, (cell, state) -> member.test(cell)
                            || FallenLogs.horizontal(state)
                                && !new FallenLogs(at, member).at(cell))
                    : new FellNode(at, chore), trees);
            }
            if (settings.flag(GroundworkContent.FORAGE)) {
                List<Job> forage = survey.forage(spared, claimed);
                forage.forEach(plant -> claimed.addAll(plant.cells()));
                settle(GroundworkContent.FORAGE, (at, chore) -> new ForageNode(at, chore, spared), forage);
            }
            if (grass || flowers) {
                Predicate<BlockState> pulled = Survey.weeds(grass, flowers);
                List<Job> weeds = survey.weed(pulled).stream()
                    .filter(weed -> !claimed.contains(weed.cell())).toList();
                weeds.forEach(weed -> {
                    claimed.add(weed.cell());
                    claimed.add(weed.cell().above());
                });
                settle(GroundworkContent.WEED,
                    (at, chore) -> new BreakNode(at, chore, BreakNode.Way.WEED, at.getBlockState(chore.job().cell()),
                        pulled, (cell, state) -> false),
                    weeds);
            }
            if (settings.flag(GroundworkContent.HAUL)) {
                settle(GroundworkContent.HAUL, (at, chore) -> new HaulNode(at, chore), survey.haul(view::at));
            }
            works.keySet().removeIf(work -> work.equals(GroundworkContent.WEED) ? !grass && !flowers
                : !settings.flag(work));
        }

        private void settle(String work, Chores.Maker maker, List<Job> jobs) {
            if (discovering) {
                Set<String> found = new LinkedHashSet<>(jobs.stream().map(Job::key).toList());
                if (initialized.contains(zone) && !(work.equals(GroundworkContent.FORAGE) && rediscoverForage)) {
                    Set<String> wanted = targets.get(work);
                    if (wanted != null) {
                        wanted.retainAll(found);
                    }
                } else {
                    targets.put(work, found);
                }
                return;
            }
            Set<String> wanted = targets.getOrDefault(work, Set.of());
            jobs = jobs.stream().filter(job -> wanted.contains(job.key())).toList();
            works.computeIfAbsent(work, any -> new Chores(zone, work, maker, sweep::nudge, made)).settle(jobs);
        }

        boolean complete() {
            return loaded && refusal.isEmpty() && initialized.contains(zone)
                && targets.values().stream().allMatch(Set::isEmpty)
                && works.values().stream().noneMatch(Chores::busy);
        }

        void update(ServerLevel level, ZoneView zone) {
            Settings settings = zone.settings();
            String configured = List.of(GroundworkContent.CLEAR, GroundworkContent.FILL, GroundworkContent.WEED,
                    GroundworkContent.FLOWERS, GroundworkContent.FELL, GroundworkContent.HAUL, GroundworkContent.FORAGE).stream()
                .map(key -> key + "=" + settings.flag(key)).collect(java.util.stream.Collectors.joining(","))
                + settings.items(GroundworkContent.BLOCK).map(ItemSpec::describe).orElse("")
                + zone.cells().stream().map(BlockPos::asLong).sorted().toList();
            if (configuration != null && !configuration.equals(configured)) {
                initialized.remove(this.zone);
                targets.clear();
                works.clear();
            }
            configuration = configured;
            discovering = true;
            loaded = false;
            refusal = Optional.empty();
            if (zone.cells().stream().anyMatch(cell -> !level.isLoaded(cell))) {
                return;
            }
            loaded = true;
            if (settings.flag(GroundworkContent.CLEAR) || settings.flag(GroundworkContent.FILL)) {
                works.clear();
                targets.clear();
                order(level, zone);
                return;
            }
            scan(level, zone);
            rediscoverForage = false;
            initialized.add(this.zone);
            targets.keySet().removeIf(work -> work.equals(GroundworkContent.WEED)
                ? !zone.settings().flag(GroundworkContent.WEED) && !zone.settings().flag(GroundworkContent.FLOWERS)
                : !zone.settings().flag(work));
            discovering = false;
            scan(level, zone);
            works.forEach((kind, chores) -> {
                Set<String> wanted = targets.get(kind);
                if (wanted != null) {
                    wanted.removeAll(chores.spent());
                }
            });
        }

        private void order(ServerLevel level, ZoneView view) {
            boolean fill = view.settings().flag(GroundworkContent.FILL);
            Set<BlockPos> cells = view.cells();
            if (cells.isEmpty()) {
                initialized.add(zone);
                return;
            }
            BlockState state = Blocks.AIR.defaultBlockState();
            if (fill) {
                Optional<BlockItem> item = view.settings().items(GroundworkContent.BLOCK)
                    .flatMap(ItemSpec::item).map(BuiltInRegistries.ITEM::get)
                    .filter(BlockItem.class::isInstance).map(BlockItem.class::cast);
                if (item.isEmpty()) {
                    refusal = Optional.of(GroundRefusal.INVALID_FILL);
                    return;
                }
                state = item.get().getBlock().defaultBlockState();
            }
            BlockPos origin = view.cells().stream().reduce(BlockPos::min).orElseThrow();
            List<BlueprintBlock> blocks = new ArrayList<>();
            for (BlockPos cell : cells) {
                blocks.add(new BlueprintBlock(cell.subtract(origin), state));
            }
            Optional<BlueprintVolume> volume = BlueprintVolume.around(
                blocks.stream().map(BlueprintBlock::offset).toList());
            if (volume.isEmpty()) {
                refusal = Optional.of(GroundRefusal.ORDER_TOO_LARGE);
                return;
            }
            Blueprint blueprint = new Blueprint(UUID.randomUUID(), "groundwork", blocks, List.of(), List.of(),
                volume.get(), level.getGameTime(), true);
            if (BuildOrders.fileOnce(colony, level, blueprint, view.at(origin))) {
                initialized.add(zone);
            } else {
                refusal = Optional.of(GroundRefusal.ORDER_UNAVAILABLE);
            }
        }

        void goals(ServerLevel level, List<Grown> into) {
            works.values().forEach(chores -> chores.goals(level, into));
        }
    }

    // What a zone's weeding pulls.
    private record Weeding(boolean grass, boolean flowers) {
    }

    private final Colony colony;

    private final Sweep sweep;

    private final Map<UUID, Site> sites = new LinkedHashMap<>();

    private final Set<UUID> initialized = new HashSet<>();

    private final PastDay made = new PastDay();

    GroundPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, GroundworkContent.ID, 100);
        Reader kept = Reader.of(colony.kept(GroundworkContent.ID));
        kept.child(TAG_MADE).ifPresent(made::load);
        for (Reader saved : kept.children("sites")) {
            saved.uuid("zone").ifPresent(id -> {
                Site site = new Site(id);
                site.configuration = saved.string("configuration").orElse(null);
                // In-place harvesting recorded only ripe roots. Rebuild just forage's targets for plant removal.
                site.rediscoverForage = saved.integer("forage_version").orElse(0) < FORAGE_VERSION;
                // Older sites had no forage switch. Preserve their already discovered targets when it stays off.
                if (site.configuration != null && !site.configuration.contains(",forage=")) {
                    site.configuration = site.configuration.replaceFirst("(haul=(?:true|false))", "$1,forage=false");
                }
                saved.blob("targets").ifPresent(targets -> {
                    for (String kind : targets.getAllKeys()) {
                        if (kind.equals(GroundworkContent.CLEAR) || kind.equals(GroundworkContent.FILL)) {
                            continue;
                        }
                        ListTag keys = targets.getList(kind, Tag.TAG_STRING);
                        Set<String> pending = new LinkedHashSet<>();
                        for (int i = 0; i < keys.size(); i++) {
                            pending.add(keys.getString(i));
                        }
                        site.targets.put(kind, pending);
                    }
                });
                sites.put(id, site);
                initialized.add(id);
            });
        }
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        if (!zone.delegation().equals(GroundworkContent.ZONE)) {
            return List.of();
        }
        Site site = sites.get(zone.id());
        return site != null && site.refusal.isPresent()
            ? List.of(Line.blocked(Notice.of(site.refusal.get()))) : made.lines(zone, view);
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        FrontView front = Fronts.of(colony);
        Set<UUID> seen = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            for (ZoneView zone : front.zonesIn(view.level())) {
                if (!zone.delegation().equals(GroundworkContent.ZONE)) {
                    continue;
                }
                Site site = sites.computeIfAbsent(zone.id(), Site::new);
                site.update(view.level(), zone);
                if (site.complete()) {
                    front.erase(zone.id());
                    continue;
                }
                seen.add(zone.id());
            }
        }
        sites.keySet().retainAll(seen);
        initialized.retainAll(seen);
        keepMade();
    }

    public void closed(Closing why) {
        keepMade();
        sites.clear();
        initialized.clear();
    }

    private void keepMade() {
        CompoundTag saved = Writer.of().blob(TAG_MADE, made.save())
            .children("sites", sites.values().stream().filter(site -> initialized.contains(site.zone)).toList(), site -> {
                CompoundTag targets = new CompoundTag();
                site.targets.forEach((kind, pending) -> {
                    ListTag keys = new ListTag();
                    Chores chores = site.works.get(kind);
                    pending.stream().filter(key -> chores == null || !chores.spent().contains(key))
                        .forEach(key -> keys.add(StringTag.valueOf(key)));
                    targets.put(kind, keys);
                });
                return Writer.of().uuid("zone", site.zone).string("configuration", site.configuration)
                    .integer("forage_version", FORAGE_VERSION).blob("targets", targets).tag();
            }).tag();
        if (!saved.equals(colony.kept(GroundworkContent.ID))) {
            colony.keep(GroundworkContent.ID, saved);
        }
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        for (Site site : sites.values()) {
            site.goals(view.level(), goals);
        }
        return goals;
    }
}
