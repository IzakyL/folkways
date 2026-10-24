package io.github.izakyl.folkways.chiselsandbits;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
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
    private static final int MAX_LAYERS = 16;

    private CandBStructureCodec() {
    }

    private static final class Read {
        private final long started = System.nanoTime();
        private long bytes;
        private long heap;

        void check() {
            if (System.nanoTime() - started > CandBConfig.PROCESSING_MILLIS.get() * 1_000_000L) {
                throw new IllegalArgumentException("Voxel processing budget exceeded");
            }
        }

        CompoundTag inflate(byte[] compressed, boolean legacy) {
            check();
            int maximum = CandBConfig.DECODE_BYTES.get();
            if (compressed.length > maximum || heap >= maximum) {
                throw new IllegalArgumentException("Schematic cell exceeds the decode limit");
            }
            NbtAccounter quota = NbtAccounter.create(maximum - heap);
            try (InputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(compressed));
                    DataInputStream input = new DataInputStream(new FilterInputStream(lz4) {
                        private void used(int count) {
                            if (count > 0) bytes += count;
                            if (bytes > maximum) throw new IllegalArgumentException("Cumulative voxel decode limit exceeded");
                            check();
                        }

                        @Override
                        public int read() throws IOException {
                            int result = in.read();
                            used(result < 0 ? 0 : 1);
                            return result;
                        }

                        @Override
                        public int read(byte[] buffer, int offset, int length) throws IOException {
                            int result = in.read(buffer, offset, length);
                            used(result);
                            return result;
                        }
                    })) {
                Tag read = legacy ? NbtIo.read(input, quota) : NbtIo.readAnyTag(input, quota);
                heap += quota.getUsage();
                check();
                if (read instanceof CompoundTag compound) return compound;
                throw new IllegalArgumentException("Missing voxel storage");
            } catch (IOException failure) {
                throw new IllegalArgumentException("Invalid voxel storage", failure);
            }
        }
    }

    public static StateEntryStorage decode(CompoundTag tag) {
        Read read = new Read();
        Tag data = tag.get("data");
        for (int layer = 0; layer < MAX_LAYERS; layer++) {
            read.check();
            if (data instanceof CompoundTag compound) {
                if (compound.contains("version")) {
                    if (compound.getInt("version") != 0 || !(compound.get("payload") instanceof net.minecraft.nbt.ByteArrayTag packed)) {
                        throw new IllegalArgumentException("Unknown voxel storage version");
                    }
                    CompoundTag payload = read.inflate(packed.getAsByteArray(), false);
                    StateEntryStorage storage = StateEntryStorage.CODEC.parse(NbtOps.INSTANCE, payload.get("storage")).getOrThrow();
                    counts(storage, read);
                    return storage;
                }
                if (compound.contains("chiseledData")) {
                    // Legacy statistics are cached hints too; only the actual voxels determine materials.
                    StateEntryStorage storage = StateEntryStorage.LEGACY_CODEC.parse(NbtOps.INSTANCE,
                        compound.get("chiseledData")).getOrThrow();
                    counts(storage, read);
                    return storage;
                }
                data = compound.get("data");
            } else if (data instanceof net.minecraft.nbt.ByteArrayTag packed) {
                data = read.inflate(packed.getAsByteArray(), true);
            } else {
                throw new IllegalArgumentException("Missing voxel data");
            }
        }
        throw new IllegalArgumentException("Too many legacy voxel wrappers");
    }

    public static Map<BlockInformation, Integer> counts(StateEntryStorage storage) {
        return counts(storage, new Read());
    }

    private static Map<BlockInformation, Integer> counts(StateEntryStorage storage, Read read) {
        Map<BlockInformation, Integer> counts = new LinkedHashMap<>();
        int size = storage.getSize();
        int paletteSize = storage.palette().paletteEntries().size();
        int width = paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        java.util.BitSet indices = java.util.BitSet.valueOf(storage.getRawData());
        if (indices.length() > size * size * size * width) {
            throw new IllegalArgumentException("Excessive voxel palette or indices");
        }
        if (size < 1 || size > 32) {
            throw new IllegalArgumentException("Unsupported voxel resolution");
        }
        for (int x = 0; x < size; x++) {
            read.check();
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    int index = mod.chiselsandbits.api.util.ByteArrayUtils.getValueAt(indices, width,
                        x * size * size + y * size + z);
                    if (index >= paletteSize) throw new IllegalArgumentException("Invalid voxel palette index");
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
