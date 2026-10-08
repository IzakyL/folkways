package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class ColonyBoards {

    private ColonyBoards() {
    }

    public static Optional<SiteBoard> firstAt(ServerLevel level, Colony colony, BlockPos at) {
        List<SiteBoard> standing = boardsAt(level, colony, at, 1);
        return standing.isEmpty() ? Optional.empty() : Optional.of(standing.get(0));
    }

    private static List<SiteBoard> boardsAt(ServerLevel level, Colony colony, BlockPos at,
            int wanted) {
        ColonyView view = ColonyViews.here(colony, level);
        List<SiteBoard> boards = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Enrollment> enrolled : Enrollments.all().entrySet()) {
            Optional<Facing> facing = Facing.of(colony, enrolled.getKey());
            if (facing.isEmpty()) {
                continue;
            }
            for (PageKind page : enrolled.getValue().pages()) {
                answering(facing.get(), page.id(), Optional.of(at), view)
                    .ifPresent(board -> boards.add(new SiteBoard(page.id(), board)));
                if (boards.size() >= wanted) {
                    return List.copyOf(boards);
                }
            }
        }
        return List.copyOf(boards);
    }

    // One page can be drawn by more than one plugin: each answering board follows the last,
    // and the first to say what an empty page means says it.
    public static Optional<Board> answering(Colony colony, ResourceLocation page,
            Optional<BlockPos> at, ColonyView view) {
        List<Board> answered = new ArrayList<>();
        for (Facing facing : Facing.all(colony)) {
            answering(facing, page, at, view).ifPresent(answered::add);
        }
        if (answered.size() <= 1) {
            return answered.stream().findFirst();
        }
        List<Board.Figure> figures = new ArrayList<>();
        List<Board.Row> rows = new ArrayList<>();
        Optional<Component> whenEmpty = Optional.empty();
        CompoundTag data = new CompoundTag();
        for (Board board : answered) {
            figures.addAll(board.figures());
            rows.addAll(board.rows());
            whenEmpty = whenEmpty.or(board::whenEmpty);
            data.merge(board.data());
        }
        return Optional.of(new Board(figures, rows, whenEmpty, data));
    }

    private static Optional<Board> answering(Facing facing, ResourceLocation page,
            Optional<BlockPos> at, ColonyView view) {
        return at
            .map(pos -> facing.boardAt(page, pos, view))
            .orElseGet(() -> facing.board(page, view));
    }

    public static Optional<Attempt> act(ServerLevel level, Colony colony, ResourceLocation page,
            Optional<BlockPos> at, String actionKey) {
        ColonyView view = ColonyViews.forBoard(colony, level, at);
        for (Facing facing : Facing.all(colony)) {
            Optional<Board> board = answering(facing, page, at, view);
            if (board.isEmpty() || !offers(board.get(), actionKey)) {
                continue;
            }
            return Optional.of(facing.act(page, actionKey, at, view));
        }
        return Optional.empty();
    }

    private static boolean offers(Board board, String actionKey) {
        for (Board.Row row : board.rows()) {
            for (Board.Act act : row.acts()) {
                if (act instanceof Board.Act.Do carry && carry.actionKey().equals(actionKey)) {
                    return true;
                }
            }
        }
        return false;
    }
}
