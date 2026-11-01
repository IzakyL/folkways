package io.github.izakyl.folkways.efficientconstruction;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.plugins.build.BlueprintBuildOrder;
import io.github.izakyl.folkways.plugins.build.BuildOrders;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The add-on's own commands: a hand to raise settlers with, the colony's orders to look at, and a site to start over.
 *
 * <p>A body only counts as a resident while the colony holds it, so a summoned {@code folkways:resident} that never
 * went through {@code Colony#hold} is let go the moment it enters the world. {@code settler} does the whole of it:
 * take the body up, bind it to the colony, and let the colony know it is here.
 */
public final class EfficientConstructionCommand {

    private static final String ROOT = "folkwaysbuild";
    private static final String SETTLER = "settler";
    private static final String COUNT = "count";
    private static final String ORDERS = "orders";
    private static final String RESTART = "restart";
    private static final String WHICH = "order";
    private static final int MOST_AT_ONCE = 8;
    private static final int ID_SHOWN = 8;

    private EfficientConstructionCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(ROOT)
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal(SETTLER)
                .executes(context -> raise(context, 1))
                .then(Commands.argument(COUNT, IntegerArgumentType.integer(1, MOST_AT_ONCE))
                    .executes(context -> raise(context, IntegerArgumentType.getInteger(context, COUNT)))))
            .then(Commands.literal(ORDERS)
                .executes(EfficientConstructionCommand::list))
            .then(Commands.literal(RESTART)
                .executes(context -> restart(context, ""))
                .then(Commands.argument(WHICH, StringArgumentType.greedyString())
                    .suggests(EfficientConstructionCommand::suggest)
                    .executes(context -> restart(context, StringArgumentType.getString(context, WHICH))))));
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

    // What the colony is building, so an order that will not move can be named rather than guessed at.
    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        Colony colony = player == null ? null : CurrentColony.of(player).orElse(null);
        if (player == null || colony == null) {
            source.sendFailure(Component.literal("你不在殖民地范围内"));
            return 0;
        }
        List<BlueprintBuildOrder> orders = BuildOrders.orders(colony);
        if (orders.isEmpty()) {
            source.sendSuccess(() -> Component.literal("这个殖民地没有正在建造的订单"), false);
            return 0;
        }
        ServerLevel level = player.serverLevel();
        int index = 0;
        for (BlueprintBuildOrder order : orders) {
            index++;
            final int number = index;
            final String name = order.name() == null || order.name().isBlank() ? "(无名)" : order.name();
            final String id = order.id().toString().substring(0, ID_SHOWN);
            final String where = order.anchor().in(level)
                ? order.anchor().block(level).toShortString() : "其它维度";
            source.sendSuccess(() -> Component.literal("#" + number + "  " + name + "  " + id + "  " + where),
                false);
        }
        return index;
    }

    // One order's site started over. Nothing given names the one nearest the player, which is almost always the one
    // that stopped moving in front of them.
    private static int restart(CommandContext<CommandSourceStack> context, String which) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        Colony colony = player == null ? null : CurrentColony.of(player).orElse(null);
        if (player == null || colony == null) {
            source.sendFailure(Component.literal("你不在殖民地范围内"));
            return 0;
        }
        List<BlueprintBuildOrder> found = matching(BuildOrders.orders(colony), which,
            player.serverLevel(), player.blockPosition());
        if (found.isEmpty()) {
            source.sendFailure(Component.literal("没有找到匹配的订单，用 /" + ROOT + " " + ORDERS
                + " 看列表"));
            return 0;
        }
        if (found.size() > 1) {
            source.sendFailure(Component.literal("有 " + found.size() + " 个订单匹配，请写得更具体："));
            for (BlueprintBuildOrder order : found) {
                source.sendFailure(Component.literal("  " + order.name() + "  " + order.id()));
            }
            return 0;
        }
        BlueprintBuildOrder order = found.getFirst();
        if (!BuildOrders.restart(colony, order.id())) {
            source.sendFailure(Component.literal("这个订单现在没有施工中的工地，不用重启"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("已重启订单「" + order.name()
            + "」，已建成的部分照旧算完成"), true);
        return 1;
    }

    private static List<BlueprintBuildOrder> matching(List<BlueprintBuildOrder> orders, String which,
            ServerLevel level, BlockPos at) {
        if (which.isBlank()) {
            BlueprintBuildOrder nearest = nearest(orders, level, at);
            return nearest == null ? List.of() : List.of(nearest);
        }
        String wanted = which.trim().toLowerCase(Locale.ROOT);
        List<BlueprintBuildOrder> found = new ArrayList<>();
        for (BlueprintBuildOrder order : orders) {
            String name = order.name() == null ? "" : order.name().toLowerCase(Locale.ROOT);
            if (name.startsWith(wanted) || order.id().toString().startsWith(wanted)) {
                found.add(order);
            }
        }
        return found;
    }

    @Nullable
    private static BlueprintBuildOrder nearest(List<BlueprintBuildOrder> orders, ServerLevel level, BlockPos at) {
        BlueprintBuildOrder nearest = null;
        double closest = Double.MAX_VALUE;
        for (BlueprintBuildOrder order : orders) {
            if (!order.anchor().in(level)) {
                continue;
            }
            double away = order.anchor().block(level).distSqr(at);
            if (away < closest) {
                closest = away;
                nearest = order;
            }
        }
        return nearest;
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player != null) {
            CurrentColony.of(player).ifPresent(colony -> {
                for (BlueprintBuildOrder order : BuildOrders.orders(colony)) {
                    boolean unnamed = order.name() == null || order.name().isBlank();
                    builder.suggest(unnamed ? order.id().toString() : order.name());
                }
            });
        }
        return builder.buildFuture();
    }
}
