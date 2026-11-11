package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Immutable diagnostic data, posted on the server thread. It does not retain work, bodies or a world in a chat queue.
public final class LaborIncident extends Event {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    // Published by the front as an immutable snapshot; planning threads may read it too.
    private static volatile Set<UUID> subscriptions = Set.of();
    private final UUID colony;
    private final ResourceKey<Level> dimension;
    private final String key;
    private final Component message;
    private final Optional<UUID> resident;
    private final Optional<Component> brief;

    public LaborIncident(UUID colony, ResourceKey<Level> dimension, String key, Component message) {
        this(colony, dimension, key, message, Optional.empty(), Optional.empty());
    }

    private LaborIncident(UUID colony, ResourceKey<Level> dimension, String key, Component message,
                          Optional<UUID> resident, Optional<Component> brief) {
        this.resident = resident;
        this.brief = brief.map(Component::copy);
        this.colony = colony;
        this.dimension = dimension;
        this.key = key;
        this.message = message.copy();
    }

    public UUID colony() { return colony; }
    public ResourceKey<Level> dimension() { return dimension; }
    public String key() { return dimension.location() + "/" + key; }
    public Optional<UUID> resident() {
        return resident;
    }

    public Component message() { return message.copy(); }

    /** The message without who and where, for a card that already names the resident. */
    public Optional<Component> brief() { return brief.map(Component::copy); }

    public static void subscriptions(Set<UUID> colonies) { subscriptions = Set.copyOf(colonies); }

    public static void publish(UUID colony, ResourceKey<Level> dimension, String key, Supplier<Component> message) {
        publish(colony, dimension, key, () -> new Told(message.get(), Optional.empty()), Optional.empty());
    }

    private record Told(Component message, Optional<Component> brief) {
    }

    private static void publish(UUID colony, ResourceKey<Level> dimension, String key, Supplier<Told> told,
                                Optional<UUID> resident) {
        if (!subscriptions.contains(colony)) {
            return;
        }
        try {
            Told said = told.get();
            NeoForge.EVENT_BUS.post(new LaborIncident(colony, dimension, key, said.message(), resident,
                said.brief()));
        } catch (RuntimeException broken) {
            LOGGER.warn("diagnostic listener failed for {}", key, broken);
        }
    }

    public static void work(UUID colony, ServerLevel level, Body body, Node node, String reason, Supplier<Component> detail) {
        if (!subscriptions.contains(colony)) return;
        publish(colony, level.dimension(), body.id() + "/" + node.id() + "/" + reason, () -> {
            Component job = job(node);
            Component target = target(level, node);
            Component said = detail.get();
            return new Told(Component.translatable("folkways.debug.incident.work",
                level.dimension().location().toString(), entity(body.mob()),
                body.mob().blockPosition().toShortString(), job, shortId(node.id()), target, said),
                Optional.of(name(reason)));
        }, Optional.of(body.id()));
    }

    // What a card lists under recent events: the incident's name alone, the detail stays in chat.
    static Component name(String reason) {
        if (reason.startsWith("urge-preempts/")) {
            return DebugText.tone(DebugText.text("incident.preempted"), DebugText.WAIT);
        }
        if (reason.equals("urge-gone")) {
            return DebugText.tone(DebugText.text("incident.urge_ended"), DebugText.MUTED);
        }
        if (reason.equals(PreemptReason.CONTROLLER.name())) {
            return DebugText.tone(DebugText.text("incident.controller"), DebugText.WAIT);
        }
        if (reason.startsWith("folkways.")) {
            return DebugText.tone(Component.translatable(reason), DebugText.BAD);
        }
        return DebugText.tone(DebugText.text("incident.other"), DebugText.MUTED);
    }

    public static Component job(Node node) {
        var spec = node.spec();
        String key = "folkways.doing." + spec.doing().getNamespace() + "." + spec.doing().getPath();
        return spec.about().<Component>map(about -> Component.translatable(key + ".about", Component.translatable(about)))
            .orElseGet(() -> Component.translatable(key));
    }

    public static Component target(ServerLevel level, Node node) {
        UUID id = node instanceof PickUp pickup ? pickup.drop()
            : node.spec().site() instanceof WorkSite.AtEntity at ? at.entity() : null;
        Entity found = id == null ? null : level.getEntity(id);
        if (found != null) return entity(found);
        return Component.literal(node.spec().site().where().toString()
            + (id == null ? "" : " #" + shortId(id)));
    }

    public static Component surroundings(Body body) {
        var mob = body.mob();
        var hurt = mob.getLastDamageSource();
        return Component.translatable("folkways.debug.incident.context",
            mob.getTarget() == null ? Component.translatable("folkways.debug.incident.unknown") : entity(mob.getTarget()),
            hurt == null ? "-" : hurt.getMsgId(), hurt == null || hurt.getEntity() == null
                ? Component.translatable("folkways.debug.incident.unknown") : entity(hurt.getEntity()));
    }

    private static Component entity(Entity entity) {
        if (entity instanceof ItemEntity drop) {
            return Component.translatable("folkways.debug.incident.item", drop.getItem().getHoverName(),
                drop.getItem().getCount(), shortId(entity.getUUID()));
        }
        return entity.getName().copy().append(" #" + shortId(entity.getUUID()));
    }

    private static String shortId(UUID id) { return id.toString().substring(0, 8); }
}
