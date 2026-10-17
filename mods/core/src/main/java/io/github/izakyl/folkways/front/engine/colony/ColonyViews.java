package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class ColonyViews {

    private ColonyViews() {
    }

    public static Optional<ColonyView> of(Colony colony, ServerLevel level) {
        for (ColonyView view : colony.views(level.getServer())) {
            if (view.dimension().equals(level.dimension())) {
                return Optional.of(view);
            }
        }
        return Optional.empty();
    }

    public static ColonyView here(Colony colony, ServerLevel level) {
        return colony.view(level);
    }

    public static ColonyView anyOf(Colony colony, ServerLevel level) {
        return of(colony, level).orElseGet(() -> {
            List<ColonyView> views = colony.views(level.getServer());
            return views.isEmpty() ? here(colony, level) : views.get(0);
        });
    }

    public static ColonyView forBoard(Colony colony, ServerLevel level, Optional<BlockPos> at) {
        return at.isPresent() ? here(colony, level) : anyOf(colony, level);
    }
}
