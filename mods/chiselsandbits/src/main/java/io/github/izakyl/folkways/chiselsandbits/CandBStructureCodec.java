package io.github.izakyl.folkways.chiselsandbits;

import io.github.izakyl.folkways.core.api.structure.StructureCompat;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.block.entities.ChiseledBlockEntity;
import mod.chiselsandbits.storage.StorageEngineBuilder;
import net.jpountz.lz4.LZ4FrameInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public final class CandBStructureCodec {
    private static final int MAX_LAYERS = 16;
    private static final long CACHE_BYTES = 128 * 1024 * 1024;
    private static final Map<Tag, Validated> CACHE = new LinkedHashMap<>(16, 0.75F, true);
    private static long cacheBytes;
    private static final ThreadLocal<Batch> PROCESSING = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> DRAWING = new ThreadLocal<>();

    public record Usage(int reads, long bytes) {}

    /** Accounting for the current synchronous operation, including attempts stopped by its budget. */
    public static Usage usage() {
        Batch batch = PROCESSING.get();
        return batch == null ? new Usage(0, 0) : new Usage(batch.reads, batch.bytes);
    }

    public static StructureCompat.Processing drawing() {
        Batch previous = PROCESSING.get();
        Boolean drawing = DRAWING.get();
        PROCESSING.remove();
        DRAWING.set(true);
        return () -> {
            if (previous == null) {
                PROCESSING.remove();
            } else {
                PROCESSING.set(previous);
            }
            if (drawing == null) {
                DRAWING.remove();
            } else {
                DRAWING.set(drawing);
            }
        };
    }

    public record Validated(StateEntryStorage storage, Map<BlockInformation, Integer> counts, long decodedBytes,
            StructureCompat.Voxels volume) {
        public Validated(StateEntryStorage storage, Map<BlockInformation, Integer> counts, long decodedBytes) {
            this(storage, counts, decodedBytes, CandBMaterials.volume(counts,
                storage.getSize() * storage.getSize() * storage.getSize()));
        }

        public Validated {
            storage = storage.createSnapshot();
            counts = Map.copyOf(counts);
        }

        public boolean matches(StateEntryStorage actual) {
            if (actual.getSize() != storage.getSize()) {
                return false;
            }
            if (actual.palette().paletteEntries().equals(storage.palette().paletteEntries())
                && java.util.Arrays.equals(actual.getRawData(), storage.getRawData())) {
                return true;
            }
            int size = storage.getSize();
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        if (!actual.getBlockInformation(x, y, z).equals(storage.getBlockInformation(x, y, z))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        public double changed(StateEntryStorage actual) {
            if (matches(actual)) {
                return 0;
            }
            int size = Math.max(actual.getSize(), storage.getSize());
            int changed = 0;
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        if (!actual.getBlockInformation(x * actual.getSize() / size, y * actual.getSize() / size,
                                z * actual.getSize() / size).equals(storage.getBlockInformation(
                                x * storage.getSize() / size, y * storage.getSize() / size,
                                    z * storage.getSize() / size))) {
                            changed++;
                        }
                    }
                }
            }
            return changed / (double) (size * size * size);
        }

        public int size() {
            return storage.getSize();
        }

        @Override
        public StateEntryStorage storage() {
            return storage.createSnapshot();
        }
    }

    private static final class Batch implements StructureCompat.Processing {
        private final long started = System.nanoTime();
        private int reads;
        private long bytes;

        void check() {
            if (reads == 0) return;
            if (reads > CandBConfig.value(CandBConfig.BLUEPRINT_READS) || bytes > CandBConfig.value(CandBConfig.BLUEPRINT_BYTES)
                || reads != 0 && System.nanoTime() - started > CandBConfig.value(CandBConfig.BLUEPRINT_MILLIS) * 1_000_000L) {
                throw new StructureCompat.Failure("folkways.refusal.build.structure_budget");
            }
        }

        void read() {
            reads++;
            check();
        }

        @Override
        public void close() {
            try { check(); } finally { PROCESSING.remove(); }
        }
    }

    public static StructureCompat.Processing processing() {
        if (PROCESSING.get() != null || Boolean.TRUE.equals(DRAWING.get())) {
            return () -> { };
        }
        Batch batch = new Batch();
        PROCESSING.set(batch);
        return batch;
    }

    private static void work() {
        Batch batch = PROCESSING.get();
        if (batch != null) batch.read();
    }

    private static void charge(long bytes) {
        Batch batch = PROCESSING.get();
        if (batch != null) {
            batch.bytes += bytes;
            batch.check();
        }
    }

    private CandBStructureCodec() {
    }

    private static final class Read {
        private final long started = System.nanoTime();
        private long bytes;
        private long heap;
        private Map<BlockInformation, Integer> counted;

        void check() {
            Batch batch = PROCESSING.get();
            if (batch != null) batch.check();
            if (System.nanoTime() - started > CandBConfig.value(CandBConfig.PROCESSING_MILLIS) * 1_000_000L) {
                throw new IllegalArgumentException("Voxel processing budget exceeded");
            }
        }

        CompoundTag inflate(byte[] compressed, boolean legacy) {
            check();
            int maximum = CandBConfig.value(CandBConfig.DECODE_BYTES);
            if (compressed.length > maximum || heap >= maximum) {
                throw new IllegalArgumentException("Schematic cell exceeds the decode limit");
            }
            NbtAccounter quota = NbtAccounter.create(maximum - heap);
            try (InputStream lz4 = new LZ4FrameInputStream(new ByteArrayInputStream(compressed));
                    DataInputStream input = new DataInputStream(new FilterInputStream(lz4) {
                        private void used(int count) {
                            if (count > 0) {
                                bytes += count;
                                charge(count);
                            }
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

    /** Placement is bounded by copies, not just distinct cache misses. */
    public static void placement(CompoundTag tag) {
        Validated decoded = validated(tag);
        work();
        charge(decoded.decodedBytes());
    }

    public static StateEntryStorage decode(CompoundTag tag) {
        return validated(tag).storage();
    }

    public static Validated validated(CompoundTag tag) {
        Tag data = tag.get("data");
        if (data == null) throw new IllegalArgumentException("Missing voxel data");
        int resolution = tag.contains("folkways:resolution") ? tag.getInt("folkways:resolution") : 16;
        if (resolution < 1 || resolution > 32 || (resolution & resolution - 1) != 0) {
            throw new IllegalArgumentException("Unsupported voxel resolution");
        }
        CompoundTag key = new CompoundTag();
        key.put("data", data);
        key.putInt("resolution", resolution);
        long encodedBytes = data.sizeInBytes();
        if (encodedBytes > CandBConfig.value(CandBConfig.DECODE_BYTES)) throw new IllegalArgumentException("Voxel input exceeds the decode limit");
        Validated found;
        synchronized (CACHE) { found = CACHE.get(key); }
        if (found != null) {
            if (found.decodedBytes() > CandBConfig.value(CandBConfig.DECODE_BYTES) || found.counts().size() > CandBConfig.value(CandBConfig.MATERIALS)) {
                throw new IllegalArgumentException("Cached voxel data exceeds current limits");
            }
            return found;
        }
        work();
        Read read = new Read();
        StateEntryStorage storage = read(data, read, resolution);
        long bytes = Math.max(encodedBytes, Math.max(read.bytes, read.heap));
        charge(bytes - read.bytes);
        Validated decoded = new Validated(storage, read.counted, bytes);
        // The key and the stored snapshot are private copies; callers may freely turn their own storage.
        synchronized (CACHE) {
            if (!CACHE.containsKey(key)) {
                key = key.copy();
                CACHE.put(key, decoded);
                cacheBytes += weight(key, decoded);
                while (cacheBytes > CACHE_BYTES || CACHE.size() > 8192) {
                    var oldest = CACHE.entrySet().iterator();
                    var entry = oldest.next();
                    cacheBytes -= weight(entry.getKey(), entry.getValue());
                    oldest.remove();
                }
            }
        }
        return decoded;
    }

    private static long weight(Tag key, Validated decoded) {
        return key.sizeInBytes() + decoded.decodedBytes() + 32768L
            + decoded.storage.palette().paletteEntries().size() * 256L + decoded.counts().size() * 128L;
    }

    private static StateEntryStorage read(Tag data, Read read, int resolution) {
        for (int layer = 0; layer < MAX_LAYERS; layer++) {
            read.check();
            if (data instanceof CompoundTag compound) {
                if (compound.contains("version")) {
                    if (compound.getInt("version") != 0 || !(compound.get("payload") instanceof net.minecraft.nbt.ByteArrayTag packed)) {
                        throw new IllegalArgumentException("Unknown voxel storage version");
                    }
                    CompoundTag payload = read.inflate(packed.getAsByteArray(), false);
                    StateEntryStorage storage = StateEntryStorage.CODEC.parse(NbtOps.INSTANCE, payload.get("storage")).getOrThrow();
                    storage = resized(storage, resolution, read);
                    read.counted = counts(storage, read);
                    return storage;
                }
                if (compound.contains("chiseledData")) {
                    // Legacy statistics are cached hints too; only the actual voxels determine materials.
                    StateEntryStorage storage = StateEntryStorage.LEGACY_CODEC.parse(NbtOps.INSTANCE,
                        compound.get("chiseledData")).getOrThrow();
                    storage = resized(storage, resolution, read);
                    read.counted = counts(storage, read);
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
        work();
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
        if (size < 1 || size > 32 || (size & size - 1) != 0) {
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
        if (counts.isEmpty() || counts.size() > CandBConfig.value(CandBConfig.MATERIALS)) {
            throw new IllegalArgumentException("Empty or excessive voxel materials");
        }
        return Map.copyOf(counts);
    }

    public static CompoundTag encode(StateEntryStorage storage) {
        Map<BlockInformation, Integer> counted = counts(storage);
        CompoundTag tag = encodeStorage(storage);
        CompoundTag key = new CompoundTag();
        key.put("data", tag.get("data").copy());
        key.putInt("resolution", storage.getSize());
        long decodedBytes = Math.max(key.sizeInBytes(),
            StateEntryStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage).getOrThrow().sizeInBytes());
        if (decodedBytes > CandBConfig.value(CandBConfig.DECODE_BYTES)) {
            throw StructureCompat.invalid();
        }
        Validated validated = new Validated(storage, counted, decodedBytes);
        synchronized (CACHE) {
            Validated old = CACHE.put(key, validated);
            if (old != null) {
                cacheBytes -= weight(key, old);
            }
            cacheBytes += weight(key, validated);
            while (cacheBytes > CACHE_BYTES || CACHE.size() > 8192) {
                var oldest = CACHE.entrySet().iterator();
                var entry = oldest.next();
                cacheBytes -= weight(entry.getKey(), entry.getValue());
                oldest.remove();
            }
        }
        return tag;
    }

    public static CompoundTag encode(Validated decoded) {
        work();
        return encodeStorage(decoded.storage);
    }

    private static CompoundTag encodeStorage(StateEntryStorage storage) {
        ChiseledBlockEntity.Payload payload = new ChiseledBlockEntity.Payload(storage,
            new ChiseledBlockEntity(net.minecraft.core.BlockPos.ZERO,
                mod.chiselsandbits.registrars.ModBlocks.CHISELED_BLOCK.get().defaultBlockState()).payload().mutableStatistics());
        Tag data = StorageEngineBuilder.<ChiseledBlockEntity.Payload>create()
            .with(ChiseledBlockEntity.Payload.MAP_CODEC).build().encodeStart(NbtOps.INSTANCE, payload).getOrThrow();
        CompoundTag tag = new CompoundTag();
        tag.put("data", data);
        tag.putBoolean("folkways:schematic", true);
        if (storage.getSize() != 16) {
            tag.putInt("folkways:resolution", storage.getSize());
        }
        Batch batch = PROCESSING.get();
        if (batch != null) batch.check();
        return tag;
    }

    // C&B's storage codec omits size and its rotation assumes sixteen; retain the sampled grid explicitly.
    private static StateEntryStorage resized(StateEntryStorage storage, int resolution, Read read) {
        if (storage.getSize() == resolution) {
            return storage;
        }
        int paletteSize = storage.palette().paletteEntries().size();
        int width = paletteSize <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
        if (java.util.BitSet.valueOf(storage.getRawData()).length() > resolution * resolution * resolution * width) {
            throw new IllegalArgumentException("Excessive voxel palette or indices");
        }
        StateEntryStorage result = new StateEntryStorage(resolution);
        try (var batch = result.batch()) {
            int original = storage.getSize();
            for (int x = 0; x < resolution; x++) {
                read.check();
                for (int y = 0; y < resolution; y++) {
                    for (int z = 0; z < resolution; z++) {
                        int index = (x * resolution + y) * resolution + z;
                        result.setBlockInformation(x, y, z, storage.getBlockInformation(
                            index / (original * original), index / original % original, index % original));
                    }
                }
            }
        }
        return result;
    }

    public static void turn(StateEntryStorage storage, Mirror mirror, Rotation rotation) {
        StateEntryStorage before = storage.createSnapshot();
        int size = storage.getSize();
        try (var batch = storage.batch()) {
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        int mx = mirror == Mirror.FRONT_BACK ? size - 1 - x : x;
                        int mz = mirror == Mirror.LEFT_RIGHT ? size - 1 - z : z;
                        int tx = switch (rotation) {
                            case CLOCKWISE_90 -> size - 1 - mz;
                            case CLOCKWISE_180 -> size - 1 - mx;
                            case COUNTERCLOCKWISE_90 -> mz;
                            default -> mx;
                        };
                        int tz = switch (rotation) {
                            case CLOCKWISE_90 -> mx;
                            case CLOCKWISE_180 -> size - 1 - mz;
                            case COUNTERCLOCKWISE_90 -> size - 1 - mx;
                            default -> mz;
                        };
                        storage.setBlockInformation(tx, y, tz, before.getBlockInformation(x, y, z));
                    }
                }
            }
        }
    }
}
