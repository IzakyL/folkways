package io.github.izakyl.folkways.vanilla.build.draft.client;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.front.api.Ghost;
import io.github.izakyl.folkways.core.front.api.GhostRecipes;
import io.github.izakyl.folkways.vanilla.build.draft.Chosen;
import io.github.izakyl.folkways.vanilla.build.draft.Drafting;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import io.github.izakyl.folkways.vanilla.build.draft.RecipeDrawing;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

public final class OrderPreviews implements GhostRecipes.Renderer {

    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final int MAX_CELLS = 65536;
    private static final int MAX_DRAWINGS = 2;
    private static final int MAX_READY = 8;
    private final Map<UUID, Entry> orders = new LinkedHashMap<>();
    private final Set<CompletableFuture<Image>> pending = ConcurrentHashMap.newKeySet();
    private Object library = Patterns.sources();
    private Level level;

    private record Image(Map<BlockPos, Ghost> cells, long bytes) {
    }

    private static final class Entry {
        final CompoundTag recipe;
        CompletableFuture<Image> drawing;
        Image image;
        boolean tried;

        Entry(CompoundTag recipe) {
            this.recipe = recipe.copy();
        }
    }

    @Override
    public void accept(List<CompoundTag> recipes, List<UUID> standing) {
        orders.keySet().removeIf(order -> !standing.contains(order));
        for (CompoundTag recipe : recipes) {
            Reader.of(recipe).uuid("order").filter(standing::contains).ifPresent(order -> {
                Entry before = orders.get(order);
                if (before == null || !before.recipe.equals(recipe)) {
                    orders.put(order, new Entry(recipe));
                }
            });
        }
    }

    @Override
    public List<Ghost> show(Level current, List<Ghost> ghosts) {
        if (level != current || library != Patterns.sources()) {
            orders.replaceAll((order, entry) -> new Entry(entry.recipe));
            level = current;
            library = Patterns.sources();
        }
        int running = pending.size();
        long bytes = 0;
        int ready = 0;
        for (Entry entry : orders.values()) {
            if (entry.drawing != null && entry.drawing.isDone()) {
                entry.image = entry.drawing.getNow(null);
                entry.drawing = null;
            }
            if (entry.image != null) {
                if (ready >= MAX_READY || bytes + entry.image.bytes() > MAX_BYTES) {
                    entry.image = null;
                } else {
                    ready++;
                    bytes += entry.image.bytes();
                }
            }
        }
        // Start only visible orders. A refused or evicted drawing waits for a recipe/library change.
        for (Ghost ghost : ghosts) {
            Entry entry = ghost.order().map(orders::get).orElse(null);
            if (entry != null && !entry.tried && running < MAX_DRAWINGS) {
                entry.tried = true;
                start(current, entry);
                if (entry.drawing != null) {
                    running++;
                }
            }
        }
        List<Ghost> shown = new ArrayList<>(ghosts.size());
        for (Ghost ghost : ghosts) {
            Entry entry = ghost.order().map(orders::get).orElse(null);
            Ghost cell = entry == null || entry.image == null ? null : entry.image.cells().get(ghost.pos());
            shown.add(cell != null && cell.state().equals(ghost.state())
                && cell.payload().hashCode() == ghost.payloadHash()
                ? new Ghost(ghost.pos(), ghost.state(), cell.payload(), ghost.order(), ghost.payloadHash()) : fallback(ghost));
        }
        return List.copyOf(shown);
    }

    private void start(Level level, Entry entry) {
        Reader recipe = Reader.of(entry.recipe);
        var pattern = recipe.id("pattern").flatMap(Patterns::find);
        var hint = recipe.blob("hint").flatMap(Hint::load);
        var settings = recipe.blob("settings");
        Direction facing = recipe.string("facing").map(Direction::byName).orElse(null);
        if (pattern.isEmpty() || hint.isEmpty() || settings.isEmpty() || facing == null) {
            return;
        }
        try {
            entry.drawing = RecipeDrawing.draw(level, pattern.get(), hint.get(),
                // Filed recipes can predate a resource reload that changes the pattern's knobs.
                Chosen.normalize(pattern.get().knobs(), settings.get()).save(), facing)
                .thenCompose(drawn -> Drafting.prepare(() -> image(drawn))).exceptionally(failed -> null);
            CompletableFuture<Image> task = entry.drawing;
            pending.add(task);
            task.whenComplete((done, failed) -> pending.remove(task));
        } catch (RuntimeException failed) {
            entry.drawing = null;
        }
    }

    private static Ghost fallback(Ghost ghost) {
        if (ghost.order().isPresent() && ghost.state().hasBlockEntity() && !ghost.payload().present()) {
            return new Ghost(ghost.pos(), Blocks.STONE.defaultBlockState());
        }
        return ghost;
    }

    private static Image image(Drawn drawn) {
        if (!(drawn instanceof Drawn.Ready ready) || ready.draft().cells().size() > MAX_CELLS) {
            return null;
        }
        Map<BlockPos, Ghost> cells = new LinkedHashMap<>();
        long bytes = 0;
        for (var cell : ready.draft().cells()) {
            bytes += 192L + cell.payload().bytes();
            if (bytes > MAX_BYTES / MAX_DRAWINGS) {
                return null;
            }
            BlockPos pos = ready.corner().offset(cell.offset());
            cells.put(pos, new Ghost(pos, cell.state(), cell.payload()));
        }
        return new Image(cells, bytes);
    }

    @Override
    public List<UUID> unavailable() {
        return orders.entrySet().stream()
            .filter(entry -> Reader.of(entry.getValue().recipe).id("pattern").flatMap(Patterns::find).isEmpty())
            .map(Map.Entry::getKey).toList();
    }

    @Override
    public void clear() {
        orders.clear();
        level = null;
    }
}
