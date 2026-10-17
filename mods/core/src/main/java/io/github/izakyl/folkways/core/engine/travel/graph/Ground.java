package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

sealed interface Ground {

    static Optional<Ground> of(ServerLevel level, Realm realm) {
        return switch (realm) {
            case Realm.Dimension dimension -> dimension.id().equals(level.dimension())
                ? Optional.of(new Open()) : Optional.empty();
            case Realm.Frame frame -> WorldSpaces.frame(level, frame.structure())
                .map(space -> new Aboard(level, realm, space.storageOrigin()));
        };
    }

    BlockPos toStorage(BlockPos cell);
    Optional<BlockPos> toLocal(BlockPos storage);

    record Open() implements Ground {
        @Override
        public BlockPos toStorage(BlockPos cell) {
            return cell;
        }

        @Override
        public Optional<BlockPos> toLocal(BlockPos storage) {
            return Optional.of(storage);
        }
    }

    record Aboard(ServerLevel level, Realm realm, BlockPos origin) implements Ground {
        @Override
        public BlockPos toStorage(BlockPos cell) {
            return cell.offset(origin);
        }

        @Override
        public Optional<BlockPos> toLocal(BlockPos storage) {
            WorldPos address = WorldPos.of(level, storage);
            return address.realm().equals(realm) ? Optional.of(address.cell()) : Optional.empty();
        }
    }
}
