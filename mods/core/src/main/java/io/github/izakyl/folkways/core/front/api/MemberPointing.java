package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.card.api.notice.Attempt;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

@FunctionalInterface
public interface MemberPointing {

    /** A present answer handles the click in place of the ordinary member toggle. */
    Optional<Attempt> pointedAt(BlockPos at, ColonyView colony, ServerPlayer by);
}
