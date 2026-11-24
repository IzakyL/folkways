package io.github.izakyl.folkways.vanilla.cookery;

import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.facility.api.Contribution;
import io.github.izakyl.folkways.core.facility.api.Facility;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Trigger;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.shell.cookery.api.Appliances;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A kitchen appliance as a prefab facility: recognised by its block, worked from its Core control, and run on demand.
 * Each appliance definition supplies its native workshop and recovers its unfinished batches.
 */
public record KitchenPrefab(UUID id, WorldPos site, Appliance appliance,
                            Function<ServerLevel, Optional<Workshop>> offer) implements Facility {

    @Override
    public FacilityExtent extent(ServerLevel level) {
        return FacilityExtent.blocks(geometry(level).stream().filter(Contribution.Protection.class::isInstance)
            .map(Contribution.Protection.class::cast).flatMap(part -> part.cells().stream()).toList());
    }

    public KitchenPrefab(UUID id, WorldPos site, Appliance appliance) {
        this(id, site, appliance, level -> Optional.empty());
    }

    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "cookery/appliance");

    static void register() {
        Prefabs.register(ID, (level, pos) -> KitchenStations.find(level.getBlockState(pos))
            .map(station -> new KitchenPrefab(identity(level, pos), WorldPos.of(level, pos), station)));
    }

    /** The kitchen appliance whose anchor is at `pos`, recognised as a prefab. */
    public static Optional<KitchenPrefab> at(ServerLevel level, BlockPos pos) {
        return Prefabs.at(level, pos).filter(KitchenPrefab.class::isInstance).map(KitchenPrefab.class::cast);
    }

    // The same block is the same facility for as long as it stands; nothing keeps the id.
    private static UUID identity(ServerLevel level, BlockPos pos) {
        return UUID.nameUUIDFromBytes((ID + "|" + level.dimension().location() + "|" + pos.asLong())
            .getBytes(StandardCharsets.UTF_8));
    }

    KitchenPrefab offering(StationApproach approach, UUID key, Stances stances, ColonyWork work) {
        return new KitchenPrefab(id, site, appliance, level ->
            approach.freeze(() -> appliance.freeze(key, work.colony, level, site, stances, work.bookings, work.changed)));
    }

    @Override
    public List<Contribution> geometry(ServerLevel level) {
        return List.of(new Contribution.Protection(Set.of(site)));
    }

    @Override
    public List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        var protection = geometry(level).getFirst();
        return offer.apply(level).<List<Contribution>>map(workshop ->
            List.of(protection, new Contribution.Workshop(workshop))).orElseGet(() -> List.of(protection));
    }

    @Override
    public Map<String, Routine.Use> routines() {
        return Map.of();
    }

    @Override
    public List<Trigger> triggers() {
        return List.of();
    }

    @Override
    public Map<String, Value> parameters() {
        return Map.of();
    }

    @Override
    public Map<String, Reading> readings(ServerLevel level) {
        return Map.of();
    }

    @Override
    public Optional<Rig> rig(ServerLevel level) {
        if (!site.in(level)) {
            return Optional.empty();
        }
        return KitchenStation.control(level, site)
            .map(port -> Rig.of(level, Map.<String, Control>of(Appliances.INPUT, port)));
    }

    List<Grown> recovery(ServerLevel level, StationApproach approach, ColonyWork work) {
        Optional<Stances> current = approach.get();
        if (current.isPresent()) {
            return approach.observe(() -> appliance.recovery(work.colony, level, site,
                current.get(), work.bookings, work.changed));
        }
        return appliance.activeRecovery(work.colony, level, site, work.bookings);
    }

    // One booking ledger per colony, shared by its appliances.
    static final class ColonyWork {
        private final UUID colony;
        private final Runnable changed;
        private final KitchenBookings bookings = new KitchenBookings();

        ColonyWork(UUID colony, Runnable changed) {
            this.colony = colony;
            this.changed = changed;
        }

        void admit(WorldPos at) {
            bookings.admit(at);
        }

        void withdraw(WorldPos at) {
            bookings.withdraw(at);
        }

        void clear() {
            bookings.clear();
        }
    }

    public interface Appliance {
        boolean matches(BlockState state);

        default List<RecipeType<?>> recipeTypes() {
            return List.of();
        }

        default List<Component> conditions() {
            return List.of();
        }

        default BlockPos anchor(Level level, BlockPos clicked) {
            return clicked.immutable();
        }

        // Where an appliance is worked from is its control's stance (Core's cookery adapter knows the handle sides).
        default Optional<Stances> stances(ServerLevel level, WorldPos at) {
            if (!at.in(level) || !level.isLoaded(at.block(level))) { return Optional.empty(); }
            Optional<Port<ItemStack>> port = KitchenStation.control(level, at);
            if (port.isEmpty()) {
                return Stances.of(level, Reach.workableCells(level, at.block(level)));
            }
            return port.get().stance(level);
        }

        // Empty means that this provider must recompute its offer on every observation.
        default Optional<Object> freezeSignature(ServerLevel level, WorldPos at) {
            return Optional.empty();
        }

        default Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                         Stances stances, KitchenBookings bookings, Runnable changed) {
            return Optional.empty();
        }

        default List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
            return List.of();
        }

        // A running recovery remains wanted when its approach is temporarily blocked. This must never
        // create a new task: providers return only the original, successfully planned recovery instance.
        default List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
            return List.of();
        }

        default void release(UUID colony, ServerLevel level, WorldPos at) {
        }

        // Runs even after the last station is unmarked: a resident may still be walking out of it.
        default void tick(ServerLevel level, UUID colony) {
        }

        // Shutdown releases running bodies while leaving physical, persistent batches to recover.
        default void stop(UUID colony, Closing why) {
        }

        default Component status(ServerLevel level, WorldPos at) {
            return Component.translatable("folkways.page.cookery.idle");
        }
    }
}
