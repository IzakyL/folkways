package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record TemplateLibraryPacket(Map<ResourceLocation, CompoundTag> files) implements CustomPacketPayload {

    static final int MOST_PIECES = 64;
    static final long MOST_BYTES = 1L << 20;

    public static final Type<TemplateLibraryPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "piece_library"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TemplateLibraryPacket> STREAM_CODEC =
        StreamCodec.ofMember(TemplateLibraryPacket::encode, TemplateLibraryPacket::decode);

    public TemplateLibraryPacket {
        files = Map.copyOf(files);
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(Math.min(files.size(), MOST_PIECES));
        int written = 0;
        for (Map.Entry<ResourceLocation, CompoundTag> entry : files.entrySet()) {
            if (written++ >= MOST_PIECES) {
                return;
            }
            buffer.writeResourceLocation(entry.getKey());
            buffer.writeNbt(entry.getValue());
        }
    }

    private static TemplateLibraryPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MOST_PIECES);
        Map<ResourceLocation, CompoundTag> files = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            ResourceLocation id = buffer.readResourceLocation();
            CompoundTag tag = buffer.readNbt();
            if (tag != null) {
                files.put(id, tag);
            }
        }
        return new TemplateLibraryPacket(files);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
