package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

final class PasturePresence implements Facing {

    private static final String TAG_MADE = "made";

    private final Colony colony;

    private final Sweep sweep;

    private final PastDay made = new PastDay();

    private final Herd herd;

    private final Map<UUID, Map<Herd.Job, PenChores>> pens = new LinkedHashMap<>();

    PasturePresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, PastureContent.ID, 100);
        this.herd = new Herd(this::remember, made);
        Reader kept = Reader.of(colony.kept(PastureContent.ID));
        herd.load(kept);
        kept.child(TAG_MADE).ifPresent(made::load);
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        return zone.delegation().equals(PastureContent.PASTURE) ? made.lines(zone, view) : List.of();
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        FrontView front = Fronts.of(colony);
        herd.begin();
        Set<UUID> standing = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            List<Workshop> udders = new ArrayList<>();
            for (Herd.Tally tally : herd.reconcile(view, front, udders)) {
                standing.add(tally.pen().zone());
                settle(tally);
            }
            colony.publish(PastureContent.ID, view.dimension(), udders);
        }
        retireAllBut(standing);
        herd.settle(server.overworld().getGameTime());
        if (made.changed()) {
            remember();
        }
    }

    public void closed(Closing why) {
        retireAllBut(Set.of());
        if (made.changed()) {
            remember();
        }
    }

    private void settle(Herd.Tally tally) {
        Map<Herd.Job, PenChores> here = pens.computeIfAbsent(tally.pen().zone(), zone -> {
            Map<Herd.Job, PenChores> chores = new EnumMap<>(Herd.Job.class);
            for (Herd.Job job : Herd.Job.values()) {
                chores.put(job, new PenChores(tally.pen(), job, sweep::nudge, made));
            }
            return chores;
        });
        here.forEach((job, chores) -> chores.settle(tally.work().getOrDefault(job, List.of())));
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        for (Map<Herd.Job, PenChores> pen : pens.values()) {
            pen.values().forEach(chores -> chores.goals(view.level(), goals));
        }
        return goals;
    }

    private void retireAllBut(Set<UUID> standing) {
        pens.keySet().retainAll(standing);
    }

    private void remember() {
        colony.keep(PastureContent.ID, Writer.of(herd.save()).blob(TAG_MADE, made.save()).tag());
    }

    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView colony) {
        if (!page.equals(PastureContent.PASTURE)) {
            return Optional.empty();
        }
        int living = 0;
        int target = 0;
        List<Board.Row> rows = new ArrayList<>();
        for (Herd.Sight pen : herd.sights()) {
            living += pen.living();
            target += pen.target();
            rows.add(Board.Row.gauged(iconOf(pen.keeps()), nameOf(pen.keeps()),
                Component.translatable("folkways.page.pasture.head", pen.living(), pen.target(),
                    pen.adults()),
                pen.living(), pen.target(),
                List.of(new Board.Act.Ping(pen.where()))));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.pasture.living",
                    Component.literal(Integer.toString(living))),
                new Board.Figure("folkways.page.pasture.target",
                    Component.literal(Integer.toString(target)))),
            rows,
            Optional.of(Component.translatable("folkways.page.pasture.empty"))));
    }

    static ItemStack iconOf(ResourceLocation keeps) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(keeps)
            .map(SpawnEggItem::byId)
            .<ItemStack>map(egg -> egg == null ? new ItemStack(Items.WHEAT) : new ItemStack(egg))
            .orElseGet(() -> new ItemStack(Items.WHEAT));
    }

    static Component nameOf(ResourceLocation keeps) {
        return BuiltInRegistries.ENTITY_TYPE.getOptional(keeps)
            .map(EntityType::getDescription)
            .orElseGet(() -> Component.literal(keeps.toString()));
    }
}
