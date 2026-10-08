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
public record LookPool(Optional<Set<ResourceLocation>> only, Set<ResourceLocation> excluded) {

    public static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_look");

    private static final String TAG_EXCLUDED = "excluded";
    private static final String TAG_ONLY = "only";
    private static final String TAG_REPLACED = "replaced";

    public LookPool {
        only = only.map(chosen -> Collections.unmodifiableSet(new LinkedHashSet<>(chosen)));
        excluded = Collections.unmodifiableSet(new LinkedHashSet<>(excluded));
    }

    public static LookPool from(CompoundTag kept) {
        Reader reader = Reader.of(kept);
        Optional<Set<ResourceLocation>> only = reader.flag(TAG_REPLACED).orElse(false)
            ? Optional.of(new LinkedHashSet<>(reader.ids(TAG_ONLY)))
            : Optional.empty();
        return new LookPool(only, new LinkedHashSet<>(reader.ids(TAG_EXCLUDED)));
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

    public static void replace(Colony colony, Collection<ResourceLocation> looks) {
        colony.keep(OWNER, new LookPool(Optional.of(new LinkedHashSet<>(looks)), Set.of()).save());
    }

    public LookPool with(ResourceLocation look, boolean allowed) {
        if (only.isPresent()) {
            Set<ResourceLocation> next = new LinkedHashSet<>(only.get());
            if (allowed) {
                next.add(look);
            } else {
                next.remove(look);
            }
            return new LookPool(Optional.of(next), excluded);
        }
        Set<ResourceLocation> next = new LinkedHashSet<>(excluded);
        if (allowed) {
            next.remove(look);
        } else {
            next.add(look);
        }
        return new LookPool(only, next);
    }

    public boolean allows(ResourceLocation look) {
        return only.map(chosen -> chosen.contains(look)).orElseGet(() -> !excluded.contains(look));
    }

    public CompoundTag save() {
        Writer writer = Writer.of().ids(TAG_EXCLUDED, excluded).flag(TAG_REPLACED, only.isPresent());
        only.ifPresent(chosen -> writer.ids(TAG_ONLY, chosen));
        return writer.tag();
    }
}
