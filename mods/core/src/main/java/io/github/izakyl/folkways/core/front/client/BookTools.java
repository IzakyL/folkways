package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.front.api.Category;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ui.BoxOffers;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.api.ui.PathOffers;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.item.BookGestures;
import io.github.izakyl.folkways.core.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.core.front.engine.item.HeldBook;
import io.github.izakyl.folkways.core.front.engine.net.CreatePathPacket;
import io.github.izakyl.folkways.core.front.engine.net.CreateZonePacket;
import io.github.izakyl.folkways.core.front.engine.net.SetBookGesturePacket;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * What the book in hand does with a click, and what a shape it draws becomes. The book keeps a gesture of its own:
 * pointing at blocks, animals and residents to take them in, marking out a box, or drawing a line; sneak and the wheel
 * turn it, as they always have. In the player's own eyes a whole shape is then handed over by using the book, which
 * asks what it should become among the kinds and offers drawn that way ({@link ShapePicker}). The view from above
 * picks that first instead, from its tools grouped by {@link Category}, and the gesture follows what it picked. What
 * was picked, and the last pick in each category, are the client's alone and reset with the session.
 */
public final class BookTools {
    public static final String POINT = "point";

    private static final Draft<ZoneSelection.Marked> BOX = new Draft<>(ZoneSelection::marked, ZoneSelection::clear,
        ZoneSelection::measure, box -> box.sizeX() * box.sizeY() * box.sizeZ(),
        (colony, box, delegation, settings) -> new CreateZonePacket(colony, box.min(), box.max(), delegation, settings),
        id -> BoxOffers.of(id).map(offer -> (box, taking) -> offer.body().build(box.min(), box.max(), taking)),
        id -> BoxOffers.of(id).flatMap(BoxOffers.Offer::act).map(act -> box -> act.take(box.min(), box.max())));
    private static final Draft<PathSelection.Drawn> LINE = new Draft<>(PathSelection::drawn, PathSelection::clear,
        path -> Component.translatable("folkways.zoneconfig.drawn", path.points().size()),
        path -> path.points().size(),
        (colony, line, delegation, settings) -> new CreatePathPacket(colony, line.points(), delegation, settings),
        id -> PathOffers.of(id).map(offer -> (line, taking) -> offer.body().build(line.points(), taking)),
        id -> Optional.empty());

    /** Something the book can be set to. Its key is stable across sessions and languages. */
    public sealed interface Entry permits Pointing, Kind, Offer {
        String key();

        Component name();

        ItemStack icon();

        Shape.Gesture gesture();

        Category category();

        /** Why it cannot be picked now, if it cannot. */
        default Optional<Component> unavailable() {
            return Optional.empty();
        }

        /** What the book then does, for a tooltip. */
        default Component how() {
            return Component.translatable("folkways.book.shape." + gesture().getSerializedName());
        }
    }

    /** Clicking blocks, animals and residents in and out of the colony, as the book always has. */
    public record Pointing() implements Entry {
        @Override
        public String key() {
            return POINT;
        }

        @Override
        public Component name() {
            return Component.translatable("folkways.book.point");
        }

        @Override
        public ItemStack icon() {
            return book(Shape.Gesture.POINT);
        }

        @Override
        public Shape.Gesture gesture() {
            return Shape.Gesture.POINT;
        }

        @Override
        public Category category() {
            return Category.TAKING;
        }
    }

    /** A kind of holding drawn as a box or a line: a farm, a patrol route, damage control. */
    public record Kind(Delegation delegation) implements Entry {
        @Override
        public String key() {
            return "kind:" + delegation.id();
        }

        @Override
        public Component name() {
            return Component.translatable("folkways.delegation." + delegation.id().getNamespace() + "."
                + delegation.id().getPath());
        }

        @Override
        public ItemStack icon() {
            return new ItemStack(BuiltInRegistries.ITEM.get(delegation.icon()));
        }

        @Override
        public Shape.Gesture gesture() {
            return delegation.shape().gesture();
        }

        @Override
        public Category category() {
            return Category.of(delegation.category());
        }

        @Override
        public Optional<Component> unavailable() {
            return delegation.disabledReason();
        }

        int most() {
            return switch (delegation.shape()) {
                case Shape.Volume volume -> volume.maxCells();
                case Shape.Path path -> path.maxPoints();
                default -> Integer.MAX_VALUE;
            };
        }

