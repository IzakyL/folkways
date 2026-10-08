package io.github.izakyl.folkways.fuzz;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * A live case's residents, watched as the server ticks them: one dying of anything the case did not do to it, or
 * leaving the platform, is a violation of its own whatever the case is about.
 */
public final class Crew {

    private final Map<UUID, ResidentEntity> people = new LinkedHashMap<>();
    private final Set<UUID> killed = new LinkedHashSet<>();
    private final Set<UUID> mourned = new LinkedHashSet<>();
    private int lowest = Plot.FLOOR - 4;

    public Crew(List<ResidentEntity> residents) {
        residents.forEach(person -> people.put(person.getUUID(), person));
    }

    /** Sets how low a resident may go before it has fallen off the platform: the case digs that deep. */
    public Crew lowest(int y) {
        lowest = y;
        return this;
    }

    public int size() {
        return people.size();
    }

    public List<ResidentEntity> standing() {
        return people.values().stream().filter(Entity::isAlive).toList();
    }

    public boolean anyKilled() {
        return !killed.isEmpty();
    }

    /** Kills the {@code who}th resident still standing, as the case means to; answers it, or null if none stands. */
    public ResidentEntity kill(int who) {
        List<ResidentEntity> standing = standing();
        if (standing.isEmpty()) {
            return null;
        }
        ResidentEntity victim = standing.get(Math.floorMod(who, standing.size()));
        killed.add(victim.getUUID());
        victim.kill();
        return victim;
    }

    public void watch(long elapsed, Function<BlockPos, String> rel, List<Violation> into) {
        watch(elapsed, rel, into, (person, cause) -> false);
    }

    /**
     * Adds a violation for each resident newly dead or fallen, unless {@code excused} says the case itself did it
     * (it is asked with the resident and the damage it died of, or "fell").
     */
    public void watch(long elapsed, Function<BlockPos, String> rel, List<Violation> into,
            BiPredicate<ResidentEntity, String> excused) {
        for (ResidentEntity person : people.values()) {
            UUID id = person.getUUID();
            if (killed.contains(id) || mourned.contains(id)) {
                continue;
            }
            if (!person.isAlive()) {
                mourned.add(id);
                String cause = person.getLastDamageSource() == null ? "unknown" : person.getLastDamageSource().getMsgId();
                if (excused.test(person, cause)) {
                    continue;
                }
                into.add(new Violation("resident.died", "a resident died of " + cause + " at "
                    + rel.apply(person.blockPosition()) + ", " + elapsed + " ticks in; nothing in the case killed it"));
            } else if (person.getY() < lowest) {
                mourned.add(id);
                if (excused.test(person, "fell")) {
                    continue;
                }
                into.add(new Violation("resident.fell", "a resident fell off the platform at "
                    + rel.apply(person.blockPosition()) + ", " + elapsed + " ticks in"));
            }
        }
    }

    /** What the residents still standing carry in their packs, by item (what a hand shows is a copy of a pack slot). */
    public Map<Item, Long> carried() {
        Map<Item, Long> out = new LinkedHashMap<>();
        for (ResidentEntity person : standing()) {
            for (int slot = 0; slot < person.pack().getContainerSize(); slot++) {
                ItemStack stack = person.pack().getItem(slot);
                if (!stack.isEmpty()) {
                    out.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
                }
            }
        }
        return out;
    }
}
