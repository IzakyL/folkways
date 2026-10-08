package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

// What one plugin's own work made over the past day, counted where it was made, for the cards of the zones it
// works. The plugin records its outcomes into it and keeps it with its own state; the core counts nothing.
public final class PastDay {

    public static final long TICKS = 24_000L;

    private static final long BUCKET_TICKS = 1_200L;

    private static final int MOST_ENTRIES = 4_096;

    private static final int REACH_Y = 2;

    private static final String MADE = "folkways.card.made";
    private static final String NOTHING = "folkways.card.made.none";

    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_AT = "at";
    private static final String TAG_ITEM = "item";
    private static final String TAG_BUCKET = "bucket";
    private static final String TAG_COUNT = "count";

    private record Key(WorldPos at, ResourceLocation item, long bucket) {
    }

    private final Map<Key, Long> counts = new LinkedHashMap<>();

    private boolean changed;

    // What a node's work made, counted at the cell it works on.
    public void record(NodeSpec spec, Outcome outcome, long now) {
        if (outcome instanceof Outcome.Done done) {
            record(at(spec), done.made(), now);
        }
    }

    public void record(WorldPos at, List<ItemStack> made, long now) {
        count(at, made, 1, now);
    }

    // What a node's work used up, taken off what was made at the cell it works on, so the card tells what the
    // work gave over what it cost.
    public void spent(NodeSpec spec, List<ItemStack> spent, long now) {
        spent(at(spec), spent, now);
    }

    public void spent(WorldPos at, List<ItemStack> spent, long now) {
        count(at, spent, -1, now);
    }

    private static WorldPos at(NodeSpec spec) {
        WorldPos site = spec.site().where();
        return spec.focus().map(site::at).orElse(site);
    }

    private synchronized void count(WorldPos at, List<ItemStack> stacks, int sign, long now) {
        boolean counted = false;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            Key key = new Key(at, BuiltInRegistries.ITEM.getKey(stack.getItem()), now / BUCKET_TICKS);
            counts.merge(key, (long) sign * stack.getCount(), Long::sum);
            counted = true;
        }
        if (counted) {
            forget(now);
            changed = true;
        }
    }

    public synchronized Map<ResourceLocation, Long> within(Predicate<WorldPos> where, long since) {
        long first = Math.floorDiv(since, BUCKET_TICKS);
        Map<ResourceLocation, Long> summed = new LinkedHashMap<>();
        counts.forEach((key, count) -> {
            if (key.bucket() >= first && where.test(key.at())) {
                summed.merge(key.item(), count, Long::sum);
            }
        });
        // What was used up more than made over the window is no output at all.
        summed.values().removeIf(count -> count <= 0);
        List<Map.Entry<ResourceLocation, Long>> sorted = new ArrayList<>(summed.entrySet());
        sorted.sort(Map.Entry.<ResourceLocation, Long>comparingByValue(Comparator.reverseOrder()));
        Map<ResourceLocation, Long> ranked = new LinkedHashMap<>();
        sorted.forEach(entry -> ranked.put(entry.getKey(), entry.getValue()));
        return ranked;
    }

    // The card lines for a zone: what was made on it, or a little above or below it, over the past day, told after
    // the zone's own glyph, and the glyph crossed out when nothing was.
    public List<Line> lines(ZoneView zone, ColonyView view) {
        Set<BlockPos> cells = zone.cells();
        if (cells.isEmpty()) {
            return List.of();
        }
        BoundingBox box = BoundingBox.encapsulatingPositions(cells).orElseThrow().inflatedBy(0, REACH_Y, 0);
        WorldPos sample = zone.at(cells.iterator().next());
        Map<ResourceLocation, Long> made = within(
            pos -> pos.sameRealm(sample) && box.isInside(pos.cell()), view.level().getGameTime() - TICKS);
        ResourceLocation glyph = ResourceLocation.fromNamespaceAndPath(zone.delegation().getNamespace(),
            "delegation/" + zone.delegation().getPath());
        if (made.isEmpty()) {
            return List.of(Line.told(Sentence.of(Sentence.glyph(glyph, new Notice(NOTHING, List.of())),
                Sentence.glyph("lacks"))));
        }
        return List.of(Line.told(Sentence.wares(Sentence.glyph(glyph, new Notice(MADE, List.of())),
            made.entrySet().stream()
                .limit(Line.Goods.MOST_STACKS)
                .map(entry -> new Sentence.Token.Ware(entry.getKey(), entry.getValue()))
                .toList())));
    }

    // Whether anything was counted since this was last asked: the plugin keeps the tally again when it was.
    public synchronized boolean changed() {
        boolean was = changed;
        changed = false;
        return was;
    }

    private void forget(long now) {
        long oldest = (now - TICKS) / BUCKET_TICKS;
        counts.keySet().removeIf(key -> key.bucket() < oldest);
        while (counts.size() > MOST_ENTRIES) {
            counts.keySet().stream().min(Comparator.comparingLong(Key::bucket)).ifPresent(counts::remove);
        }
    }

    public synchronized CompoundTag save() {
        return Writer.of()
            .children(TAG_ENTRIES, counts.entrySet(), entry -> Writer.of()
                .blob(TAG_AT, entry.getKey().at().save())
                .id(TAG_ITEM, entry.getKey().item())
                .longValue(TAG_BUCKET, entry.getKey().bucket())
                .longValue(TAG_COUNT, entry.getValue())
                .tag())
            .tag();
    }

    public synchronized void load(Reader reader) {
        counts.clear();
        for (Reader entry : reader.children(TAG_ENTRIES)) {
            Optional<WorldPos> at = entry.child(TAG_AT).flatMap(WorldPos::load);
            Optional<ResourceLocation> item = entry.id(TAG_ITEM);
            Optional<Long> bucket = entry.longValue(TAG_BUCKET);
            Optional<Long> count = entry.longValue(TAG_COUNT);
            if (at.isPresent() && item.isPresent() && bucket.isPresent() && count.isPresent()) {
                counts.merge(new Key(at.get(), item.get(), bucket.get()), count.get(), Long::sum);
            }
        }
    }
}
