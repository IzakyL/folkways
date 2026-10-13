package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

// The looks a colony draws from when someone joins. Until it is replaced by a set it is every look
// there is, less the ones struck off; once replaced, it is that set's looks, plus and less the edits since.
// Looks borrowed from another mod stay out of an unreplaced pool until they are switched on one by one.
public record LookPool(
        Optional<Set<ResourceLocation>> only, Set<ResourceLocation> excluded, Set<ResourceLocation> included) {

    public static final LookPool UNTOUCHED = new LookPool(Optional.empty(), Set.of(), Set.of());

    public static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_look");

    private static final String TAG_EXCLUDED = "excluded";
    private static final String TAG_INCLUDED = "included";
    private static final String TAG_ONLY = "only";
    private static final String TAG_REPLACED = "replaced";

    public LookPool {
        only = only.map(chosen -> Collections.unmodifiableSet(new LinkedHashSet<>(chosen)));
        excluded = Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
        included = Collections.unmodifiableSet(new LinkedHashSet<>(included));
    }

    public static LookPool from(CompoundTag kept) {
        Reader reader = Reader.of(kept);
        Optional<Set<ResourceLocation>> only = reader.flag(TAG_REPLACED).orElse(false)
            ? Optional.of(new LinkedHashSet<>(reader.ids(TAG_ONLY)))
            : Optional.empty();
        return new LookPool(only, new LinkedHashSet<>(reader.ids(TAG_EXCLUDED)),
            new LinkedHashSet<>(reader.ids(TAG_INCLUDED)));
    }

    public static LookPool of(Colony colony) {
        return from(colony.kept(OWNER));
    }

    public static boolean set(Colony colony, ResourceLocation look, boolean allowed) {
        LookPool pool = of(colony);
        if (pool.allows(look) == allowed) {
            return false;
        }
        colony.keep(OWNER, pool.with(look, allowed).save());
        return true;
    }

    public static void setAll(Colony colony, Collection<ResourceLocation> looks, boolean allowed) {
        LookPool pool = of(colony);
        LookPool next = pool;
        for (ResourceLocation look : looks) {
            next = next.with(look, allowed);
        }
        if (!next.equals(pool)) {
            colony.keep(OWNER, next.save());
        }
    }

    public static void replace(Colony colony, Collection<ResourceLocation> looks) {
        colony.keep(OWNER, new LookPool(Optional.of(new LinkedHashSet<>(looks)), Set.of(), Set.of()).save());
    }

    public LookPool with(ResourceLocation look, boolean allowed) {
        if (only.isPresent()) {
            Set<ResourceLocation> next = new LinkedHashSet<>(only.get());
            if (allowed) {
                next.add(look);
            } else {
                next.remove(look);
            }
            return new LookPool(Optional.of(next), excluded, included);
        }
        boolean borrowed = MaidLooks.isMaid(look);
        Set<ResourceLocation> next = new LinkedHashSet<>(borrowed ? included : excluded);
        if (allowed == borrowed) {
            next.add(look);
        } else {
            next.remove(look);
        }
        return borrowed ? new LookPool(only, excluded, next) : new LookPool(only, next, included);
    }

    public boolean allows(ResourceLocation look) {
        return only.map(chosen -> chosen.contains(look))
            .orElseGet(() -> MaidLooks.isMaid(look) ? included.contains(look) : !excluded.contains(look));
    }

    public CompoundTag save() {
        Writer writer = Writer.of().ids(TAG_EXCLUDED, excluded).ids(TAG_INCLUDED, included)
            .flag(TAG_REPLACED, only.isPresent());
        only.ifPresent(chosen -> writer.ids(TAG_ONLY, chosen));
        return writer.tag();
    }
}
