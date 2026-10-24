package io.github.izakyl.folkways.plugins.person.look;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns a maid model as Touhou Little Maid keeps it into the geometry and animation files GeckoLib reads. A
// resident only stands, walks and works, so the bones that mod shows on some other occasion (armour worn, a task
// taken up, a weapon drawn, eyes shut) are left out rather than drawn all at once.
final class MaidAssets {
    private static final String GEOMETRY = "minecraft:geometry";
    private static final String BONES = "bones";

    private static final String SCRIPTS = "touhou_little_maid:animation/maid/default/";
    private static final String DEFAULT_SCRIPT = "touhou_little_maid:animation/maid.default.js";
    private static final String HECATIA_SCRIPT = "touhou_little_maid:animation/special/hecatia_dimension.js";
    // What maid.default.js runs, of the scripts that show or hide a bone.
    private static final List<String> DEFAULT_SCRIPTS = List.of(SCRIPTS + "head/beg.js", SCRIPTS + "armor/default.js");
    private static final Map<String, List<String>> SHOWN_ON_OCCASION = Map.of(
        "head/beg.js", List.of("begShow"),
        "head/hurt.js", List.of("hurtBlink"),
        "sleep/default.js", List.of("sleepShow"),
        "status/backpack.js", List.of("backpackShow"),
        "status/sasimono.js", List.of("sasimonoShow"),
        "sit/skirt_hidden.js", List.of("_sittingHiddenSkirt"),
        "task/idle.js", List.of("idleHidden"),
        "armor/default.js", List.of("helmet", "chestPlate", "chestPlateLeft", "chestPlateMiddle", "chestPlateRight",
            "leggings", "leggingsLeft", "leggingsMiddle", "leggingsRight", "bootsLeft", "bootsRight"));
    // Hecatia as she is in the overworld.
    private static final List<String> HECATIA_ELSEWHERE = List.of("earthSideLeft", "earthSideRight", "moonHair",
        "logoMoon", "moonTop", "moonSideLeft", "otherHair", "logoOther", "otherTop", "otherSideRight");
    private static final String TASK_SCRIPTS = "task/";
    private static final String HEALTH_SCRIPT = "health/less_show.js";

    private static final Set<String> WORDS = Set.of("lerp_mode", "easing");

    private static final Pattern LAYER = Pattern.compile("(pre_)?parallel(\\d+)");
    private static final Pattern HELPER = Pattern.compile("gui|[mM]olang\\d*");

    private MaidAssets() {
    }

    // The model as GeckoLib's geometry, less the named bones and everything hung from them. Touhou Little Maid
    // reads two layouts: the legacy one keeps a lone geometry.* object where the current one keeps a list.
    static JsonObject geometry(JsonObject model, Set<String> hidden) {
        JsonObject geometry;
        if (model.get(GEOMETRY) instanceof JsonArray listed && !listed.isEmpty() && listed.get(0).isJsonObject()) {
            geometry = listed.get(0).getAsJsonObject().deepCopy();
        } else {
            geometry = fromLegacy(model);
        }
        if (geometry.get(BONES) instanceof JsonArray bones) {
            geometry.add(BONES, without(bones, hidden));
        }
        JsonArray geometries = new JsonArray();
        geometries.add(geometry);
        JsonObject written = new JsonObject();
        written.addProperty("format_version", "1.12.0");
        written.add(GEOMETRY, geometries);
        return written;
    }

    static List<String> boneNames(JsonObject model) {
        JsonObject geometry = geometry(model, Set.of()).getAsJsonArray(GEOMETRY).get(0).getAsJsonObject();
        List<String> names = new ArrayList<>();
        if (geometry.get(BONES) instanceof JsonArray bones) {
            for (JsonElement bone : bones) {
                names.add(nameOf(bone));
            }
        }
        return names;
    }

