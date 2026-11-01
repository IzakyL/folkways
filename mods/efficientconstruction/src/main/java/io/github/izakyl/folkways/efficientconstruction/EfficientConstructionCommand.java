package io.github.izakyl.folkways.efficientconstruction;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * A hand for testing: raises settlers for the colony the player is in, through the same door immigration uses.
 *
 * <p>A body only counts as a resident while the colony holds it, so a summoned {@code folkways:resident} that never
 * went through {@code Colony#hold} is let go the moment it enters the world. This does the whole of it: take the body
 * up, bind it to the colony, and let the colony know it is here.
 */
public final class EfficientConstructionCommand {

    private static final String ROOT = "folkwaysbuild";
    private static final String SETTLER = "settler";
    private static final String COUNT = "count";
    private static final int MOST_AT_ONCE = 8;

    private EfficientConstructionCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT)
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal(SETTLER)
                .executes(context -> raise(context, 1))
                .then(Commands.argument(COUNT, IntegerArgumentType.integer(1, MOST_AT_ONCE))
                    .executes(context -> raise(context, IntegerArgumentType.getInteger(context, COUNT))))));
    }

    private static int raise(CommandContext<CommandSourceStack> context, int asked) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("a settler needs a player to join the colony through"));
            return 0;
        }
        Colony colony = CurrentColony.of(player).orElse(null);
        if (colony == null) {
            source.sendFailure(Component.literal("you are not in a colony"));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos at = player.blockPosition();
        int made = 0;
        for (int raised = 0; raised < asked; raised++) {
            ResidentEntity person = PersonBody.RESIDENT.get().create(level);
            if (person == null) {
                break;
            }
            person.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
            if (!level.addFreshEntity(person)) {
                person.discard();
                break;
            }
            if (colony.hold(PersonContent.ID, Held.Entity.of(person)).isEmpty()) {
                person.discard();
                break;
            }
            person.joinColony(colony.id());
            colony.entered(person);
            made++;
        }
        int raised = made;
        player.sendSystemMessage(Component.literal("raised " + raised + " settler(s) into " + colony.id()));
        return raised;
    }
}
