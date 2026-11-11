package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record DebugFocusPacket(DebugTarget target, List<Line> card, List<Link> links, List<StoreMark> stores)
        implements CustomPacketPayload {
    public static final int MAX_LINES = 48;
    public static final int MAX_LINKS = 32;
    public static final int MAX_STORES = 256;

    public enum Kind {
        ROUTE, DRAW, INTO, ASSIGNEE
    }

    public record Link(DebugTarget from, DebugTarget to, Kind kind, int index, Component label) {
    }

    public record StoreMark(BlockPos pos, InventorySnapshot.State state, boolean differs) {
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, Link> LINK_CODEC = StreamCodec.of((buffer, link) -> {
        DebugTarget.STREAM_CODEC.encode(buffer, link.from());
        DebugTarget.STREAM_CODEC.encode(buffer, link.to());
        buffer.writeEnum(link.kind());
        buffer.writeVarInt(link.index());
        ComponentSerialization.STREAM_CODEC.encode(buffer, link.label());
    }, buffer -> new Link(DebugTarget.STREAM_CODEC.decode(buffer), DebugTarget.STREAM_CODEC.decode(buffer),
        buffer.readEnum(Kind.class), buffer.readVarInt(), ComponentSerialization.STREAM_CODEC.decode(buffer)));

    private static final StreamCodec<RegistryFriendlyByteBuf, StoreMark> STORE_CODEC =
        StreamCodec.of((buffer, mark) -> {
            buffer.writeBlockPos(mark.pos());
            buffer.writeEnum(mark.state());
            buffer.writeBoolean(mark.differs());
        }, buffer -> new StoreMark(buffer.readBlockPos(), buffer.readEnum(InventorySnapshot.State.class),
            buffer.readBoolean()));

    public static final Type<DebugFocusPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "debug_focus"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DebugFocusPacket> STREAM_CODEC = StreamCodec.composite(
        DebugTarget.STREAM_CODEC, DebugFocusPacket::target,
        SyncCodecs.lines(MAX_LINES), DebugFocusPacket::card,
        SyncCodecs.capped(LINK_CODEC, MAX_LINKS), DebugFocusPacket::links,
        SyncCodecs.capped(STORE_CODEC, MAX_STORES), DebugFocusPacket::stores,
        DebugFocusPacket::new);

    public DebugFocusPacket {
        card = List.copyOf(card.subList(0, Math.min(card.size(), MAX_LINES)));
        links = List.copyOf(links.subList(0, Math.min(links.size(), MAX_LINKS)));
        stores = List.copyOf(stores.subList(0, Math.min(stores.size(), MAX_STORES)));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
