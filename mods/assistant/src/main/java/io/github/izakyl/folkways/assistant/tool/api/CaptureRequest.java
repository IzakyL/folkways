package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One screenshot the client takes after a successful run. A framed shot carries the box it looks at and the side it
 * looks from; the client places its camera outside that box once it knows the window's shape.
 */
public record CaptureRequest(Vec3 position, Vec3 lookAt, Float yaw, Float pitch, float fov, AABB subject,
        String view, int size) {

    /** Images one run may attach; four sides and a top view fit with one to spare. */
    public static final int MAX_PER_RUN = 6;

    /** Longest side of each image when a run attaches at most {@link #FULL_SIZE_IMAGES}. */
    public static final int FULL_SIZE = 1024;

    /** Longest side of each image in a larger set, which keeps a full set near the cost of two full shots. */
    public static final int SET_SIZE = 768;

    public static final int FULL_SIZE_IMAGES = 2;

    /** The {@code around} value that frames everything the same run staged. */
    public static final String CHANGES = "changes";

    public static final double SIDE_ELEVATION = 30;

    private static final Set<String> SINGLE = Set.of("position", "look_at", "yaw", "pitch", "fov");

    private static final Set<String> FRAMED = Set.of("around", "views", "fov");

    // Sides name where the camera stands: "north" stands north of the subject and looks south.
    private static final Map<String, double[]> SIDES = Map.of(
            "north", new double[] {0, -1}, "east", new double[] {1, 0},
            "south", new double[] {0, 1}, "west", new double[] {-1, 0},
            "northeast", new double[] {1, -1}, "southeast", new double[] {1, 1},
            "southwest", new double[] {-1, 1}, "northwest", new double[] {-1, -1},
            "top", new double[] {0, 0});

    /** Views a count selects, in order: the four diagonals, then the top. */
    private static final List<String> COUNTED = List.of("southeast", "southwest", "northwest", "northeast", "top");

    private static final int DEFAULT_VIEWS = 4;

    public CaptureRequest(Vec3 position, Vec3 lookAt, Float yaw, Float pitch, float fov) {
        this(position, lookAt, yaw, pitch, fov, null, null, FULL_SIZE);
    }

    public CaptureRequest sized(int longest) {
        return new CaptureRequest(position, lookAt, yaw, pitch, fov, subject, view, longest);
    }

    /** Reads a single-camera request. */
    public static CaptureRequest read(JsonObject arguments) {
        if (!SINGLE.containsAll(arguments.keySet())) {
            var unknown = arguments.keySet().stream().filter(key -> !SINGLE.contains(key)).sorted().toList();
            throw new IllegalArgumentException("Unknown camera parameter " + String.join(", ", unknown)
                    + ": one camera takes position, look_at, yaw, pitch and fov; views needs around");
        }
        Vec3 position = vector(arguments, "position");
        Vec3 lookAt = vector(arguments, "look_at");
        Float yaw = number(arguments, "yaw");
        Float pitch = number(arguments, "pitch");
        float fov = fov(arguments);
        if (pitch != null && Math.abs(pitch) > 90) {
            throw new IllegalArgumentException("Pitch must be within -90 to 90 and FOV within 30 to 110");
        }
        return new CaptureRequest(position, lookAt, yaw, pitch, fov);
    }

    /** Checks one capture() call and returns how many images it takes. */
    public static int images(JsonObject arguments) {
        if (!arguments.has("around")) {
            read(arguments);
            return 1;
        }
        if (!FRAMED.containsAll(arguments.keySet())) {
            throw new IllegalArgumentException("around takes only views and fov");
        }
        fov(arguments);
        if (!isNamed(arguments.get("around"))) {
            box(arguments.get("around"));
        }
        return views(arguments).size();
    }

    /**
     * Expands one capture() call into its shots. {@code changes} is the box of what the run staged, or null when it
     * staged nothing to frame.
     */
    public static List<CaptureRequest> expand(JsonObject arguments, AABB changes) {
        if (!arguments.has("around")) {
            return List.of(read(arguments));
        }
        images(arguments);
        AABB subject = isNamed(arguments.get("around")) ? changes : box(arguments.get("around"));
        if (subject == null) {
            throw new IllegalStateException("nothing_to_frame");
        }
        float fov = fov(arguments);
        return views(arguments).stream()
                .map(view -> new CaptureRequest(null, null, null, null, fov, subject, view, FULL_SIZE)).toList();
    }

    /**
     * Places a framed shot's camera outside its subject, looking at the centre from its side, far enough back that
     * the whole box fits a window of this aspect. Side views stand {@code elevation} degrees up; the top view looks
     * straight down with north at the top of the image.
     */
    public CaptureRequest framed(double aspect, double elevation) {
        if (subject == null) {
            return this;
        }
        Vec3 centre = subject.getCenter();
        double[] side = SIDES.get(view);
        boolean top = view.equals("top");
        double flat = Math.hypot(side[0], side[1]);
        double up = Math.toRadians(top ? 90 : elevation);
        Vec3 out = top ? new Vec3(0, 1, 0) : new Vec3(side[0] / flat * Math.cos(up), Math.sin(up),
                side[1] / flat * Math.cos(up));
        Vec3 right = top ? new Vec3(1, 0, 0) : new Vec3(-out.z, 0, out.x).normalize();
        Vec3 screenUp = top ? new Vec3(0, 0, -1) : right.cross(out).normalize();
        double tanHigh = Math.tan(Math.toRadians(fov) / 2) * 0.9;
        double tanWide = tanHigh * Math.max(0.25, aspect);
        double distance = 0;
        double nearest = 0;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 q = new Vec3((corner & 1) == 0 ? subject.minX : subject.maxX,
                    (corner & 2) == 0 ? subject.minY : subject.maxY,
                    (corner & 4) == 0 ? subject.minZ : subject.maxZ).subtract(centre);
            double along = q.dot(out);
            nearest = Math.max(nearest, along);
            distance = Math.max(distance, along + Math.max(Math.abs(q.dot(screenUp)) / tanHigh,
                    Math.abs(q.dot(right)) / tanWide));
        }
        distance = Math.max(distance, nearest + 2);
        Vec3 position = centre.add(out.scale(distance));
        return top ? new CaptureRequest(position, null, 180F, 90F, fov, subject, view, size)
                : new CaptureRequest(position, centre, null, null, fov, subject, view, size);
    }

    public CaptureRequest framed(double aspect) {
        return framed(aspect, SIDE_ELEVATION);
    }

    /** The box around everything a run staged: the ghosts it drew and the places its changes name. */
    public static AABB changes(List<BlockPos> cells, JsonArray intents) {
        List<int[]> points = new ArrayList<>();
        for (BlockPos cell : cells) {
            points.add(new int[] {cell.getX(), cell.getY(), cell.getZ()});
        }
        if (intents != null) {
            for (JsonElement entry : intents) {
                JsonObject intent = entry.getAsJsonObject();
                if (!intent.has("place")) {
                    continue;
                }
                JsonObject place = intent.getAsJsonObject("place");
                for (String key : List.of("box", "points")) {
                    if (place.has(key)) {
                        for (JsonElement point : place.getAsJsonArray(key)) {
                            JsonArray xyz = point.getAsJsonArray();
                            points.add(new int[] {xyz.get(0).getAsInt(), xyz.get(1).getAsInt(),
                                    xyz.get(2).getAsInt()});
                        }
                    }
                }
            }
        }
        if (points.isEmpty()) {
            return null;
        }
        int[] min = points.getFirst().clone();
        int[] max = points.getFirst().clone();
        for (int[] point : points) {
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], point[axis]);
                max[axis] = Math.max(max[axis], point[axis]);
            }
        }
        return new AABB(min[0], min[1], min[2], max[0] + 1, max[1] + 1, max[2] + 1);
    }

    private static boolean isNamed(JsonElement around) {
        return around.isJsonPrimitive() && around.getAsJsonPrimitive().isString();
    }

    private static List<String> views(JsonObject arguments) {
        if (isNamed(arguments.get("around")) && !arguments.get("around").getAsString().equals(CHANGES)) {
            throw new IllegalArgumentException("around must be \"changes\", an area or two corners");
        }
        if (!arguments.has("views")) {
            return COUNTED.subList(0, DEFAULT_VIEWS);
        }
        JsonElement views = arguments.get("views");
        if (views.isJsonPrimitive() && views.getAsJsonPrimitive().isNumber()) {
            double count = views.getAsDouble();
            if (count != Math.rint(count) || count < 1 || count > COUNTED.size()) {
                throw new IllegalArgumentException("views count must be 1 to " + COUNTED.size());
            }
            return COUNTED.subList(0, (int) count);
        }
        if (!views.isJsonArray() || views.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("views must be a list of sides or a count");
        }
        Set<String> sides = new LinkedHashSet<>();
        for (JsonElement side : views.getAsJsonArray()) {
            if (!side.isJsonPrimitive() || !SIDES.containsKey(side.getAsString()) || !sides.add(side.getAsString())) {
                throw new IllegalArgumentException("views must be distinct sides: north, east, south, west, "
                        + "northeast, southeast, southwest, northwest or top");
            }
        }
        return List.copyOf(sides);
    }

    // An area {from, to} with XYZ dicts or lists, or a list of two XYZ corners; both corners are inclusive cells.
    private static AABB box(JsonElement around) {
        JsonElement from;
        JsonElement to;
        if (around.isJsonObject() && around.getAsJsonObject().has("from") && around.getAsJsonObject().has("to")) {
            from = around.getAsJsonObject().get("from");
            to = around.getAsJsonObject().get("to");
        } else if (around.isJsonArray() && around.getAsJsonArray().size() == 2) {
            from = around.getAsJsonArray().get(0);
            to = around.getAsJsonArray().get(1);
        } else {
            throw new IllegalArgumentException("around must be \"changes\", an area or two corners");
        }
        Vec3 a = corner(from);
        Vec3 b = corner(to);
        AABB box = new AABB(Math.floor(Math.min(a.x, b.x)), Math.floor(Math.min(a.y, b.y)),
                Math.floor(Math.min(a.z, b.z)), Math.floor(Math.max(a.x, b.x)) + 1,
                Math.floor(Math.max(a.y, b.y)) + 1, Math.floor(Math.max(a.z, b.z)) + 1);
        if (Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) > 256) {
            throw new IllegalArgumentException("around spans more than 256 blocks");
        }
        return box;
    }

    // XYZ dicts may carry the dimension a position read returns; the camera stays in the player's dimension.
    private static Vec3 corner(JsonElement corner) {
        JsonArray array = new JsonArray();
        if (corner.isJsonObject()) {
            JsonObject xyz = corner.getAsJsonObject();
            for (String axis : List.of("x", "y", "z")) {
                if (!xyz.has(axis)) {
                    throw new IllegalArgumentException("Expected x, y and z");
                }
                array.add(xyz.get(axis));
            }
        } else if (corner.isJsonArray()) {
            array = corner.getAsJsonArray();
        } else {
            throw new IllegalArgumentException("Expected three coordinates");
        }
        JsonObject holder = new JsonObject();
        holder.add("corner", array);
        return vector(holder, "corner");
    }

    private static float fov(JsonObject arguments) {
        Float fov = number(arguments, "fov");
        if (fov != null && (fov < 30 || fov > 110)) {
            throw new IllegalArgumentException("Pitch must be within -90 to 90 and FOV within 30 to 110");
        }
        return fov == null ? 70 : fov;
    }

    private static Vec3 vector(JsonObject arguments, String name) {
        if (!arguments.has(name)) {
            return null;
        }
        JsonElement raw = arguments.get(name);
        if (raw.isJsonObject()) {
            return corner(raw);
        }
        if (!raw.isJsonArray()) {
            throw new IllegalArgumentException(name + " must be an XYZ list or {x, y, z}");
        }
        var array = raw.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException("Expected three coordinates");
        }
        for (var value : array) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                    || !Double.isFinite(value.getAsDouble())) {
                throw new IllegalArgumentException("Invalid coordinate");
            }
        }
        return new Vec3(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static Float number(JsonObject arguments, String name) {
        if (!arguments.has(name)) {
            return null;
        }
        var value = arguments.getAsJsonPrimitive(name);
        if (!value.isNumber() || !Float.isFinite(value.getAsFloat())) {
            throw new IllegalArgumentException("Invalid camera number");
        }
        return value.getAsFloat();
    }
}
