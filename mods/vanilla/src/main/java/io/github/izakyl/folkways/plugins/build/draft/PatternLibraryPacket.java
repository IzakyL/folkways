package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

public record PatternLibraryPacket(Map<ResourceLocation, String> sources) implements CustomPacketPayload {

    static final int MAX_PATTERNS = 256;
    static final int MAX_SOURCE = 131_072;
    static final int MAX_LIBRARY_BYTES = 900_000;

    public static final Type<PatternLibraryPacket> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "pattern_library"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PatternLibraryPacket> STREAM_CODEC =
        StreamCodec.ofMember(PatternLibraryPacket::encode, PatternLibraryPacket::decode);

    public PatternLibraryPacket {
        sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
    }

    static Map<ResourceLocation, String> sendable(Map<ResourceLocation, String> read, Logger logger) {
        Map<ResourceLocation, String> kept = new LinkedHashMap<>();
        long bytes = 0;
        for (Map.Entry<ResourceLocation, String> entry : read.entrySet()) {
            String source = entry.getValue();
            long size = source.getBytes(StandardCharsets.UTF_8).length;
            if (source.length() > MAX_SOURCE) {
                logger.error("{}: {} characters, over the {} a pattern may run to; it is left out",
                    entry.getKey(), source.length(), MAX_SOURCE);
            } else if (kept.size() >= MAX_PATTERNS || bytes + size > MAX_LIBRARY_BYTES) {
                logger.error("{}: over the {} patterns or {} bytes the server sends its players; it is left out",
                    entry.getKey(), MAX_PATTERNS, MAX_LIBRARY_BYTES);
            } else {
                kept.put(entry.getKey(), source);
                bytes += size;
            }
        }
        return kept;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        List<Map.Entry<ResourceLocation, String>> fitting = sources.entrySet().stream()
            .filter(entry -> entry.getValue().length() <= MAX_SOURCE)
            .limit(MAX_PATTERNS)
            .toList();
        buffer.writeVarInt(fitting.size());
        for (Map.Entry<ResourceLocation, String> entry : fitting) {
            buffer.writeResourceLocation(entry.getKey());
            buffer.writeUtf(entry.getValue(), MAX_SOURCE);
        }
    }

    private static PatternLibraryPacket decode(RegistryFriendlyByteBuf buffer) {
        int count = Math.min(buffer.readVarInt(), MAX_PATTERNS);
        Map<ResourceLocation, String> sources = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            ResourceLocation id = buffer.readResourceLocation();
            sources.put(id, buffer.readUtf(MAX_SOURCE));
        }
        return new PatternLibraryPacket(sources);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
