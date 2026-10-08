package blockwright.ldlib2;

import static blockwright.minecraft.core.Values.obj;

import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Fail;
import blockwright.minecraft.core.Reflect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;

/**
 * The LDLib2 panel on the open screen, read reflectively: LDLib2 is not on the classpath this module is
 * checked against, and a mod may ship any build of it. Every member is reached through a path the TS side
 * sends ({@code routes}), the same paths it checks with {@code reflect.symbols}, so the member names live
 * in one place.
 *
 * <p>{@code routes}: {@code {holders: {name: path from the screen to its ModularUI}, root: path from the
 * ModularUI to the root element, children: path from an element to its child list, select: {key: path}}}.
 * {@code select} keys this class understands: {@code id, text, ownText, classes, slotIndex, slotItem,
 * slotCount, x, y, width, height, contentX, contentY, contentWidth, contentHeight, visible, active,
 * displayed, opacity, overflowVisible}; a path an element lacks (or that throws) reads as absent.
 *
 * <p>Speed: a panel can hold thousands of elements and a wait reads it every frame, so paths are split once
 * per call and each segment is resolved to a method handle once per runtime class (a member a class lacks is
 * remembered as absent and never looked up again). A read walks the tree reading only what its caller needs
 * of every element (a selector: ids or texts); the rest (geometry, flags, style) is read for the elements a
 * rule looks at ({@link Node#need}). A full read ({@link Node#ALL}) reads everything.
 *
 * <p>Limits: {@code maxDepth} is the deepest depth read (the root is depth 0), as in {@code reflect.query}:
 * the children of an element at {@code maxDepth} are not read, and the tree is marked truncated.
 * {@code maxNodes} caps the elements read. The elements are answered as one pre-built JSON array
 * ({@link Tree#json}), which the agent's JSON output budget does not walk, so it never cuts into them.
 */
public final class Panel {
    public static final int DEFAULT_MAX_DEPTH = 24;
    public static final int DEFAULT_MAX_NODES = 8_000;

    private Panel() {
    }

    // ---- paths, resolved once per runtime class ----

    /** One resolved path segment; a member the class lacks resolves to {@link #MISSING}. */
    @FunctionalInterface
    private interface Getter {
        Object get(Object owner) throws Throwable;
    }

    private static final Getter MISSING = owner -> null;

