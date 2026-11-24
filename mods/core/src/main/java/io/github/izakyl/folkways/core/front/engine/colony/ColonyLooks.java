package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Meter;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.ResidentLines;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

public final class ColonyLooks {

    private static final double RANGE_SQR = 96.0D * 96.0D;

    private ColonyLooks() {
    }

    public static List<Body> nearest(ServerLevel level, Colony colony, Player player, int most) {
        List<Body> near = new ArrayList<>();
        for (Body resident : Residents.of(colony, level.getServer())) {
            Mob mob = resident.mob();
            if (mob.level() == level && mob.distanceToSqr(player) <= RANGE_SQR) {
                near.add(resident);
            }
        }
        near.sort(Comparator.comparingDouble(resident -> resident.mob().distanceToSqr(player)));
        return List.copyOf(near.subList(0, Math.min(near.size(), most)));
    }

    public static List<Line> of(Colony colony, Body resident, List<ColonyView> views) {
        List<Line> lines = new ArrayList<>();
        Mob mob = resident.mob();
        var sources = Enrollments.residentLines(colony).values();
        Sentence marks = Sentence.EMPTY;
        for (ColonyView view : views) {
            for (ResidentLines linesSource : sources) {
                marks = marks.then(linesSource.nameMarks(resident.id(), view));
            }
        }
        lines.add(new Line.Text(mob.getDisplayName(), marks));
        lines.add(Line.gauge(Meter.HEALTH, Mth.ceil(mob.getHealth()), Mth.ceil(mob.getMaxHealth())));
        for (ColonyView view : views) {
            for (ResidentLines linesSource : sources) {
                lines.addAll(linesSource.lookLines(resident.id(), view));
            }
        }
        lines.add(Line.busy(resident.doing().orElseGet(() -> Doing.open(Doings.IDLE))));
        for (ColonyView view : views) {
            for (ResidentLines linesSource : sources) {
                lines.addAll(linesSource.doingLines(resident.id(), view));
            }
        }
        lines.removeIf(Line.Blocked.class::isInstance);
        return List.copyOf(lines);
    }
}
