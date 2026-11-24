package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.RailReads;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptApi;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class RailGameTests {

    private RailGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void railReadsArePureAndValidateMembersAndBounds(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            fixture.warm();
            Purity.assertPure(helper, "rail reads", () -> {
                ok(helper, read(fixture, "rail_stations", new JsonObject()));
                ok(helper, read(fixture, "rail_tracks", box(fixture)));
            });
            for (String function : new String[]{"rail_stations", "rail_tracks"}) {
                JsonObject args = function.equals("rail_tracks") ? box(fixture) : new JsonObject();
                for (int value : new int[]{0, -1, function.equals("rail_tracks") ? 257 : 65}) {
                    args.addProperty("limit", value);
                    error(helper, read(fixture, function, args), "bounds_limit");
                }
                args.addProperty("limit", 1.5);
                error(helper, read(fixture, function, args), "invalid_arguments");
            }
            JsonObject args = new JsonObject();
            args.addProperty("cursor", "bad cursor");
            error(helper, read(fixture, "rail_stations", args), "invalid_cursor");
            args = box(fixture);
            args.getAsJsonObject("to").addProperty("dimension", "minecraft:the_nether");
            error(helper, read(fixture, "rail_tracks", args), "mixed_dimensions");
            args = box(fixture);
            args.add("to", fixture.at(fixture.chest.east(1024)));
            error(helper, read(fixture, "rail_tracks", args), "bounds_limit");
            args = box(fixture);
            args.add("to", fixture.at(new BlockPos(30_000_000, 64, 0)));
            error(helper, read(fixture, "rail_tracks", args), "outside_world");
            Charters.keep(fixture.colony, Charter.founded("Revoked", UUID.randomUUID()));
            error(helper, read(fixture, "rail_stations", new JsonObject()), "not_member");
            error(helper, read(fixture, "rail_tracks", box(fixture)), "not_member");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void missingRailProviderReturnsALocalizedStructuredError(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ToolRegistry registry = new ToolRegistry();
            RailReads.register(registry, ResourceLocation.parse("folkways_assistant:missing_rail_provider"));
            fixture.warm();
            Purity.assertPure(helper, "missing railway capability", () -> {
                for (String function : new String[]{"rail_stations", "rail_tracks"}) {
                    JsonObject args = function.equals("rail_tracks") ? box(fixture) : new JsonObject();
                    ToolReply reply = registry.execute(fixture.player, function, args);
                    error(helper, reply, "rail_unavailable");
                    helper.assertTrue(reply.component("refusal").getContents() instanceof TranslatableContents,
                            "missing provider keeps localized refusal");
                }
            });
            Charters.keep(fixture.colony, Charter.founded("Revoked", UUID.randomUUID()));
            error(helper, registry.execute(fixture.player, "rail_stations", new JsonObject()), "not_member");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void railFunctionsShareHelpAndRunBindings(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        String help = ScriptApi.help("rail_tracks", false);
        helper.assertTrue(help.contains("path_point") && help.contains("outward") && help.contains("45°"),
                "rail help describes track ends");
        helper.assertTrue(ScriptApi.run(false).description().contains("rail_stations(limit=64, cursor=None)"),
                "run advertises pagination");
        var check = new ScriptHost().run(fixture.player, "at = " + fixture.at(fixture.chest) + "\n"
                + "result = [rail_stations(limit=1, cursor=None), rail_tracks(at, at, limit=1)]", new JsonObject())
                .thenAccept(reply -> {
                    ok(helper, reply);
                    var result = reply.data().getAsJsonArray("result");
                    helper.assertTrue(result.get(0).getAsJsonObject().has("next_cursor"), "station result shape");
                    helper.assertTrue(result.get(1).getAsJsonObject().has("ends"), "track result shape");
                    helper.assertFalse(reply.data().has("revision"), "reads queue no intentions");
                });
        AsyncWait.succeedWhen(helper, check, () -> { }, fixture::close);
    }

    private static JsonObject box(ReadFixture fixture) {
        JsonObject args = new JsonObject();
        args.add("at", fixture.at(fixture.chest));
        args.add("to", fixture.at(fixture.chest));
        return args;
    }

    private static ToolReply read(ReadFixture fixture, String name, JsonObject args) {
        return ToolRegistry.READS.execute(fixture.player, name, args);
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), reply.data().toString());
    }

    private static void error(GameTestHelper helper, ToolReply reply, String code) {
        helper.assertTrue(reply.data().has("error") && reply.data().get("error").getAsString().equals(code),
                code + ": " + reply.data());
    }
}
