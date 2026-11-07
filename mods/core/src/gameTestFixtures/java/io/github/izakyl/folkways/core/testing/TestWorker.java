package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.support.Tools;
import io.github.izakyl.folkways.effect.Effects;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * A worker for driving work by hand. Unless told otherwise it stands nowhere, holds no tool, is supplied nothing,
 * has no stores within reach or route, and treats spilling anything as a failure.
 */
public final class TestWorker implements Worker {
    private final Supplier<Resident> resident;
    private final Supplier<Mob> body;
    private Supplier<Container> pack;
    private Supplier<Effects> effects;
    private Supplier<List<ItemStack>> supplied = List::of;
    private List<Store> within = List.of();
    private Function<ToolNeed, ItemStack> held = need -> ItemStack.EMPTY;
    private Consumer<ItemStack> spill = stack -> {
        if (!stack.isEmpty()) {
            throw new AssertionError("unexpected spill: " + stack);
        }
    };
    private Supplier<List<Node>> route = List::of;
    private Placement placement = Placement.NOWHERE;

    private TestWorker(Supplier<Resident> resident, Supplier<Mob> body, Supplier<Container> pack,
                       Supplier<Effects> effects) {
        this.resident = resident;
        this.body = body;
        this.pack = pack;
        this.effects = effects;
    }

    public TestWorker(Supplier<Resident> resident, Supplier<Mob> body, Supplier<Container> pack,
            Placement placement, List<Store> stores) {
        this(resident, body, pack, () -> Effects.NONE);
        this.placement = placement;
        this.within = stores;
    }

    public TestWorker route(Supplier<List<Node>> route) {
        this.route = route;
        return this;
    }

    // A resident's own body, pack and perks, read as they stand on each call.
    public static TestWorker of(Body body) {
        return new TestWorker(body::resident, body::mob, body::pack, body::effects);
    }

    public static TestWorker of(Mob body, Resident resident, Container pack) {
        return new TestWorker(() -> resident, () -> body, () -> pack, () -> Effects.NONE);
    }

    public TestWorker pack(Container pack) {
        this.pack = () -> pack;
        return this;
    }

    public TestWorker effects(Effects effects) {
        return effects(() -> effects);
    }

    public TestWorker effects(Supplier<Effects> effects) {
        this.effects = effects;
        return this;
    }

    // Fresh goods on every call, as the core takes them.
    public TestWorker supplied(Supplier<List<ItemStack>> supplied) {
        this.supplied = supplied;
        return this;
    }

    public TestWorker within(Store... within) {
        return within(List.of(within));
    }

    public TestWorker within(List<Store> within) {
        this.within = within;
        return this;
    }

    public TestWorker held(Function<ToolNeed, ItemStack> held) {
        this.held = held;
        return this;
    }

    // The first slot of the live pack that answers the need, itself rather than a copy.
    public TestWorker heldFromPack() {
        return held(need -> {
            Container from = pack();
            for (int slot = 0; slot < from.getContainerSize(); slot++) {
                if (Tools.answers(from.getItem(slot), need)) {
                    return from.getItem(slot);
                }
            }
            return ItemStack.EMPTY;
        });
    }

    public TestWorker spill(Consumer<ItemStack> spill) {
        this.spill = spill;
        return this;
    }

    public TestWorker route(List<Node> route) {
        this.route = () -> route;
        return this;
    }

    public TestWorker placement(Placement placement) {
        this.placement = placement;
        return this;
    }

    public Resident resident() {
        return resident.get();
    }

    public Mob body() {
        return body.get();
    }

    public Container pack() {
        return pack.get();
    }

    public List<ItemStack> supplied() {
        return supplied.get();
    }

    public List<Store> within() {
        return within;
    }

    public ItemStack held(ToolNeed need) {
        return held.apply(need);
    }

    public void spill(ItemStack stack) {
        spill.accept(stack);
    }

    public Effects effects() {
        return effects.get();
    }

    public List<Node> route() {
        return route.get();
    }

    public Placement placement() {
        return placement;
    }
}
