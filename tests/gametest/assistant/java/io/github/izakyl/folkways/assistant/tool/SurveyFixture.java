package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.Surveys;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Cleanup;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

final class SurveyFixture {

    private SurveyFixture() {
    }

    static void confirm(GameTestHelper helper, ServerPlayer player, BlockPos from, BlockPos to) {
        Cleanup.on(helper, () -> Surveys.forget(player));
        JsonObject args = new JsonObject();
        args.addProperty("dimension", player.serverLevel().dimension().location().toString());
        JsonArray points = new JsonArray();
        for (BlockPos pos : new BlockPos[] {from, to}) {
            JsonObject point = new JsonObject();
            point.addProperty("x", pos.getX());
            point.addProperty("y", pos.getY());
            point.addProperty("z", pos.getZ());
            points.add(point);
        }
        args.add("points", points);
        var actions = ActionTools.create();
        var preview = actions.preview(player, "request_survey", args).data();
        helper.assertTrue(preview.has("revision"), "survey preview succeeds: " + preview);
        var result = AsyncWait.result(helper, actions.apply(player, "request_survey", args,
                preview.get("revision").getAsString())).data();
        helper.assertFalse(result.has("error"), "survey confirmation succeeds: " + result);
    }
}
