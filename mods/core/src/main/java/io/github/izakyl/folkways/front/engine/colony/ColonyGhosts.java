package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Ghost;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;

public final class ColonyGhosts {

    private ColonyGhosts() {
    }

    public static List<Ghost> owed(ColonyView view, Colony colony, int most, BlockPos near) {
        List<Ghost> ghosts = new ArrayList<>();
        for (Facing facing : Facing.all(colony)) {
            ghosts.addAll(facing.ghosts(view));
        }
        if (ghosts.size() > most) {
            ghosts.sort(Comparator.comparingDouble(ghost -> ghost.pos().distSqr(near)));
        }
        return List.copyOf(ghosts.subList(0, Math.min(most, ghosts.size())));
    }
}
