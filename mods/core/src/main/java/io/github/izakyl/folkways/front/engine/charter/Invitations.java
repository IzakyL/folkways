package io.github.izakyl.folkways.front.engine.charter;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// Being asked into a colony: told once in chat, with an answer to click, and answered there or from the panel.
@EventBusSubscriber(modid = FolkwaysMod.CORE_ID)
public final class Invitations {

    private static final String COMMAND = "folkways";

    private Invitations() {
    }

    // Tells the player of the colony's invitation if they are online and have not heard of it yet.
    public static void tell(MinecraftServer server, Colony colony, UUID asked) {
        ServerPlayer player = server.getPlayerList().getPlayer(asked);
        if (player == null) {
            return;
        }
        Charter charter = Charters.written(colony);
        Optional<Charter.Invitation> invitation = charter.invitation(asked);
        if (invitation.isEmpty() || invitation.get().told()) {
            return;
        }
        player.sendSystemMessage(Component.translatable("folkways.charter.invitation",
                Charters.nameOf(server, invitation.get().by()), Charters.title(server, charter))
            .append(" ")
            .append(answer("folkways.charter.accept", "accept", colony, ChatFormatting.GREEN))
            .append(" ")
            .append(answer("folkways.charter.decline", "decline", colony, ChatFormatting.RED)));
        Charters.keep(colony, charter.told(asked));
    }

    public static void tellPending(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        for (Colony colony : Charters.invitations(server, player.getUUID())) {
            tell(server, colony, player.getUUID());
        }
    }

    // Answers for the player, with what came of it in their chat or action bar.
    public static boolean accept(ServerPlayer player, Colony colony) {
        MinecraftServer server = player.getServer();
        Optional<CharterRefusal> refused = Charters.accept(server, colony, player.getUUID());
        refused.ifPresentOrElse(
            why -> player.displayClientMessage(Component.translatable(why.translationKey()), true),
            () -> player.displayClientMessage(Component.translatable("folkways.charter.joined",
                Charters.title(server, Charters.of(server, colony))), false));
        return refused.isEmpty();
    }

    public static boolean decline(ServerPlayer player, Colony colony) {
        MinecraftServer server = player.getServer();
        Optional<CharterRefusal> refused = Charters.decline(server, colony, player.getUUID());
        refused.ifPresentOrElse(
            why -> player.displayClientMessage(Component.translatable(why.translationKey()), true),
            () -> player.displayClientMessage(Component.translatable("folkways.charter.declined",
                Charters.title(server, Charters.of(server, colony))), false));
        return refused.isEmpty();
    }

    private static MutableComponent answer(String key, String verb, Colony colony, ChatFormatting color) {
        String command = "/" + COMMAND + " invitation " + verb + " " + colony.id();
        return Component.literal("[").append(Component.translatable(key)).append("]")
            .withStyle(style -> style.withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable(key))));
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        ArgumentType<UUID> colony = UuidArgument.uuid();
        dispatcher.register(Commands.literal(COMMAND)
            .then(Commands.literal("invitation")
                .then(Commands.literal("accept")
                    .then(Commands.argument("colony", colony).executes(context -> run(context, Invitations::accept))))
                .then(Commands.literal("decline")
                    .then(Commands.argument("colony", colony).executes(context -> run(context, Invitations::decline))))));
    }

    private static int run(CommandContext<CommandSourceStack> context,
            BiFunction<ServerPlayer, Colony, Boolean> answer) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        UUID id = UuidArgument.getUuid(context, "colony");
        MinecraftServer server = player.getServer();
        Optional<Colony> colony = Colonies.razed(server, id) ? Optional.empty() : Colonies.of(server, id);
        if (colony.isEmpty()) {
            player.displayClientMessage(Component.translatable(CharterRefusal.NOT_INVITED.translationKey()), true);
            return 0;
        }
        return answer.apply(player, colony.get()) ? 1 : 0;
    }
}
