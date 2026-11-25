package io.github.izakyl.folkways.assistant.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.ConfirmedAction;
import io.github.izakyl.folkways.assistant.tool.api.CaptureRequest;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost.RunReply;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.jpi.agent.Agent;
import io.github.izakyl.jpi.agent.AgentEvent;
import io.github.izakyl.jpi.agent.AgentToolResult;
import io.github.izakyl.jpi.ai.AssistantMessage;
import io.github.izakyl.jpi.ai.CancellationToken;
import io.github.izakyl.jpi.ai.ContentBlock;
import io.github.izakyl.jpi.ai.Message;
import io.github.izakyl.jpi.ai.Model;
import io.github.izakyl.jpi.ai.OpenAiCompat;
import io.github.izakyl.jpi.ai.ProviderRegistry;
import io.github.izakyl.jpi.ai.StopReason;
import io.github.izakyl.jpi.ai.StreamOptions;
import io.github.izakyl.jpi.ai.ToolResultMessage;
import io.github.izakyl.jpi.ai.UserMessage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class ScreenshotGameTests {

    private ScreenshotGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void imageInputDefaultsOnAndKeepsSavedChoice(GameTestHelper helper) throws IOException {
        Gson json = new Gson();
        for (String provider : List.of("deepseek", "openai-compatible", "anthropic", "google")) {
            var defaults = AssistantSettings.preset(provider, Set.of());
            helper.assertTrue(defaults.imageInput(), "every provider assumes the model sees images");
            for (boolean enabled : List.of(true, false)) {
                var settings = new AssistantSettings(provider, defaults.baseUrl(), defaults.model(), Set.of("work"),
                        enabled);
                helper.assertTrue(json.fromJson(json.toJson(settings), AssistantSettings.class).equals(settings),
                        "explicit image setting and approvals survive JSON serialization");
                helper.assertTrue(AssistantModels.create(settings).model().input().contains("image") == enabled,
                        "the model input matches the saved image setting");
            }
        }
        String saved = """
                {"entries": [{"id": "off", "provider": "deepseek", "baseUrl": "https://api.deepseek.com",
                  "model": "deepseek-v4-flash", "imageInput": false},
                 {"id": "unset", "provider": "deepseek", "baseUrl": "https://api.deepseek.com",
                  "model": "deepseek-v4-flash"}],
                 "selectedEntryId": "off"}
                """;
        var config = AssistantSettings.parse(saved);
        helper.assertTrue(!config.active().imageInput() && !config.active().effective().imageInput(),
                "a saved false stays off");
        helper.assertTrue(config.entries().get(1).settings(Set.of()).imageInput(),
                "an entry saved before the setting existed turns on");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void historyKeepsTheLatestSetOfViews(GameTestHelper helper) {
        ContentBlock.Image image = new ContentBlock.Image("AQID", "image/jpeg");
        UserMessage old = new UserMessage(List.of(image, image), 1);
        ToolResultMessage set = ToolResultMessage.builder("set", "run")
                .content(List.of(new ContentBlock.Text("{}"), image, image, image, image, image))
                .details(new JsonObject()).timestamp(2).build();
        UserMessage text = new UserMessage(List.of(new ContentBlock.Text("thanks")), 3);
        var trimmed = ScreenshotHistory.trim(List.of(old, set, text));
        helper.assertTrue(trimmed.get(1) == set, "the newest five-view set stays whole");
        helper.assertTrue(trimmed.getFirst().content().stream().allMatch(ContentBlock.Text.class::isInstance)
                && trimmed.getFirst().content().size() == 2, "older images fall out once the set fills the budget");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void framedViewsStandOutsideTheirSubjectAndSeeAllOfIt(GameTestHelper helper) {
        AABB subject = new AABB(10, 64, -20, 41, 66, -17);
        JsonObject arguments = new JsonObject();
        arguments.add("around", new Gson().toJsonTree(List.of(List.of(10, 64, -20), List.of(40, 65, -18))));
        arguments.add("views", new Gson().toJsonTree(List.of("north", "east", "south", "west", "southeast", "top")));
        List<CaptureRequest> shots = CaptureRequest.expand(arguments, null);
        helper.assertTrue(shots.size() == 6 && shots.stream().allMatch(shot -> shot.subject().equals(subject)),
                "named views keep their order and the inclusive box");
        for (CaptureRequest shot : shots) {
            CaptureRequest placed = shot.framed(16 / 9.0);
            Vec3 eye = placed.position();
            helper.assertTrue(!subject.inflate(1).contains(eye), shot.view() + " stands outside its subject");
            Vec3 forward;
            if (shot.view().equals("top")) {
                helper.assertTrue(placed.pitch() == 90 && placed.yaw() == 180 && eye.y > subject.maxY
                        && Math.abs(eye.x - 25.5) < 1.0e-6 && Math.abs(eye.z + 18.5) < 1.0e-6,
                        "top looks straight down over the centre with north up");
                forward = new Vec3(0, -1, 0);
            } else {
                helper.assertTrue(placed.lookAt().equals(subject.getCenter()), shot.view() + " looks at the centre");
                forward = placed.lookAt().subtract(eye).normalize();
            }
            double side = switch (shot.view()) {
                case "north" -> subject.getCenter().z - eye.z;
                case "south" -> eye.z - subject.getCenter().z;
                case "east" -> eye.x - subject.getCenter().x;
                case "west" -> subject.getCenter().x - eye.x;
                default -> 1;
            };
            helper.assertTrue(side > 0, shot.view() + " stands on its named side");
            Vec3 right = shot.view().equals("top") ? new Vec3(1, 0, 0)
                    : forward.cross(new Vec3(0, 1, 0)).normalize();
            Vec3 up = right.cross(forward).normalize();
            double tanHigh = Math.tan(Math.toRadians(placed.fov()) / 2);
            for (int corner = 0; corner < 8; corner++) {
                Vec3 q = new Vec3((corner & 1) == 0 ? subject.minX : subject.maxX,
                        (corner & 2) == 0 ? subject.minY : subject.maxY,
                        (corner & 4) == 0 ? subject.minZ : subject.maxZ).subtract(eye);
                double depth = q.dot(forward);
                helper.assertTrue(depth > 0 && Math.abs(q.dot(up)) <= depth * tanHigh
                        && Math.abs(q.dot(right)) <= depth * tanHigh * 16 / 9.0, shot.view() + " sees every corner");
            }
        }
        JsonObject counted = new JsonObject();
        counted.addProperty("around", CaptureRequest.CHANGES);
        counted.addProperty("views", 5);
        AABB changes = CaptureRequest.changes(List.of(new BlockPos(1, 2, 3), new BlockPos(5, 2, 1)), null);
        var views = CaptureRequest.expand(counted, changes).stream().map(CaptureRequest::view).toList();
        helper.assertTrue(views.equals(List.of("southeast", "southwest", "northwest", "northeast", "top"))
                && changes.equals(new AABB(1, 2, 1, 6, 3, 4)), "a count takes the diagonals then the top");
        counted.remove("views");
        helper.assertTrue(CaptureRequest.expand(counted, changes).size() == 4, "four diagonal views by default");
        try {
            CaptureRequest.expand(counted, null);
            helper.fail("framing changes needs something staged");
        } catch (IllegalStateException expected) {
            helper.assertTrue(expected.getMessage().equals("nothing_to_frame"), "nothing staged to frame");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void capturingStagedChangesHoldsTheirReview(GameTestHelper helper) throws Exception {
        var image = ScreenshotImage.encode(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB));
        JsonObject data = new Gson().fromJson("""
                {"revision": "r", "preview_dimension": "minecraft:overworld", "summary": {"$text": 0},
                 "intents": [{"action": "create_zone", "summary": {"$text": 0},
                   "place": {"dimension": "minecraft:overworld", "box": [[0, 60, 0], [8, 62, 4]]}}],
                 "units": [{"revision": "u1", "changes": [0], "ghost_start": 0, "ghost_end": 0}]}
                """, JsonObject.class);
        ToolReply staged = new ToolReply(data, List.of(Component.literal("zone")));
        List<String> decisions = new java.util.ArrayList<>();
        List<CaptureRequest> shots = new java.util.ArrayList<>();
        AtomicInteger stages = new AtomicInteger();
        AtomicInteger restores = new AtomicInteger();
        ConfirmedAction tool = new ConfirmedAction(false, new ConfirmedAction.Transport() {
            @Override
            public CompletableFuture<RunReply> run(String script, CancellationToken cancellation) {
                JsonObject around = new JsonObject();
                around.addProperty("around", CaptureRequest.CHANGES);
                return CompletableFuture.completedFuture(new RunReply(staged, null, List.of(around), 0));
            }

            @Override
            public CompletableFuture<ToolReply> decide(String revision, boolean accept,
                    CancellationToken cancellation) {
                decisions.add(revision + "=" + accept);
                return CompletableFuture.completedFuture(new ToolReply(new JsonObject(), List.of()));
            }
        }, (preview, cancellation) -> {
            throw new AssertionError("a held check never reaches the player");
        }, new ConfirmedAction.Capture() {
            @Override
            public CompletableFuture<AgentToolResult> take(CaptureRequest camera, CancellationToken cancellation) {
                helper.assertTrue(stages.get() == 1 && restores.get() == 0, "shots are taken while staged");
                shots.add(camera);
                return CompletableFuture.completedFuture(image);
            }

            @Override
            public Runnable stage(ToolReply shown) {
                helper.assertTrue(shown == staged, "the staged preview is shown");
                stages.incrementAndGet();
                return restores::incrementAndGet;
            }
        });
        JsonObject args = new JsonObject();
        args.addProperty("script", "...");
        var result = tool.execute("check", args, new CancellationToken(), update -> { });
        JsonObject details = result.details().getAsJsonObject();
        helper.assertTrue(!result.isError() && details.get("held").getAsBoolean() && !details.has("units")
                && details.getAsJsonArray("captures").size() == 4, "the run returns a held check with its shots");
        helper.assertTrue(decisions.equals(List.of("u1=false")) && restores.get() == 1, "staged change dropped");
        helper.assertTrue(shots.stream().allMatch(shot -> shot.size() == CaptureRequest.SET_SIZE
                && shot.subject().equals(new AABB(0, 60, 0, 9, 63, 5))), "a set of four uses smaller images");
        helper.assertTrue(result.content().stream().filter(ContentBlock.Image.class::isInstance).count() == 4,
                "every view reaches the model");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void historyKeepsTwoImagesWithoutChangingReasoningOrToolMetadata(GameTestHelper helper) {
        ContentBlock.Image image = new ContentBlock.Image("AQID", "image/jpeg");
        AssistantMessage reasoning = AssistantMessage.builder("openai-completions", "deepseek", "fake")
                .content(List.of(new ContentBlock.Thinking("reason", "signature"),
                        new ContentBlock.ToolCall("shot", "run", new JsonObject())))
                .stopReason(StopReason.TOOL_USE).timestamp(3).build();
        JsonObject details = new JsonObject();
        details.addProperty("width", 1024);
        UserMessage first = new UserMessage(List.of(new ContentBlock.Text("question", "text-signature"), image), 1);
        ToolResultMessage tool = ToolResultMessage.builder("shot", "run")
                .content(List.of(image, image)).details(details).isError(true).timestamp(4).build();
        UserMessage latest = new UserMessage(List.of(image), 5);
        List<Message> original = List.of(first, reasoning, tool, latest);
        Purity.assertPure(helper, "screenshot history", () -> {
            List<Message> trimmed = ScreenshotHistory.trim(original);
            helper.assertTrue(trimmed.get(1) == reasoning, "reasoning, signatures and calls remain untouched");
            helper.assertTrue(trimmed.get(0).content().get(0) == first.content().get(0)
                    && trimmed.get(0).timestamp() == 1, "user text and timestamp retained");
            helper.assertTrue(trimmed.get(0).content().get(1).equals(
                    new ContentBlock.Text("[earlier screenshot omitted]")), "old user image omitted");
            var result = (ToolResultMessage) trimmed.get(2);
            helper.assertTrue(result.content().get(0) instanceof ContentBlock.Text
                    && result.content().get(1) == image && trimmed.get(3) == latest, "newest two blocks retained");
            helper.assertTrue(result.details().equals(details) && result.isError() && result.timestamp() == 4
                    && result.toolCallId().equals("shot") && result.toolName().equals("run"),
                    "tool result metadata retained");
            helper.assertTrue(ScreenshotHistory.trim(trimmed).equals(trimmed), "trimming is idempotent");
            helper.assertTrue(first.content().get(1) == image && tool.content().get(0) == image,
                    "input history is immutable");
            helper.assertTrue(ScreenshotHistory.trim(List.of()).isEmpty(), "empty history supported");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void screenshotsEncodeJpegAndBoundBothOrientations(GameTestHelper helper) throws IOException {
        for (int[] size : List.of(new int[] {2048, 512}, new int[] {512, 2048}, new int[] {32, 16})) {
            BufferedImage source = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_ARGB);
            source.setRGB(0, 0, 0xffff0000);
            var result = ScreenshotImage.encode(source);
            var image = (ContentBlock.Image) result.content().get(1);
            byte[] bytes = Base64.getDecoder().decode(image.data());
            helper.assertTrue((bytes[0] & 255) == 255 && (bytes[1] & 255) == 216
                    && image.mimeType().equals("image/jpeg"), "encoded bytes are JPEG");
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            double scale = Math.min(1.0, 1024.0 / Math.max(size[0], size[1]));
            helper.assertTrue(decoded.getWidth() == Math.round(size[0] * scale)
                    && decoded.getHeight() == Math.round(size[1] * scale), "aspect ratio and size cap respected");
            helper.assertTrue(result.details().getAsJsonObject().get("width").getAsInt() == decoded.getWidth()
                    && result.details().getAsJsonObject().get("height").getAsInt() == decoded.getHeight(),
                    "metadata reports encoded dimensions");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void runAttachesImagesOnlyForSuccessfulScripts(GameTestHelper helper) throws Exception {
        var image = ScreenshotImage.encode(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB));
        AtomicInteger captures = new AtomicInteger();
        var tool = runTool(script -> switch (script) {
            case "capture()" -> new RunReply(new ToolReply(new JsonObject(), List.of()), null,
                    List.of(new JsonObject()), 0);
            case "capture()\nfail(\"broken\")" -> new RunReply(ToolReply.error("eval_error"), null,
                    List.of(new JsonObject()), 0);
            default -> new RunReply(ToolReply.error("image_input_disabled"));
        }, (camera, cancellation) -> {
            captures.incrementAndGet();
            return CompletableFuture.completedFuture(image);
        });
        for (String script : List.of("capture()\nfail(\"broken\")", "disabled", "capture()")) {
            JsonObject args = new JsonObject();
            args.addProperty("script", script);
            var result = tool.execute("shot", args, new CancellationToken(), update -> { });
            boolean success = script.equals("capture()");
            helper.assertTrue(result.isError() != success
                    && result.content().stream().anyMatch(ContentBlock.Image.class::isInstance) == success,
                    "only successful runs return a JPEG");
        }
        helper.assertTrue(captures.get() == 1, "failed and disabled scripts never invoke capture");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void localSseReceivesUserAndToolImagesWhileReplayingReasoning(GameTestHelper helper)
            throws IOException {
        ScreenshotSseServer server = new ScreenshotSseServer();
        var captured = ScreenshotImage.encode(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB));
        ConfirmedAction tool = runTool(script -> new RunReply(new ToolReply(new JsonObject(), List.of()), null,
                List.of(new JsonObject()), 0), (camera, cancellation) -> CompletableFuture.completedFuture(captured));
        Model model = Model.builder("fake", server.url()).provider("deepseek").reasoning(true)
                .compat(OpenAiCompat.deepseek()).input(List.of("text", "image")).build();
        Agent agent = new Agent(model, "Inspect screen", List.of(tool), StreamOptions.builder().apiKey("fake-key").build(),
                ProviderRegistry.defaults(), ScreenshotHistory::trim);
        var answer = agent.prompt(new UserMessage("What is visible?"));
        AsyncWait.succeedWhen(helper, answer, () -> {
            helper.assertTrue(server.requests.size() == 4, "three screenshot calls and a final answer");
            String first = server.requests.getFirst().getAsJsonArray("messages").toString();
            helper.assertTrue(!first.contains("image_url"), "user message has no attached image");
            String second = server.requests.get(1).getAsJsonArray("messages").toString();
            helper.assertTrue(second.contains("image_url") && second.contains("\"role\":\"tool\"")
                    && second.contains("\"width\":32".replace("\"", "\\\"")),
                    "tool result includes image and structured text");
            var last = server.requests.getLast().getAsJsonArray("messages").toString();
            helper.assertTrue(last.split("\"type\":\"image_url\"", -1).length - 1 == 2
                    && last.contains("[earlier screenshot omitted]"), "only two images reach later loop requests");
            helper.assertTrue(last.contains("reasoning_content") && last.contains("Inspect screen 1")
                    && last.contains("Inspect screen 3"), "DeepSeek reasoning replay remains intact");
        }, () -> {
            agent.abort();
            server.close();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void runCardSummarizesCapturesAndHistoryPages(GameTestHelper helper) throws IOException {
        var transcript = new AssistantTranscript(() -> {}, text -> {});
        var call = new ContentBlock.ToolCall("shot", "run", new JsonObject());
        transcript.receive(new AgentEvent.ToolExecutionStart(call));
        var shot = ScreenshotImage.encode(new BufferedImage(32, 16, BufferedImage.TYPE_INT_RGB));
        JsonObject details = new JsonObject();
        details.addProperty("history_reads", 2);
        JsonArray captures = new JsonArray();
        captures.add(shot.details());
        details.add("captures", captures);
        transcript.receive(new AgentEvent.ToolExecutionEnd(call, new AgentToolResult(shot.content(), details)));
        helper.assertTrue(transcript.result(0).equals(Component.translatable("folkways.assistant.script_result", 0, 0)
                .append(" · ").append(Component.translatable("folkways.assistant.history_result", 2))
                .append(" · ").append(Component.translatable("folkways.assistant.screenshot_result", 32, 16))),
                "run card reports history pages and image dimensions through translation keys");
        helper.succeed();
    }

    private static ConfirmedAction runTool(Function<String, RunReply> run,
            ConfirmedAction.Capture capture) {
        return new ConfirmedAction(false, new ConfirmedAction.Transport() {
            @Override
            public CompletableFuture<RunReply> run(String script, CancellationToken cancellation) {
                return CompletableFuture.completedFuture(run.apply(script));
            }

            @Override
            public CompletableFuture<ToolReply> decide(String revision, boolean accept, CancellationToken cancellation) {
                throw new AssertionError("capture requires no approval");
            }
        }, (preview, cancellation) -> {
            throw new AssertionError("capture requires no approval");
        }, capture);
    }
}
