package io.github.izakyl.folkways.vanilla.fixture.facility;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

public final class ScriptedFacilityFixture {

    public static final String FACILITY = "Script test";
    public static final String ROUTINE = "operate";

    public static final String POSITION_SOURCE = """
        load("@std//position", "position_step", "position_readings", "position_ready", "position_check")

        def step(state, readings, args):
            return position_step(state, readings, args)

        def readings(input, args):
            return position_readings(input, args)

        def ready(readings, args):
            return position_ready(readings, args)

        def check(args):
            return position_check(args)
        """;

    private final GameTestHelper helper;
    public final ServerLevel level;
    public final Colony colony;
    public final DefinedFacilities service;
    public final BlockPos left;
    public final BlockPos right;
    public Runnable beforeClose = () -> { };

    public ScriptedFacilityFixture(GameTestHelper helper, boolean resident) {
        this.helper = helper;
        level = helper.getLevel();
        colony = Colonies.mint(level.getServer());
        service = DefinedFacilities.of(colony).orElseThrow();
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        left = helper.absolutePos(new BlockPos(3, 2, 2));
        right = helper.absolutePos(new BlockPos(5, 2, 2));
        for (BlockPos pos : List.of(left, right)) {
            level.setBlockAndUpdate(pos,
                Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR));
        }
        if (resident) {
            var body = PersonBody.RESIDENT.get().create(level);
            body.moveTo(Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 2))));
            level.addFreshEntity(body);
            colony.hold(PersonBody.ID, Claimed.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id());
            colony.entered(body);
            var labor = new LaborFixture(colony, level);
            helper.onEachTick(labor::tick);
            Cleanup.on(helper, () -> {
                labor.close();
                body.discard();
                colony.raze();
            });
        } else {
            Cleanup.on(helper, () -> {
                beforeClose.run();
                colony.raze();
            });
        }
    }

    public ServerPlayer keeper() {
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "script-keeper"),
            ClientInformation.createDefault());
        Charters.keep(colony, Charter.founded("Script fixture", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        return player;
    }

    public Map<String, Object> routine(String source) {
        return definition(level, FACILITY, left, right, controls(),
            Map.of("source", source, "with", Map.of("value", false)),
            Map.of("manual", Map.of("kind", "manual", "routine", ROUTINE)));
    }

    public Map<String, Object> condition(String script) {
        return definition(level, FACILITY, left, right, controls(),
            Map.of("use", "folkways_core:write_all", "with", Map.of("right", true)),
            Map.of("auto", Map.of("kind", "condition", "routine", ROUTINE, "when", script, "enabled", true)));
    }

    public Map<String, Object> controls() {
        return Map.of("left", Map.of("part", "lever", "offset", List.of(0, 0, 0)),
            "right", Map.of("part", "lever", "offset", pos(right.subtract(left))));
    }

    public void install(Map<String, Object> definition) {
        var problems = service.apply(service.preview(FACILITY, Optional.of(definition)));
        helper.assertTrue(problems.isEmpty(), "installing scripted facility: " + problems);
    }

    public void run(Map<String, Value> arguments) {
        var problems = service.run(FACILITY, ROUTINE, arguments);
        helper.assertTrue(problems.isEmpty(), "submitting scripted routine: " + problems);
    }

    public boolean recent(String ending) {
        return service.recent(FACILITY).filter(result -> result.ending().equals(ending)).isPresent();
    }

    public String state() {
        var list = colony.kept(DefinedFacilities.ID).getCompound(FACILITY).getList("scripts", Tag.TAG_COMPOUND);
        return list.isEmpty() ? "" : new String(list.getCompound(0).getByteArray("state"), StandardCharsets.UTF_8);
    }

    public void power(BlockPos at, boolean powered) {
        level.setBlockAndUpdate(at, level.getBlockState(at).setValue(LeverBlock.POWERED, powered));
    }

    public boolean powered(BlockPos at) {
        return level.getBlockState(at).getValue(LeverBlock.POWERED);
    }

    public static Map<String, Object> definition(ServerLevel level, String name, BlockPos min, BlockPos max,
            Map<String, Object> controls, Map<String, Object> routine, Map<String, Object> triggers) {
        return new LinkedHashMap<>(Map.of("name", name,
            "extent", Map.of("elements", List.of(Map.of("space", Map.of("dimension", level.dimension().location().toString()),
                "min", pos(min), "max", pos(max)))),
            "controls", controls, "routines", Map.of(ROUTINE, routine), "triggers", triggers));
    }

    public static List<Integer> pos(BlockPos at) {
        return List.of(at.getX(), at.getY(), at.getZ());
    }
}
