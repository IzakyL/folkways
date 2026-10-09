package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Balk;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Meter;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

public final class ColonyLooks {

    private static final double RANGE_SQR = 32.0D * 32.0D;

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
        lines.add(Line.literal(mob.getDisplayName().getString()));
        lines.add(Line.gauge(Meter.HEALTH, Mth.ceil(mob.getHealth()), Mth.ceil(mob.getMaxHealth())));
        for (ColonyView view : views) {
            for (Facing facing : Facing.all(colony)) {
                lines.addAll(facing.lookLines(resident.id(), view));
            }
        }
        lines.add(Line.busy(resident.doing().orElseGet(() -> Doing.open(Doings.IDLE))));
        resident.balk().map(ColonyLooks::balked).ifPresent(lines::add);
        for (ColonyView view : views) {
            for (Facing facing : Facing.all(colony)) {
                lines.addAll(facing.doingLines(resident.id(), view));
            }
        }
        return List.copyOf(lines);
    }

    // Goods that were missing, or had nowhere to go, are told in pictures after the doing's glyph: "Missing: [eating]
    // ✗", "No room: [stowing] ⊘ cobblestone". Any other refusal is said in its own words, filled with what the work
    // was done to.
    private static Line balked(Balk balk) {
        Doing doing = balk.doing();
        RefusalKind why = balk.why();
        return switch (why.shortfall()) {
            case LACKING -> Line.blocked(Notice.of(LookNotice.LACKING), goods(doing, "lacks"));
            case NO_ROOM -> Line.blocked(Notice.of(LookNotice.NO_ROOM), goods(doing, "refuses"));
            case NONE -> Line.blocked(Notice.of(why, doing.about().map(Notice::named).orElseGet(() -> Notice.text(""))));
        };
    }

    private static Sentence goods(Doing doing, String mark) {
        List<Doing.Ware> named = doing.wares().used().isEmpty() ? doing.wares().made() : doing.wares().used();
        List<Sentence.Token.Ware> wares = new ArrayList<>();
        for (Doing.Ware ware : named) {
            if (BuiltInRegistries.ITEM.containsKey(ware.item())) {
                wares.add(new Sentence.Token.Ware(ware.item(), ware.count()));
            }
        }
        return Sentence.of(Sentence.doing(doing.what())).then(Sentence.wares(Sentence.glyph(mark), wares));
    }
}
