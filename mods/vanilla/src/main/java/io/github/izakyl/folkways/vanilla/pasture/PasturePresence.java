package io.github.izakyl.folkways.vanilla.pasture;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.FrontView;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.PastDay;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

final class PasturePresence implements HoldingKind {

    private static final String TAG_MADE = "made";

    private final Colony colony;

    private final Sweep sweep;

    private final PastDay made = new PastDay();

    private final Herd herd;

    private final Apiaries apiaries;

    private final Map<UUID, Map<Herd.Job, PenChores>> pens = new LinkedHashMap<>();

    PasturePresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, PastureContent.ID, 100);
        this.herd = new Herd(this::remember, made);
        this.apiaries = new Apiaries(colony, made, sweep::nudge);
        Reader kept = Reader.of(colony.kept(PastureContent.ID));
        if (!kept.keys().isEmpty()) {
            herd.load(kept);
            made.load(kept.child(TAG_MADE).orElseThrow());
        }
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        ZoneView zone = held.zone().orElse(null);
        if (zone == null) {
            return List.of();
        }
        if (zone.delegation().equals(PastureContent.APIARY)) {
            List<Line> lines = new ArrayList<>(apiaries.lines(zone));
            lines.addAll(made.lines(zone, view));
            return List.copyOf(lines);
        }
        if (!zone.delegation().equals(PastureContent.PASTURE)) {
            return List.of();
        }
        Optional<PastureNotice> warning = herd.enclosure(zone.id()).flatMap(PasturePresence::warning);
        if (warning.isEmpty()) {
            return made.lines(zone, view);
        }
        List<Line> lines = new ArrayList<>();
        lines.add(Line.blocked(Notice.of(warning.get())));
        lines.addAll(made.lines(zone, view));
        return List.copyOf(lines);
    }

    private static Optional<PastureNotice> warning(Enclosure enclosure) {
        return switch (enclosure.shape()) {
            case OPEN -> Optional.of(PastureNotice.UNFENCED);
            case SPLIT -> Optional.of(PastureNotice.SPLIT);
            case BARE, FENCED -> Optional.empty();
        };
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        FrontView front = Fronts.of(colony);
        herd.begin();
        Set<UUID> standing = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            apiaries.refresh(view, front);
            List<Workshop> udders = new ArrayList<>();
            for (Herd.Tally tally : herd.reconcile(view, front, udders)) {
                standing.add(tally.pen().zone());
                settle(tally);
            }
            colony.publish(PastureContent.ID, view.dimension(), udders);
        }
        retireAllBut(standing);
        herd.settle(server.overworld().getGameTime());
        if (made.consumeChanged()) {
            remember();
        }
    }

    public void closed(Closing why) {
        retireAllBut(Set.of());
        apiaries.close();
        if (made.consumeChanged()) {
            remember();
        }
    }

    private void settle(Herd.Tally tally) {
        Map<Herd.Job, PenChores> here = pens.computeIfAbsent(tally.pen().zone(), zone -> {
            Map<Herd.Job, PenChores> chores = new EnumMap<>(Herd.Job.class);
            for (Herd.Job job : Herd.Job.values()) {
                chores.put(job, new PenChores(tally.pen(), job, sweep::nudge, made,
                    level -> Pen.find(colony.view(level), Fronts.of(colony), zone)));
            }
            return chores;
        });
        here.forEach((job, chores) -> chores.settle(tally.pen(), tally.work().getOrDefault(job, List.of())));
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>(apiaries.goals(view));
        Optional<UUID> nurturer = view.residents().stream().filter(resident -> {
            var entity = view.level().getEntity(resident.id());
            return entity instanceof net.minecraft.world.entity.LivingEntity living
                && io.github.izakyl.folkways.core.resident.api.Bodies.of(living)
                    .map(body -> body.perks().rank(PastureContent.NURTURE) > 0).orElse(false);
        }).map(resident -> resident.id()).findFirst();
        for (Map<Herd.Job, PenChores> pen : pens.values()) {
            pen.values().forEach(chores -> chores.goals(view.level(), goals, nurturer));
        }
        return goals;
    }

    private void retireAllBut(Set<UUID> standing) {
        pens.keySet().retainAll(standing);
    }

    private void remember() {
        colony.keep(PastureContent.ID, Writer.of(herd.save()).blob(TAG_MADE, made.save()).tag());
    }
}
