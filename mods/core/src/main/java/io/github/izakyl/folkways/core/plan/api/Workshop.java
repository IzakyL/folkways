package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Hold;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface Workshop extends Refinement {

    UUID key();

    WorkSite site();

    /** Optional durable continuation of an accepted external recipe, including its original operating supplies. */
    default Optional<CompoundTag> saveWork(Grown work, List<Hold> holds, HolderLookup.Provider registries) {
        return Optional.empty();
    }

    /** Restores the original steps and booking without repricing already consumed inputs. Holds are not taken yet. */
    default Optional<Refinement.Change> resumeWork(CompoundTag data, HolderLookup.Provider registries) {
        return Optional.empty();
    }

    // Override for dynamic component outputs not enumerable from registered items or Minecraft recipe results.
    // This is the derived-demand repertoire, not the colony's submitted root work.
    default List<WorkshopRecipes.Recipe> recipes(Set<ItemSpec> products) {
        return WorkshopRecipes.discover(this, products);
    }

    /** Carried work uses this resident alone, at its AtEntity site, with inputs and output in its pack.
     * Every offered node must declare the same worker and use WHEREVER stances. */
    default Optional<UUID> bearer() {
        return Optional.empty();
    }

    /**
     * Whether the workshop owns the inventory at its site, excluding it from ordinary colony supply while
     * retaining storage room.
     * Pack-crafting workstations supplied by an enrolled store return false: their work uses the worker's pack,
     * and the same block remains a store. Explicit {@code Colony.withhold} exclusions still apply.
     */
    default boolean withholdsStore() {
        return true;
    }

    // Whether its offers for one ask are ways to the same goods that spoken for hold the same, and differ only in
    // where the work is done: the plan takes whichever suits the crew where it stands, and weighs that again until the
    // work is begun. A node that may still be swapped for another way says so by being replannable.
    default boolean alike() {
        return false;
    }
}
