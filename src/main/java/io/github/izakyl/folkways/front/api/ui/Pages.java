package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class Pages {

    // The colony a player may act for through the book in hand.
    private static Function<ServerPlayer, Optional<Colony>> authority = player -> Optional.empty();

    private Pages() {
    }

    public static void install(Function<ServerPlayer, Optional<Colony>> installed) {
        authority = installed;
    }

    public static Optional<Colony> colonyOf(Player player) {
        return player instanceof ServerPlayer serverPlayer ? authority.apply(serverPlayer) : Optional.empty();
    }

    public static Optional<ServerLevel> levelOf(Player player) {
        return player instanceof ServerPlayer serverPlayer
            ? Optional.of(serverPlayer.serverLevel())
            : Optional.empty();
    }

    public static Tag text(HolderLookup.Provider registries, Component component) {
        return ComponentSerialization.CODEC
            .encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), component)
            .result()
            .orElseGet(CompoundTag::new);
    }

    public static Component readText(HolderLookup.Provider registries, Tag tag) {
        if (tag == null) {
            return Component.empty();
        }
        return ComponentSerialization.CODEC
            .parse(registries.createSerializationContext(NbtOps.INSTANCE), tag)
            .result()
            .filter(Objects::nonNull)
            .orElseGet(Component::empty);
    }
}
