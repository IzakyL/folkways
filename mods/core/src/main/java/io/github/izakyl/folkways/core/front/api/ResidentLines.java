package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.card.api.notice.Sentence;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import java.util.List;
import java.util.UUID;

public interface ResidentLines {

    default List<Line> debugResidentLines(UUID resident, ColonyView colony) {
        return List.of();
    }

    default List<Line> lookLines(UUID resident, ColonyView colony) {
        return List.of();
    }

    // Pictures set after a resident's name on its card, such as how the resident feels.
    default Sentence nameMarks(UUID resident, ColonyView colony) {
        return Sentence.EMPTY;
    }

    // Lines under a resident's doing row, telling what that doing is about right now.
    default List<Line> doingLines(UUID resident, ColonyView colony) {
        return List.of();
    }
}
