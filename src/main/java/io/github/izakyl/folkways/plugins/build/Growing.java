package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.build.draft.Growth;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

record Growing(
    UUID id,
    ResourceLocation pattern,
    String name,
    Hint hint,
    CompoundTag settings,
    Direction facing,
    long seed,
    WorldPos anchor,
    Growth growth,
    Optional<UUID> order,
    boolean last,
    Optional<UUID> playerId,
    String playerName,
    String stuck,
    String stuckDetail,
    long retryAt
) {
    private static final String TAG_ID = "id";
    private static final String TAG_PATTERN = "pattern";
    private static final String TAG_NAME = "name";
    private static final String TAG_HINT = "hint";
    private static final String TAG_SETTINGS = "settings";
    private static final String TAG_FACING = "facing";
    private static final String TAG_SEED = "seed";
    private static final String TAG_ANCHOR = "anchor";
    private static final String TAG_ROUND = "round";
    private static final String TAG_KEPT = "kept";
    private static final String TAG_REACH = "reach";
    private static final String TAG_ORDER = "order";
    private static final String TAG_LAST = "last";
    private static final String TAG_PLAYER_ID = "playerId";
    private static final String TAG_PLAYER_NAME = "playerName";
    private static final String TAG_STUCK = "stuck";
    private static final String TAG_STUCK_DETAIL = "stuckDetail";
    private static final String TAG_RETRY_AT = "retryAt";

    Growing {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pattern, "pattern");
        name = SiteNames.typed(name);
        Objects.requireNonNull(hint, "hint");
        settings = Objects.requireNonNull(settings, "settings").copy();
        if (facing == null || facing.getAxis() == Direction.Axis.Y) {
            facing = Direction.NORTH;
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(growth, "growth");
        Objects.requireNonNull(order, "order");
        Objects.requireNonNull(playerId, "playerId");
        playerName = playerName == null ? "" : playerName;
        stuck = stuck == null ? "" : stuck;
        stuckDetail = stuckDetail == null ? "" : stuckDetail;
    }

    static Growing begun(ResourceLocation pattern, String name, Hint hint, CompoundTag settings, Direction facing,
            long seed, WorldPos anchor, Optional<UUID> playerId, String playerName) {
        return new Growing(UUID.randomUUID(), pattern, name, hint, settings, facing, seed, anchor, Growth.first(hint),
            Optional.empty(), false, playerId, playerName, "", "", 0L);
    }

    long roundSeed() {
        return seed * 31L + growth.round();
    }

    Growing building(UUID placed, Growth next, boolean lastRound) {
        return new Growing(id, pattern, name, hint, settings, facing, seed, anchor, next, Optional.of(placed), lastRound,
            playerId, playerName, "", "", 0L);
    }

    Growing waiting() {
        return new Growing(id, pattern, name, hint, settings, facing, seed, anchor, growth, Optional.empty(), last,
            playerId, playerName, stuck, stuckDetail, retryAt);
    }

    Growing named(String given) {
        return new Growing(id, pattern, given, hint, settings, facing, seed, anchor, growth, order, last, playerId,
            playerName, stuck, stuckDetail, retryAt);
    }

    Growing stuck(String why, String detail, long retry) {
        return new Growing(id, pattern, name, hint, settings, facing, seed, anchor, growth, order, last, playerId,
            playerName, why, detail, retry);
    }

    CompoundTag save() {
        BoundingBox reach = growth.reach();
        return Writer.of()
            .uuid(TAG_ID, id)
            .id(TAG_PATTERN, pattern)
            .string(TAG_NAME, name)
            .blob(TAG_HINT, hint.save())
            .blob(TAG_SETTINGS, settings)
            .string(TAG_FACING, facing.getName())
            .longValue(TAG_SEED, seed)
            .blob(TAG_ANCHOR, anchor.save())
            .integer(TAG_ROUND, growth.round())
            .blob(TAG_KEPT, growth.kept())
            .intArray(TAG_REACH, new int[] {reach.minX(), reach.minY(), reach.minZ(), reach.maxX(), reach.maxY(),
                reach.maxZ()})
            .uuid(TAG_ORDER, order)
            .flag(TAG_LAST, last)
            .uuid(TAG_PLAYER_ID, playerId)
            .string(TAG_PLAYER_NAME, playerName)
            .string(TAG_STUCK, stuck)
            .string(TAG_STUCK_DETAIL, stuckDetail)
            .longValue(TAG_RETRY_AT, retryAt)
            .tag();
    }

    static Optional<Growing> load(Reader reader) {
        Optional<UUID> id = reader.uuid(TAG_ID);
        Optional<ResourceLocation> pattern = reader.id(TAG_PATTERN);
        Optional<Hint> hint = reader.blob(TAG_HINT).flatMap(Hint::load);
        Optional<WorldPos> anchor = reader.child(TAG_ANCHOR).flatMap(WorldPos::load);
        int[] reach = reader.intArray(TAG_REACH).orElse(new int[0]);
        if (id.isEmpty() || pattern.isEmpty() || hint.isEmpty() || anchor.isEmpty() || reach.length != 6) {
            return Optional.empty();
        }
        Growth growth = new Growth(Math.max(0, reader.integer(TAG_ROUND).orElse(0)),
            reader.blob(TAG_KEPT).orElseGet(CompoundTag::new),
            new BoundingBox(reach[0], reach[1], reach[2], reach[3], reach[4], reach[5]));
        return Optional.of(new Growing(id.get(), pattern.get(), reader.string(TAG_NAME).orElse(""),
            hint.get(),
            reader.blob(TAG_SETTINGS).orElseGet(CompoundTag::new),
            Direction.byName(reader.string(TAG_FACING).orElse("north")),
            reader.longValue(TAG_SEED).orElse(0L), anchor.get(), growth, reader.uuid(TAG_ORDER),
            reader.flag(TAG_LAST).orElse(false), reader.uuid(TAG_PLAYER_ID),
            reader.string(TAG_PLAYER_NAME).orElse(""), reader.string(TAG_STUCK).orElse(""),
            reader.string(TAG_STUCK_DETAIL).orElse(""),
            reader.longValue(TAG_RETRY_AT).orElse(0L)));
    }
}
