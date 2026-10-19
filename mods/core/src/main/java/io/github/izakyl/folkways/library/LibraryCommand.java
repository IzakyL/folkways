package io.github.izakyl.folkways.library;

import com.mojang.brigadier.CommandDispatcher;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// /folkways library: what each shelf holds, where it came from, and what was turned away.
public final class LibraryCommand {

    private static final String COMMAND = "folkways";
    private static final String TOKEN = "folkways.library";
    private static final int MOST_LINES = 8;

    private LibraryCommand() {
    }

    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal(COMMAND)
            .then(Commands.literal("library")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(context -> show(context.getSource()))));
    }

    private static int show(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable(TOKEN + ".heading", folder()), false);
        for (Shelves.Shelf shelf : Shelves.all()) {
            Shelves.Stock stock = Shelves.stock(shelf);
            source.sendSuccess(() -> Component.translatable(TOKEN + ".shelf",
                Component.literal(shelf.name()).withStyle(ChatFormatting.GOLD),
                stock.entries().size(), packs(stock)), false);
            List<String> misnamed = LooseFolder.misnamed(shelf);
            lines(source, stock.rejected(), TOKEN + ".rejected");
            lines(source, misnamed, TOKEN + ".misnamed");
        }
        return Shelves.all().size();
    }

    private static Component folder() {
        String path = Shelves.root().toAbsolutePath().toString();
        return Component.literal(path).withStyle(style -> style
            .withColor(ChatFormatting.AQUA)
            .withUnderlined(true)
            .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, path))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                Component.translatable(TOKEN + ".open"))));
    }

    private static Component packs(Shelves.Stock stock) {
        Map<String, Integer> counts = new TreeMap<>();
        stock.entries().values().forEach(pack -> counts.merge(pack, 1, Integer::sum));
        MutableComponent line = Component.empty();
        counts.forEach((pack, count) -> {
            if (!line.getSiblings().isEmpty()) {
                line.append(", ");
            }
            line.append(Component.literal(pack + " ×" + count).withStyle(ChatFormatting.GRAY));
        });
        return line;
    }

    private static void lines(CommandSourceStack source, List<String> lines, String key) {
        lines.stream().limit(MOST_LINES).forEach(line -> source.sendSuccess(() ->
            Component.translatable(key, line).withStyle(ChatFormatting.RED), false));
        if (lines.size() > MOST_LINES) {
            source.sendSuccess(() -> Component.translatable(TOKEN + ".more", lines.size() - MOST_LINES)
                .withStyle(ChatFormatting.DARK_GRAY), false);
        }
    }
}
