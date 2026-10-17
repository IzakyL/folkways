package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.Collection;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

// Which block delegation the book hands a block over under: a particular one first, a fallback only when no
// particular one takes it.
public final class ColonySites {

    private ColonySites() {
    }

    public static Optional<Delegation> accepting(Level level, BlockPos pos) {
        return accepting(Enrollments.delegations(), level, pos);
    }

    // The cell a block is held under, so either half of a double chest names the same member.
    public static BlockPos anchor(Level level, BlockPos pos) {
        return accepting(level, pos)
            .map(delegation -> ((Shape.Block) delegation.shape()).anchor().of(level, pos))
            .orElse(pos);
    }

    private static Optional<Delegation> accepting(Collection<Delegation> declared, Level level, BlockPos pos) {
        Delegation fallback = null;
        for (Delegation delegation : declared) {
            if (delegation.shape() instanceof Shape.Block block && block.accepts().test(level, pos)) {
                if (!block.fallback()) {
                    return Optional.of(delegation);
                }
                if (fallback == null) {
                    fallback = delegation;
                }
            }
        }
        return Optional.ofNullable(fallback);
    }
}
