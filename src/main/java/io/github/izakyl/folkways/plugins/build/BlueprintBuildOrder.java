package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

public record BlueprintBuildOrder(
    UUID id,
    UUID blueprintId,
    String name,
    WorldPos anchor,
    Optional<UUID> playerId,
    String playerName,
    long createdGameTime
) {
    private static final String TAG_ID = "id";
    private static final String TAG_BLUEPRINT_ID = "blueprintId";
    private static final String TAG_NAME = "name";
    private static final String TAG_ANCHOR = "anchor";
    private static final String TAG_PLAYER_ID = "playerId";
    private static final String TAG_PLAYER_NAME = "playerName";
    private static final String TAG_CREATED_GAME_TIME = "createdGameTime";
    private static final int MAX_PLAYER_NAME_LENGTH = 64;

    public BlueprintBuildOrder {
        id = Objects.requireNonNull(id);
        blueprintId = Objects.requireNonNull(blueprintId);
        name = SiteNames.typed(name);
        playerId = Objects.requireNonNull(playerId);
        playerName = normalizePlayerName(playerName);
    }

    private static String normalizePlayerName(String value) {
        String normalized = value == null ? "" : value;
        return normalized.length() > MAX_PLAYER_NAME_LENGTH
            ? normalized.substring(0, MAX_PLAYER_NAME_LENGTH)
            : normalized;
    }

    public static BlueprintBuildOrder create(UUID blueprintId, String name, WorldPos anchor, Optional<UUID> playerId,
            String playerName, long createdGameTime) {
        return new BlueprintBuildOrder(UUID.randomUUID(), blueprintId, name, anchor, playerId, playerName,
            createdGameTime);
    }

    BlueprintBuildOrder named(String given) {
        return new BlueprintBuildOrder(id, blueprintId, given, anchor, playerId, playerName, createdGameTime);
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        return Writer.of()
            .uuid(TAG_ID, id)
            .uuid(TAG_BLUEPRINT_ID, blueprintId)
            .string(TAG_NAME, name)
            .blob(TAG_ANCHOR, anchor.save())
            .uuid(TAG_PLAYER_ID, playerId)
            .string(TAG_PLAYER_NAME, playerName)
            .longValue(TAG_CREATED_GAME_TIME, createdGameTime)
            .tag();
    }

    public static Optional<BlueprintBuildOrder> load(Reader reader, HolderLookup.Provider provider) {
        Optional<UUID> id = reader.uuid(TAG_ID);
        Optional<UUID> blueprintId = reader.uuid(TAG_BLUEPRINT_ID);
        Optional<WorldPos> anchor = reader.child(TAG_ANCHOR).flatMap(WorldPos::load);
        if (id.isEmpty() || blueprintId.isEmpty() || anchor.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            new BlueprintBuildOrder(
                id.get(),
                blueprintId.get(),
                reader.string(TAG_NAME).orElse(""),
                anchor.get(),
                reader.uuid(TAG_PLAYER_ID),
                reader.string(TAG_PLAYER_NAME).orElse(""),
                reader.longValue(TAG_CREATED_GAME_TIME).orElse(0L)
            )
        );
    }

}
