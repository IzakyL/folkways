package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ModelLibraryPacket(String fingerprint, List<Entry> entries, List<Asset> assets)
        implements CustomPacketPayload {

    public record Entry(ResourceLocation id, ResidentLook look) {
    }

    public record Asset(String path, int bytes) {
    }

    private static final int MAX_ENTRIES = 512;
    private static final int MAX_ASSETS = 1024;
    private static final int MAX_FINGERPRINT_LENGTH = 64;

    public static final Type<ModelLibraryPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_model_library")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, ModelLibraryPacket> STREAM_CODEC =
        StreamCodec.ofMember(ModelLibraryPacket::encode, ModelLibraryPacket::decode);

    private static final StreamCodec<io.netty.buffer.ByteBuf, ResidentLook> LOOK =
        ByteBufCodecs.fromCodec(ResidentLook.CODEC);

    public ModelLibraryPacket {
        entries = List.copyOf(entries);
        assets = List.copyOf(assets);
    }

    public static ModelLibraryPacket of(ModelLibrary library) {
        List<Entry> entries = new ArrayList<>();
        library.entries().forEach((id, look) -> entries.add(new Entry(id, look)));
        List<Asset> assets = new ArrayList<>();
        library.assets().forEach((path, bytes) -> assets.add(new Asset(path, bytes.length)));
        return new ModelLibraryPacket(library.fingerprint(), entries, assets);
    }

    public Map<ResourceLocation, ResidentLook> pool() {
        Map<ResourceLocation, ResidentLook> map = new LinkedHashMap<>();
        for (Entry entry : entries) {
            map.put(entry.id(), entry.look());
        }
        return map;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeUtf(fingerprint, MAX_FINGERPRINT_LENGTH);
        buffer.writeVarInt(Math.min(entries.size(), MAX_ENTRIES));
        for (Entry entry : entries.subList(0, Math.min(entries.size(), MAX_ENTRIES))) {
            buffer.writeResourceLocation(entry.id());
            LOOK.encode(buffer, entry.look());
        }
        buffer.writeVarInt(Math.min(assets.size(), MAX_ASSETS));
        for (Asset asset : assets.subList(0, Math.min(assets.size(), MAX_ASSETS))) {
            buffer.writeUtf(asset.path());
            buffer.writeVarInt(asset.bytes());
        }
    }

    private static ModelLibraryPacket decode(RegistryFriendlyByteBuf buffer) {
        String fingerprint = buffer.readUtf(MAX_FINGERPRINT_LENGTH);
        int entryCount = buffer.readVarInt();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            ResourceLocation id = buffer.readResourceLocation();
            ResidentLook look = LOOK.decode(buffer);
            if (i < MAX_ENTRIES) {
                entries.add(new Entry(id, look));
            }
        }
        int assetCount = buffer.readVarInt();
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < assetCount; i++) {
            String path = buffer.readUtf();
            int bytes = buffer.readVarInt();
            if (i < MAX_ASSETS) {
                assets.add(new Asset(path, bytes));
            }
        }
        return new ModelLibraryPacket(fingerprint, entries, assets);
    }
}
