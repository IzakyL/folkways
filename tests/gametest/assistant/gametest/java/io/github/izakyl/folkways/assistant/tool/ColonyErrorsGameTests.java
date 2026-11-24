package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.core.base.task.ColonyLog;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.internal.ColonyWorks;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.ThreadContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class ColonyErrorsGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_assistant:colony_error_test");
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final class Probe {
        private String message;
    }

    @SubscribeEvent
    public static void declare(Declaring event) {
        event.colony(OWNER, scope -> {
            Probe probe = new Probe();
            scope.share(probe);
            scope.tick(server -> {
                if (probe.message != null) {
                    LOGGER.warn("{}", probe.message);
                }
            });
        });
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonyErrorsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void membersSeeOnlyTheirColonyAndOperatorsSeeEveryOrigin(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var server = helper.getLevel().getServer();
            var other = Colonies.mint(server);
            String marker = UUID.randomUUID().toString();
            try {
                fixture.colony.service(OWNER, Probe.class).orElseThrow().message = marker + " A";
                other.service(OWNER, Probe.class).orElseThrow().message = marker + " B";
                ((ColonyWorks) fixture.colony).pulse(server);
                ((ColonyWorks) other).pulse(server);
                LOGGER.warn("{}", marker + " untagged");
                helper.assertFalse(ThreadContext.containsKey(ColonyLog.KEY), "ticks restore the server context");
                JsonObject args = new JsonObject();
                args.addProperty("limit", 200);
                Purity.assertPure(helper, "colony log isolation", () -> {
                    var rows = read(fixture, args).getAsJsonArray("errors").asList().stream()
                            .map(value -> value.getAsJsonObject())
                            .filter(row -> row.get("message").getAsString().contains(marker)).toList();
                    helper.assertTrue(rows.size() == 1 && rows.getFirst().get("message").getAsString().endsWith(" A")
                            && rows.getFirst().get("colony_id").getAsString().equals(fixture.colony.id().toString()),
                            "member A sees A, excluding B and untagged messages");
                });
                args.addProperty("limit", 1);
                helper.assertTrue(read(fixture, args).getAsJsonArray("errors").get(0).getAsJsonObject()
                        .get("message").getAsString().endsWith(" A"), "filtering precedes the tail limit");
                fixture.permission = 2;
                args.addProperty("limit", 200);
                Purity.assertPure(helper, "operator log origins", () -> {
                    var rows = read(fixture, args).getAsJsonArray("errors").asList().stream()
                            .map(value -> value.getAsJsonObject())
                            .filter(row -> row.get("message").getAsString().contains(marker)).toList();
                    helper.assertTrue(rows.size() == 3
                            && rows.get(0).get("colony_id").getAsString().equals(fixture.colony.id().toString())
                            && rows.get(1).get("colony_id").getAsString().equals(other.id().toString())
                            && rows.get(2).get("colony_id").isJsonNull(), "OP sees both colonies and explicit null");
                });
            } finally {
                fixture.colony.service(OWNER, Probe.class).orElseThrow().message = null;
                other.raze();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void residentTicksCarryAndRestoreTheColony(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            String marker = UUID.randomUUID().toString();
            ResidentEntity resident = new ResidentEntity(PersonBody.RESIDENT.get(), helper.getLevel()) {
                @Override
                public void tick() {
                    LOGGER.warn("{} resident", marker);
                }
            };
            try {
                resident.joinColony(fixture.colony.id());
                helper.getLevel().tickNonPassenger(resident);
                JsonObject args = new JsonObject();
                args.addProperty("limit", 200);
                helper.assertTrue(read(fixture, args).getAsJsonArray("errors").asList().stream()
                        .map(value -> value.getAsJsonObject()).anyMatch(row ->
                                row.get("message").getAsString().contains(marker)
                                        && row.get("colony_id").getAsString().equals(fixture.colony.id().toString())),
                        "the real entity tick boundary tags resident behavior");
                helper.assertFalse(ThreadContext.containsKey(ColonyLog.KEY), "entity ticks restore the context");
            } finally {
                resident.discard();
            }
        }
        helper.succeed();
    }

    private static JsonObject read(ReadFixture fixture, JsonObject args) {
        ToolReply reply = ReadTools.create().execute(fixture.player, "recent_errors", args);
        if (reply.data().has("error")) {
            throw new AssertionError(reply.data());
        }
        return reply.data();
    }
}
