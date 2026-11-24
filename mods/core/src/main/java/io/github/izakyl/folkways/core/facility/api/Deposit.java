package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Deposits a nearby dropped item stack into the first available item port named in `into`.
 * Accepts a single control name or a sequence of control names in preference order.
 */
public final class Deposit implements Routine {

    @Override
    public com.google.gson.JsonObject argumentSchema() {
        return Routine.argumentsSchema(Map.of("into", "array", "target", "string"));
    }

    @Override
    public String description() {
        return "Gather dropped items into an item port.";
    }

    @Override
    public java.util.List<String> writeTargets() {
        return java.util.List.of("into");
    }

    @Override
    public ResourceLocation id() {
        return Routines.DEPOSIT;
    }

    @Override
    public Optional<Refinement.Change> expand(Call call) {
        return Optional.empty();
    }

    @Override
    public Optional<String> check(Call call) {
        if (destinations(call.arguments()).isEmpty()) {
            return Optional.of("into must name at least one port control");
        }
        return target(call).isEmpty() ? Optional.of("expected item entity UUID") : Optional.empty();
    }

    @Override
    public Set<String> writes(Map<String, Value> arguments) {
        return Set.copyOf(destinations(arguments));
    }

    @Override
    public void validate(ServerLevel level, FacilityDefinition facility, Use use) {
        var controls = facility.resolve(level);
        for (String name : destinations(use.resolve(facility.parameters()).with())) {
            if (!facility.controls().containsKey(name)) {
                throw new IllegalArgumentException("into names an unknown control " + name);
            }
            if (!isItemPort(controls.get(name))) {
                throw new IllegalArgumentException(name + " must be an item port");
            }
        }
    }

    @Override
    public Optional<Outcome> direct(ServerLevel level, FacilityDefinition facility, Call call) {
        return direct(level, facility, call, null);
    }

    Optional<Outcome> direct(ServerLevel level, FacilityDefinition facility, Call call, Posts posts) {
        if (target(call).map(level::getEntity).orElse(null) instanceof ItemEntity item
                && item.isAlive() && !item.getItem().isEmpty()) {
            var controls = facility.resolve(level);
            for (String name : destinations(call.arguments())) {
                Control control = controls.get(name);
                if (!isItemPort(control) || !control.valid(level) || WorldSpaces.world(level, control.anchor())
                        .filter(at -> item.position().distanceToSqr(at) <= 9).isEmpty()) {
                    continue;
                }
                Worker worker = posts == null ? null : posts.coveringPost(level, facility, control.ref())
                    .flatMap(post -> posts.occupant(level, post)).orElse(null);
                if (worker == null && !isRemoteItemPort(control)) {
                    continue;
                }
                ItemStack inserted = PostTransfer.port(level, control.ref())
                    .map(port -> insert(level, port, worker, item)).orElse(ItemStack.EMPTY);
                if (!inserted.isEmpty()) {
                    item.setItem(item.getItem().copyWithCount(item.getItem().getCount() - inserted.getCount()));
                    if (item.getItem().isEmpty()) {
                        item.discard();
                    }
                    return Optional.of(Outcome.done());
                }
            }
        }
        return Optional.of(Outcome.failed(FacilityRefusal.INVALID));
    }

    private static ItemStack insert(ServerLevel level, Port<ItemStack> port, Worker worker, ItemEntity item) {
        ItemStack offered = item.getItem().copy();
        if (worker == null) {
            return port.deposit(level, null, offered, false);
        }
        if (!port.admits(worker.body())) {
            return ItemStack.EMPTY;
        }
        boolean borrowed = Lease.held(port).isPresent();
        var lease = Lease.take(port, worker);
        if (lease.isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            port.prepareDeposit(level, worker.body(), offered);
            return port.deposit(level, worker.body(), offered, false);
        } finally {
            if (!borrowed) {
                lease.get().close();
            }
        }
    }

    private static boolean isItemPort(Control control) {
        return control instanceof Port<?> port && ResourceKind.ITEM.equals(port.resource());
    }

    static boolean isRemoteItemPort(Control control) {
        return isItemPort(control) && control.cost() instanceof Control.Cost.Remote;
    }

    private static List<String> destinations(Map<String, Value> arguments) {
        Value into = arguments.get("into");
        if (into instanceof Value.Text text && !text.value().isBlank()) {
            return List.of(text.value());
        }
        if (into instanceof Value.Sequence sequence
                && sequence.values().stream().allMatch(value -> value instanceof Value.Text text
                    && !text.value().isBlank())) {
            return sequence.values().stream().map(Value.Text.class::cast).map(Value.Text::value).toList();
        }
        return List.of();
    }

    private static Optional<UUID> target(Call call) {
        if (call.arguments().get("target") instanceof Value.Text text) {
            try {
                return Optional.of(UUID.fromString(text.value()));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
