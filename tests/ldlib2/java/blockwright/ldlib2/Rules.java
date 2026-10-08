package blockwright.ldlib2;

import static blockwright.minecraft.core.Values.obj;

import blockwright.ldlib2.Panel.Node;
import blockwright.ldlib2.Panel.Tree;
import blockwright.minecraft.core.Args;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which element a selector picks and whether LDLib2 would hand it a click. The in-game twin of the pure
 * helpers in the TS package ({@code selectElement}, {@code isHittable}, {@code isActionable},
 * {@code clipVerdict}, {@code isFullyRevealed}); both are tested against the same cases, so keep them in step.
 *
 * <p>Selector: {@code {id?, idPattern?, text?, className?, index?, within?, path?}}. {@code index} narrows the
 * pool first; then the first of {@code id} (exact), {@code path} (child positions from the uniquely id'd
 * {@code within}, or from the root), {@code text} (substring), {@code className} (substring),
 * {@code idPattern} (glob with {@code *}) that is set decides; none set picks the first element.
 */
public final class Rules {
    private Rules() {
    }

    public record Box(double x, double y, double width, double height) {
        Box intersect(Box other) {
            double left = Math.max(x, other.x);
            double top = Math.max(y, other.y);
            return new Box(left, top, Math.min(x + width, other.x + other.width) - left,
                Math.min(y + height, other.y + other.height) - top);
        }

        public Map<String, Object> json() {
            return obj("x", x, "y", y, "width", width, "height", height);
        }
    }

    private static final double CLIP_EPSILON = 1.0 / 64;

    /** The element {@code selector} picks; reads the ids or texts of every element when the selector needs them. */
    public static Node select(Tree tree, Args selector) {
        List<Node> pool = tree.nodes();
        if (selector.has("index")) {
            long index = selector.integer("index");
            pool = pool.stream().filter(node -> node.index == index).toList();
        }
        if (selector.has("id")) {
            String id = selector.string("id");
            pool.forEach(node -> node.need(Node.ID));
            return pool.stream().filter(node -> id.equals(node.id)).findFirst().orElse(null);
        }
        if (selector.has("path")) {
            List<Object> path = selector.list("path");
            Node found = resolveAnchor(tree, selector.string("within", null), path);
            return found != null && pool.contains(found) ? found : null;
        }
        if (selector.has("text")) {
            String text = selector.string("text");
            pool.forEach(node -> node.need(Node.TEXT));
            return pool.stream().filter(node -> (node.text == null ? "" : node.text).contains(text)).findFirst().orElse(null);
        }
        if (selector.has("className")) {
            String className = selector.string("className");
            return pool.stream().filter(node -> node.className.contains(className)).findFirst().orElse(null);
        }
        if (selector.has("idPattern")) {
            Pattern glob = glob(selector.string("idPattern"));
            pool.forEach(node -> node.need(Node.ID));
            return pool.stream().filter(node -> node.id != null && !node.id.isEmpty() && glob.matcher(node.id).matches())
                .findFirst().orElse(null);
        }
        return pool.isEmpty() ? null : pool.get(0);
    }

    static Pattern glob(String pattern) {
        String[] parts = pattern.split("\\*", -1);
        StringBuilder regex = new StringBuilder();
        for (int at = 0; at < parts.length; at++) {
            if (at > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(parts[at]));
        }
        return Pattern.compile(regex.toString());
    }

    private static Node resolveAnchor(Tree tree, String within, List<Object> path) {
        Node current = null;
        for (Node node : tree.nodes()) {
            if (within == null ? node.depth == 0 : within.equals(node.need(Node.ID).id)) {
                current = node;
                break;
            }
        }
        for (Object step : path) {
            if (current == null) {
                return null;
            }
            int at = step instanceof Number number ? number.intValue() : -1;
            List<Node> children = tree.childrenOf(current);
            current = at >= 0 && at < children.size() ? children.get(at) : null;
        }
        return current;
    }

    public static Box box(Node node) {
        node.complete();
        return new Box(node.x, node.y, node.width, node.height);
    }

    static Box contentBox(Node node) {
        node.complete();
        return new Box(node.contentX, node.contentY, node.contentWidth, node.contentHeight);
    }