    private static final ClassValue<Map<String, Getter>> GETTERS = new ClassValue<>() {
        @Override
        protected Map<String, Getter> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static Getter getter(Class<?> type, String segment) {
        Map<String, Getter> byClass = GETTERS.get(type);
        Getter found = byClass.get(segment);
        if (found == null) {
            found = resolve(type, segment);
            byClass.put(segment, found);
        }
        return found;
    }

    /** What {@code Reflect.navigate} would call for {@code segment} on {@code type}, as a handle. */
    private static Getter resolve(Class<?> type, String segment) {
        try {
            if (segment.startsWith("[")) {
                int index = Integer.parseInt(segment.substring(1, segment.length() - 1).trim());
                return owner -> elementAt(owner, index);
            }
            if (segment.endsWith("()")) {
                Method method = Reflect.findMethod(type, segment.substring(0, segment.length() - 2));
                MethodHandle handle = handle(() -> MethodHandles.lookup().unreflect(method), Modifier.isStatic(method.getModifiers()));
                return handle != null ? owner -> (Object) handle.invokeExact(owner) : owner -> method.invoke(owner);
            }
            Field field = Reflect.findField(type, segment);
            MethodHandle handle = handle(() -> MethodHandles.lookup().unreflectGetter(field), Modifier.isStatic(field.getModifiers()));
            return handle != null ? owner -> (Object) handle.invokeExact(owner) : field::get;
        } catch (Exception | LinkageError absent) {
            return MISSING;
        }
    }

    @FunctionalInterface
    private interface Unreflect {
        MethodHandle get() throws IllegalAccessException;
    }

    /** {@code (Object) -> Object} over the member, or null when no handle can be made (reflection is used then). */
    private static MethodHandle handle(Unreflect unreflect, boolean isStatic) {
        try {
            MethodHandle handle = unreflect.get();
            if (isStatic) {
                handle = MethodHandles.dropArguments(handle, 0, Object.class);
            }
            return handle.asType(MethodType.methodType(Object.class, Object.class));
        } catch (Exception | LinkageError unusable) {
            return null;
        }
    }

    private static Object elementAt(Object owner, int index) {
        if (owner.getClass().isArray()) {
            return Array.get(owner, index);
        }
        if (owner instanceof List<?> list) {
            return list.get(index);
        }
        if (owner instanceof Collection<?> collection) {
            Iterator<?> iterator = collection.iterator();
            for (int at = 0; at < index; at++) {
                iterator.next();
            }
            return iterator.next();
        }
        throw new IllegalArgumentException("not an array or collection: " + owner.getClass().getName());
    }

    /** A path split once; {@link #read} answers null where an element lacks a member, a step is null, or a read throws. */
    static final class Path {
        final String text;
        private final String[] segments;

        private Path(String text) {
            this.text = text;
            this.segments = Reflect.splitPath(text).toArray(String[]::new);
        }

        static Path of(String text) {
            return text == null || text.isEmpty() ? null : new Path(text);
        }

        Object read(Object target) {
            Object current = target;
            for (String segment : segments) {
                if (current == null) {
                    return null;
                }
                Getter getter = getter(current.getClass(), segment);
                if (getter == MISSING) {
                    return null;
                }
                try {
                    current = getter.get(current);
                } catch (LinkageError absent) {
                    return null;
                } catch (Error error) {
                    throw error;
                } catch (Throwable absent) {
                    return null;
                }
            }
            return current;
        }
    }

    static Object read(Object target, Path path) {
        return path == null ? null : path.read(target);
    }

    // ---- routes ----

    /** The element reads {@code select} declares, compiled once per call. */
    static final class Select {
        final Path id;
        final Path text;
        final Path ownText;
        final Path classes;
        final Path slotIndex;
        final Path slotItem;
        final Path slotCount;
        final Path x;
        final Path y;
        final Path width;
        final Path height;
        final Path contentX;
        final Path contentY;
        final Path contentWidth;
        final Path contentHeight;
        final Path visible;
        final Path active;
        final Path displayed;
        final Path opacity;
        final Path overflowVisible;

        Select(Map<String, String> select) {
            id = Path.of(select.get("id"));
            text = Path.of(select.get("text"));
            ownText = Path.of(select.get("ownText"));
            classes = Path.of(select.get("classes"));
            slotIndex = Path.of(select.get("slotIndex"));
            slotItem = Path.of(select.get("slotItem"));
            slotCount = Path.of(select.get("slotCount"));
            x = Path.of(select.get("x"));
            y = Path.of(select.get("y"));
            width = Path.of(select.get("width"));
            height = Path.of(select.get("height"));
            contentX = Path.of(select.get("contentX"));
            contentY = Path.of(select.get("contentY"));
            contentWidth = Path.of(select.get("contentWidth"));
            contentHeight = Path.of(select.get("contentHeight"));
            visible = Path.of(select.get("visible"));
            active = Path.of(select.get("active"));
            displayed = Path.of(select.get("displayed"));
            opacity = Path.of(select.get("opacity"));
            overflowVisible = Path.of(select.get("overflowVisible"));
        }

        void fill(Node node, int flags) {
            Object target = node.target;
            if ((flags & Node.ID) != 0) {
                node.id = text(read(target, id));
            }
            if ((flags & Node.TEXT) != 0) {
                String drawn = text(read(target, text));
                node.text = drawn != null ? drawn : text(read(target, ownText));
            }
            if ((flags & Node.REST) != 0) {
                node.classes = classes(read(target, classes));
                node.slotIndex = integer(read(target, slotIndex));
                if (node.slotIndex != null) {
                    node.slotItem = text(read(target, slotItem));
                    node.slotCount = integer(read(target, slotCount));
                }
                node.x = number(read(target, x), 0);
                node.y = number(read(target, y), 0);
                node.width = number(read(target, width), 0);
                node.height = number(read(target, height), 0);
                node.contentX = number(read(target, contentX), node.x);
                node.contentY = number(read(target, contentY), node.y);
                node.contentWidth = number(read(target, contentWidth), node.width);
                node.contentHeight = number(read(target, contentHeight), node.height);
                node.visible = flag(read(target, visible), true);
                node.active = flag(read(target, active), true);
                node.displayed = flag(read(target, displayed), true);
                node.opacity = number(read(target, opacity), 1);
                node.overflowVisible = flag(read(target, overflowVisible), true);
            }
        }
    }

    /** The paths the TS side declared; parsed once per call. */
    public record Routes(Map<String, String> holders, String root, Path children, Select select) {
        public static Routes of(Args args) {
            Args routes = args.requireObject("routes");
            Map<String, String> holders = routes.stringMap("holders");
            if (holders == null || holders.isEmpty()) {
                throw Fail.invalid("routes.holders must name at least one path to a ModularUI");
            }
            Map<String, String> select = routes.stringMap("select");
            return new Routes(holders, routes.string("root"), Path.of(routes.string("children")),
                new Select(select == null ? Map.of() : select));
        }
    }

    /** Where the panel was found: through which holder, the ModularUI, and why the other holders did not resolve. */
    public record Located(String screen, String holder, Object modularUI, List<String> refusals) {
        public boolean found() {
            return modularUI != null;
        }

        /** One sentence on why there is no panel. */
        public String why() {
            if (screen == null) {
                return "no screen is open";
            }
            return "the open screen " + screen + " holds no LDLib2 ModularUI (" + String.join("; ", refusals) + ")";
        }
    }

    /** The first holder route that resolves on the open screen, in the order declared. */
    public static Located locate(Minecraft mc, Routes routes) {
        Object screen = mc.screen;
        List<String> refusals = new ArrayList<>();
        if (screen == null) {
            return new Located(null, null, null, refusals);
        }
        for (Map.Entry<String, String> holder : routes.holders().entrySet()) {
            try {
                Object ui = Reflect.navigate(screen, holder.getValue());
                if (ui != null) {
                    return new Located(screen.getClass().getName(), holder.getKey(), ui, refusals);
                }
                refusals.add(holder.getKey() + " (." + holder.getValue() + ") is null");
            } catch (Exception | LinkageError refused) {
                refusals.add(holder.getKey() + " (." + holder.getValue() + "): " + message(refused));
            }
        }
        return new Located(screen.getClass().getName(), null, null, refusals);
    }

    private static String message(Throwable failure) {
        Throwable cause = failure.getCause() != null && failure.getMessage() == null ? failure.getCause() : failure;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    // ---- the tree ----

    /**
     * One element, as read. Coordinates are GUI-scaled; the box is unclipped layout. Fields are read on
     * demand: {@link #need} them before use ({@code className}, the tree position and {@code target} are
     * always there). {@link Rules} and {@link Tree#ancestorsOf} read what they use themselves.
     */
    public static final class Node {
        /** {@code id}. */
        public static final int ID = 1;
        /** {@code text} (drawn text, or the element's own). */
        public static final int TEXT = 2;
        /** Everything else: style classes, slot, geometry, flags, opacity, overflow. */
        public static final int REST = 4;
        public static final int ALL = ID | TEXT | REST;

        public final Object target;
        public final int index;
        public final int depth;
        /** Index of the parent node, or -1 for the root. */
        public final int parent;
        /** Position among the parent's children. */
        public final int position;
        public final String className;
        public String id;
        public String text;
        public List<String> classes = List.of();
        public Integer slotIndex;
        public String slotItem;
        public Integer slotCount;
        public double x;
        public double y;
        public double width;
        public double height;
        public double contentX;
        public double contentY;
        public double contentWidth;
        public double contentHeight;
        public boolean visible = true;
        public boolean active = true;
        public boolean displayed = true;
        public double opacity = 1;
        public boolean overflowVisible = true;

        private final Select select;
        private int read;

        Node(Object target, int index, int depth, int parent, int position, Select select) {
            this.target = target;
            this.index = index;
            this.depth = depth;
            this.parent = parent;
            this.position = position;
            this.className = target.getClass().getName();
            this.select = select;
        }

        /** Reads the fields in {@code flags} ({@link #ID}, {@link #TEXT}, {@link #REST}) not read yet. */
        public Node need(int flags) {
            int missing = flags & ~read;
            if (missing != 0) {
                select.fill(this, missing);
                read |= missing;
            }
            return this;
        }

        /** Every field read. */
        public Node complete() {
            return need(ALL);
        }

        /** The shape of the TS {@code Element}. */
        public Map<String, Object> json() {
            complete();
            Map<String, Object> out = new LinkedHashMap<>();
            if (id != null) {
                out.put("id", id);
            }
            if (text != null) {
                out.put("text", text);
            }
            out.put("className", className);
            if (!classes.isEmpty()) {
                out.put("classes", classes);
            }
            if (slotIndex != null) {
                Map<String, Object> slot = obj("index", slotIndex);
                if (slotItem != null && !slotItem.equals("minecraft:air")) {
                    Map<String, Object> item = obj("id", slotItem);
                    if (slotCount != null) {
                        item.put("count", slotCount);
                    }
                    slot.put("item", item);
                }
                out.put("slot", slot);
            }
            out.put("x", x);
            out.put("y", y);
            out.put("width", width);
            out.put("height", height);
            out.put("visible", visible);
            out.put("active", active);
            out.put("displayed", displayed);
            out.put("opacity", opacity);
            out.put("contentX", contentX);
            out.put("contentY", contentY);
            out.put("contentWidth", contentWidth);
            out.put("contentHeight", contentHeight);
            out.put("overflowVisible", overflowVisible);
            out.put("index", index);
            out.put("depth", depth);
            return out;
        }

        /** {@link #json()}, built as JSON directly (how a whole panel is answered). */
        JsonObject jsonObject() {
            complete();
            JsonObject out = new JsonObject();
            if (id != null) {
                out.addProperty("id", id);
            }
            if (text != null) {
                out.addProperty("text", text);
            }
            out.addProperty("className", className);
            if (!classes.isEmpty()) {
                JsonArray styles = new JsonArray();
                classes.forEach(styles::add);
                out.add("classes", styles);
            }
            if (slotIndex != null) {
                JsonObject slot = new JsonObject();
                slot.addProperty("index", slotIndex);
                if (slotItem != null && !slotItem.equals("minecraft:air")) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", slotItem);
                    if (slotCount != null) {
                        item.addProperty("count", slotCount);
                    }
                    slot.add("item", item);
                }
                out.add("slot", slot);
            }
            out.addProperty("x", x);
            out.addProperty("y", y);
            out.addProperty("width", width);
            out.addProperty("height", height);
            out.addProperty("visible", visible);
            out.addProperty("active", active);
            out.addProperty("displayed", displayed);
            out.addProperty("opacity", opacity);
            out.addProperty("contentX", contentX);
            out.addProperty("contentY", contentY);
            out.addProperty("contentWidth", contentWidth);
            out.addProperty("contentHeight", contentHeight);
            out.addProperty("overflowVisible", overflowVisible);
            out.addProperty("index", index);
            out.addProperty("depth", depth);
            return out;
        }
    }

    /** The element tree in preorder, with whether a limit cut it short. */
    public static final class Tree {
        private final List<Node> nodes;
        private final boolean truncated;
        private Map<String, Integer> idCounts;

        Tree(List<Node> nodes, boolean truncated) {
            this.nodes = nodes;
            this.truncated = truncated;
        }

        public List<Node> nodes() {
            return nodes;
        }

        public boolean truncated() {
            return truncated;
        }

        /** Reads {@code flags} on every node. */
        public Tree needAll(int flags) {
            for (Node node : nodes) {
                node.need(flags);
            }
            return this;
        }

        public List<Node> childrenOf(Node parent) {
            List<Node> children = new ArrayList<>();
            for (int at = parent.index + 1; at < nodes.size() && nodes.get(at).depth > parent.depth; at++) {
                if (nodes.get(at).depth == parent.depth + 1) {
                    children.add(nodes.get(at));
                }
            }
            return children;
        }

        /** The ancestors of {@code node}, outermost first, each read whole. */
        public List<Node> ancestorsOf(Node node) {
            Deque<Node> chain = new ArrayDeque<>();
            for (int at = node.parent; at >= 0; at = nodes.get(at).parent) {
                chain.addFirst(nodes.get(at).complete());
            }
            return new ArrayList<>(chain);
        }

        public boolean uniqueId(Node node) {
            if (idCounts == null) {
                idCounts = new HashMap<>();
                for (Node each : nodes) {
                    String id = each.need(Node.ID).id;
                    if (id != null && !id.isEmpty()) {
                        idCounts.merge(id, 1, Integer::sum);
                    }
                }
            }
            node.need(Node.ID);
            return node.id != null && !node.id.isEmpty() && idCounts.getOrDefault(node.id, 0) == 1;
        }

        /** Every element, read whole, as one JSON array. */
        public JsonArray json() {
            JsonArray out = new JsonArray();
            for (Node node : nodes) {
                out.add(node.jsonObject());
            }
            return out;
        }
    }

    /**
     * Reads the element tree of {@code modularUI} through {@code routes}: its structure, and the fields in
     * {@code eager} ({@link Node} flags) of every element; the rest is read when a node {@link Node#need}s it.
     */
    public static Tree read(Object modularUI, Routes routes, int maxDepth, int maxNodes, int eager) throws Exception {
        Object root = Reflect.navigate(modularUI, routes.root());
        List<Node> nodes = new ArrayList<>();
        boolean truncated = false;
        if (root == null) {
            return new Tree(nodes, false);
        }
        Select select = routes.select();
        Path childrenPath = routes.children();
        // Preorder with an explicit stack, so a deep maxDepth cannot overflow the thread's stack.
        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(root, 0, -1, 0));
        List<Object> children = new ArrayList<>();
        while (!pending.isEmpty()) {
            Pending next = pending.pop();
            if (nodes.size() >= maxNodes) {
                truncated = true;
                break;
            }
            Node node = new Node(next.target, nodes.size(), next.depth, next.parent, next.position, select);
            node.need(eager);
            nodes.add(node);
            Object edge = childrenPath == null ? null : childrenPath.read(node.target);
            if (!(edge instanceof Iterable<?> iterable)) {
                continue;
            }
            children.clear();
            for (Object child : iterable) {
                if (child != null) {
                    children.add(child);
                }
            }
            if (children.isEmpty()) {
                continue;
            }
            // As reflect.query: an element at maxDepth is read, its children are not.
            if (next.depth >= maxDepth) {
                truncated = true;
                continue;
            }
            for (int at = children.size() - 1; at >= 0; at--) {
                pending.push(new Pending(children.get(at), next.depth + 1, node.index, at));
            }
        }
        return new Tree(nodes, truncated);
    }

    private record Pending(Object target, int depth, int parent, int position) {
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : fallback;
    }

    private static boolean flag(Object value, boolean fallback) {
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static List<String> classes(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object each : collection) {
                if (each != null && !each.toString().isBlank()) {
                    out.add(each.toString().trim());
                }
            }
        } else if (value != null) {
            // Set.toString(): "[a, b]"
            String inner = value.toString().trim().replaceAll("^\\[|\\]$", "").trim();
            for (String each : inner.split(",")) {
                if (!each.isBlank()) {
                    out.add(each.trim());
                }
            }
        }
        return out;
    }
}
