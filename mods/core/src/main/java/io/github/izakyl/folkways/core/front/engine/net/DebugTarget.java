package io.github.izakyl.folkways.core.front.engine.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

public sealed interface DebugTarget {
    DebugTarget NONE = new None();

    record None() implements DebugTarget {
    }

    record Resident(int entityId) implements DebugTarget {
    }

    record Block(BlockPos pos) implements DebugTarget {
        public Block {
            pos = pos.immutable();
        }
    }

    StreamCodec<RegistryFriendlyByteBuf, DebugTarget> STREAM_CODEC = StreamCodec.of((buffer, target) -> {
        switch (target) {
            case None ignored -> buffer.writeVarInt(0);
            case Resident resident -> {
                buffer.writeVarInt(1);
                buffer.writeVarInt(resident.entityId());
            }
            case Block block -> {
                buffer.writeVarInt(2);
                buffer.writeBlockPos(block.pos());
            }
        }
    }, buffer -> switch (buffer.readVarInt()) {
        case 0 -> NONE;
        case 1 -> new Resident(buffer.readVarInt());
        case 2 -> new Block(buffer.readBlockPos());
        default -> throw new DecoderException("Unknown debug target");
    });
}
