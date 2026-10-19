package io.github.izakyl.folkways.core.api.terms;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class WorldSpaces {

    public interface Frame {
        UUID id();
        Level level();
        BlockPos storageOrigin();
        Vec3 toWorld(Vec3 local);
        Vec3 toLocal(Vec3 world);

        default Vec3 toWorld(Vec3 local, float partialTick) {
            return toWorld(local);
        }

        default BlockPos storage(BlockPos local) {
            return local.offset(storageOrigin());
        }

        default BlockPos local(BlockPos storage) {
            return storage.subtract(storageOrigin());
        }
    }

    public interface Source {
        Optional<? extends Frame> frame(Level level, UUID id);
        Optional<? extends Frame> containing(Level level, BlockPos storage);
        Optional<? extends Frame> aboard(Entity entity);
    }

    public record Block(ServerLevel level, BlockPos cell) {
    }

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private WorldSpaces() {
    }

    public static Runnable install(Source source) {
        SOURCES.add(source);
        return () -> SOURCES.remove(source);
    }

    public static Optional<Frame> frame(Level level, UUID id) {
        for (Source source : SOURCES) {
            Optional<? extends Frame> found = source.frame(level, id);
            if (found.isPresent()) {
                return Optional.of(found.get());
            }
        }
        return Optional.empty();
    }

    public static Optional<Frame> frame(MinecraftServer server, UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            Optional<Frame> found = frame(level, id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    public static Optional<Frame> containing(Level level, BlockPos storage) {
        for (Source source : SOURCES) {
            Optional<? extends Frame> found = source.containing(level, storage);
            if (found.isPresent()) {
                return Optional.of(found.get());
            }
        }
        return Optional.empty();
    }

    public static WorldPos at(Level level, BlockPos storage) {
        return containing(level, storage)
            .map(frame -> new WorldPos(new Realm.Frame(frame.id()), frame.local(storage)))
            .orElseGet(() -> new WorldPos(Realm.of(level), storage));
    }

    public static WorldPos at(Entity body) {
        for (Source source : SOURCES) {
            Optional<? extends Frame> found = source.aboard(body);
            if (found.isPresent()) {
                Frame frame = found.get();
                return new WorldPos(new Realm.Frame(frame.id()), BlockPos.containing(frame.toLocal(body.position())));
            }
        }
        return at(body.level(), body.blockPosition());
    }

    public static Optional<Block> resolve(MinecraftServer server, WorldPos address) {
        if (address.realm() instanceof Realm.Dimension dimension) {
            return Optional.ofNullable(server.getLevel(dimension.id())).map(level -> new Block(level, address.cell()));
        }
        UUID id = ((Realm.Frame) address.realm()).structure();
        return frame(server, id).filter(frame -> frame.level() instanceof ServerLevel)
            .map(frame -> new Block((ServerLevel) frame.level(), frame.storage(address.cell())));
    }

    public static Optional<BlockPos> storage(Level level, WorldPos address) {
        if (address.realm() instanceof Realm.Dimension dimension) {
            return dimension.id().equals(level.dimension()) ? Optional.of(address.cell()) : Optional.empty();
        }
        return frame(level, ((Realm.Frame) address.realm()).structure()).map(frame -> frame.storage(address.cell()));
    }

    public static Optional<Vec3> world(Level level, WorldPos address) {
        if (address.realm() instanceof Realm.Dimension dimension) {
            return dimension.id().equals(level.dimension())
                ? Optional.of(Vec3.atBottomCenterOf(address.cell())) : Optional.empty();
        }
        return frame(level, ((Realm.Frame) address.realm()).structure())
            .map(frame -> frame.toWorld(Vec3.atBottomCenterOf(address.cell())));
    }

    public static Optional<Vec3> relative(Level level, Realm realm, Vec3 world) {
        if (realm instanceof Realm.Dimension dimension) {
            return dimension.id().equals(level.dimension()) ? Optional.of(world) : Optional.empty();
        }
        return frame(level, ((Realm.Frame) realm).structure()).map(frame -> frame.toLocal(world));
    }
}
