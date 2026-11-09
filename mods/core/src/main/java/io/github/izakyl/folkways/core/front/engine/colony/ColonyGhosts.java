package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.core.front.api.Ghost;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

public final class ColonyGhosts {

    private ColonyGhosts() {
    }

    public static List<Ghost> owed(ColonyView view, Colony colony, int most, BlockPos near) {
        List<Ghost> ghosts = new ArrayList<>();
        for (Facing facing : Facing.all(colony)) {
            ghosts.addAll(facing.ghosts(view, most, near));
        }
        return Ghost.limited(ghosts, most, near);
    }
}
