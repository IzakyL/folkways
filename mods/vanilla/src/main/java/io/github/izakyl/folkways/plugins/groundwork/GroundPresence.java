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
import io.github.izakyl.folkways.core.api.work.Node;
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

    // One zone's standing work, a list of chores for each kind of work ticked, kept from sweep to sweep while the
    // zone asks for it. A kind unticked is dropped with what it had under way, and so is a fill whose block changed
    // or weeding that pulls something else now: nothing is laid with a block the zone no longer names, nor pulled
    // that it keeps.
    private final class Site {

        private final UUID zone;
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
            Predicate<BlockPos> member = cell -> colony.holdingAt(WorldPos.of(level, cell)).isPresent();
            Predicate<BlockState> kept = kept(with);
            // A cell is worked by one kind at a time: a tree is felled whole before anyone clears round it, and a weed
            // is pulled before a block is laid over it.
            Set<BlockPos> claimed = new HashSet<>();
            if (settings.flag(GroundworkContent.FELL)) {
                List<Job> trees = survey.fell();
                trees.forEach(tree -> {
                    claimed.addAll(tree.cells());
                    claimed.addAll(tree.canopy());
                });
                settle(GroundworkContent.FELL, (at, chore) -> new FellNode(at, chore), trees);
            }
            if (grass || flowers) {
                Predicate<BlockState> pulled = Survey.weeds(grass, flowers);
                List<Job> weeds = survey.weed(pulled);
                weeds.forEach(weed -> {
                    claimed.add(weed.cell());
                    claimed.add(weed.cell().above());
                });
                settle(GroundworkContent.WEED,
                    (at, chore) -> new BreakNode(chore, BreakNode.Way.WEED, at.getBlockState(chore.job().cell()),
                        pulled, (cell, state) -> false),
                    weeds);
            }
            boolean digging = false;
            if (settings.flag(GroundworkContent.CLEAR)) {
                List<Job> clear = survey.clear(member, kept, claimed);
                digging = !clear.isEmpty();
                settle(GroundworkContent.CLEAR,
                    (at, chore) -> new BreakNode(chore, BreakNode.Way.CLEAR, at.getBlockState(chore.job().cell()),
                        state -> true, (cell, state) -> member.test(cell) || kept.test(state)),
                    clear);
            }
            // Cleared and filled, the box is dug out as far as anyone can get at it before anything is laid in it.
            List<Job> fill = digging ? List.of() : survey.fill(claimed);
            with.ifPresent(spec -> settle(GroundworkContent.FILL, (at, chore) -> new FillNode(chore, spec), fill));
            if (settings.flag(GroundworkContent.HAUL)) {
                settle(GroundworkContent.HAUL, (at, chore) -> new HaulNode(at, chore), survey.haul(view::at));
            }
            works.keySet().removeIf(work -> work.equals(GroundworkContent.WEED) ? !grass && !flowers
                : !settings.flag(work) || work.equals(GroundworkContent.FILL) && with.isEmpty());
        }

        private void settle(String work, Chores.Maker maker, List<Job> jobs) {
            String kind = work.equals(GroundworkContent.FILL)
                ? work + "/" + filling.map(ItemSpec::describe).orElse("")
                : work;
            works.computeIfAbsent(work, any -> new Chores(zone, kind, maker, sweep::nudge, made)).settle(jobs);
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

    private final PastDay made = new PastDay();

    GroundPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, GroundworkContent.ID, 100);
        Reader.of(colony.kept(GroundworkContent.ID)).child(TAG_MADE).ifPresent(made::load);
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
                sites.computeIfAbsent(zone.id(), Site::new).scan(view.level(), zone);
                seen.add(zone.id());
            }
        }
        sites.keySet().retainAll(seen);
        keepMade();
    }

    public void closed(Closing why) {
        sites.clear();
        keepMade();
    }

    private void keepMade() {
        if (made.changed()) {
            colony.keep(GroundworkContent.ID, Writer.of().blob(TAG_MADE, made.save()).tag());
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