    private static JsonObject fromLegacy(JsonObject model) {
        JsonObject legacy = model.entrySet().stream()
            .filter(member -> member.getKey().startsWith("geometry.") && member.getValue().isJsonObject())
            .map(member -> member.getValue().getAsJsonObject())
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("the file holds no geometry"));
        JsonObject description = new JsonObject();
        description.addProperty("identifier", "geometry.maid");
        copy(legacy, "texturewidth", description, "texture_width");
        copy(legacy, "textureheight", description, "texture_height");
        for (String bound : List.of("visible_bounds_width", "visible_bounds_height", "visible_bounds_offset")) {
            copy(legacy, bound, description, bound);
        }
        JsonObject geometry = new JsonObject();
        geometry.add("description", description);
        geometry.add(BONES, legacy.get(BONES) instanceof JsonArray bones ? bones.deepCopy() : new JsonArray());
        return geometry;
    }

    private static void copy(JsonObject from, String key, JsonObject to, String as) {
        if (from.has(key)) {
            to.add(as, from.get(key));
        }
    }

    private static JsonArray without(JsonArray bones, Set<String> hidden) {
        Map<String, List<String>> children = new HashMap<>();
        for (JsonElement bone : bones) {
            String parent = textOf(bone, "parent");
            if (!parent.isEmpty()) {
                children.computeIfAbsent(parent, key -> new ArrayList<>()).add(nameOf(bone));
            }
        }
        Set<String> dropped = new HashSet<>();
        Deque<String> open = new ArrayDeque<>(hidden);
        while (!open.isEmpty()) {
            String bone = open.pop();
            if (dropped.add(bone)) {
                open.addAll(children.getOrDefault(bone, List.of()));
            }
        }
        JsonArray kept = new JsonArray();
        for (JsonElement bone : bones) {
            if (!dropped.contains(nameOf(bone))) {
                kept.add(bone);
            }
        }
        return kept;
    }

    private static String nameOf(JsonElement bone) {
        return textOf(bone, "name");
    }

    private static String textOf(JsonElement bone, String key) {
        JsonElement value = bone.isJsonObject() ? bone.getAsJsonObject().get(key) : null;
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    // The bones a scripted maid's scripts keep hidden on a maid that is awake, unhurt, unarmoured and idle.
    static Set<String> hiddenByScripts(List<String> scripts, List<String> bones) {
        Set<String> running = new HashSet<>(scripts.isEmpty() ? List.of(DEFAULT_SCRIPT) : scripts);
        if (running.contains(DEFAULT_SCRIPT)) {
            running.addAll(DEFAULT_SCRIPTS);
        }
        Set<String> hidden = new HashSet<>();
        for (String script : running) {
            if (script.equals(HECATIA_SCRIPT)) {
                hidden.addAll(HECATIA_ELSEWHERE);
            }
            if (!script.startsWith(SCRIPTS)) {
                continue;
            }
            String which = script.substring(SCRIPTS.length());
            if (SHOWN_ON_OCCASION.containsKey(which)) {
                hidden.addAll(SHOWN_ON_OCCASION.get(which));
            } else if (which.startsWith(TASK_SCRIPTS) && which.endsWith(".js")) {
                hidden.add(camel(which.substring(TASK_SCRIPTS.length(), which.length() - ".js".length())) + "Show");
            } else if (which.equals(HEALTH_SCRIPT)) {
                bones.stream().filter(bone -> bone.startsWith("healthLess") && bone.endsWith("Show"))
                    .forEach(hidden::add);
            }
        }
        return hidden;
    }

    private static String camel(String snake) {
        StringBuilder camel = new StringBuilder();
        for (String word : snake.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            camel.append(camel.isEmpty() ? word.charAt(0) : Character.toUpperCase(word.charAt(0)))
                .append(word.substring(1));
        }
        return camel.toString();
    }

    // The bones a GeckoLib maid's standing layers scale away to nothing at rest, plus the ones that only carry
    // values for its animations. Of the layers (pre_parallelN, then parallelN) the last to scale a bone decides.
    static Set<String> hiddenByLayers(JsonObject animations, List<String> bones) {
        List<String> layers = animations.keySet().stream()
            .filter(name -> LAYER.matcher(name).matches())
            .sorted(Comparator.comparing((String name) -> !name.startsWith("pre_")).thenComparingInt(MaidAssets::layerOf))
            .toList();
        Map<String, Boolean> last = new LinkedHashMap<>();
        for (String layer : layers) {
            if (!(animations.get(layer) instanceof JsonObject animation)
                    || !(animation.get(BONES) instanceof JsonObject moved)) {
                continue;
            }
            for (Map.Entry<String, JsonElement> bone : moved.entrySet()) {
                if (bone.getValue() instanceof JsonObject channels && channels.has("scale")) {
                    last.put(bone.getKey(), vanishes(channels.get("scale")));
                }
            }
        }
        Set<String> hidden = new HashSet<>();
        last.forEach((bone, gone) -> {
            if (gone) {
                hidden.add(bone);
            }
        });
        bones.stream().filter(bone -> HELPER.matcher(bone).matches()).forEach(hidden::add);
        return hidden;
    }

    private static int layerOf(String name) {
        Matcher matcher = LAYER.matcher(name);
        return matcher.matches() ? Integer.parseInt(matcher.group(2)) : 0;
    }

    // A scale channel is a value, three of them, or keyframes of either; the first keyframe is the resting one.
    private static boolean vanishes(JsonElement scale) {
        JsonElement resting = scale;
        if (scale instanceof JsonObject keyframes) {
            String first = null;
            for (String time : keyframes.keySet()) {
                try {
                    if (first == null || Double.parseDouble(time) < Double.parseDouble(first)) {
                        first = time;
                    }
                } catch (NumberFormatException exception) {
                    return false;
                }
            }
            if (first == null) {
                return false;
            }
            resting = keyframes.get(first);
            if (resting instanceof JsonObject eased) {
                resting = eased.has("post") ? eased.get("post") : eased.has("pre") ? eased.get("pre") : eased.get("vector");
            }
        }
        if (resting == null) {
            return false;
        }
        List<JsonElement> parts = new ArrayList<>();
        if (resting instanceof JsonArray axes) {
            axes.forEach(parts::add);
        } else {
            parts.add(resting);
        }
        boolean zero = false;
        for (JsonElement part : parts) {
            if (!part.isJsonPrimitive()) {
                return false;
            }
            OptionalDouble value = part.getAsJsonPrimitive().isNumber()
                ? OptionalDouble.of(part.getAsDouble())
                : Molang.atRest(part.getAsString());
            if (value.isEmpty()) {
                return false;
            }
            zero |= value.getAsDouble() == 0.0;
        }
        return zero;
    }

    // Animation files laid over one another the way Touhou Little Maid does: a later file's animation replaces
    // an earlier one of the same name.
    static JsonObject merged(List<JsonObject> files) {
        JsonObject merged = new JsonObject();
        for (JsonObject file : files) {
            if (file.get("animations") instanceof JsonObject animations) {
                animations.entrySet().forEach(animation -> merged.add(animation.getKey(), animation.getValue()));
            }
        }
        return merged;
    }

    // An animation with the expressions its bones move by rewritten for GeckoLib; the words beside them that
    // name an easing are no expressions and stay.
    private static JsonElement portable(JsonElement authored, boolean moving) {
        if (authored instanceof JsonObject object) {
            JsonObject portable = new JsonObject();
            object.entrySet().forEach(member -> portable.add(member.getKey(), WORDS.contains(member.getKey())
                ? member.getValue()
                : portable(member.getValue(), moving || member.getKey().equals(BONES))));
            return portable;
        }
        if (authored instanceof JsonArray array) {
            JsonArray portable = new JsonArray();
            array.forEach(element -> portable.add(portable(element, moving)));
            return portable;
        }
        return moving && authored instanceof JsonPrimitive value && value.isString()
            ? new JsonPrimitive(Molang.portable(value.getAsString()))
            : authored;
    }

    // The animation file of one GeckoLib maid: the few motions a resident plays, each under the maid's own name.
    static JsonObject animations(MaidLooks.Maid maid, JsonObject merged) {
        JsonObject animations = new JsonObject();
        for (String name : MaidLooks.PLAYED) {
            if (merged.get(name) instanceof JsonObject animation) {
                animations.add(maid.animation(name), portable(animation, false));
            } else if (List.of("idle", "walk", "swing_hand").contains(name)) {
                animations.add(maid.animation(name), new JsonObject());
            }
        }
        for (String motion : List.of("dining", "drinking")) {
            String use = motion.equals("dining") ? "use_mainhand:eat" : "use_mainhand:drink";
            if (merged.get(use) instanceof JsonObject gesture) {
                JsonObject seated = seatedGesture(merged.get("chair") instanceof JsonObject chair ? chair : new JsonObject(), gesture);
                animations.add(maid.animation(motion + "_seated"), portable(seated, false));
            }
        }
        JsonObject written = new JsonObject();
        written.addProperty("format_version", "1.8.0");
        written.add("animations", animations);
        return written;
    }

    static JsonObject seatedGesture(JsonObject chair, JsonObject gesture) {
        JsonObject seated = chair.deepCopy();
        // Item-use clips usually animate only the arm. Retain the authored chair's legs and
        // merge individual channels so a hand gesture does not discard its seated position.
        JsonObject bones = seated.get("bones") instanceof JsonObject existing ? existing : new JsonObject();
        if (gesture.get("bones") instanceof JsonObject arms) {
            arms.entrySet().forEach(bone -> {
                JsonObject channels = bones.get(bone.getKey()) instanceof JsonObject existing
                    ? existing.deepCopy() : new JsonObject();
                if (bone.getValue() instanceof JsonObject added) {
                    added.entrySet().forEach(channel -> channels.add(channel.getKey(), channel.getValue()));
                }
                bones.add(bone.getKey(), channels);
            });
        }
        seated.add("bones", bones);
        seated.addProperty("loop", true);
        double chairLength = seated.has("animation_length") ? seated.get("animation_length").getAsDouble() : 0;
        double useLength = gesture.has("animation_length") ? gesture.get("animation_length").getAsDouble() : 0;
        seated.addProperty("animation_length", Math.max(chairLength, useLength));
        return seated;
    }

}
