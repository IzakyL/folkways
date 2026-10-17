package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.NoticeKind;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class MemberToggle {

    public enum Moved implements NoticeKind {
        JOINED,
        LEFT;

        @Override
        public String translationKey() {
            return "folkways.member." + name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Refusal implements RefusalKind {
        NOT_ENROLLABLE,
        NO_BLOCK_ENTITY,
        ANOTHER_COLONY;

        @Override
        public String translationKey() {
            return "folkways.refusal.member." + name().toLowerCase(Locale.ROOT);
        }
    }

    private MemberToggle() {
    }

    public static boolean enrollable(Level level, BlockPos pos) {
        return ColonySites.accepting(level, pos).isPresent();
    }

    public static Attempt toggleAt(ServerLevel level, Colony colony, BlockPos clicked,
                                   ServerPlayer player) {
        Optional<Attempt> answered = pointed(level, colony, clicked, player);
        if (answered.isPresent()) {
            return told(player, answered.get());
        }
        Delegation declaring = ColonySites.accepting(level, clicked).orElse(null);
        if (declaring == null) {
            return told(player, Attempt.refused(Refusal.NOT_ENROLLABLE));
        }
        BlockPos pos = ((Shape.Block) declaring.shape()).anchor().of(level, clicked);
        if (!enrollable(level, pos)) {
            return told(player, Attempt.refused(Refusal.NOT_ENROLLABLE));
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return told(player, Attempt.refused(Refusal.NO_BLOCK_ENTITY));
        }
        Optional<UUID> owner = ColonyGround.ofBlock(level, pos).map(Colony::id);
        boolean ours = owner.filter(colony.id()::equals).isPresent();
        if (!ours && owner.isPresent()) {
            return told(player, Attempt.refused(Refusal.ANOTHER_COLONY));
        }
        if (ours) {
            ColonyGround.letGo(level, colony, pos);
            player.displayClientMessage(Notice.of(Moved.LEFT).component(), true);
        } else if (ColonyGround.hold(level, colony, declaring.id(), pos).isPresent()) {
            player.displayClientMessage(Notice.of(Moved.JOINED).component(), true);
        } else {
            return told(player, Attempt.refused(Refusal.ANOTHER_COLONY));
        }
        return Attempt.went();
    }

    // A block some part of Folkways answers for itself is never a member of its own.
    private static Optional<Attempt> pointed(ServerLevel level, Colony colony, BlockPos clicked,
                                             ServerPlayer player) {
        ColonyView view = ColonyViews.anyOf(colony, level);
        for (Facing facing : Facing.all(colony)) {
            Optional<Attempt> answered = facing.pointedAt(clicked, view, player);
            if (answered.isPresent()) {
                return answered;
            }
        }
        return Optional.empty();
    }

    private static Attempt told(ServerPlayer player, Attempt attempt) {
        attempt.refusal().ifPresent(why -> player.displayClientMessage(why.component(), true));
        return attempt;
    }
}
