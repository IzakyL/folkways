package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DamageControlState(UUID colony, ResourceLocation dimension, List<Area> areas, List<Mark> marks)
    implements CustomPacketPayload {
    public static final int MAX_AREAS = 128;
    public static final int MAX_MARKS = 512;
    public record Area(UUID id, BlockPos min, BlockPos max, int flags, boolean baseline, List<Line> lines) {
    }
    public record Mark(UUID area, BlockPos at, int color, List<Line> lines) {
    }

    public static final Type<DamageControlState> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "damage_control_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DamageControlState> CODEC = StreamCodec.of(
        (buffer, state) -> {
            buffer.writeUUID(state.colony());
            buffer.writeResourceLocation(state.dimension());
            buffer.writeVarInt(state.areas().size());
            for (Area area : state.areas()) {
                buffer.writeUUID(area.id());
                buffer.writeBlockPos(area.min());
                buffer.writeBlockPos(area.max());
                buffer.writeVarInt(area.flags());
                buffer.writeBoolean(area.baseline());
                Line.encodeAll(buffer, area.lines(), 12);
            }
            buffer.writeVarInt(state.marks().size());
            for (Mark mark : state.marks()) {
                buffer.writeUUID(mark.area());
                buffer.writeBlockPos(mark.at());
                buffer.writeInt(mark.color());
                Line.encodeAll(buffer, mark.lines(), 12);
            }
        }, buffer -> {
            UUID colony = buffer.readUUID();
            ResourceLocation dimension = buffer.readResourceLocation();
            List<Area> areas = new ArrayList<>();
            for (int left = count(buffer, MAX_AREAS); left > 0; left--) {
                areas.add(new Area(buffer.readUUID(), buffer.readBlockPos(), buffer.readBlockPos(),
                    buffer.readVarInt(), buffer.readBoolean(), Line.decodeAll(buffer, 12)));
            }
            List<Mark> marks = new ArrayList<>();
            for (int left = count(buffer, MAX_MARKS); left > 0; left--) {
                marks.add(new Mark(buffer.readUUID(), buffer.readBlockPos(), buffer.readInt(),
                    Line.decodeAll(buffer, 12)));
            }
            return new DamageControlState(colony, dimension, List.copyOf(areas), List.copyOf(marks));
        });

    private static int count(RegistryFriendlyByteBuf buffer, int max) {
        int size = buffer.readVarInt();
        if (size < 0 || size > max) {
            throw new DecoderException("damage control state count " + size);
        }
        return size;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