        Component limit() {
            return switch (delegation.shape()) {
                case Shape.Volume volume -> Component.translatable("folkways.zoneconfig.cells", volume.maxCells());
                case Shape.Path path -> Component.translatable("folkways.zoneconfig.points", path.maxPoints());
                default -> Component.empty();
            };
        }
    }

    /** Something else a drawn shape can be handed to: it asks what it needs in a panel of its own, or acts at once. */
    public record Offer(ResourceLocation id, String nameKey, ResourceLocation iconId, Shape.Gesture gesture,
            ResourceLocation categoryId) implements Entry {
        @Override
        public String key() {
            return "offer:" + id;
        }

        @Override
        public Component name() {
            return Component.translatable(nameKey);
        }

        @Override
        public ItemStack icon() {
            return new ItemStack(BuiltInRegistries.ITEM.get(iconId));
        }

        @Override
        public Category category() {
            return Category.of(categoryId);
        }
    }

    public record Marks(String tool, ResourceKey<Level> dimension, long tick, Shape.Gesture gesture,
            List<BlockPos> points) {

        public Marks {
            points = List.copyOf(points);
        }
    }

    private static Level markedLevel;
    private static long markedTick;

    static void mark(Level level) {
        markedLevel = level;
        markedTick = level.getGameTime();
    }

    public static Optional<Marks> marks() {
        if (markedLevel == null || markedLevel != Minecraft.getInstance().level) {
            return Optional.empty();
        }
        Shape.Gesture gesture = gesture();
        List<BlockPos> points = switch (gesture) {
            case BOX -> ZoneSelection.corners();
            case LINE -> PathSelection.points();
            case POINT -> List.of();
        };
        String tool = picked().map(Entry::key).orElse(gesture.getSerializedName());
        return points.isEmpty() ? Optional.empty() : Optional.of(new Marks(tool, markedLevel.dimension(),
            markedTick, gesture, points));
    }

    /** How long a turn of the book is taken at the client's word before the book itself says otherwise. */
    private static final long TURN_TRUSTED_MS = 2000L;

    /** The gesture the book was last turned to here, until the server's copy of the book catches up. */
    private static Shape.Gesture turned;
    private static long turnedAt;
    /** What a shape is handed to from above, by key; it holds only while the book draws that way. */
    private static String target = POINT;
    /** The category open among the tools from above, and the last pick in each. */
    private static Category open = Category.TAKING;
    private static final Map<ResourceLocation, String> lastIn = new HashMap<>();
    /** When the last shape was handed over, so a held use key does not go on to open what was aimed at. */
    private static long handedAt;

    private BookTools() {
    }

    static ItemStack book(Shape.Gesture gesture) {
        ItemStack book = new ItemStack(FolkwaysItems.COLONY_BOOK.get());
        ColonyBookItem.setGesture(book, gesture);
        return book;
    }

