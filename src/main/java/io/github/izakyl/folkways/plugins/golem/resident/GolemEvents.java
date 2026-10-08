package io.github.izakyl.folkways.plugins.golem.resident;

import dev.xkmc.modulargolems.content.entity.common.AbstractGolemEntity;
import dev.xkmc.modulargolems.events.event.GolemCollectInventoryEvent;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class GolemEvents {

    private static final TagKey<Item> COMMANDS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("modulargolems", "golem_interact"));

    private static final TagKey<Item> RETRIEVES = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("modulargolems", "holders"));

    private static final ResourceLocation RETRIEVAL_WAND =
        ResourceLocation.fromNamespaceAndPath("modulargolems", "retrieval_wand");

    private GolemEvents() {
    }

    @SubscribeEvent
    public static void onCollectInventory(GolemCollectInventoryEvent event) {
        enrolled(event.getEntity()).ifPresent(body -> event.add(body.pack()));
    }

    @SubscribeEvent
    public static void onEntityTick(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof AbstractGolemEntity<?, ?> golem
            && !golem.level().isClientSide()) {
            enrolled(golem).ifPresent(body -> {
                if (colonyRazed(golem, body)) {
                    body.leaveColony();
                    return;
                }
                GolemAi.hold(golem);
                body.kind().locomotion().fit(golem);
            });
        }
    }

    @SubscribeEvent
    public static void onInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof AbstractGolemEntity<?, ?> golem)
            || enrolled(golem).isEmpty()) {
            return;
        }
        if (event.getItemStack().is(RETRIEVES) || is(event.getItemStack(), RETRIEVAL_WAND)) {
            return;
        }
        if (event.getItemStack().is(COMMANDS)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Body body = enrolled(golem).orElse(null);
        if (body == null) {
            return;
        }
        if (colonyRazed(golem, body)) {
            body.leaveColony();
            return;
        }
        body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id)).ifPresent(colony -> {
            GolemAi.takeOver(golem);
            colony.entered(body);
        });
    }

    @SubscribeEvent
    public static void onLeaveLevel(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof AbstractGolemEntity<?, ?> golem)
            || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Body body = enrolled(golem).orElse(null);
        if (body == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        body.colonyId().flatMap(id -> Colonies.of(server, id)).ifPresent(colony ->
            Release.fromWorld(golem.getRemovalReason())
                    .ifPresentOrElse(why -> colony.holdingOf(body.id())
                        .ifPresent(held -> colony.release(held.id(), why)), () -> colony.exited(body.id())));
    }

    private static boolean colonyRazed(AbstractGolemEntity<?, ?> golem, Body body) {
        MinecraftServer server = golem.getServer();
        return server != null && body.colonyId().filter(id -> Colonies.razed(server, id)).isPresent();
    }

    private static Optional<Body> enrolled(Entity golem) {
        return Bodies.of(golem).filter(body -> body.colonyId().isPresent());
    }

    private static boolean is(ItemStack stack, ResourceLocation id) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(id);
    }
}
