package io.github.izakyl.folkways.core.world.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class WorldSpaces {

    public interface Frame {
        UUID id();
        Level level();
        BlockPos storageOrigin();
        default Optional<net.minecraft.world.level.levelgen.structure.BoundingBox> bounds() {
            return Optional.empty();
        }
        Vec3 toWorld(Vec3 local);
        Vec3 toLocal(Vec3 world);

        /** World velocity at a local point, in blocks per server tick. */
        default Vec3 velocity(Vec3 local) {
            return Vec3.ZERO;
        }

        /** World angular velocity, in radians per server tick. */
        default Vec3 angularVelocity() {
            return Vec3.ZERO;
        }

        default Vec3 toWorld(Vec3 local, float partialTick) {
            return toWorld(local);
        }

        default Vec3 toLocal(Vec3 world, float partialTick) {
            return toLocal(world);
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

        default List<? extends Frame> frames(Level level) {
            return List.of();
        }
    }

    public record Block(ServerLevel level, BlockPos cell) {
    }

    /** The part of a moving structure a world box covers, as an inclusive box in the structure's local cells. */
    public record Overlap(Frame frame, BoundingBox local) {
    }

    // Past this many candidate cells an overlap is the structure's box clipped to the world box's, unchecked cell
    // by cell; a turned structure then reads a little larger than it is.
    private static final int MOST_CHECKED = 1 << 18;

    private static final List<Source> SOURCES = new CopyOnWriteArrayList<>();

    private WorldSpaces() {
    }

    public static Runnable install(Source source) {
        SOURCES.add(source);
        return () -> SOURCES.remove(source);
    }

    public static List<? extends Frame> frames(Level level) {
        return SOURCES.stream().flatMap(source -> source.frames(level).stream()).toList();
    }

    /**
     * The moving structures in this level a world box covers, each with the local cells whose middles lie in it.
     * Works on either side, as far as that side knows the structures.
     */
    public static List<Overlap> overlapping(Level level, BlockPos min, BlockPos max) {
        AABB world = new AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max).add(1, 1, 1));
        List<Overlap> found = new java.util.ArrayList<>();
        for (Frame frame : frames(level)) {
            Optional<BoundingBox> bounds = frame.bounds();
            if (bounds.isEmpty()) {
                continue;
            }
            double[] lo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] hi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int corner = 0; corner < 8; corner++) {
                Vec3 local = frame.toLocal(new Vec3((corner & 1) == 0 ? world.minX : world.maxX,
                    (corner & 2) == 0 ? world.minY : world.maxY, (corner & 4) == 0 ? world.minZ : world.maxZ));
                double[] at = {local.x, local.y, local.z};
                for (int axis = 0; axis < 3; axis++) {
                    lo[axis] = Math.min(lo[axis], at[axis]);
                    hi[axis] = Math.max(hi[axis], at[axis]);
                }
            }
            BoundingBox box = bounds.get();
            int x0 = Math.max(box.minX(), (int) Math.floor(lo[0]));
            int y0 = Math.max(box.minY(), (int) Math.floor(lo[1]));
            int z0 = Math.max(box.minZ(), (int) Math.floor(lo[2]));
            int x1 = Math.min(box.maxX(), (int) Math.ceil(hi[0]) - 1);
            int y1 = Math.min(box.maxY(), (int) Math.ceil(hi[1]) - 1);
            int z1 = Math.min(box.maxZ(), (int) Math.ceil(hi[2]) - 1);
            if (x0 > x1 || y0 > y1 || z0 > z1) {
                continue;
            }
            if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > MOST_CHECKED) {
                found.add(new Overlap(frame, new BoundingBox(x0, y0, z0, x1, y1, z1)));
                continue;
            }
            BoundingBox covered = null;
            for (BlockPos cell : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
                if (world.contains(frame.toWorld(Vec3.atCenterOf(cell)))) {
                    covered = covered == null ? new BoundingBox(cell) : covered.encapsulate(cell);
                }
            }
            if (covered != null) {
                found.add(new Overlap(frame, covered));
            }
        }
        return List.copyOf(found);
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