    /** Everything the book can be set to: pointing, the kinds drawn as boxes, those drawn as lines, then the offers. */
    public static List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Pointing());
        for (Delegation declared : Enrollments.delegations()) {
            if (declared.shape() instanceof Shape.Volume) {
                entries.add(new Kind(declared));
            }
        }
        for (Delegation declared : Enrollments.delegations()) {
            if (declared.shape() instanceof Shape.Path) {
                entries.add(new Kind(declared));
            }
        }
        BoxOffers.all().forEach(offer -> entries.add(
            new Offer(offer.id(), offer.nameKey(), offer.icon(), Shape.Gesture.BOX, offer.category())));
        PathOffers.all().forEach(offer -> entries.add(
            new Offer(offer.id(), offer.nameKey(), offer.icon(), Shape.Gesture.LINE, offer.category())));
        return entries;
    }

    /** The entries grouped by category, in the categories' order and each in the order {@link #entries} gives. */
    public static Map<Category, List<Entry>> grouped() {
        List<Entry> entries = entries();
        Map<Category, List<Entry>> grouped = new LinkedHashMap<>();
        entries.stream().map(Entry::category).distinct().sorted(Category.ORDER)
            .forEach(category -> grouped.put(category, new ArrayList<>()));
        entries.forEach(entry -> grouped.get(entry.category()).add(entry));
        return grouped;
    }

    public static Optional<Entry> entry(String key) {
        return entries().stream().filter(entry -> entry.key().equals(key)).findFirst();
    }

    /** What the book does with a click: the gesture it was last turned to. */
    public static Shape.Gesture gesture() {
        Minecraft minecraft = Minecraft.getInstance();
        Shape.Gesture held = minecraft.player == null ? BookGestures.DEFAULT
            : HeldBook.any(minecraft.player).map(book -> ColonyBookItem.gesture(book.stack()))
                .orElse(BookGestures.DEFAULT);
        if (turned != null) {
            if (turned == held || Util.getMillis() - turnedAt > TURN_TRUSTED_MS) {
                turned = null;
            } else {
                return turned;
            }
        }
        return held;
    }

    /** What a whole shape is handed to from above, when that has been picked and the book still draws that way. */
    public static Optional<Entry> picked() {
        Shape.Gesture gesture = gesture();
        if (gesture == Shape.Gesture.POINT) {
            return entry(target).filter(entry -> entry.gesture() == gesture).or(() -> Optional.of(new Pointing()));
        }
        return entry(target).filter(entry -> entry.gesture() == gesture);
    }

    /** The category open among the tools from above: that of what is picked, else the one last opened. */
    public static Category openCategory() {
        return picked().map(Entry::category).orElse(open);
    }

    /** The last pick in a category, while it can still be picked; else its first that can. */
    public static Optional<Entry> lastIn(Category category) {
        List<Entry> in = grouped().getOrDefault(category, List.of()).stream()
            .filter(entry -> entry.unavailable().isEmpty()).toList();
        String last = lastIn.get(category.id());
        return in.stream().filter(entry -> entry.key().equals(last)).findFirst()
            .or(() -> in.stream().findFirst());
    }

    /** Picks an entry by its key, and turns the book to draw for it; one that cannot be picked now says why. */
    public static boolean choose(String key) {
        Optional<Entry> found = entry(key);
        if (found.isEmpty()) {
            return false;
        }
        Entry entry = found.get();
        if (entry.unavailable().isPresent()) {
            Draft.say(entry.unavailable().get());
            return false;
        }
        OverheadTools.deselect();
        if (HeldPlacement.holding()) {
            HeldPlacement.clear();
        }
        Optional<Entry> before = picked();
        // A shape already drawn stays for another kind drawn the same way, so it can be given to that one instead.
        if (gesture() != entry.gesture()) {
            turnTo(entry.gesture());
        } else if (before.isEmpty() || !before.get().key().equals(entry.key())) {
            ShapePreview.clear();
        }
        target = entry.key();
        open = entry.category();
        lastIn.put(entry.category().id(), entry.key());
        // From above the tools show what is picked; the bar under them is left to what is being drawn.
        if (!Overhead.on()) {
            Draft.say(Component.translatable("folkways.book.tool", entry.name()));
        }
        return true;
    }

    /** Opens a category from above at its last pick. */
    public static boolean open(Category category) {
        return lastIn(category).map(entry -> choose(entry.key())).orElse(false);
    }

    /** Opens the next category from above, or the one before; wraps around. */
    public static void cycleCategory(int step) {
        List<Category> categories = grouped().entrySet().stream()
            .filter(group -> group.getValue().stream().anyMatch(entry -> entry.unavailable().isEmpty()))
            .map(Map.Entry::getKey).toList();
        if (categories.isEmpty()) {
            return;
        }
        int at = Math.max(0, categories.indexOf(openCategory()));
        open(categories.get(Math.floorMod(at + Integer.signum(step), categories.size())));
    }

    /** Steps to the next pick in the open category, or back; wraps around. */
    public static void cycleWithin(int step) {
        List<Entry> in = grouped().getOrDefault(openCategory(), List.of()).stream()
            .filter(entry -> entry.unavailable().isEmpty()).toList();
        if (in.isEmpty()) {
            return;
        }
        String here = picked().map(Entry::key).orElse("");
        int at = -1;
        for (int index = 0; index < in.size(); index++) {
            if (in.get(index).key().equals(here)) {
                at = index;
            }
        }
        int next = at < 0 ? (step > 0 ? 0 : in.size() - 1) : Math.floorMod(at + Integer.signum(step), in.size());
        choose(in.get(next).key());
    }

    /** Sneak and the wheel in the player's own eyes: the book turns to its next gesture, or back. */
    public static void cycleGesture(int step) {
        List<Shape.Gesture> offered = BookGestures.offered();
        int at = Math.max(0, offered.indexOf(gesture()));
        Shape.Gesture next = offered.get(Math.floorMod(at + Integer.signum(step), offered.size()));
        if (HeldPlacement.holding()) {
            HeldPlacement.clear();
        }
        turnTo(next);
        Draft.say(Component.translatable("folkways.book.gesture." + next.getSerializedName()));
    }

    private static void turnTo(Shape.Gesture gesture) {
        Minecraft minecraft = Minecraft.getInstance();
        Optional<HeldBook> book = minecraft.player == null ? Optional.empty() : HeldBook.any(minecraft.player);
        if (book.isEmpty()) {
            return;
        }
        if (gesture() != gesture) {
            drop();
        }
        turned = gesture;
        turnedAt = Util.getMillis();
        PacketDistributor.sendToServer(new SetBookGesturePacket(book.get().hand(), gesture));
    }

    /** Whether a whole shape is drawn, ready to be handed over by using the book. */
    public static boolean ready() {
        return draft(gesture()).map(Draft::ready).orElse(false);
    }

    /** Whether a shape is being drawn at all, whole or not. */
    public static boolean drafting() {
        return switch (gesture()) {
            case BOX -> ZoneSelection.drafting();
            case LINE -> PathSelection.drafting();
            case POINT -> false;
        };
    }

    static boolean justHanded() {
        return Util.getMillis() - handedAt < 400L;
    }

    /**
     * Uses the book on a whole shape. From above it goes to what was picked there; otherwise the book asks what it
     * should become. False if no whole shape is drawn.
     */
    public static boolean hand() {
        if (!ready()) {
            return false;
        }
        Optional<Entry> picked = picked().filter(entry -> !(entry instanceof Pointing));
        if (Overhead.on() && picked.isPresent()) {
            return handTo(picked.get().key());
        }
        ShapePicker.open(gesture());
        handedAt = Util.getMillis();
        return true;
    }

    /** What a whole shape drawn now could become: the kinds and offers drawn its way, by category. */
    public static Map<Category, List<Entry>> candidates(Shape.Gesture gesture) {
        Map<Category, List<Entry>> candidates = new LinkedHashMap<>();
        grouped().forEach((category, entries) -> {
            List<Entry> drawn = entries.stream()
                .filter(entry -> !(entry instanceof Pointing) && entry.gesture() == gesture).toList();
            if (!drawn.isEmpty()) {
                candidates.put(category, drawn);
            }
        });
        return candidates;
    }

    /** Why the shape drawn now cannot become that entry, if it cannot: unavailable now, or drawn too large for it. */
    static Optional<Component> refusal(Entry entry) {
        if (entry.unavailable().isPresent()) {
            return entry.unavailable();
        }
        if (entry instanceof Kind kind && draft(kind.gesture()).map(Draft::extent).orElse(0) > kind.most()) {
            return Optional.of(Component.translatable("folkways.book.too_large", kind.name(), kind.limit()));
        }
        return Optional.empty();
    }

    /** Hands the drawn shape to an entry: a kind becomes a holding, an offer opens. False if nothing was. */
    public static boolean handTo(String key) {
        Optional<Entry> found = entry(key);
        Optional<Draft<?>> draft = found.flatMap(entry -> draft(entry.gesture())).filter(Draft::ready);
        Optional<UUID> colony = Optional.ofNullable(Minecraft.getInstance().player)
            .flatMap(HeldBook::bound).map(HeldBook::colonyId);
        if (draft.isEmpty() || colony.isEmpty() || found.get().gesture() != gesture()) {
            return false;
        }
        Entry entry = found.get();
        target = entry.key();
        lastIn.put(entry.category().id(), entry.key());
        switch (entry) {
            case Kind kind -> {
                Optional<Component> refused = refusal(kind);
                if (refused.isPresent()) {
                    Draft.say(refused.get());
                } else {
                    draft.get().send(colony.get(), kind.delegation().id());
                    handedAt = Util.getMillis();
                }
            }
            case Offer offer -> draft.get().offer(offer.id(), offer.name());
            case Pointing ignored -> {
                return false;
            }
        }
        return true;
    }

    /** Lets go of whatever is being drawn. */
    public static void drop() {
        BOX.drop();
        LINE.drop();
    }

    static Optional<Component> measure() {
        return draft(gesture()).flatMap(Draft::measure);
    }

    private static Optional<Draft<?>> draft(Shape.Gesture gesture) {
        return switch (gesture) {
            case BOX -> Optional.of(BOX);
            case LINE -> Optional.of(LINE);
            case POINT -> Optional.empty();
        };
    }

    static void reset() {
        turned = null;
        target = POINT;
        open = Category.TAKING;
        lastIn.clear();
        handedAt = 0L;
        markedLevel = null;
    }
}
