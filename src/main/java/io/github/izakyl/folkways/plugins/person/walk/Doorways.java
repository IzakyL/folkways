package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

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

    // A resident removed while going through (dead, unloaded) never stops its goal, so it never leaves. Drop it
    // with the next one that does; until then the doorway stays counted, and the next resident through closes it.
    private static void forgetVanished(Set<Mob> using) {
        using.removeIf(Entity::isRemoved);
    }

    public static void forgetServer() {
        USING.clear();
    }
}
