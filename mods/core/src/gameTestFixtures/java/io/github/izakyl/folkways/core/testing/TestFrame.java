package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A moving structure's frame, as a contraption or ship mod would install one: blocks stored from a storage origin,
 * drawn in the world from an origin of its own, turned a quarter about the vertical when turned. Both can be moved
 * while a test runs.
 */
public final class TestFrame implements WorldSpaces.Frame {
    private final UUID id = UUID.randomUUID();
    private final Level level;
    private final boolean turned;
    private volatile BlockPos storage;
    private volatile Vec3 origin;

    private TestFrame(Level level, BlockPos storage, Vec3 origin, boolean turned) {
        this.level = level;
        this.storage = storage;
        this.origin = origin;
        this.turned = turned;
    }

    // Drawn a quarter turn round from `origin`.
    public static TestFrame turned(Level level, BlockPos storage, Vec3 origin) {
        return new TestFrame(level, storage, origin, true);
    }

    // Drawn just where it is stored, so moving its storage moves it in the world too.
    public static TestFrame stored(Level level, BlockPos storage) {
        return new TestFrame(level, storage, null, false);
    }

    public void moveBy(Vec3 by) {
        origin = origin().add(by);
    }

    public void store(BlockPos at) {
        storage = at;
    }

    // Finds this frame by its id and, when `contains`, holding the block at its storage origin; never anyone aboard.
    public Runnable install(boolean contains) {
        return WorldSpaces.install(new WorldSpaces.Source() {
            public Optional<WorldSpaces.Frame> frame(Level host, UUID key) {
                return host == level && key.equals(id) ? Optional.of(TestFrame.this) : Optional.empty();
            }

            public Optional<WorldSpaces.Frame> containing(Level host, BlockPos cell) {
                return contains && host == level && cell.equals(storage) ? Optional.of(TestFrame.this)
                    : Optional.empty();
            }

            public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                return Optional.empty();
            }
        });
    }

    private Vec3 origin() {
        Vec3 at = origin;
        return at == null ? Vec3.atLowerCornerOf(storage) : at;
    }

    public UUID id() {
        return id;
    }

    public Level level() {
        return level;
    }

    public BlockPos storageOrigin() {
        return storage;
    }

    public Vec3 toWorld(Vec3 local) {
        Vec3 from = origin();
        return turned ? from.add(-local.z, local.y, local.x) : local.add(from);
    }

    public Vec3 toLocal(Vec3 world) {
        Vec3 delta = world.subtract(origin());
        return turned ? new Vec3(delta.z, delta.y, -delta.x) : delta;
    }
}
