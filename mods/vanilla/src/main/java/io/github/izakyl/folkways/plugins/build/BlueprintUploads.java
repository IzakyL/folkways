package io.github.izakyl.folkways.plugins.build;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

// A file past what one packet carries goes over in slices, and is put back together before its upload is read.
public final class BlueprintUploads {

    // The most a whole file may run to, slices and all.
    public static final int MOST_BYTES = 2 * 1024 * 1024;

    private static final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private BlueprintUploads() {
    }

    public static boolean fits(byte[] file) {
        return file.length <= MOST_BYTES;
    }

    // What a client sends for one upload: the slices first, if any, then the upload.
    public static void send(UploadBlueprintPacket upload, Consumer<CustomPacketPayload> sender) {
        byte[] file = upload.file();
        if (file.length <= UploadBlueprintPacket.MAX_BYTES) {
            sender.accept(upload);
            return;
        }
        int size = UploadBlueprintPacket.MAX_BYTES;
        int count = (file.length + size - 1) / size;
        for (int index = 0; index < count; index++) {
            int from = index * size;
            byte[] chunk = Arrays.copyOfRange(file, from, Math.min(file.length, from + size));
            sender.accept(new BlueprintChunkPacket(index, count, chunk));
        }
        sender.accept(upload.withFile(new byte[0]));
    }

    static void accept(UUID player, BlueprintChunkPacket chunk) {
        if (chunk.index() == 0) {
            pending.put(player, new Pending(chunk.count()));
        }
        Pending held = pending.get(player);
        if (held == null || !held.take(chunk)) {
            pending.remove(player);
        }
    }

    // The file the slices made, once they all came, in order and within the bound.
    static Optional<byte[]> assembled(UUID player) {
        Pending held = pending.remove(player);
        return held == null ? Optional.empty() : held.whole();
    }

    static void forget(UUID player) {
        pending.remove(player);
    }

    private static final class Pending {
        private final int count;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int next;

        Pending(int count) {
            this.count = count;
        }

        boolean take(BlueprintChunkPacket chunk) {
            if (chunk.count() != count || chunk.index() != next || count <= 0
                    || bytes.size() + chunk.chunk().length > MOST_BYTES) {
                return false;
            }
            bytes.writeBytes(chunk.chunk());
            next++;
            return true;
        }

        Optional<byte[]> whole() {
            return next == count ? Optional.of(bytes.toByteArray()) : Optional.empty();
        }
    }
}
