package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public interface Facing {

    default Optional<Board> board(ResourceLocation page, ColonyView colony) {
        return Optional.empty();
    }

    default Optional<Board> boardAt(ResourceLocation page, BlockPos at, ColonyView colony) {
        return Optional.empty();
    }

    default Attempt act(ResourceLocation page, String actionKey, Optional<BlockPos> at,
            ColonyView colony) {
        return Attempt.went();
    }

    // The book pointed at a block in member mode; a present answer stands in for making the block a member.
    default Optional<Attempt> pointedAt(BlockPos at, ColonyView colony, ServerPlayer by) {
        return Optional.empty();
    }

    default List<Line> lookLines(UUID resident, ColonyView colony) {
        return List.of();
    }

    // Lines under a resident's doing row, telling what that doing is about right now.
    default List<Line> doingLines(UUID resident, ColonyView colony) {
        return List.of();
    }

    // Lines under the card of a colony block the book is aimed at, telling what that block holds or does right now.
    default List<Line> blockLines(BlockPos at, ColonyView colony) {
        return List.of();
    }

    default List<Line> zoneLines(ZoneView zone, ColonyView colony) {
        return List.of();
    }

    default List<Line> pathLines(PathView path, ColonyView colony) {
        return List.of();
    }

    default List<Placard> placards(ColonyView colony) {
        return List.of();
    }

    default List<Ghost> ghosts(ColonyView colony) {
        return List.of();
    }

    static Optional<Facing> of(Colony colony, ResourceLocation owner) {
        return Facings.of(colony, owner);
    }

    static List<Facing> all(Colony colony) {
        return Facings.all(colony);
    }
}
