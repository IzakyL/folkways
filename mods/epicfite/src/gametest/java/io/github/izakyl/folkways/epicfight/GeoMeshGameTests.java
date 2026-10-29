package io.github.izakyl.folkways.epicfight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.epicfight.client.geo.GeoMeshData;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

/** Pure conversion regressions: no client renderer, external model pack or graphics context is required. */
@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GeoMeshGameTests {
    // Original six-cuboid fixture, authored for these tests. It contains no third-party model assets.
    private static final String CANONICAL = """
        {"minecraft:geometry":[{"description":{"texture_width":64,"texture_height":64},"bones":[
          {"name":"Root","pivot":[0,0,0]},
          {"name":"Body","parent":"Root","pivot":[0,12,0],"cubes":[{"origin":[-4,12,-2],"size":[8,12,4],"uv":[16,16]}]},
          {"name":"Head","parent":"Body","pivot":[0,24,0],"cubes":[{"origin":[-4,24,-4],"size":[8,8,8],"uv":[0,0]}]},
          {"name":"RightArm","parent":"Body","pivot":[-5,22,0],"cubes":[{"origin":[-8,12,-2],"size":[4,12,4],"uv":[40,16]}]},
          {"name":"LeftArm","parent":"Body","pivot":[5,22,0],"cubes":[{"origin":[4,12,-2],"size":[4,12,4],"uv":[32,48]}]},
          {"name":"RightLeg","parent":"Root","pivot":[-2,12,0],"cubes":[{"origin":[-4,0,-2],"size":[4,12,4],"uv":[0,16]}]},
          {"name":"LeftLeg","parent":"Root","pivot":[2,12,0],"cubes":[{"origin":[0,0,-2],"size":[4,12,4],"uv":[16,48]}]}
        ]}]}
        """;

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GeoMeshGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void canonicalHumanoidKeepsFacesAndValidSkinning(GameTestHelper helper) {
        GeoMeshData.Data mesh = GeoMeshData.convert(CANONICAL, 1);
        helper.assertValueEqual(mesh.quads(), 36, "all six cuboids retain all six faces");
        helper.assertValueEqual(mesh.positions().length, 36 * 4 * 3, "all face corners survive conversion");
        helper.assertValueEqual(mesh.normals().length, mesh.positions().length, "every vertex has a normal");
        helper.assertValueEqual(mesh.uvs().length, mesh.joints().length * 2, "every vertex has texture coordinates");
        helper.assertValueEqual(mesh.parts().values().stream().mapToInt(part -> part.length).sum(), 36 * 6,
            "each quad becomes exactly two triangles");
        for (int joint : mesh.joints()) {
            helper.assertTrue(joint >= 0 && joint < 20, "every vertex binds to an existing BIPED joint");
        }
        for (float uv : mesh.uvs()) {
            helper.assertTrue(Float.isFinite(uv), "texture coordinates remain finite");
        }
        assertOutwardNormals(helper, mesh);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void noncanonicalPivotsAndProportionsAreRejected(GameTestHelper helper) {
        JsonObject shoulder = fixture();
        bone(shoulder, 3).add("pivot", JsonParser.parseString("[-8,22,0]"));
        assertRejected(helper, shoulder.toString(), 1, "bind pivot");

        JsonObject forwardHead = fixture();
        bone(forwardHead, 2).add("pivot", JsonParser.parseString("[0,24,2]"));
        assertRejected(helper, forwardHead.toString(), 1, "bind pivot");

        JsonObject parentRotation = fixture();
        bone(parentRotation, 0).add("rotation", JsonParser.parseString("[0,90,0]"));
        assertRejected(helper, parentRotation.toString(), 1, "bind");

        JsonObject headRotation = fixture();
        bone(headRotation, 2).add("rotation", JsonParser.parseString("[0,0,5]"));
        assertRejected(helper, headRotation.toString(), 1, "rotated humanoid bind pose");

        JsonObject giantHead = fixture();
        bone(giantHead, 2).getAsJsonArray("cubes").get(0).getAsJsonObject()
            .add("size", JsonParser.parseString("[16,16,16]"));
        assertRejected(helper, giantHead.toString(), 1, "head geometry");
        assertRejected(helper, CANONICAL, 0.8F, "bind pivot");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void hundredsOfDecorationBonesStayWithinComputePartCapacity(GameTestHelper helper) {
        JsonObject decorated = fixture();
        JsonArray bones = bones(decorated);
        for (int i = 0; i < 300; i++) {
            JsonObject extra = JsonParser.parseString("""
                {"parent":"Head","pivot":[0,24,0],"cubes":[{"origin":[-1,26,-1],"size":[1,1,1],"uv":[0,0]}]}
                """).getAsJsonObject();
            extra.addProperty("name", "decoration" + i);
            bones.add(extra);
        }
        GeoMeshData.Data mesh = GeoMeshData.convert(decorated.toString(), 1);
        helper.assertValueEqual(mesh.quads(), 36 + 300 * 6, "all decoration faces survive joint grouping");
        helper.assertValueEqual(mesh.joints().length, mesh.quads() * 4, "grouping preserves every face vertex");
        helper.assertTrue(mesh.parts().size() <= 20, "source bone count cannot exhaust the joint-based part limit");
        helper.assertTrue(mesh.parts().size() + 12 <= 256,
            "converted parts and the reserved humanoid parts fit Epic Fight's visibility buffer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void mirrorLightingNormalsFollowPhysicalTriangleWinding(GameTestHelper helper) {
        JsonObject boneMirrors = fixture();
        bones(boneMirrors).forEach(element -> element.getAsJsonObject().addProperty("mirror", true));
        GeoMeshData.Data byBone = GeoMeshData.convert(boneMirrors.toString(), 1);
        helper.assertValueEqual(byBone.quads(), 36, "bone mirroring keeps all cube faces");
        assertOutwardNormals(helper, byBone);

        JsonObject cubeMirrors = fixture();
        bones(cubeMirrors).forEach(element -> {
            JsonObject bone = element.getAsJsonObject();
            if (bone.has("cubes")) {
                bone.getAsJsonArray("cubes").forEach(cube -> cube.getAsJsonObject().addProperty("mirror", true));
            }
        });
        GeoMeshData.Data byCube = GeoMeshData.convert(cubeMirrors.toString(), 1);
        helper.assertValueEqual(byCube.quads(), 36, "cube mirroring keeps all cube faces");
        assertOutwardNormals(helper, byCube);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void malformedHierarchyAndUnsupportedGeometryFailBeforeRendering(GameTestHelper helper) {
        JsonObject cyclic = fixture();
        bone(cyclic, 0).addProperty("parent", "Head");
        assertRejected(helper, cyclic.toString(), 1, "parent bone");

        JsonObject missingParent = fixture();
        bone(missingParent, 2).addProperty("parent", "absent");
        assertRejected(helper, missingParent.toString(), 1, "parent bone");

        JsonObject polygon = fixture();
        bone(polygon, 2).add("poly_mesh", new JsonObject());
        assertRejected(helper, polygon.toString(), 1, "polygon-mesh");
        assertRejected(helper, CANONICAL, Float.NaN, "scale");
        helper.succeed();
    }

    private static JsonObject fixture() {
        return JsonParser.parseString(CANONICAL).getAsJsonObject();
    }

    private static JsonArray bones(JsonObject root) {
        return root.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject().getAsJsonArray("bones");
    }

    private static JsonObject bone(JsonObject root, int index) {
        return bones(root).get(index).getAsJsonObject();
    }

    private static void assertRejected(GameTestHelper helper, String source, float scale, String reason) {
        try {
            GeoMeshData.convert(source, scale);
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage() != null && expected.getMessage().contains(reason),
                "the unsupported geometry reports why it must use a fallback: " + reason);
            return;
        }
        helper.fail("unsupported geometry was accepted instead of selecting a fallback: " + reason);
    }

    private static void assertOutwardNormals(GameTestHelper helper, GeoMeshData.Data mesh) {
        for (int[] part : mesh.parts().values()) {
            for (int i = 0; i < part.length; i += 3) {
                int first = part[i];
                Vector3f origin = position(mesh, first);
                Vector3f edge = position(mesh, part[i + 1]).sub(origin);
                Vector3f other = position(mesh, part[i + 2]).sub(origin);
                Vector3f normal = new Vector3f(mesh.normals()[first * 3], mesh.normals()[first * 3 + 1],
                    mesh.normals()[first * 3 + 2]);
                helper.assertTrue(normal.isFinite() && Math.abs(normal.lengthSquared() - 1) < 0.0001F,
                    "converted face normals remain finite unit vectors");
                helper.assertTrue(edge.cross(other).dot(normal) > 0,
                    "lighting follows outward triangle winding for every nondegenerate fixture face");
            }
        }
    }

    private static Vector3f position(GeoMeshData.Data mesh, int vertex) {
        return new Vector3f(mesh.positions()[vertex * 3], mesh.positions()[vertex * 3 + 1],
            mesh.positions()[vertex * 3 + 2]);
    }
}
