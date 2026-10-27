package io.github.izakyl.folkways.raid;

import com.mojang.brigadier.context.CommandContext;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = FolkwaysRaidMod.ID)
public final class RaidEvents {

    private RaidEvents() {
    }

    @SubscribeEvent
    public static void reload(AddReloadListenerEvent event) {
        event.addListener(RaidDefinitions.INSTANCE);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer() instanceof GameTestServer) {
            return;
        }
        if (event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        if (event.getServer().getTickCount() % 200 == 0) {
            event.getServer().getAllLevels().forEach(level -> AttributedFires.of(level).prune(level));
        }
        for (var colony : Colonies.all(event.getServer())) {
            RaidDirector.of(colony).ifPresent(RaidDirector::tick);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void portal(BlockEvent.PortalSpawnEvent event) {
        // Fire placement runs onPlace before the source can be recorded in the fire ledger.
        if (RaidEdits.placingFire(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void trample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof Mob mob
            && RaidMembers.marked(mob)
            && !RaidEdits.reserve(mob, event.getPos(), false)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void explosion(ExplosionEvent.Detonate event) {
        if (!(event.getExplosion().getDirectSourceEntity() instanceof Mob mob) || !RaidMembers.marked(mob)) {
            return;
        }
        RaidEdits.explosion(mob, event.getAffectedBlocks());
        if (mob instanceof Creeper) {
            RaidMembers.director(mob).ifPresent(director -> director.died(mob.getUUID()));
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void exploding(ExplosionEvent.Start event) {
        if (event.isCanceled()
            && event.getExplosion().getDirectSourceEntity() instanceof Creeper mob
            && RaidMembers.marked(mob)) {
            RaidMembers.director(mob).ifPresent(director -> director.died(mob.getUUID()));
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var raids = Commands.literal("raids").requires(source -> source.hasPermission(2));
        raids.then(
            Commands.literal("start")
            .then(
            Commands.argument("colony", UuidArgument.uuid())
            .executes(context -> start(context, RaidDefinitions.INSTANCE.first()))
            .then(
            Commands.argument("definition", ResourceLocationArgument.id())
            .executes(
            context ->
            start(
            context,
            RaidDefinitions.INSTANCE.get(
            ResourceLocationArgument.getId(context, "definition")))))));
        raids.then(
            Commands.literal("stop")
            .then(
            Commands.argument("colony", UuidArgument.uuid())
            .executes(
            context -> {
                Optional<RaidDirector> director = director(context);
                director.ifPresent(RaidDirector::stop);
                return director.isPresent() ? 1 : 0;
            })));
        raids.then(
            Commands.literal("status")
            .then(
            Commands.argument("colony", UuidArgument.uuid())
            .executes(
            context -> {
                Optional<RaidDirector> director = director(context);
                director.ifPresent(value -> context.getSource().sendSuccess(value::status, false));
                return director.isPresent() ? 1 : 0;
            })));
        event.getDispatcher().register(Commands.literal("folkways").then(raids));
    }

    private static Optional<RaidDirector> director(CommandContext<CommandSourceStack> context) {
        return Colonies.of(context.getSource().getServer(), UuidArgument.getUuid(context, "colony"))
            .flatMap(RaidDirector::of);
    }

    private static int start(CommandContext<CommandSourceStack> context, RaidDefinition definition) {
        Optional<RaidDirector> director = director(context);
        director.ifPresent(value -> context.getSource().sendSuccess(() -> value.start(definition), true));
        return director.isPresent() ? 1 : 0;
    }
}
