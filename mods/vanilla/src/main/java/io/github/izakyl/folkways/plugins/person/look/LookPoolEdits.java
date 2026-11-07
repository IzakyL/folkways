package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Books;
import io.github.izakyl.folkways.core.colony.api.Colony;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

// A confirmed edit travels in slices and is applied once they have all arrived. Only changed looks travel:
// replacing the pool would also change what it does with looks added later, or ones the client cannot load.
public record LookPoolEdits(int index, int count, byte[] bytes) implements CustomPacketPayload {

    public static final int CHUNK_BYTES = 24_000;
    public static final int MOST_BYTES = 8 * 1024 * 1024;
    public static final Type<LookPoolEdits> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "look_pool_edits"));
    public static final StreamCodec<RegistryFriendlyByteBuf, LookPoolEdits> CODEC =
        StreamCodec.ofMember(LookPoolEdits::encode, LookPoolEdits::decode);
    private static final Map<ServerPlayer, Pending> pending = new WeakHashMap<>();

    public record Edit(ResourceLocation look, boolean on) {
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void encode(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(index);
        buffer.writeVarInt(count);
        buffer.writeByteArray(bytes);
    }

    private static LookPoolEdits decode(RegistryFriendlyByteBuf buffer) {
        return new LookPoolEdits(buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES));
    }

    public static void send(List<Edit> edits, Consumer<CustomPacketPayload> sender) {
        if (edits.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        edits.forEach(edit -> text.append(edit.on() ? '+' : '-').append(edit.look()).append('\n'));
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MOST_BYTES) {
            throw new IllegalArgumentException("Look pool edits exceed " + MOST_BYTES + " bytes");
        }
        int count = (bytes.length + CHUNK_BYTES - 1) / CHUNK_BYTES;
        for (int index = 0; index < count; index++) {
            int from = index * CHUNK_BYTES;
            sender.accept(new LookPoolEdits(index, count,
                Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + CHUNK_BYTES))));
        }
    }

    static void accept(ServerPlayer player, LookPoolEdits part) {
        Optional<Colony> colony = Books.acting(player);
        if (colony.isEmpty()) {
            pending.remove(player);
            return;
        }
        if (part.index() == 0) {
            pending.put(player, new Pending(part.count(), colony.get()));
        }
        Pending held = pending.get(player);
        if (held == null || held.colony != colony.get() || !held.take(part)) {
            pending.remove(player);
            return;
        }
        if (held.next == held.count) {
            pending.remove(player);
        }
        held.whole().ifPresent(edits -> {
            Set<ResourceLocation> offered = ResidentLooks.pool(player.registryAccess()).keySet();
            LookPool before = LookPool.of(colony.get());
            LookPool after = apply(before, edits, offered);
            if (!after.equals(before)) {
                colony.get().keep(LookPool.OWNER, after.save());
            }
        });
    }

    static LookPool apply(LookPool pool, List<Edit> edits, Set<ResourceLocation> offered) {
        Set<ResourceLocation> only = pool.only().map(LinkedHashSet::new).orElseGet(LinkedHashSet::new);
        Set<ResourceLocation> excluded = new LinkedHashSet<>(pool.excluded());
        Set<ResourceLocation> included = new LinkedHashSet<>(pool.included());
        for (Edit edit : edits) {
            if (!offered.contains(edit.look())) {
                continue;
            }
            boolean borrowed = MaidLooks.isMaid(edit.look());
            Set<ResourceLocation> into = pool.only().isPresent() ? only : borrowed ? included : excluded;
            boolean add = pool.only().isPresent() ? edit.on() : edit.on() == borrowed;
            if (add) {
                into.add(edit.look());
            } else {
                into.remove(edit.look());
            }
        }
        return new LookPool(pool.only().map(ignored -> only), excluded, included);
    }

    static final class Pending {
        private final int count;
        private final Colony colony;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int next;

        Pending(int count, Colony colony) {
            this.count = count;
            this.colony = colony;
        }

        boolean take(LookPoolEdits part) {
            if (count <= 0 || count > (MOST_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES
                    || part.count() != count || part.index() != next || part.bytes().length > CHUNK_BYTES
                    || bytes.size() + part.bytes().length > MOST_BYTES) {
                return false;
            }
            bytes.writeBytes(part.bytes());
            next++;
            return true;
        }

        Optional<List<Edit>> whole() {
            if (next != count) {
                return Optional.empty();
            }
            List<Edit> edits = new ArrayList<>();
            for (String line : bytes.toString(StandardCharsets.UTF_8).split("\n")) {
                if (line.length() < 2 || line.charAt(0) != '+' && line.charAt(0) != '-') {
                    return Optional.empty();
                }
                ResourceLocation id = ResourceLocation.tryParse(line.substring(1));
                if (id == null) {
                    return Optional.empty();
                }
                edits.add(new Edit(id, line.charAt(0) == '+'));
            }
            return Optional.of(List.copyOf(edits));
        }
    }
}
