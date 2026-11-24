package io.github.izakyl.folkways.core.facility.api;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class DefinitionCodec {

    private DefinitionCodec() {
    }

    static Map<String, Object> write(FacilityDefinition facility) {
        Map<String, Object> data = new LinkedHashMap<>(Map.ofEntries(
            Map.entry("name", facility.name()),
            Map.entry("extent", extent(facility.extent())),
            Map.entry("anchor", pos(facility.anchor().cell())),
            Map.entry("anchor_space", space(facility.anchor().realm())),
            Map.entry("controls", mapped(facility.controls(), DefinitionCodec::placed)),
            Map.entry("routines", mapped(facility.routines(), DefinitionRoutineCodec::routineData)),
            Map.entry("triggers", mapped(facility.switches(), DefinitionTriggerCodec::trigger)),
            Map.entry("parameters", mapped(facility.parameters(), DefinitionCodec::value)),
            Map.entry("readings", mapped(facility.probes(), DefinitionCodec::probe)),
            Map.entry("ui", DefinitionCardCodec.ui(facility.declaredCard()))));
        if (!facility.posts().isEmpty()) {
            data.put("posts", mapped(facility.posts(), DefinitionCodec::post));
        }
        List<Map<String, Object>> projections = new ArrayList<>();
        facility.gates().forEach(gate -> projections.add(Map.of("gate", gate(gate))));
        facility.rides().forEach(ride -> projections.add(Map.of("ride", ride(ride))));
        facility.keepouts().forEach(keepout -> projections.add(Map.of("keepout", keepout.source()
            .<Map<String, Object>>map(source -> Map.of("source", source))
            .orElseGet(() -> Map.of("while_moving", keepout.parts(), "margin", keepout.margin())))));
        facility.lanes().forEach(lane -> projections.add(Map.of("lane", lane(lane))));
        data.put("projections", List.copyOf(projections));
        facility.workshop().ifPresent(workshop -> data.put("workshop", workshop(workshop)));
        return Map.copyOf(data);
    }

    public static Map<String, Object> extent(FacilityExtent extent) {
        return Map.of("elements", extent.elements().stream().map(element -> switch (element) {
            case FacilityExtent.Block block -> Map.of("space", space(block.pos().realm()), "block", pos(block.pos().cell()));
            case FacilityExtent.Box box -> Map.of("space", space(box.realm()), "min", pos(box.min()), "max", pos(box.max()));
            case FacilityExtent.Entity entity -> Map.of("entity", entity.id().toString());
        }).toList());
    }

    private static Map<String, String> space(Realm realm) {
        return switch (realm) {
            case Realm.Dimension dimension -> Map.of("dimension", dimension.id().location().toString());
            case Realm.Frame frame -> Map.of("structure", frame.structure().toString());
        };
    }

    private static Realm space(Field field) {
        return field.has("dimension")
            ? Realm.of(ResourceKey.create(Registries.DIMENSION, field.get("dimension").id()))
            : new Realm.Frame(field.get("structure").uuid());
    }

    public static FacilityExtent extent(Map<String, ?> data) {
        return extent(new Field("extent", data));
    }

    private static FacilityExtent extent(Field extent) {
        return new FacilityExtent(new java.util.LinkedHashSet<>(extent.get("elements").list(element -> {
            if (element.has("entity")) {
                return new FacilityExtent.Entity(element.get("entity").uuid());
            }
            Realm realm = space(element.get("space"));
            if (element.has("block")) {
                return new FacilityExtent.Block(new WorldPos(realm, element.get("block").pos()));
            }
            return new FacilityExtent.Box(realm, element.get("min").pos(), element.get("max").pos());
        })));
    }

    public static Map<String, Value> arguments(Map<String, ?> values) {
        return new Field("args", values).map(DefinitionCodec::value);
    }

    private static List<Integer> pos(BlockPos pos) {
        return List.of(pos.getX(), pos.getY(), pos.getZ());
    }

    static Tag saveReferences(Map<String, Routine.Argument> references) {
        return encode(mapped(references, DefinitionRoutineCodec::argumentData));
    }

    static Map<String, Routine.Argument> loadReferences(CompoundTag references) {
        return new Field("references", decode(references)).map(DefinitionRoutineCodec::routineArgument);
    }

    public static Object value(Value value) {
        return switch (value) {
            case Value.Text text -> text.value();
            case Value.Bool bool -> bool.on();
            case Value.Real real -> real.value();
            case Value.Vector vector -> vector.values();
            case Value.Sequence sequence -> sequence.values().stream().map(DefinitionCodec::value).toList();
            case Value.Pulse ignored -> Map.of("pulse", true);
            case Value.Items items -> Map.of("items", items.filters().stream().map(ItemFilter::describe).toList());
            case Value.Data data -> isObject(data) ? mapped(fields(data), DefinitionCodec::value)
                : Map.of("snbt", data.tag().toString());
        };
    }

    /**
     * A value as plain data for a reader. Raw NBT also exposes its fields directly, so a script or a probe report
     * can read {@code r["x"]}. The original SNBT is available under {@code snbt}.
     */
    public static Object readout(Value value) {
        if (!(value instanceof Value.Data data) || isObject(data)) {
            return value(value);
        }
        var fields = new LinkedHashMap<String, Object>();
        for (String key : data.tag().getAllKeys()) {
            Object plain = plain(data.tag().get(key));
            if (plain != null) {
                fields.put(key, plain);
            }
        }
        fields.put("snbt", data.tag().toString());
        return fields;
    }

    static Object plain(Tag tag) {
        return switch (tag) {
            case NumericTag number -> Double.isFinite(number.getAsDouble()) ? number.getAsDouble() : null;
            case StringTag text -> text.getAsString();
            case CompoundTag compound -> {
                var map = new LinkedHashMap<String, Object>();
                for (String key : compound.getAllKeys()) {
                    Object plain = plain(compound.get(key));
                    if (plain != null) {
                        map.put(key, plain);
                    }
                }
                yield map;
            }
            case CollectionTag<?> list -> {
                List<Object> items = new ArrayList<>();
                for (Tag item : list) {
                    Object plain = plain(item);
                    if (plain != null) {
                        items.add(plain);
                    }
                }
                yield items;
            }
            default -> null;
        };
    }

    public static Value object(Map<String, Value> values) {
        var tag = new CompoundTag();
        tag.put("fields", Value.saveAll(values));
        return new Value.Data(tag);
    }

    private static boolean isObject(Value.Data data) {
        return data.tag().size() == 1 && data.tag().contains("fields", Tag.TAG_COMPOUND);
    }

    public static Map<String, Value> fields(Value value) {
        if (!(value instanceof Value.Data data) || !data.tag().contains("fields", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("expected an object");
        }
        return Value.loadAll(data.tag().getCompound("fields"));
    }

    private static Vec3 point(Field field) {
        var axes = field.list(Field::number);
        if (axes.size() != 3) {
            throw field.invalid();
        }
        return new Vec3(axes.get(0), axes.get(1), axes.get(2));
    }

    static Value argument(Object data) {
        return value(new Field("argument", data));
    }

    static Value value(Field field) {
        return switch (field.value()) {
            case String text -> new Value.Text(text);
            case Boolean bool -> Value.of(bool);
            case Number ignored -> Value.of(field.number());
            case List<?> list -> list.stream().allMatch(Number.class::isInstance)
                ? new Value.Vector(field.list(Field::number)) : new Value.Sequence(field.list(DefinitionCodec::value));
            case Map<?, ?> map -> {
                if (map.size() == 1 && field.has("pulse") && field.get("pulse").bool()) {
                    yield Value.PULSE;
                }
                if (map.size() == 1 && field.has("items")) {
                    yield new Value.Items(field.get("items").list(each -> ItemFilter.query(each.string())
                        .orElseThrow(each::invalid)));
                }
                if (map.size() == 1 && field.has("snbt")) {
                    Field data = field.get("snbt");
                    try {
                        yield new Value.Data(TagParser.parseTag(data.string()));
                    } catch (CommandSyntaxException exception) {
                        throw data.invalid();
                    }
                }
                // Any other dict is an object of values, such as a recipe passed to a routine.
                yield object(field.map(DefinitionCodec::value));
            }
            case null, default -> throw field.invalid();
        };
    }

    static <T> Map<String, Object> mapped(Map<String, T> map, Function<T, Object> convert) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((name, item) -> result.put(name, convert.apply(item)));
        return Map.copyOf(result);
    }

    static FacilityDefinition read(UUID id, Map<String, ?> data) {
        Field root = new Field("", data);
        FacilityExtent extent = extent(root.get("extent"));
        WorldPos first = extent.firstBlock().orElse(null);
        Realm realm = root.has("anchor_space") || first == null ? space(root.get("anchor_space")) : first.realm();
        BlockPos cell = root.has("anchor") || first == null ? root.get("anchor").pos() : first.cell();
        WorldPos anchor = new WorldPos(realm, cell);
        Field ui = root.get("ui", Map.of());
        List<Field> projections = root.get("projections", List.of()).list(Function.identity());
        List<FacilityDefinition.Gate> gates = new ArrayList<>();
        List<FacilityDefinition.Ride> rides = new ArrayList<>();
        List<FacilityDefinition.Lane> lanes = new ArrayList<>();
        List<MovingKeepout> keepouts = new ArrayList<>();
        for (Field projection : projections) {
            if (only(projection, "lane") && projection.has("lane")) {
                lanes.add(lane(projection.get("lane")));
            } else if (only(projection, "ride") && projection.has("ride")) {
                rides.add(ride(projection.get("ride")));
            } else if (only(projection, "keepout") && projection.has("keepout")) {
                Field keepout = projection.get("keepout");
                if (keepout.has("source") && only(keepout, "source")) {
                    keepouts.add(new MovingKeepout(List.of(), 0, Optional.of(keepout.get("source").string())));
                } else if (only(keepout, "while_moving", "margin")) {
                    keepouts.add(new MovingKeepout(keepout.get("while_moving").list(Field::string),
                        keepout.get("margin", 0).number(), Optional.empty()));
                } else {
                    throw keepout.invalid();
                }
            } else {
                gates.add(gate(projection));
            }
        }
        return new FacilityDefinition(id, anchor,
            root.get("controls", Map.of()).map(DefinitionCodec::placed),
            root.get("routines", Map.of()).map(DefinitionRoutineCodec::routine),
            root.get("parameters", Map.of()).map(DefinitionCodec::value),
            root.get("readings", Map.of()).map(DefinitionCodec::probe),
            root.get("name").string(), extent,
            new Card(ui.get("card", List.of()).list(DefinitionCardCodec::element),
                ui.get("overlay", List.of()).list(DefinitionCardCodec::overlay)),
            gates,
            root.get("triggers", Map.of()).map(DefinitionTriggerCodec::trigger),
            root.has("workshop") ? Optional.of(workshop(root.get("workshop"))) : Optional.empty(),
            lanes, keepouts, root.get("posts", Map.of()).map(DefinitionCodec::post), rides);
    }

    private static Map<String, Object> post(PostDefinition post) {
        Map<String, Object> stance = switch (post.stance()) {
            case PostDefinition.Footing footing -> Map.of("footing", pos(footing.offset()));
            case PostDefinition.SeatBlock seat -> Map.of("seat_block", pos(seat.offset()));
            case PostDefinition.SeatEntity seat -> Map.of("seat_entity", seat.entity().toString(), "seat", seat.seat());
        };
        return Map.of("stance", stance, "controls", post.controls().stream().sorted().toList(),
            "staffing", post.staffing() == Post.Staffing.STANDING ? "standing" : "on_demand");
    }

    private static PostDefinition post(Field field) {
        Field stance = field.get("stance");
        PostDefinition.Place place;
        if (stance.object().keySet().equals(Set.of("footing"))) {
            place = new PostDefinition.Footing(stance.get("footing").pos());
        } else if (stance.object().keySet().equals(Set.of("seat_block"))) {
            place = new PostDefinition.SeatBlock(stance.get("seat_block").pos());
        } else if (stance.object().keySet().equals(Set.of("seat_entity"))
                || stance.object().keySet().equals(Set.of("seat_entity", "seat"))) {
            Field index = stance.get("seat", 0);
            if (index.integer() < 0) {
                throw index.invalid();
            }
            place = new PostDefinition.SeatEntity(stance.get("seat_entity").uuid(), index.integer());
        } else {
            throw stance.invalid();
        }
        Field staffing = field.get("staffing", "standing");
        return new PostDefinition(place, Set.copyOf(field.get("controls", List.of()).list(Field::string)),
            switch (staffing.string()) {
                case "standing" -> Post.Staffing.STANDING;
                case "on_demand" -> Post.Staffing.ON_DEMAND;
                default -> throw staffing.invalid();
            });
    }

    private static Map<String, Object> placed(FacilityDefinition.Placed part) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of("offset", pos(part.offset()), "part", part.part()));
        part.entity().ifPresent(entity -> data.put("entity", entity.toString()));
        part.carrier().ifPresent(carrier -> {
            data.put("carried_by", carrier.part());
            var scale = carrier.perUnit();
            data.put("per_unit", List.of(scale.x, scale.y, scale.z));
        });
        return data;
    }

    private static FacilityDefinition.Placed placed(Field part) {
        return new FacilityDefinition.Placed(part.get("offset", List.of(0, 0, 0)).pos(), part.get("part").string(),
            part.has("carried_by") ? Optional.of(new FacilityDefinition.Carrier(part.get("carried_by").string(),
                point(part.get("per_unit")))) : Optional.empty(),
            part.has("entity") ? Optional.of(part.get("entity").uuid()) : Optional.empty());
    }

    private static Map<String, Object> lane(FacilityDefinition.Lane lane) {
        Map<String, Object> data = new LinkedHashMap<>(Map.of(
            "from", lane.from(), "to", lane.to(), "latency", lane.latency()));
        if (lane.automatic()) {
            data.put("automatic", true);
        }
        if (!lane.items().isEmpty()) {
            data.put("items", lane.items().stream().map(ResourceLocation::toString).toList());
        }
        lane.rate().ifPresent(rate -> data.put("rate", Map.of("ticks", rate.ticks(), "count", rate.count())));
        return Map.copyOf(data);
    }

    private static FacilityDefinition.Lane lane(Field field) {
        if (!only(field, "from", "to", "automatic", "items", "latency", "rate")) {
            throw field.invalid();
        }
        Optional<Contribution.Lane.Rate> rate = Optional.empty();
        if (field.has("rate")) {
            Field value = field.get("rate");
            if (!only(value, "ticks", "count")) {
                throw value.invalid();
            }
            rate = Optional.of(new Contribution.Lane.Rate(positive(value.get("ticks")), positive(value.get("count"))));
        }
        return new FacilityDefinition.Lane(field.get("from").string(), field.get("to").string(),
            field.get("automatic", false).bool(), field.get("items", List.of()).list(DefinitionCodec::itemId),
            positive(field.get("latency")), rate);
    }

    private static ResourceLocation itemId(Field field) {
        ResourceLocation id = field.id();
        var registry = net.minecraft.core.registries.BuiltInRegistries.ITEM;
        if (!registry.containsKey(id) || id.equals(registry.getDefaultKey())) {
            throw field.invalid();
        }
        return id;
    }

    private static Map<String, Object> probe(FacilityDefinition.Probed probe) {
        if (probe.source().isPresent()) {
            return Map.of("source", probe.source().get());
        }
        Map<String, Object> data = new LinkedHashMap<>(mapped(probe.config(), DefinitionCodec::value));
        data.put("probe", probe.probe().toString());
        data.put("offset", pos(probe.offset()));
        return Map.copyOf(data);
    }

    private static FacilityDefinition.Probed probe(Field field) {
        if (field.has("source")) {
            if (!only(field, "source")) {
                throw field.invalid();
            }
            return new FacilityDefinition.Probed(Routines.SCRIPT, BlockPos.ZERO, Map.of(),
                Optional.of(field.get("source").string()));
        }
        Map<String, Value> config = new LinkedHashMap<>();
        for (Object key : field.object().keySet()) {
            if (!(key instanceof String name) || name.isBlank()) {
                throw field.invalid();
            }
            if (!name.equals("probe") && !name.equals("offset")) {
                config.put(name, value(field.get(name)));
            }
        }
        return new FacilityDefinition.Probed(field.get("probe").id(), field.get("offset", List.of(0, 0, 0)).pos(), config);
    }

    private static Map<String, Object> workshop(FacilityWorkshop workshop) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("routine", workshop.trigger().routine());
        if (!workshop.explicit().isEmpty()) {
            data.put("recipes", workshop.explicit().stream().map(recipe -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("inputs", recipe.inputs().stream().map(DefinitionCodec::item).toList());
                row.put("outputs", recipe.outputs().stream().map(DefinitionCodec::item).toList());
                row.put("ticks", recipe.ticks());
                recipe.id().ifPresent(id -> row.put("id", id.toString()));
                if (recipe.scale() > 1) {
                    row.put("scale", recipe.scale());
                }
                return row;
            }).toList());
        }
        return data;
    }

    private static Map<String, Object> item(FacilityWorkshop.Item item) {
        return Map.of("item", item.item(), "count", item.count());
    }

    private static FacilityWorkshop workshop(Field field) {
        Trigger.Demand trigger = new Trigger.Demand(field.get("routine").string());
        Field recipes = field.get("recipes", List.of());
        return new FacilityWorkshop(trigger, recipes.list(row -> recipe(row, Map.of())));
    }

    static List<FacilityWorkshop.Recipe> scriptRecipes(Object data, Map<String, ItemSpec> nativeItems) {
        return new Field("recipes", data).list(row -> recipe(row, nativeItems));
    }

    static List<io.github.izakyl.folkways.core.work.api.Amount> scriptItems(Object data) {
        return new Field("items", data).list(row -> {
            var spec = row.has("items")
                ? io.github.izakyl.folkways.core.item.api.ItemSpec.anyOf(
                    row.get("items").list(each -> FacilityWorkshop.spec(each.string(), true)))
                : FacilityWorkshop.spec(row.get("item").string(), true);
            if (row.has("components")) {
                spec = spec.withComponents(components(row.get("components")));
            }
            return new io.github.izakyl.folkways.core.work.api.Amount(spec, positive(row.get("count")));
        });
    }

    /** A component map such as {@code {"createbigcannons:power": 4}}, as an item's data components are written. */
    static net.minecraft.core.component.DataComponentMap components(Field field) {
        var json = GSON_TREE.toJsonTree(field.object());
        var ops = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(
            net.minecraft.core.registries.BuiltInRegistries.REGISTRY).createSerializationContext(
            com.mojang.serialization.JsonOps.INSTANCE);
        return net.minecraft.core.component.DataComponentMap.CODEC.parse(ops, json).result()
            .filter(map -> !map.isEmpty()).orElseThrow(field::invalid);
    }

    private static final com.google.gson.Gson GSON_TREE = new com.google.gson.Gson();

    private static FacilityWorkshop.Recipe recipe(Field row, Map<String, ItemSpec> nativeItems) {
        var outputs = items(row.get("outputs"), false, nativeItems);
        if (outputs.isEmpty()) {
            throw row.get("outputs").invalid();
        }
        return new FacilityWorkshop.Recipe(items(row.get("inputs"), true, nativeItems), outputs, positive(row.get("ticks")),
            row.has("id") ? Optional.of(row.get("id").id()) : Optional.empty(),
            row.has("scale") ? positive(row.get("scale")) : 1);
    }

    private static List<FacilityWorkshop.Item> items(Field field, boolean tags, Map<String, ItemSpec> nativeItems) {
        return field.list(item -> {
            String spec = item.get("item").string();
            try {
                FacilityWorkshop.spec(spec, tags, nativeItems);
            } catch (IllegalArgumentException invalid) {
                throw item.get("item").invalid();
            }
            return new FacilityWorkshop.Item(spec, positive(item.get("count")));
        });
    }

    private static int positive(Field field) {
        int count = field.integer();
        if (count < 1) {
            throw field.invalid();
        }
        return count;
    }

    static Map<String, Value> predicate(Field field) {
        if (field.value() instanceof String source) {
            return Map.of("", new Value.Text(source));
        }
        return field.map(DefinitionCodec::value);
    }

    static Object predicateData(Map<String, Value> values) {
        return FacilitySamples.predicate(values).<Object>map(source -> source.text())
            .orElseGet(() -> mapped(values, DefinitionCodec::value));
    }

    static boolean only(Field field, String... keys) {
        return Set.of(keys).containsAll(field.object().keySet());
    }

    private static Map<String, Object> gate(FacilityDefinition.Gate gate) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cells", gate.cells().stream().map(DefinitionCodec::pos).toList());
        data.put("open_when", predicateData(gate.openWhen()));
        gate.axes().ifPresent(axes -> data.put("axes", axes.stream().sorted().toList()));
        return Map.copyOf(data);
    }

    private static FacilityDefinition.Gate gate(Field field) {
        Field gate = field.get("gate");
        if (!only(gate, "cells", "open_when", "axes")) {
            throw gate.invalid();
        }
        Optional<Set<String>> axes = gate.has("axes")
            ? Optional.of(Set.copyOf(gate.get("axes").list(Field::string))) : Optional.empty();
        return new FacilityDefinition.Gate(gate.get("cells").list(Field::pos), predicate(gate.get("open_when")),
            axes);
    }

    private static Map<String, Object> ride(FacilityDefinition.Ride ride) {
        return Map.of("stops", mapped(ride.stops(), cells -> Map.of("cells",
                cells.stream().map(DefinitionCodec::pos).toList())),
            "routine", ride.routine(), "ticks", ride.ticks(), "capacity", ride.capacity(),
            "available_when", predicateData(ride.availableWhen()));
    }

    private static FacilityDefinition.Ride ride(Field field) {
        if (!only(field, "stops", "routine", "ticks", "capacity", "available_when")) {
            throw field.invalid();
        }
        return new FacilityDefinition.Ride(field.get("stops").map(stop -> {
            if (!only(stop, "cells")) {
                throw stop.invalid();
            }
            return stop.get("cells").list(Field::pos);
        }), field.get("routine").string(), field.get("ticks").integer(), field.get("capacity").integer(),
            predicate(field.get("available_when", Map.of())));
    }

    static CompoundTag save(FacilityDefinition facility) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", facility.id());
        tag.put("data", encode(facility.data()));
        return tag;
    }

    static FacilityDefinition load(CompoundTag tag) {
        if (!tag.hasUUID("id")) {
            return null;
        }
        return read(tag.getUUID("id"), new Field("", decode(tag.getCompound("data"))).map(Field::value));
    }

    private static Tag encode(Object value) {
        return switch (value) {
            case Map<?, ?> map -> {
                CompoundTag tag = new CompoundTag();
                map.forEach((key, item) -> tag.put((String) key, encode(item)));
                yield tag;
            }
            case List<?> list -> {
                ListTag tags = new ListTag();
                for (Object item : list) {
                    CompoundTag entry = new CompoundTag();
                    entry.put("value", encode(item));
                    tags.add(entry);
                }
                yield tags;
            }
            case Boolean bool -> ByteTag.valueOf(bool);
            case Integer number -> IntTag.valueOf(number);
            case Long number -> LongTag.valueOf(number);
            case Number number -> DoubleTag.valueOf(number.doubleValue());
            case String string -> StringTag.valueOf(string);
            case null, default -> throw new IllegalArgumentException("data");
        };
    }

    private static Object decode(Tag tag) {
        return switch (tag) {
            case CompoundTag map -> {
                Map<String, Object> result = new LinkedHashMap<>();
                map.getAllKeys().forEach(key -> result.put(key, decode(map.get(key))));
                yield result;
            }
            case ListTag list -> list.stream().map(item -> decode(((CompoundTag) item).get("value"))).toList();
            case ByteTag bool -> bool.getAsByte() != 0;
            case IntTag number -> number.getAsInt();
            case LongTag number -> number.getAsLong();
            case NumericTag number -> number.getAsDouble();
            case StringTag string -> string.getAsString();
            case null, default -> throw new IllegalArgumentException("data");
        };
    }

    record Field(String path, Object value) {
        IllegalArgumentException invalid() {
            return new IllegalArgumentException(path.isEmpty() ? "facility" : path);
        }

        Map<?, ?> object() {
            if (value instanceof Map<?, ?> object) {
                return object;
            }
            throw invalid();
        }

        boolean has(String key) {
            return object().containsKey(key);
        }

        Field get(String key) {
            return new Field(path.isEmpty() ? key : path + "." + key, object().get(key));
        }

        Field get(String key, Object fallback) {
            return has(key) ? get(key) : new Field(get(key).path, fallback);
        }

        String string() {
            if (value instanceof String string) {
                return string;
            }
            throw invalid();
        }

        ResourceLocation id() {
            ResourceLocation id = ResourceLocation.tryParse(string());
            if (id == null) {
                throw invalid();
            }
            return id;
        }

        UUID uuid() {
            try {
                return UUID.fromString(string());
            } catch (IllegalArgumentException exception) {
                throw invalid();
            }
        }

        boolean bool() {
            if (value instanceof Boolean bool) {
                return bool;
            }
            throw invalid();
        }

        double number() {
            if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
                return number.doubleValue();
            }
            throw invalid();
        }

        int integer() {
            double number = number();
            if ((int) number == number) {
                return (int) number;
            }
            throw invalid();
        }

        BlockPos pos() {
            List<Integer> coords = list(Field::integer);
            if (coords.size() != 3) {
                throw invalid();
            }
            return new BlockPos(coords.get(0), coords.get(1), coords.get(2));
        }

        <T> Map<String, T> map(Function<Field, T> convert) {
            Map<String, T> result = new LinkedHashMap<>();
            for (Object key : object().keySet()) {
                if (!(key instanceof String name) || name.isBlank()) {
                    throw invalid();
                }
                result.put(name, convert.apply(get(name)));
            }
            return result;
        }

        <T> List<T> list(Function<Field, T> convert) {
            if (!(value instanceof List<?> list)) {
                throw invalid();
            }
            return java.util.stream.IntStream.range(0, list.size())
                .mapToObj(index -> convert.apply(new Field(path + "[" + index + "]", list.get(index)))).toList();
        }
    }
}
