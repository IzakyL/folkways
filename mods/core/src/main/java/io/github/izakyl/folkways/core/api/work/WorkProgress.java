package io.github.izakyl.folkways.core.api.work;

import java.util.Optional;
import java.util.UUID;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** A colony's work moving on: reported once when a worker takes a node up, and once when the node ends.
 * Read-only, for statistics and diagnostics; listeners change nothing about the work.
 * The worker is named when a node is taken up; a colony node's end does not name one, but a body's own
 * business, which is never reported as taken up, names its worker when it finishes.
 */
public final class WorkProgress extends Event {

    public enum Step { STARTED, FINISHED, RELEASED, FAILED }

    private final UUID colony;
    private final Node node;
    private final Step step;
    private final Optional<UUID> worker;

    private WorkProgress(UUID colony, Node node, Step step, Optional<UUID> worker) {
        this.colony = colony;
        this.node = node;
        this.step = step;
        this.worker = worker;
    }

    public static void reported(UUID colony, Node node, Step step, Optional<UUID> worker) {
        if (node != null) {
            NeoForge.EVENT_BUS.post(new WorkProgress(colony, node, step, worker));
        }
    }

    public UUID colony() { return colony; }
    public Node node() { return node; }
    public Step step() { return step; }
    public Optional<UUID> worker() { return worker; }
}
