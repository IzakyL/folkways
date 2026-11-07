package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.inventory.api.ColonyInventory;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Stall;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

public interface Colony {

    UUID id();

    default ColonyInventory inventory() {
        InventorySnapshot empty = InventorySnapshot.empty(id());
        return () -> empty;
    }

    default InventoryFeedback inventoryFeedback() {
        return InventoryFeedback.NONE;
    }

    void publish(ResourceLocation owner, ResourceKey<Level> dimension, Collection<Workshop> workshops);

    void publishTransport(ResourceLocation owner, ResourceKey<Level> dimension,
                          Collection<TransportPublication.Offering> modes);

    TransportPublication transports();

    default long transportVersion() {
        return transports().version();
    }

    // Replaces every store this owner offers the colony's work in that dimension.
    void stores(ResourceLocation owner, ResourceKey<Level> dimension, Collection<WorldPos> stores);

    List<WorldPos> storesIn(ResourceKey<Level> dimension);

    // The resident's view of an offered store, with the player's slot and admission rules applied.
    default Optional<Container> store(ServerLevel level, WorldPos at) {
        return Optional.empty();
    }

    // Effective rules at a store, including the permissions checked again when a transfer executes.
    default List<StoreRule> storeRules(WorldPos at) {
        return List.of();
    }

    // Current unspent input obligations; these must be reconciled with actual goods, never counted as stock.
    default List<io.github.izakyl.folkways.core.work.api.ReservedInput> reservedInputs(ServerLevel level) {
        return List.of();
    }

    // Active body control for a work node, including personal work.
    default Optional<UUID> holder(UUID node) {
        return Optional.empty();
    }

    // An assigned body is still approaching the node's initial planned stance.
    default boolean isApproaching(UUID node) {
        return false;
    }

    // Suspended work retains its node resources while another holding uses the body.
    default boolean isSuspended(UUID node) {
        return false;
    }

    // A concrete controller order takes the body immediately, including during soft cleanup.
    default void controllerOrder(Body body) {
    }

    default boolean isHeld(UUID node) {
        return holder(node).isPresent();
    }

    // The colony's work standing still in that dimension, each told where it is; see Stall.
    List<Stall> stallsIn(ResourceKey<Level> dimension);

    // Replaces every store this owner answers for itself in that dimension: its goods are reached only through what
    // the owner offers, so the colony's work never counts them as a store's, whoever else offers it.
    void withhold(ResourceLocation owner, ResourceKey<Level> dimension, Collection<WorldPos> stores);

    // Replaces every rule this owner set on the colony's stores in that dimension.
    void rule(ResourceLocation owner, ResourceKey<Level> dimension, Collection<StoreRule> rules);

    // Each node of the work is asked on its own, and heard ending on its own.
    void submit(ResourceLocation owner, ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended);

    void withdraw(ResourceLocation owner, UUID node);

    void keep(ResourceLocation owner, CompoundTag tag);

    CompoundTag kept(ResourceLocation owner);

    // Takes it up for `owner`; empty when a block or entity is already held, by this colony or another.
    Optional<Claim> hold(ResourceLocation owner, Claimed what);

    void release(UUID claim, ClaimRelease why);

    List<Claim> claims();

    List<Claim> claims(ResourceLocation owner);

    Optional<Claim> claim(UUID claim);

    Optional<Claim> claimAt(WorldPos cell);

    Optional<Claim> claimOf(UUID entity);

    // The held entities of a kind of resident, loaded or not.
    List<Resident> residents();

    // A held body came into the world, or went out of it without being let go.
    void entered(Body body);

    void exited(UUID entity);

    // A held body was removed from its level for `reason`: let go when it died or was discarded, only gone out of the
    // world when it was unloaded, and neither when what carries it took it off.
    void removed(UUID entity, Entity.RemovalReason reason);

    // A held body was taken out of the world by what carries it, which puts it back as it was, like a train's rider
    // while the carriage runs over unloaded ground. It has not left: it stays held and at its work, only not simulated
    // until it comes back.
    void carriedOff(UUID entity);

    boolean isCarriedOff(UUID entity);

    void raze();

    <T> Optional<T> service(ResourceLocation owner, Class<T> type);

    List<ColonyView> views(MinecraftServer server);

    ColonyView view(ServerLevel level);
}