    public static double[] aim(Node node) {
        node.complete();
        return new double[] {node.x + node.width / 2, node.y + node.height / 2};
    }

    /** Ancestors with overflow hidden, outermost first: the viewports that clip the node. */
    public static List<Node> clippingAncestors(Tree tree, Node node) {
        List<Node> clipping = new ArrayList<>();
        for (Node ancestor : tree.ancestorsOf(node)) {
            if (!ancestor.overflowVisible) {
                clipping.add(ancestor);
            }
        }
        return clipping;
    }

    /** The intersection of every clipping ancestor's content box, or null when nothing clips. */
    public static Box clipBox(Tree tree, Node node) {
        Box clip = null;
        for (Node ancestor : clippingAncestors(tree, node)) {
            clip = clip == null ? contentBox(ancestor) : clip.intersect(contentBox(ancestor));
        }
        return clip;
    }

    private static boolean outside(double point, double low, double extent) {
        return point < low || point >= low + extent;
    }

    /** The innermost clipping ancestor whose content box leaves out the aim point, or null. */
    public static Node clippedBy(Tree tree, Node node) {
        double[] aim = aim(node);
        Node verdict = null;
        for (Node ancestor : clippingAncestors(tree, node)) {
            Box clip = contentBox(ancestor);
            if (outside(aim[0], clip.x(), clip.width()) || outside(aim[1], clip.y(), clip.height())) {
                verdict = ancestor;
            }
        }
        return verdict;
    }

    /** LDLib2's hit gate: displayed, visible, opaque, with area, and its centre not clipped away. */
    public static boolean hittable(Tree tree, Node node) {
        node.complete();
        boolean lit = node.displayed && node.visible && node.opacity > 0 && node.width > 0 && node.height > 0;
        return lit && clippedBy(tree, node) == null;
    }

    /** Hittable and active: LDLib2 hands it the click. */
    public static boolean actionable(Tree tree, Node node) {
        return hittable(tree, node) && node.complete().active;
    }

    /** Wholly inside its viewports (or as much of it as they can show). */
    public static boolean fullyRevealed(Tree tree, Node node) {
        Box clip = clipBox(tree, node);
        if (clip == null) {
            return true;
        }
        Box shown = box(node).intersect(clip);
        node.complete();
        return shown.width() >= Math.min(node.width, clip.width()) - CLIP_EPSILON
            && shown.height() >= Math.min(node.height, clip.height()) - CLIP_EPSILON;
    }

    /** Revealed on y, however it stands on x: a mouse wheel cannot help any further. */
    public static boolean revealedVertically(Tree tree, Node node) {
        Box clip = clipBox(tree, node);
        node.complete();
        return clip == null || box(node).intersect(clip).height() >= Math.min(node.height, clip.height()) - CLIP_EPSILON;
    }

    /** Its id, or its class inside the nearest id'd ancestor, or its class and index. */
    public static String label(Tree tree, Node node) {
        node.need(Node.ID);
        if (node.id != null && !node.id.isEmpty()) {
            return node.id;
        }
        List<Node> ancestors = tree.ancestorsOf(node);
        for (int at = ancestors.size() - 1; at >= 0; at--) {
            Node ancestor = ancestors.get(at);
            if (ancestor.id != null && !ancestor.id.isEmpty()) {
                return shortClass(node.className) + " inside " + ancestor.id;
            }
        }
        return shortClass(node.className) + "#" + node.index;
    }

    static String shortClass(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    /** What a wait reports about an element that is not (yet) in the state it waits for. */
    public static Map<String, Object> observed(Tree tree, Node node) {
        node.complete();
        Map<String, Object> seen = obj(
            "label", label(tree, node),
            "displayed", node.displayed,
            "visible", node.visible,
            "active", node.active,
            "opacity", node.opacity,
            "x", node.x,
            "y", node.y,
            "width", node.width,
            "height", node.height);
        Node clipper = clippedBy(tree, node);
        if (clipper != null) {
            seen.put("clippedBy", obj("label", label(tree, clipper), "viewport", contentBox(clipper).json()));
        }
        return seen;
    }
}
