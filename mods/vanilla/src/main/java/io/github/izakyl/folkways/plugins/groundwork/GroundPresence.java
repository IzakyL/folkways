package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.Goods;
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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Stances;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

final class GroundPresence implements Facing {

    private static final String TAG_MADE = "made";

    // Targets are discovered once; later sweeps only update access to the remaining work.
    private final class Site {

        private final UUID zone;
        private final Map<String, Set<String>> targets = new LinkedHashMap<>();
        private boolean discovering;
        private String configuration;
        private final Map<String, Chores> works = new LinkedHashMap<>();
        private Optional<ItemSpec> filling = Optional.empty();
        private Weeding weeding = new Weeding(false, false);

        Site(UUID zone) {
            this.zone = zone;
        }

        void scan(ServerLevel level, ZoneView view) {
            Settings settings = view.settings();
            Optional<ItemSpec> with = settings.flag(GroundworkContent.FILL)
                ? settings.items(GroundworkContent.BLOCK).filter(GroundPresence::lays)
                : Optional.empty();
            if (!with.equals(filling)) {
                works.remove(GroundworkContent.FILL);
                filling = with;
            }
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
            Predicate<BlockState> kept = kept(with);
            // A cell is worked by one kind at a time: a tree is felled whole before anyone clears round it, and a weed
            // is pulled before a block is laid over it.
            Set<BlockPos> claimed = new HashSet<>();
            if (settings.flag(GroundworkContent.FELL)) {
                List<Job> trees = new ArrayList<>(survey.fell());
                trees.forEach(tree -> {
                    claimed.addAll(tree.cells());
                    claimed.addAll(tree.canopy());
                });
                List<Job> fallen = survey.fallen(member, kept, claimed);
                fallen.forEach(log -> {
                    claimed.addAll(log.cells());
                    claimed.addAll(log.canopy());
                });
                trees.addAll(fallen);
                settle(GroundworkContent.FELL, (at, chore) -> chore.job().key().startsWith("fallen:")
                    ? new BreakNode(at, chore, BreakNode.Way.FALLEN, at.getBlockState(chore.job().cell()),
                        FallenLogs::horizontal, (cell, state) -> member.test(cell) || kept.test(state)
                            || FallenLogs.horizontal(state)
                                && !new FallenLogs(at, member).at(cell))
                    : new FellNode(at, chore), trees);
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
            boolean digging = false;
            if (settings.flag(GroundworkContent.CLEAR)) {
                List<Job> clear = survey.clear(member, kept, claimed);
                digging = clear.stream().anyMatch(job -> !initialized.contains(zone)
                    || targets.getOrDefault(GroundworkContent.CLEAR, Set.of()).contains(job.key()));
                settle(GroundworkContent.CLEAR,
                    (at, chore) -> new BreakNode(at, chore, BreakNode.Way.CLEAR, at.getBlockState(chore.job().cell()),
                        state -> true, (cell, state) -> member.test(cell) || kept.test(state)),
                    clear);
            }
            // Cleared and filled, the box is dug out as far as anyone can get at it before anything is laid in it.
            List<Job> fill = digging && !discovering ? List.of() : survey.fill(claimed);
            if (discovering && with.isPresent()) {
                Map<String, Job> future = new LinkedHashMap<>();
                fill.forEach(job -> future.put(job.key(), job));
                if (settings.flag(GroundworkContent.CLEAR)) {
                    survey.clear(member, kept, Set.of()).forEach(job -> future.put(job.key(), job));
                }
                for (BlockPos cell : claimed) {
                    if (view.cells().contains(cell)) {
                        future.put(Job.keyOf(cell), Job.at(cell,
                            new WorkSite.AtBlock(WorldPos.of(level, cell)),
                            Stances.WHEREVER));
                    }
                }
                fill = List.copyOf(future.values());
            }
            if (with.isPresent()) {
                ItemSpec spec = with.get();
                settle(GroundworkContent.FILL, (at, chore) -> new FillNode(chore, spec), fill);
            }
            if (settings.flag(GroundworkContent.HAUL)) {
                settle(GroundworkContent.HAUL, (at, chore) -> new HaulNode(at, chore), survey.haul(view::at));
            }
            works.keySet().removeIf(work -> work.equals(GroundworkContent.WEED) ? !grass && !flowers
                : !settings.flag(work) || work.equals(GroundworkContent.FILL) && with.isEmpty());
        }

        private void settle(String work, Chores.Maker maker, List<Job> jobs) {
            if (discovering) {
                Set<String> found = new LinkedHashSet<>(jobs.stream().map(Job::key).toList());
                if (initialized.contains(zone)) {
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
            String kind = work.equals(GroundworkContent.FILL)
                ? work + "/" + filling.map(ItemSpec::describe).orElse("")
                : work;
            works.computeIfAbsent(work, any -> new Chores(zone, kind, maker, sweep::nudge, made)).settle(jobs);
        }

        boolean complete() {
            return initialized.contains(zone) && targets.values().stream().allMatch(Set::isEmpty)
                && works.values().stream().noneMatch(Chores::busy);
        }

        void update(ServerLevel level, ZoneView zone) {
            Settings settings = zone.settings();
            String configured = List.of(GroundworkContent.CLEAR, GroundworkContent.FILL, GroundworkContent.WEED,
                    GroundworkContent.FLOWERS, GroundworkContent.FELL, GroundworkContent.HAUL).stream()
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
            if (zone.cells().stream().anyMatch(cell -> !level.isLoaded(cell))) {
                return;
            }
            scan(level, zone);
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

        void goals(ServerLevel level, List<Grown> into) {
            works.values().forEach(chores -> chores.goals(level, into));
        }
    }

    // What a zone's weeding pulls.
    private record Weeding(boolean grass, boolean flowers) {
    }

    // A fill lays a block: an item that is none is no fill at all.
    private static boolean lays(ItemSpec spec) {
        return spec.item().map(id -> BuiltInRegistries.ITEM.get(id) instanceof BlockItem).orElse(true);
    }

    // What clearing leaves standing in a box that is also filled: the block it would be filled with.
    private static Predicate<BlockState> kept(Optional<ItemSpec> filling) {
        return filling.<Predicate<BlockState>>map(spec -> state -> {
            Item item = state.getBlock().asItem();
            return item != Items.AIR && Goods.matches(new ItemStack(item), spec);
        }).orElse(state -> false);
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
                saved.blob("targets").ifPresent(targets -> {
                    for (String kind : targets.getAllKeys()) {
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
        return zone.delegation().equals(GroundworkContent.ZONE) ? made.lines(zone, view) : List.of();
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
                    .blob("targets", targets).tag();
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
