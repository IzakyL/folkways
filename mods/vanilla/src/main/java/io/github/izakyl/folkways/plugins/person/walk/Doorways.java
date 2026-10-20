package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

// A count of the residents using each doorway they opened: one comes in as it is about to go through, and goes
// out as its body leaves the opening. The doorway closes when the count falls to nobody.
public final class Doorways {

    private static final Map<WorldPos, Set<Mob>> USING = new HashMap<>();

    private Doorways() {
    }

    static boolean inUse(Level level, BlockPos door) {
        return USING.containsKey(WorldPos.of(level, door));
    }

    static void enter(Level level, BlockPos door, Mob mob) {
        USING.computeIfAbsent(WorldPos.of(level, door), key -> new HashSet<>()).add(mob);
    }

    // True when nobody uses the doorway any longer, and so it wants closing.
    static boolean leave(Level level, BlockPos door, Mob mob) {
        WorldPos key = WorldPos.of(level, door);
        Set<Mob> using = USING.get(key);
        if (using == null) {
            return true;
        }
        using.remove(mob);
        forgetVanished(using);
        if (!using.isEmpty()) {
            return false;
        }
        USING.remove(key);
        return true;
    }

    // A resident removed while going through never stops its goal. Release its doorways at removal too.
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        var entries = USING.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<WorldPos, Set<Mob>> entry = entries.next();
            if (!entry.getValue().remove(mob) || !entry.getValue().isEmpty()) {
                continue;
            }
            WorldPos door = entry.getKey();
            entries.remove();
            WorldSpaces.storage(level, door).filter(level::isLoaded)
                .ifPresent(cell -> OpenDoorwayGoal.setOpen(mob, level, cell, false));
        }
    }

    private static void forgetVanished(Set<Mob> using) {
        using.removeIf(Entity::isRemoved);
    }

    public static void forgetServer() {
        USING.clear();
    }
}
