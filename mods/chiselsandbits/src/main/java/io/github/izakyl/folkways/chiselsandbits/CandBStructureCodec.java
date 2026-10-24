package io.github.izakyl.folkways.chiselsandbits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.storage.StorageEngineBuilder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.jpountz.lz4.LZ4FrameInputStream;

public final class CandBStructureCodec {
    // Read storage alone: uploaded statistics are never evidence of the materials present.
    private static final Codec<StateEntryStorage> CODEC = StorageEngineBuilder.<StateEntryStorage>create()
        .with(compressed(false).fieldOf("payload"))
        .fallback(compressed(true).fieldOf("data"))
        .build();

    private CandBStructureCodec() {
    }

    private static Codec<StateEntryStorage> compressed(boolean legacy) {
        return Codec.BYTE_BUFFER.comapFlatMap(bytes -> {
            if (bytes.remaining() > CandBConfig.DECODE_BYTES.get()) {
                return DataResult.error(() -> "Schematic cell exceeds the decode limit");
            }
            byte[] compressed = new byte[bytes.remaining()];
            bytes.get(compressed);
            try (DataInputStream input = new DataInputStream(new LZ4FrameInputStream(new ByteArrayInputStream(compressed)))) {
                NbtAccounter limit = NbtAccounter.create(CandBConfig.DECODE_BYTES.get());
                Tag read = legacy ? NbtIo.read(input, limit) : NbtIo.readAnyTag(input, limit);
                if (!(read instanceof CompoundTag tag)) {
                    return DataResult.error(() -> "Missing voxel storage");
                }
                return (legacy ? StateEntryStorage.LEGACY_CODEC : StateEntryStorage.CODEC)
                    .parse(NbtOps.INSTANCE, tag.get(legacy ? "chiseledData" : "storage"));
            } catch (IOException | RuntimeException failure) {
                return DataResult.error(() -> "Invalid voxel storage: " + failure.getMessage());
            }
        }, storage -> {
            throw new UnsupportedOperationException("Schematic storage is written as a complete C&B payload");
        });
    }

    public static StateEntryStorage decode(CompoundTag tag) {
        Tag data = tag.get("data");
        if (data == null) {
            throw new IllegalArgumentException("Missing voxel data");
        }
        StateEntryStorage storage = CODEC.parse(NbtOps.INSTANCE, data).getOrThrow();
        counts(storage);
        return storage;
    }

    public static Map<BlockInformation, Integer> counts(StateEntryStorage storage) {
        Map<BlockInformation, Integer> counts = new LinkedHashMap<>();
        int size = storage.getSize();
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("Unsupported voxel resolution");
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    BlockInformation material = storage.getBlockInformation(x, y, z);
                    if (!material.isAir()) {
                        counts.merge(material, 1, Integer::sum);
                    }
                }
            }
        }
        if (counts.isEmpty() || counts.size() > CandBConfig.MATERIALS.get()) {
            throw new IllegalArgumentException("Empty or excessive voxel materials");
        }
        return Map.copyOf(counts);
    }

    public static CompoundTag encode(StateEntryStorage storage) {
        counts(storage);
        ChiseledBlockEntity.Payload payload = new ChiseledBlockEntity.Payload(storage,
            new ChiseledBlockEntity(net.minecraft.core.BlockPos.ZERO,
                mod.chiselsandbits.registrars.ModBlocks.CHISELED_BLOCK.get().defaultBlockState()).payload().mutableStatistics());
        Tag data = StorageEngineBuilder.<ChiseledBlockEntity.Payload>create()
            .with(ChiseledBlockEntity.Payload.MAP_CODEC).build().encodeStart(NbtOps.INSTANCE, payload).getOrThrow();
        CompoundTag tag = new CompoundTag();
        tag.put("data", data);
        tag.putBoolean("folkways:schematic", true);
        return tag;
    }

    public static void turn(StateEntryStorage storage, Mirror mirror, Rotation rotation) {
        if (mirror != Mirror.NONE) {
            storage.mirror(mirror == Mirror.FRONT_BACK ? Direction.Axis.X : Direction.Axis.Z);
        }
        int turns = switch (rotation) {
            case CLOCKWISE_90 -> 3;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 1;
            default -> 0;
        };
        if (turns != 0) {
            storage.rotate(Direction.Axis.Y, turns);
        }
    }
}
