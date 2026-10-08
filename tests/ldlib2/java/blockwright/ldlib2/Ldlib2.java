package blockwright.ldlib2;

import static blockwright.minecraft.core.Values.obj;

import blockwright.ldlib2.Panel.Located;
import blockwright.ldlib2.Panel.Node;
import blockwright.ldlib2.Panel.Routes;
import blockwright.ldlib2.Panel.Tree;
import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Fail;
import blockwright.minecraft.core.Handles;
import blockwright.minecraft.core.Reflect;
import blockwright.minecraft.core.Wait;
import blockwright.minecraft.client.Mouse;
import blockwright.minecraft.core.client.ClientGame;
import dev.blockwright.api.Context;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import net.minecraft.client.Minecraft;

/**
 * Entry points of the LDLib2 prefab's tasks (client thread). Every call carries {@code routes} (see
 * {@link Panel}); selectors are described in {@link Rules}.
 */
public final class Ldlib2 {
    /** Handles a target answer registers at most for its subtree. */
    private static final int MAX_SUBTREE_HANDLES = 512;

    private Ldlib2() {
    }

    /**
     * The tree, with {@code eager} ({@link Node} flags) read on every element; checks and locates read no more
     * than their selector and rules need, so a wait that reads a big panel every frame stays cheap.
     */
    private static Tree tree(Located located, Routes routes, Args args, int eager) throws Exception {
        return Panel.read(located.modularUI(), routes,
            (int) args.integer("maxDepth", 0, 10_000, Panel.DEFAULT_MAX_DEPTH),
            (int) args.integer("maxNodes", 1, 1_000_000, Panel.DEFAULT_MAX_NODES), eager);
    }

    /**
     * The UI read probe: {@code null} when the open screen holds no LDLib2 panel (or none is open), else
     * {@code {holder, screen, truncated, elements}}.
     */
    public static Object read(Context ctx, Args args) throws Exception {
        Routes routes = Routes.of(args);
        Located located = Panel.locate(ClientGame.mc(ctx), routes);
        return located.found() ? describe(located, tree(located, routes, args, Node.ALL)) : null;
    }

    /** {@link #read}, but no panel ends the run {@code not-found}, saying what is open instead. */
    public static Object panel(Context ctx, Args args) throws Exception {
        Routes routes = Routes.of(args);
        Located located = Panel.locate(ClientGame.mc(ctx), routes);
        if (!located.found()) {
            throw Fail.notFound("no LDLib2 panel is open: " + located.why(), obj("screen", located.screen()));
        }
        return describe(located, tree(located, routes, args, Node.ALL));
    }

    /** {@code elements} is one pre-built JSON array: the agent's output budget does not walk (or cut) it. */
    private static Map<String, Object> describe(Located located, Tree tree) {
        return obj("holder", located.holder(), "screen", located.screen(), "truncated", tree.truncated(),
            "elements", tree.json());
    }

    /**
     * A wait check ({@code waitUntil} on the frame clock): {@code {selector, state: "actionable" | "absent",
     * target?}}. Actionable holds with the element (and, with {@code target}, what a click needs: the aim
     * point, the ModularUI's handle and the handles of the element's subtree); absent holds with
     * {@code {absent: true}}. Otherwise it reports what it saw.
     */
    public static Object check(Context ctx, Args args) throws Exception {
        boolean absent = "absent".equals(args.string("state", "actionable"));
        Routes routes = Routes.of(args);
        Located located = Panel.locate(ClientGame.mc(ctx), routes);
        if (!located.found()) {
            return absent ? obj("absent", true, "panel", false) : Wait.pending(obj("panel", located.why()));
        }
        Tree tree = tree(located, routes, args, 0);
        Node node = Rules.select(tree, args.requireObject("selector"));
        if (absent) {
            if (node == null && !tree.truncated()) {
                return obj("absent", true, "panel", true);
            }
            if (node != null && !Rules.hittable(tree, node)) {
                return obj("absent", true, "panel", true, "element", node.json());
            }
            return Wait.pending(node == null
                ? obj("elements", tree.nodes().size(), "truncated", true)
                : obj("elements", tree.nodes().size(), "target", Rules.observed(tree, node)));
        }
        if (node != null && Rules.actionable(tree, node)) {
            return args.flag("target", false) ? target(located, tree, node) : obj("element", node.json());
        }
        return Wait.pending(obj("elements", tree.nodes().size(), "truncated", tree.truncated(),
            "target", node == null ? "absent" : Rules.observed(tree, node)));
    }

    /** What acting on {@code node} needs, pinned by handle so a press is judged by this very UI. */
    private static Map<String, Object> target(Located located, Tree tree, Node node) {
        double[] aim = Rules.aim(node);
        List<String> subtree = new ArrayList<>();
        List<String> owned = new ArrayList<>();
        subtree.add(pin(node.target, owned));
        for (int at = node.index + 1; at < tree.nodes().size() && tree.nodes().get(at).depth > node.depth
            && subtree.size() < MAX_SUBTREE_HANDLES; at++) {
            subtree.add(pin(tree.nodes().get(at).target, owned));
        }
        return obj(
            "element", node.json(),
            "label", Rules.label(tree, node),
            "aim", obj("x", aim[0], "y", aim[1]),
            "holder", located.holder(),
            "ui", pin(located.modularUI(), owned),
            "subtree", subtree,
            "owned", owned);
    }

    /** The shared handle of {@code object}; a handle made here (no one else had one) is added to {@code owned}. */
    private static String pin(Object object, List<String> owned) {
        boolean had = Handles.existing(object) != null;
        String handle = Handles.registerShared(object);
        if (!had) {
            owned.add(handle);
        }
        return handle;
    }

