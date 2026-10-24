package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

// Immutable diagnostic data, posted on the server thread. It does not retain work, bodies or a world in a chat queue.
public final class LaborIncident extends Event {
    private final UUID colony;
    private final ResourceKey<Level> dimension;
    private final String key;
    private final Component message;

    public LaborIncident(UUID colony, ResourceKey<Level> dimension, String key, Component message) {
        this.colony = colony;
        this.dimension = dimension;
        this.key = key;
        this.message = message.copy();
    }

    public UUID colony() { return colony; }
    public ResourceKey<Level> dimension() { return dimension; }
    public String key() { return dimension.location() + "/" + key; }
    public Component message() { return message.copy(); }

    static void publish(UUID colony, ResourceKey<Level> dimension, String key, Component message) {
        try {
            NeoForge.EVENT_BUS.post(new LaborIncident(colony, dimension, key, message));
        } catch (RuntimeException broken) {
            Labor.LOGGER.warn("diagnostic listener failed for {}", key, broken);
        }
    }

    static void work(UUID colony, ServerLevel level, Body body, Node node, String reason, Component detail) {
        publish(colony, level.dimension(), body.id() + "/" + node.id() + "/" + reason,
            Component.translatable("folkways.debug.incident.work", level.dimension().location().toString(),
                entity(body.mob()), body.mob().blockPosition().toShortString(), job(node), shortId(node.id()),
                target(level, node), detail));
    }

    static Component job(Node node) {
        var spec = node.spec();
        String key = "folkways.doing." + spec.doing().getNamespace() + "." + spec.doing().getPath();
        return spec.about().<Component>map(about -> Component.translatable(key + ".about", Component.translatable(about)))
            .orElseGet(() -> Component.translatable(key));
    }

    static Component target(ServerLevel level, Node node) {
        UUID id = node instanceof PickUp pickup ? pickup.drop()
            : node.spec().site() instanceof WorkSite.AtEntity at ? at.entity() : null;
        Entity found = id == null ? null : level.getEntity(id);
        if (found != null) return entity(found);
        return Component.literal(node.spec().site().where().toString()
            + (id == null ? "" : " #" + shortId(id)));
    }

    static Component surroundings(Body body) {
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
