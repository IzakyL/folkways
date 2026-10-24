package io.github.izakyl.folkways.war;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = FolkwaysWarMod.ID)
public final class WarEvents {
    private WarEvents() { }

    @SubscribeEvent
    public static void reload(AddReloadListenerEvent event) { event.addListener(RaidDefinitions.INSTANCE); }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) { return; }
        if (event.getServer().getTickCount() % 20 != 0) { return; }
        if (event.getServer().getTickCount() % 200 == 0) { event.getServer().getAllLevels().forEach(level -> AttributedFires.of(level).prune(level)); }
        for (var colony : Colonies.all(event.getServer())) { RaidDirector.of(colony).ifPresent(RaidDirector::tick); }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void trample(net.neoforged.neoforge.event.level.BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.Mob mob && RaidMembers.marked(mob)
                && !WarEdits.reserve(mob, event.getPos(), false)) { event.setCanceled(true); }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    public static void explosion(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (!(event.getExplosion().getDirectSourceEntity() instanceof net.minecraft.world.entity.Mob mob) || !RaidMembers.marked(mob)) { return; }
        WarEdits.explosion(mob, event.getAffectedBlocks());
        if (mob instanceof net.minecraft.world.entity.monster.Creeper) { RaidMembers.director(mob).ifPresent(director -> director.died(mob.getUUID())); }
    }

    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST, receiveCanceled = true)
    public static void exploding(net.neoforged.neoforge.event.level.ExplosionEvent.Start event) {
        if (event.isCanceled() && event.getExplosion().getDirectSourceEntity() instanceof net.minecraft.world.entity.monster.Creeper mob && RaidMembers.marked(mob)) {
            RaidMembers.director(mob).ifPresent(director -> director.died(mob.getUUID()));
        }
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        var raids = Commands.literal("raids").requires(source -> source.hasPermission(2));
        for (String action : new String[] { "start", "stop", "status" }) {
            var command = Commands.literal(action).then(Commands.argument("colony", UuidArgument.uuid()).executes(context -> {
                var director = Colonies.of(context.getSource().getServer(), UuidArgument.getUuid(context, "colony")).flatMap(RaidDirector::of);
                if (director.isEmpty()) { return 0; }
                if (action.equals("start")) { context.getSource().sendSuccess(() -> director.get().start(RaidDefinitions.INSTANCE.first()), true); }
                else if (action.equals("stop")) { director.get().stop(); }
                else { context.getSource().sendSuccess(director.get()::status, false); }
                return 1;
            }));
            if (action.equals("start")) {
                command.then(Commands.argument("colony", UuidArgument.uuid()).then(Commands.argument("definition", StringArgumentType.word()).executes(context -> {
                    var director = Colonies.of(context.getSource().getServer(), UuidArgument.getUuid(context, "colony")).flatMap(RaidDirector::of);
                    var id = ResourceLocation.tryParse(StringArgumentType.getString(context, "definition"));
                    if (director.isEmpty() || id == null) { return 0; }
                    context.getSource().sendSuccess(() -> director.get().start(RaidDefinitions.INSTANCE.get(id)), true);
                    return 1;
                })));
            }
            raids.then(command);
        }
        event.getDispatcher().register(Commands.literal("folkways").then(raids));
    }
}
