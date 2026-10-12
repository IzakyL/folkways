package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** A build site asking a colony that only writes down what it is asked, for the game tests to answer by hand. */
final class SiteRig implements BuildSite.Asks {

    final List<Grown> asked = new ArrayList<>();
    final List<UUID> withdrawn = new ArrayList<>();
    private final Map<UUID, BiConsumer<UUID, Ending>> heard = new HashMap<>();
    final BuildSite site;

    private SiteRig(BuildTarget target, TemporaryScaffolds temporary) {
        site = new BuildSite(UUID.randomUUID(), this, target, temporary);
    }

    static SiteRig start(ServerLevel level, BuildTarget target) {
        return start(level, target, new TemporaryScaffolds(() -> { }), Set.of());
    }

    static SiteRig start(ServerLevel level, BuildTarget target, TemporaryScaffolds temporary, Set<BlockPos> met) {
        SiteRig rig = new SiteRig(target, temporary);
        rig.site.start(level, met);
        return rig;
    }

    @Override
    public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
        asked.add(work);
        work.nodes().forEach(node -> heard.put(node.id(), ended));
    }

    @Override
    public void withdraw(UUID node) {
        withdrawn.add(node);
    }

    Optional<BuildSite.Piece> at(BlockPos cell) {
        return site.pieces().stream()
            .filter(piece -> piece.work().step().map(step -> step.after().containsKey(cell)).orElse(false))
            .findFirst();
    }

    BuildSite.Piece pieceAt(BlockPos cell) {
        return at(cell).orElseThrow(() -> new AssertionError("no piece of work at " + cell.toShortString()));
    }

    Node node(BlockPos cell) {
        return pieceAt(cell).node();
    }

    Step step(BlockPos cell) {
        return pieceAt(cell).work().step().orElseThrow();
    }

    /** Whether the piece with the node {@code first} goes in before the one with {@code then}. */
    boolean ordered(UUID first, UUID then) {
        return site.pieces().stream().filter(piece -> piece.node().id().equals(then))
            .anyMatch(piece -> site.before(piece).contains(first));
    }

    /** Whether the piece at {@code first} goes in before the piece at {@code then}. */
    boolean before(BlockPos first, BlockPos then) {
        return site.before(pieceAt(then)).contains(node(first).id());
    }

    /** The cells of every piece that goes in straight before the piece at {@code cell}. */
    List<BlockPos> after(BlockPos cell) {
        Map<UUID, BlockPos> focus = new HashMap<>();
        site.pieces().forEach(piece -> focus.put(piece.node().id(), piece.work().focus()));
        return site.before(pieceAt(cell)).stream().map(focus::get).filter(java.util.Objects::nonNull).toList();
    }

    /** Whether nothing has to go in before the piece at {@code cell}. */
    boolean free(BlockPos cell) {
        return site.before(pieceAt(cell)).isEmpty();
    }

    void end(Node node, Ending how) {
        site.ended(node.id(), how);
    }

    /** Does the piece at {@code cell} as a worker would, tells the site it ended, and lets the site hear it. */
    Outcome work(GameTestHelper helper, BlockPos cell, Worker worker) {
        Node node = node(cell);
        Outcome outcome = Commit.run(helper.getLevel(), node, worker);
        if (outcome instanceof Outcome.Done) {
            end(node, Ending.DONE);
            site.tick(helper.getLevel());
        }
        return outcome;
    }

    /** Does every piece still out, in the order asked, until none is left or one fails. */
    void workAll(GameTestHelper helper, Worker worker) {
        for (int round = 0; round < 64 && !site.pieces().isEmpty(); round++) {
            for (BuildSite.Piece piece : site.pieces()) {
                Outcome outcome = Commit.run(helper.getLevel(), piece.node(), worker);
                helper.assertTrue(outcome instanceof Outcome.Done, "work asked for can be done, not " + outcome);
                end(piece.node(), Ending.DONE);
            }
            site.tick(helper.getLevel());
        }
    }
}
