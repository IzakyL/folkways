package io.github.izakyl.folkways.plugins.person.look;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

// Looks filed by their ids: a folder for each namespace, and under it one for each stretch of path before a stroke.
public final class LookTree {

    private LookTree() {
    }

    // prefix is what every id under the folder begins with, "namespace:" or "namespace:some/path/".
    public record Folder(String prefix, String name, List<Folder> folders, List<ResourceLocation> looks) {

        public List<ResourceLocation> all() {
            List<ResourceLocation> all = new ArrayList<>(looks);
            folders.forEach(folder -> all.addAll(folder.all()));
            return all;
        }

        public boolean holds(ResourceLocation look) {
            return under(prefix, look);
        }
    }

    public static boolean under(String prefix, ResourceLocation look) {
        return look.toString().startsWith(prefix);
    }

    public static List<Folder> of(Collection<ResourceLocation> looks) {
        Map<String, Growing> roots = new TreeMap<>();
        for (ResourceLocation look : looks) {
            Growing at = roots.computeIfAbsent(look.getNamespace(),
                namespace -> new Growing(namespace + ":", namespace));
            String[] steps = look.getPath().split("/");
            for (int step = 0; step < steps.length - 1; step++) {
                Growing parent = at;
                String name = steps[step];
                at = parent.folders.computeIfAbsent(name, key -> new Growing(parent.prefix + key + "/", key));
            }
            at.looks.add(look);
        }
        return roots.values().stream().map(Growing::grown).toList();
    }

    private static final class Growing {
        private final String prefix;
        private final String name;
        private final Map<String, Growing> folders = new TreeMap<>();
        private final List<ResourceLocation> looks = new ArrayList<>();

        private Growing(String prefix, String name) {
            this.prefix = prefix;
            this.name = name;
        }

        // A folder holding nothing but one other folder is shown as that one, named by the whole way down to it.
        private Folder grown() {
            if (looks.isEmpty() && folders.size() == 1) {
                Folder only = folders.values().iterator().next().grown();
                return new Folder(only.prefix(), name + "/" + only.name(), only.folders(), only.looks());
            }
            return new Folder(prefix, name, folders.values().stream().map(Growing::grown).toList(),
                looks.stream().sorted().toList());
        }
    }
}
