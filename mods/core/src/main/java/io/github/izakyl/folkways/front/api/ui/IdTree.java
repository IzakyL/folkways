package io.github.izakyl.folkways.front.api.ui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

// Things filed by where they are kept, written "root:some/path/name": a folder for each root, and under it one for
// each stretch of path before a stroke.
public final class IdTree {

    private IdTree() {
    }

    // prefix is what everything filed under the folder begins with, "root:" or "root:some/path/". A root reads as
    // its name, and a folder that stands for a run of folders reads as the whole way down, "root/some/path".
    public record Folder<T>(String prefix, String name, boolean root, List<Folder<T>> folders, List<T> leaves) {

        public List<T> all() {
            List<T> all = new ArrayList<>(leaves);
            folders.forEach(folder -> all.addAll(folder.all()));
            return all;
        }

        public boolean holds(String filed) {
            return under(prefix, filed);
        }

        // The prefix without the mark that ends it, in the dots a token and a translation key are written in.
        public String dotted() {
            return prefix.substring(0, prefix.length() - 1).replace(':', '.').replace('/', '.');
        }
    }

    public static boolean under(String prefix, String filed) {
        return filed.startsWith(prefix);
    }

    public static <T> List<Folder<T>> of(Collection<T> leaves, Function<T, String> filing) {
        // The roots stand in the order they first come; the folders under them, by name.
        Map<String, Growing<T>> roots = new LinkedHashMap<>();
        for (T leaf : leaves) {
            String filed = filing.apply(leaf);
            int colon = filed.indexOf(':');
            String root = colon < 0 ? "" : filed.substring(0, colon);
            Growing<T> at = roots.computeIfAbsent(root, name -> new Growing<>(name + ":", name, true));
            String[] steps = filed.substring(colon + 1).split("/");
            for (int step = 0; step < steps.length - 1; step++) {
                Growing<T> parent = at;
                at = parent.folders.computeIfAbsent(steps[step],
                    name -> new Growing<>(parent.prefix + name + "/", name, false));
            }
            at.leaves.add(leaf);
        }
        return roots.values().stream().map(Growing::grown).toList();
    }

    private static final class Growing<T> {
        private final String prefix;
        private final String name;
        private final boolean root;
        private final Map<String, Growing<T>> folders = new TreeMap<>();
        private final List<T> leaves = new ArrayList<>();

        private Growing(String prefix, String name, boolean root) {
            this.prefix = prefix;
            this.name = name;
            this.root = root;
        }

        // A folder holding nothing but one other folder is shown as that one, named by the whole way down to it.
        private Folder<T> grown() {
            if (leaves.isEmpty() && folders.size() == 1) {
                Folder<T> only = folders.values().iterator().next().grown();
                return new Folder<>(only.prefix(), name + "/" + only.name(), root, only.folders(), only.leaves());
            }
            return new Folder<>(prefix, name, root, folders.values().stream().map(Growing::grown).toList(),
                List.copyOf(leaves));
        }
    }
}
