package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Entries;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

public record UnloadedBlock(BlockPos offset, CompoundTag state, List<Turn> turns) {

    static final String TAG_TURNS = "turns";

    public record Turn(Mirror mirror, Rotation rotation) {

        private static final String TAG_MIRROR = "mirror";
        private static final String TAG_ROTATION = "rotation";

        BlockState apply(BlockState state) {
            return state.mirror(mirror).rotate(rotation);
        }

        CompoundTag save() {
            return Writer.of().string(TAG_MIRROR, mirror.name()).string(TAG_ROTATION, rotation.name()).tag();
        }

        static Optional<Turn> load(Reader reader) {
            try {
                return Optional.of(new Turn(Mirror.valueOf(reader.string(TAG_MIRROR).orElseThrow()),
                    Rotation.valueOf(reader.string(TAG_ROTATION).orElseThrow())));
            } catch (RuntimeException malformed) {
                return Optional.empty();
            }
        }
    }

    public UnloadedBlock {
        offset = offset.immutable();
        state = state.copy();
        turns = List.copyOf(turns);
    }

    public String block() {
        return state.getString("Name");
    }

    UnloadedBlock turned(BlockPos to, Mirror mirror, Rotation rotation) {
        List<Turn> more = new ArrayList<>(turns);
        more.add(new Turn(mirror, rotation));
        return new UnloadedBlock(to, state, more);
    }

    BlockState turn(BlockState read) {
        BlockState turned = read;
        for (Turn turn : turns) {
            turned = turn.apply(turned);
        }
        return turned;
    }

    CompoundTag save() {
        return Writer.of()
            .blockPos(BlueprintBlock.TAG_OFFSET, offset)
            .blob(BlueprintBlock.TAG_STATE, state)
            .children(TAG_TURNS, turns, Turn::save)
            .tag();
    }

    static List<Turn> turnsOf(Reader reader) {
        return Entries.of(reader, TAG_TURNS, Turn::load);
    }
}
