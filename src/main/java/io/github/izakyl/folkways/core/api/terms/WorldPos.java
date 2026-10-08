package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public record WorldPos(Realm realm, BlockPos cell) {

    private static final String TAG_REALM = "realm";
    private static final String TAG_POS = "pos";

    public WorldPos {
        Objects.requireNonNull(realm, "realm");
        cell = Objects.requireNonNull(cell, "cell").immutable();
    }

    public static WorldPos of(Level level, BlockPos cell) {
        return WorldSpaces.at(level, cell);
    }

    public static WorldPos of(ResourceKey<Level> dimension, BlockPos cell) {
        return new WorldPos(Realm.of(dimension), cell);
    }

    public BlockPos block(Level level) {
        return WorldSpaces.storage(level, this).orElseThrow(
            () -> new IllegalStateException("unresolved address " + this + " in " + level.dimension().location()));
    }

    public Optional<ServerLevel> level(MinecraftServer server) {
        return WorldSpaces.resolve(server, this).map(WorldSpaces.Block::level);
    }

    public boolean sameRealm(WorldPos other) {
        return realm.equals(other.realm);
    }

    public boolean in(Level level) {
        return WorldSpaces.storage(level, this).isPresent();
    }

    public boolean in(ResourceKey<Level> dimension) {
        return realm.isDimension(dimension);
    }

    public WorldPos at(BlockPos other) {
        return new WorldPos(realm, other);
    }

    public WorldPos above() {
        return new WorldPos(realm, cell.above());
    }

    public CompoundTag save() {
        return Writer.of()
            .blob(TAG_REALM, realm.save())
            .blockPos(TAG_POS, cell)
            .tag();
    }

    public static Optional<WorldPos> load(Reader reader) {
        Optional<Realm> realm = reader.child(TAG_REALM).flatMap(Realm::load);
        Optional<BlockPos> cell = reader.blockPos(TAG_POS);
        if (realm.isEmpty() || cell.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WorldPos(realm.get(), cell.get()));
    }

    public static WorldPos read(FriendlyByteBuf buffer) {
        Realm realm = buffer.readBoolean()
            ? new Realm.Frame(buffer.readUUID())
            : Realm.of(buffer.readResourceKey(Registries.DIMENSION));
        return new WorldPos(realm, buffer.readBlockPos());
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBoolean(realm instanceof Realm.Frame);
        if (realm instanceof Realm.Frame frame) {
            buffer.writeUUID(frame.structure());
        } else {
            buffer.writeResourceKey(((Realm.Dimension) realm).id());
        }
        buffer.writeBlockPos(cell);
    }

    @Override
    public String toString() {
        return switch (realm) {
            case Realm.Dimension(ResourceKey<Level> id) -> id.location() + "@" + cell.toShortString();
            case Realm.Frame(UUID structure) -> structure + "@" + cell.toShortString();
        };
    }
}
