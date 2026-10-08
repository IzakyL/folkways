package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public interface Colony {

    UUID id();

    void publish(ResourceLocation owner, ResourceKey<Level> dimension, Collection<Workshop> workshops);

    // Replaces every store this owner offers the colony's work in that dimension.
    void stores(ResourceLocation owner, ResourceKey<Level> dimension, Collection<WorldPos> stores);

    List<WorldPos> storesIn(ResourceKey<Level> dimension);

    // Replaces every rule this owner set on the colony's stores in that dimension.
    void rule(ResourceLocation owner, ResourceKey<Level> dimension, Collection<StoreRule> rules);

    // Each node of the work is asked on its own, and heard ending on its own.
    void submit(ResourceLocation owner, ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended);

    void withdraw(ResourceLocation owner, UUID node);

    void keep(ResourceLocation owner, CompoundTag tag);

    CompoundTag kept(ResourceLocation owner);

    // Takes it up for `owner`; empty when a block or entity is already held, by this colony or another.
    Optional<Holding> hold(ResourceLocation owner, Held what);

    void release(UUID holding, Release why);

    List<Holding> holdings();

    List<Holding> holdings(ResourceLocation owner);

    Optional<Holding> holding(UUID holding);

    Optional<Holding> holdingAt(WorldPos cell);

    Optional<Holding> holdingOf(UUID entity);

    // The held entities of a kind of resident, loaded or not.
    List<Resident> residents();

    // A held body came into the world, or went out of it without being let go.
    void entered(Body body);

    void exited(UUID entity);

    void raze();

    <T> Optional<T> service(ResourceLocation owner, Class<T> type);

    List<ColonyView> views(MinecraftServer server);

    ColonyView view(ServerLevel level);
}
