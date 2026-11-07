package io.github.izakyl.folkways.epicfight.client.geo;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Pure geometry conversion. Unsupported anatomy is rejected before a renderer adopts the mesh. */
public final class GeoMeshData {
    public static final int MAX_SOURCE_CHARS = 4 * 1024 * 1024;
    private static final int MAX_BONES = 1_024;
    private static final int MAX_DEPTH = 96;
    private static final int MAX_CUBES = 8_192;
    private static final int MAX_QUADS = 32_768;
    private static final float PIXEL = 1.0F / 16.0F;
    private static final float PIVOT_TOLERANCE = PIXEL + 0.00001F;

    public record Data(float[] positions, float[] normals, float[] uvs, int[] joints,
            Map<String, int[]> parts, int quads) {
    }

    private GeoMeshData() {
    }

    public static Data convert(String source, float scale) {
        if (source.length() > MAX_SOURCE_CHARS || !Float.isFinite(scale) || scale <= 0 || scale > 4) {
            throw new IllegalArgumentException("geometry size or scale is outside the supported range");
        }
        JsonObject root = JsonParser.parseString(source).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.size() != 1) {
            throw new IllegalArgumentException("one Bedrock GEO geometry is required");
        }
        validate(geometries.get(0).getAsJsonObject());
        BedrockGeometry model = BedrockGeometry.parse(source);
        if (model == null || model.bonesByName.isEmpty()) {
            throw new IllegalArgumentException("geometry has no reachable bones");
        }
        humanoid(model, scale);
        Builder builder = new Builder(model, scale);
        for (BedrockGeometry.Bone rootBone : model.topLevelBones) {
            builder.walk(rootBone, new Matrix4f(), 0);
        }
        Data data = builder.finish();
        proportions(data);
        return data;
    }

    private static void validate(JsonObject geometry) {
        JsonArray bones = geometry.getAsJsonArray("bones");
        if (bones == null || bones.isEmpty() || bones.size() > MAX_BONES) {
            throw new IllegalArgumentException("geometry bone count is outside the supported range");
        }
        Map<String, String> parents = new HashMap<>();
        int cubes = 0;
        for (JsonElement element : bones) {
            JsonObject bone = element.getAsJsonObject();
            String name = bone.get("name").getAsString();
            if (name.isEmpty() || parents.containsKey(name) || bone.has("poly_mesh")) {
                throw new IllegalArgumentException("duplicate, unnamed or polygon-mesh bone");
            }
            parents.put(name, bone.has("parent") ? bone.get("parent").getAsString() : "");
            if (bone.has("cubes")) {
                cubes += bone.getAsJsonArray("cubes").size();
            }
        }
        if (cubes == 0 || cubes > MAX_CUBES) {
            throw new IllegalArgumentException("geometry cube count is outside the supported range");
        }
        for (String name : parents.keySet()) {
            Set<String> seen = new HashSet<>();
            String at = name;
            while (!at.isEmpty()) {
                if (!seen.add(at) || seen.size() > MAX_DEPTH || !parents.containsKey(at)) {
                    throw new IllegalArgumentException("cyclic, missing or overly deep parent bone");
                }
                at = parents.get(at);
            }
        }
    }

    // These are the model-space bind pivots from EF 21.17.3.1's entity/biped.json, after its
    // Blender-to-Minecraft correction. The one-pixel tolerance also admits Steve/Alex GEO rigs.
    // This is deliberately not retargeting: a different rest skeleton must fall back to the skin.
    private static void humanoid(BedrockGeometry model, float scale) {
        Set<Integer> found = new HashSet<>();
        Set<String> alternateForms = BoneAlternateForms.baseFormsPresent(model.bonesByName.keySet().toArray(String[]::new));
        for (BedrockGeometry.Bone root : model.topLevelBones) {
            bindPivots(root, new Matrix4f(), scale, found, alternateForms);
        }
        if (!found.containsAll(Set.of(HumanoidJoints.ID_HEAD, HumanoidJoints.ID_ARM_L,
                HumanoidJoints.ID_ARM_R, HumanoidJoints.ID_THIGH_L, HumanoidJoints.ID_THIGH_R))) {
            throw new IllegalArgumentException("model is missing a canonical head, shoulder or hip bone");
        }
    }

    private static void bindPivots(BedrockGeometry.Bone bone, Matrix4f parent, float scale,
            Set<Integer> found, Set<String> alternateForms) {
        if (BoneAlternateForms.isAlternateForm(bone.name, alternateForms)) {
            return;
        }
        Matrix4f transform = bindTransform(bone, parent);
        if (GeoJointMapper.isDirectlyMapped(bone)) {
            int joint = GeoJointMapper.resolveJointId(bone);
            Vector3f expected = switch (joint) {
                case HumanoidJoints.ID_HEAD -> new Vector3f(0, 1.513972F, -0.000946F);
                case HumanoidJoints.ID_ARM_R -> new Vector3f(0.374994F, 1.393554F, -0.000946F);
                case HumanoidJoints.ID_ARM_L -> new Vector3f(-0.375006F, 1.393554F, -0.000946F);
                case HumanoidJoints.ID_THIGH_R -> new Vector3f(0.124989F, 0.761141F, -0.000958F);
                case HumanoidJoints.ID_THIGH_L -> new Vector3f(-0.125011F, 0.761141F, -0.000958F);
                default -> null;
            };
            if (expected != null) {
                Vector3f actual = new Vector3f(bone.pivotX, bone.pivotY, bone.pivotZ).mulPosition(parent).mul(scale);
                if (!actual.isFinite() || Math.abs(actual.x - expected.x) > PIVOT_TOLERANCE
                        || Math.abs(actual.y - expected.y) > PIVOT_TOLERANCE
                        || Math.abs(actual.z - expected.z) > PIVOT_TOLERANCE) {
                    throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "bone %s (%s) bind pivot [%.4f, %.4f, %.4f] differs from canonical BIPED by over one pixel",
                        bone.name, HumanoidJoints.nameOf(joint), actual.x, actual.y, actual.z));
                }
                if (!identityAxes(transform)) {
                    throw new IllegalArgumentException("bone " + bone.name + " has a rotated humanoid bind pose");
                }
                found.add(joint);
            }
        }
        for (BedrockGeometry.Bone child : bone.children) {
            bindPivots(child, transform, scale, found, alternateForms);
        }
    }

    private static Matrix4f bindTransform(BedrockGeometry.Bone bone, Matrix4f parent) {
        return new Matrix4f(parent).translate(bone.pivotX, bone.pivotY, bone.pivotZ)
            .rotateZ(bone.rotZ).rotateY(bone.rotY).rotateX(bone.rotX)
            .translate(-bone.pivotX, -bone.pivotY, -bone.pivotZ);
    }

    private static boolean identityAxes(Matrix4f matrix) {
        return Math.abs(matrix.m00() - 1) < 0.001F && Math.abs(matrix.m11() - 1) < 0.001F
            && Math.abs(matrix.m22() - 1) < 0.001F && Math.abs(matrix.m01()) < 0.001F
            && Math.abs(matrix.m02()) < 0.001F && Math.abs(matrix.m10()) < 0.001F
            && Math.abs(matrix.m12()) < 0.001F && Math.abs(matrix.m20()) < 0.001F
            && Math.abs(matrix.m21()) < 0.001F;
    }

    private static void proportions(Data data) {
        Bounds[] parts = {new Bounds(), new Bounds(), new Bounds(), new Bounds(), new Bounds()};
        for (int i = 0; i < data.joints.length; i++) {
            int part = switch (data.joints[i]) {
                case HumanoidJoints.ID_HEAD -> 0;
                case HumanoidJoints.ID_ARM_R, HumanoidJoints.ID_HAND_R,
                    HumanoidJoints.ID_TOOL_R, HumanoidJoints.ID_ELBOW_R -> 1;
                case HumanoidJoints.ID_ARM_L, HumanoidJoints.ID_HAND_L,
                    HumanoidJoints.ID_TOOL_L, HumanoidJoints.ID_ELBOW_L -> 2;
                case HumanoidJoints.ID_THIGH_R, HumanoidJoints.ID_LEG_R, HumanoidJoints.ID_KNEE_R -> 3;
                case HumanoidJoints.ID_THIGH_L, HumanoidJoints.ID_LEG_L, HumanoidJoints.ID_KNEE_L -> 4;
                default -> -1;
            };
            if (part >= 0) {
                parts[part].add(data.positions[i * 3], data.positions[i * 3 + 1], data.positions[i * 3 + 2]);
            }
        }
        Bounds head = parts[0];
        if (!head.dimensions(0.375F, 0.75F, 0.375F, 0.75F, 0.25F, 0.75F)
                || !head.centered(0, 0, PIXEL) || !head.heights(1.5F, 2, PIXEL * 2)) {
            throw new IllegalArgumentException("head geometry differs from canonical BIPED proportions");
        }
        for (int side = 0; side < 2; side++) {
            Bounds arm = parts[side + 1];
            if (!arm.dimensions(0.1875F, 0.375F, 0.625F, 0.875F, 0.1875F, 0.375F)
                    || !arm.centered(side == 0 ? 0.375F : -0.375F, 0, PIXEL)
                    || !arm.heights(0.75F, 1.5F, PIXEL * 2)) {
                throw new IllegalArgumentException((side == 0 ? "right" : "left")
                    + " arm geometry differs from canonical BIPED proportions");
            }
            Bounds leg = parts[side + 3];
            if (!leg.dimensions(0.1875F, 0.375F, 0.625F, 0.875F, 0.1875F, 0.4375F)
                    || !leg.centered(side == 0 ? 0.125F : -0.125F, 0, PIXEL)
                    || !leg.heights(0, 0.75F, PIXEL)) {
                throw new IllegalArgumentException((side == 0 ? "right" : "left")
                    + " leg geometry differs from canonical BIPED proportions");
            }
        }
    }

    private static final class Bounds {
        private final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        private final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);

        void add(float x, float y, float z) {
            min.min(new Vector3f(x, y, z));
            max.max(new Vector3f(x, y, z));
        }

        boolean dimensions(float minWidth, float maxWidth, float minHeight, float maxHeight,
                float minDepth, float maxDepth) {
            Vector3f size = new Vector3f(max).sub(min);
            return size.isFinite() && size.x >= minWidth && size.x <= maxWidth
                && size.y >= minHeight && size.y <= maxHeight && size.z >= minDepth && size.z <= maxDepth;
        }

        boolean centered(float x, float z, float tolerance) {
            return Math.abs((min.x + max.x) / 2 - x) <= tolerance
                && Math.abs((min.z + max.z) / 2 - z) <= tolerance;
        }

        boolean heights(float bottom, float top, float tolerance) {
            return Math.abs(min.y - bottom) <= tolerance && Math.abs(max.y - top) <= tolerance;
        }
    }

    private static final class Builder {
        private final BedrockGeometry model;
        private final float scale;
        private final List<Float> positions = new ArrayList<>();
        private final List<Float> normals = new ArrayList<>();
        private final List<Float> uvs = new ArrayList<>();
        private final List<Integer> joints = new ArrayList<>();
        private final Map<String, List<Integer>> parts = new LinkedHashMap<>();
        private final Set<String> alternateForms;
        private int quads;

        Builder(BedrockGeometry model, float scale) {
            this.model = model;
            this.scale = scale;
            alternateForms = BoneAlternateForms.baseFormsPresent(model.bonesByName.keySet().toArray(String[]::new));
        }

        void walk(BedrockGeometry.Bone bone, Matrix4f parent, int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("geometry hierarchy is too deep");
            }
            if (BoneAlternateForms.isAlternateForm(bone.name, alternateForms)) {
                return;
            }
            Matrix4f transform = bindTransform(bone, parent);
            int joint = GeoJointMapper.resolveJointId(bone, model);
            // EF's GPU visibility buffer admits 256 parts. All these parts have the same texture
            // and no per-bone transform provider, so grouping by the 20 joints preserves the draw
            // while bounding the total to 20 GEO parts plus HumanoidMesh's 12 reserved parts.
            List<Integer> indices = parts.computeIfAbsent("geo/" + HumanoidJoints.nameOf(joint),
                key -> new ArrayList<>());
            for (BedrockGeometry.Quad quad : bone.quads) {
                if (++quads > MAX_QUADS) {
                    throw new IllegalArgumentException("geometry has too many faces");
                }
                int first = joints.size();
                // Bedrock mirror swaps physical faces as well as UVs. Derive lighting from the
                // emitted winding so bone/cube mirrors cannot leave a face's old signed normal.
                Vector3f faceNormal = new Vector3f(quad.positions[1]).sub(quad.positions[0])
                    .cross(new Vector3f(quad.positions[2]).sub(quad.positions[0]));
                if (faceNormal.lengthSquared() <= 1.0E-12F) {
                    // Zero-size decorative cubes also contain degenerate side faces.
                    faceNormal.set(quad.normal);
                }
                faceNormal.mulDirection(transform).normalize();
                for (int i = 0; i < 4; i++) {
                    Vector3f position = new Vector3f(quad.positions[i]).mulPosition(transform).mul(scale);
                    Vector3f normal = faceNormal;
                    if (!position.isFinite() || !normal.isFinite() || Math.abs(position.x) > 4
                            || position.y < -0.25F || position.y > 3 || Math.abs(position.z) > 4
                            || !Float.isFinite(quad.uvs[i][0]) || !Float.isFinite(quad.uvs[i][1])) {
                        throw new IllegalArgumentException("geometry contains nonfinite or outlying vertices");
                    }
                    positions.add(position.x); positions.add(position.y); positions.add(position.z);
                    normals.add(normal.x); normals.add(normal.y); normals.add(normal.z);
                    uvs.add(quad.uvs[i][0]); uvs.add(quad.uvs[i][1]);
                    joints.add(joint);
                }
                for (int corner : new int[] {0, 1, 2, 2, 3, 0}) {
                    indices.add(first + corner);
                }
            }
            for (BedrockGeometry.Bone child : bone.children) {
                walk(child, transform, depth + 1);
            }
        }

        Data finish() {
            if (quads == 0) {
                throw new IllegalArgumentException("geometry has no supported cube faces");
            }
            Map<String, int[]> written = new LinkedHashMap<>();
            parts.forEach((name, indices) -> {
                if (!indices.isEmpty()) {
                    written.put(name, indices.stream().mapToInt(Integer::intValue).toArray());
                }
            });
            return new Data(floats(positions), floats(normals), floats(uvs),
                joints.stream().mapToInt(Integer::intValue).toArray(), Map.copyOf(written), quads);
        }
    }

    private static float[] floats(List<Float> source) {
        float[] array = new float[source.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = source.get(i);
        }
        return array;
    }
}
