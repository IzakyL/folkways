package io.github.izakyl.folkways.plugins.person.name;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

// The names a colony draws from when someone joins. Until it is edited it is every name set
// the data packs offer, taken together; after that it is its own copy, kept with the colony.
public final class ColonyNames {

    public static final ResourceLocation OWNER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "resident_name");

    private static final String TAG_NAMES = "names";
    private static final String TAG_SURNAMES = "surnames";

    public enum Part {
        GIVEN,
        SURNAME
    }

    private ColonyNames() {
    }

    public static NamePool of(Colony colony, RegistryAccess registries) {
        return read(colony.kept(OWNER)).orElseGet(() -> ResidentNames.merged(registries));
    }

    public static boolean add(Colony colony, RegistryAccess registries, Part part, String written) {
        String name = written.trim();
        if (!fits(name)) {
            return false;
        }
        NamePool pool = of(colony, registries);
        List<String> into = new ArrayList<>(listOf(pool, part));
        if (into.contains(name)) {
            return false;
        }
        into.add(name);
        keep(colony, part == Part.GIVEN ? new NamePool(into, pool.surnames()) : new NamePool(pool.names(), into));
        return true;
    }

    public static boolean remove(Colony colony, RegistryAccess registries, Part part, String name) {
        NamePool pool = of(colony, registries);
        List<String> from = new ArrayList<>(listOf(pool, part));
        if (!from.remove(name)) {
            return false;
        }
        keep(colony, part == Part.GIVEN ? new NamePool(from, pool.surnames()) : new NamePool(pool.names(), from));
        return true;
    }

    public static void replace(Colony colony, NamePool with) {
        keep(colony, with);
    }

    public static List<String> listOf(NamePool pool, Part part) {
        return part == Part.GIVEN ? pool.names() : pool.surnames();
    }

    public static boolean fits(String name) {
        return !name.isEmpty() && name.length() <= NamePool.MAX_LENGTH;
    }

    private static void keep(Colony colony, NamePool pool) {
        CompoundTag tag = new CompoundTag();
        tag.put(TAG_NAMES, strings(pool.names()));
        tag.put(TAG_SURNAMES, strings(pool.surnames()));
        colony.keep(OWNER, tag);
    }

    private static Optional<NamePool> read(CompoundTag kept) {
        if (!kept.contains(TAG_NAMES, Tag.TAG_LIST)) {
            return Optional.empty();
        }
        return Optional.of(new NamePool(strings(kept, TAG_NAMES), strings(kept, TAG_SURNAMES)));
    }

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        values.forEach(value -> list.add(StringTag.valueOf(value)));
        return list;
    }

    private static List<String> strings(CompoundTag tag, String key) {
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        List<String> values = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) {
            values.add(list.getString(index));
        }
        return values;
    }
}
