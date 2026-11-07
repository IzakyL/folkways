package io.github.izakyl.folkways.rts.combat.command;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

/** Ordinary axe work for one resident that keeps going until told to finish, counting its commits and releases. */
public final class CommandJob implements Node {
    public static final ResourceLocation WORK = ResourceLocation.fromNamespaceAndPath("folkways_test", "command_work");

    public final AtomicBoolean working = new AtomicBoolean();
    public final AtomicBoolean finish = new AtomicBoolean();
    public final AtomicInteger released = new AtomicInteger();
    public final AtomicInteger committed = new AtomicInteger();
    private final NodeSpec spec;
    private final UUID worker;

    public CommandJob(WorldPos at, Body body) {
        worker = body.id();
        Workload.Continuous load =
            (level, who) -> {
                working.set(true);
                return !finish.get();
            };
        spec =
            NodeSpec.of(UUID.randomUUID(), WORK, new WorkSite.AtBlock(at), Stances.at(at), load)
            .tools(new ToolUse(new ToolNeed.Tagged(ItemTags.AXES), 0))
            .done();
    }

    public CommandJob submit(Colony colony, ServerLevel level) {
        colony.submit(WORK, level.dimension(), Grown.of(this), (id, how) -> {});
        return this;
    }

    // The eight ingots and the damage-7 iron axe the body carried before a command took it over.
    public static void assertCarried(GameTestHelper helper, Body body) {
        helper.assertValueEqual(body.pack().count(Items.IRON_INGOT), 8, "cargo is retained through takeover");
        helper.assertValueEqual(body.pack().count(Items.IRON_AXE), 1, "the borrowed tool has one real owner");
        helper.assertTrue(
            body.pack().contents().stream()
            .filter(stack -> stack.is(Items.IRON_AXE))
            .allMatch(stack -> stack.getDamageValue() == 7),
            "the tool keeps its component state");
    }

    public NodeSpec spec() {
        return spec;
    }

    public Optional<UUID> worker() {
        return Optional.of(worker);
    }

    public Outcome commit(ServerLevel level, Worker who) {
        committed.incrementAndGet();
        return Outcome.done();
    }

    public void released(ServerLevel level, Worker who, Ending how) {
        released.incrementAndGet();
        working.set(false);
    }
}