    /** {@code {handles: [..]}}: releases the handles a target pinned for itself once the action is done. */
    public static Object release(Context ctx, Args args) {
        return obj("released", Handles.release(args.strings("handles")));
    }

    /**
     * Where the element stands against its viewports, after {@code afterFrames} rendered frames (so a
     * scroll has been laid out) and, with {@code settleMs}, once its y has held still for two more frames
     * (a timeout's {@code last} is the reading it last took): {@code {element, label, clip?, clipLabel?, clippingAncestors, revealed,
     * revealedVertically}}. Ends {@code not-found} when there is no panel or no such element (naming the ids
     * that are there).
     */
    public static Object locate(Context ctx, Args args) {
        long frames = args.integer("afterFrames", 0, 1_000, 0);
        // settleMs: the budget of waiting for the element to stop moving; 0 = no limit, absent = no settling.
        boolean settle = args.has("settleMs");
        long settleMs = args.integer("settleMs", 0, Long.MAX_VALUE, 0);
        if (frames == 0 && !settle) {
            return locateNow(ctx, args);
        }
        CompletableFuture<Wait.Result> waited = frames == 0
            ? CompletableFuture.completedFuture(null)
            : Wait.ticks(ctx, Wait.Clock.FRAME, frames);
        if (!settle) {
            return waited.thenApply(done -> locateNow(ctx, args));
        }
        // LDLib2 animates a scroll over several frames: answer once the element has stopped moving.
        return waited.thenCompose(done -> Wait.untilStill(ctx, Wait.Clock.FRAME, new Wait.Options(settleMs, 0, 0, true), 2,
            () -> locateNow(ctx, args), now -> ((Map<?, ?>) now.get("element")).get("y")))
            .thenApply(Wait.Result::value);
    }

    private static Map<String, Object> locateNow(Context ctx, Args args) {
        try {
            Minecraft mc = ClientGame.mc(ctx);
            Routes routes = Routes.of(args);
            Located located = Panel.locate(mc, routes);
            if (!located.found()) {
                throw Fail.notFound("no LDLib2 panel: " + located.why(), obj("screen", located.screen()));
            }
            Tree tree = tree(located, routes, args, 0);
            Args selector = args.requireObject("selector");
            Node node = Rules.select(tree, selector);
            if (node == null) {
                List<String> ids = new ArrayList<>();
                for (Node each : tree.needAll(Node.ID).nodes()) {
                    if (each.id != null && !each.id.isEmpty()) {
                        ids.add(each.id);
                    }
                }
                throw Fail.notFound("LDLib2 element " + selector.raw() + " is not on the panel (ids present: " + ids + ")",
                    obj("ids", ids));
            }
            List<Node> clipping = Rules.clippingAncestors(tree, node);
            Map<String, Object> out = obj(
                "element", node.json(),
                "label", Rules.label(tree, node),
                "clippingAncestors", clipping.size(),
                "revealed", Rules.fullyRevealed(tree, node),
                "revealedVertically", Rules.revealedVertically(tree, node));
            Rules.Box clip = Rules.clipBox(tree, node);
            if (clip != null) {
                out.put("clip", clip.json());
                out.put("clipLabel", Rules.label(tree, clipping.get(clipping.size() - 1)));
            }
            return out;
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CompletionException(failure);
        }
    }

    /**
     * Puts the cursor at GUI-scaled {@code (x, y)} and answers once {@code frames} frames have been drawn with
     * it there (LDLib2 works out what is hovered while rendering). With {@code ui} (a ModularUI handle) and
     * {@code hoveredPath} it also reports what that UI then calls hovered: {@code hovered: {class, handle} |
     * null}, and whether it is one of the {@code within} handles.
     */
    public static Object hover(Context ctx, Args args) {
        Minecraft mc = ClientGame.mc(ctx);
        Map<String, Object> moved = Mouse.move(mc, args.number("x"), args.number("y"));
        String ui = args.string("ui", null);
        String path = args.string("hoveredPath", null);
        List<String> within = args.has("within") ? args.strings("within") : List.of();
        return Wait.ticks(ctx, Wait.Clock.FRAME, args.integer("frames", 1, 1_000, 2)).thenApply(done -> {
            Map<String, Object> out = new LinkedHashMap<>(moved);
            if (ui == null || path == null) {
                return out;
            }
            Object hovered;
            try {
                hovered = Reflect.navigate(Handles.resolve(ui), path);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
            if (hovered == null) {
                out.put("hovered", null);
                out.put("within", false);
                return out;
            }
            String handle = Handles.registerShared(hovered);
            out.put("hovered", obj("class", hovered.getClass().getName(), "handle", handle));
            out.put("within", within.contains(handle));
            return out;
        });
    }

    /**
     * Wheel turns at GUI-scaled {@code (x, y)}: the cursor moves there, a frame is drawn (LDLib2 sends the
     * wheel to what it last saw hovered), then {@code count} scroll events of {@code yOffset} each are
     * dispatched in one client-thread turn — LDLib2 scrolls a fixed step per event, whatever its size.
     */
    public static Object wheel(Context ctx, Args args) {
        Minecraft mc = ClientGame.mc(ctx);
        double x = args.number("x");
        double y = args.number("y");
        double offset = args.number("yOffset");
        long count = args.integer("count", 1, 100, 1);
        Mouse.move(mc, x, y);
        return Wait.ticks(ctx, Wait.Clock.FRAME, 1).thenApply(done -> {
            Mouse.scroll(ctx, Args.of(obj("yOffset", offset, "count", count), "wheel"));
            return obj("x", x, "y", y, "yOffset", offset, "count", count);
        });
    }
}
