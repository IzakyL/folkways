package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

// Every block in a marked box the book could take in one by one, taken in at once: the box is looked through by the
// same rules a single point follows, so either half of a double chest names one member.
public final class BlockSweep {

    public static final int MOST_CELLS = 32_768;

    public enum Swept implements NoticeKind {
        TAKEN;

        @Override
        public String translationKey() {
            return "folkways.sweep." + name().toLowerCase(Locale.ROOT);
        }
    }

    public enum SweepRefusal implements RefusalKind.Named {
        TOO_LARGE,
        NOTHING_TO_TAKE;
    }

    private BlockSweep() {
    }

    public static boolean fits(BlockPos min, BlockPos max) {
        long cells = (long) (Math.abs(max.getX() - min.getX()) + 1)
            * (Math.abs(max.getY() - min.getY()) + 1)
            * (Math.abs(max.getZ() - min.getZ()) + 1);
        return cells <= MOST_CELLS;
    }

    // The blocks each delegation would take, by the cell each is held under, in the order they were come upon.
    public static Map<Delegation, List<BlockPos>> found(Level level, BlockPos min, BlockPos max) {
        Map<Delegation, List<BlockPos>> found = new LinkedHashMap<>();
        if (!fits(min, max)) {
            return found;
        }
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (!level.isLoaded(cell)) {
                continue;
            }
            Delegation declaring = ColonySites.accepting(level, cell).orElse(null);
            if (declaring == null) {
                continue;
            }
            BlockPos anchor = ((Shape.Block) declaring.shape()).anchor().of(level, cell).immutable();
            if (seen.add(anchor) && level.getBlockEntity(anchor) != null) {
                found.computeIfAbsent(declaring, any -> new ArrayList<>()).add(anchor);
            }
        }
        return found;
    }

    // Takes in what the chosen delegations would, past what some colony already holds.
    public static Attempt take(ServerLevel level, Colony colony, BlockPos min, BlockPos max,
                               Set<ResourceLocation> kinds, ServerPlayer player) {
        if (!fits(min, max)) {
            return told(player, Attempt.refused(SweepRefusal.TOO_LARGE));
        }
        int taken = 0;
        for (Map.Entry<Delegation, List<BlockPos>> kind : found(level, min, max).entrySet()) {
            if (!kinds.contains(kind.getKey().id())) {
                continue;
            }
            for (BlockPos anchor : kind.getValue()) {
                if (ColonyGround.ofBlock(level, anchor).isEmpty()
                    && ColonyGround.hold(level, colony, kind.getKey().id(), anchor).isPresent()) {
                    taken++;
                }
            }
        }
        if (taken == 0) {
            return told(player, Attempt.refused(SweepRefusal.NOTHING_TO_TAKE));
        }
        player.displayClientMessage(Notice.of(Swept.TAKEN, Notice.count(taken)).component(), true);
        return Attempt.went();
    }

    private static Attempt told(ServerPlayer player, Attempt attempt) {
        attempt.refusal().ifPresent(why -> player.displayClientMessage(why.component(), true));
        return attempt;
    }
}
