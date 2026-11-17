import blockwright.minecraft.core.Args;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunk;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedGeometryChunkRenderer;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedQuadData;
import com.github.mcmodderanchor.simplebedrockmodel.v2.common.model.baked.BakedVertexData;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.blockwright.api.Context;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Map;

// Exercise the native model emitter and Iris/Sodium conversion with real triangle buffers. A bad bulk serializer
// can leave the final vertices unwritten, so a screenshot alone is unreliable: fresh memory may happen to be zero.
public final class Task {
    public static Object run(Context ctx) {
        var rows = new ArrayList<Object>();
        for (int count : new int[] {3, 6, 12, 258}) {
            float[] positions = new float[count * 3];
            float[] normals = new float[count * 3];
            float[] uvs = new float[count * 2];
            for (int i = 0; i < count; i++) {
                positions[i * 3] = i + 1;
                positions[i * 3 + 1] = i % 2;
                positions[i * 3 + 2] = i % 3;
                normals[i * 3 + 1] = 1;
                uvs[i * 2] = i % 2;
                uvs[i * 2 + 1] = i % 3;
            }
            var chunk = new BakedGeometryChunk(0, BakedQuadData.EMPTY,
                new BakedVertexData(positions, normals, uvs, count), new String[] {"triangle-probe"});
            try (var storage = new ByteBufferBuilder(65536)) {
                var builder = new BufferBuilder(storage, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
                BakedGeometryChunkRenderer.INSTANCE.renderVertexChunk(chunk, new PoseStack(), builder,
                    15728880, 0, 1, 1, 1, 1);
                try (var mesh = builder.buildOrThrow()) {
                    int stride = mesh.drawState().format().getVertexSize();
                    var bytes = mesh.vertexBuffer().order(ByteOrder.nativeOrder());
                    var wrong = new ArrayList<Object>();
                    for (int i = 0; i < count; i++) {
                        for (int axis = 0; axis < 3; axis++) {
                            float actual = bytes.getFloat(i * stride + axis * 4);
                            float expected = positions[i * 3 + axis];
                            if (actual != expected) {
                                wrong.add(Map.of("vertex", i, "axis", axis,
                                    "expected", expected, "actual", Float.toString(actual)));
                            }
                        }
                    }
                    rows.add(Map.of("vertices", count, "stride", stride, "wrong", wrong));
                    if (Args.of(ctx).flag("verify", true) && !wrong.isEmpty()) {
                        throw new IllegalStateException("Corrupt shader triangle buffer: " + rows);
                    }
                }
            }
        }
        return Map.of("triangles", rows);
    }
}
