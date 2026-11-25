package io.github.izakyl.folkways.core.shell.cbc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import io.github.izakyl.folkways.assistant.sandbox.Probe;
import io.github.izakyl.folkways.assistant.sandbox.Sandbox;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.mechanism.MechanismScan;
import io.github.izakyl.folkways.core.shell.cbc.mixin.CannonMountAccess;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PrefixGameTestTemplate(false)
public final class CbcSableGameTests {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-cbc-tests");

    // A ship resting on a floor carries a cannon; the probe aims the copy's cannon and leaves the real one as it was.
    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 800,
            batch = "assistant_probe_cannon")
    public static void probeAimsTheCannonOnACopiedShip(GameTestHelper helper) {
        var level = helper.getLevel();
        var mountPos = new BlockPos(72, 100, 40);
        var floor = new ArrayList<BlockPos>();
        var forced = new ArrayList<Long>();
        for (BlockPos pos : BlockPos.betweenClosed(mountPos.offset(-6, -2, -6), mountPos.offset(6, -2, 7))) {
            if (level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true)) {
                forced.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
            }
            level.setBlockAndUpdate(pos, Blocks.OBSIDIAN.defaultBlockState());
            floor.add(pos.immutable());
        }
        var deck = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(mountPos.offset(-3, -1, -2), mountPos.offset(3, -1, 4))) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            deck.add(pos.immutable());
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, mountPos.below(), deck, BoundingBox3i.from(deck));
        var storedMount = BlockPos.containing(ship.logicalPose().transformPositionInverse(Vec3.atCenterOf(mountPos)));
        var mount = CbcGameTests.mount(level, storedMount);
        var anchor = WorldSpaces.at(level, storedMount);
        var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "probe-test"),
            ClientInformation.createDefault());
        Cleanup.on(helper, () -> {
            mount.disassemble();
            var container = SubLevelContainer.getContainer(level);
            if (!ship.isRemoved()) {
                container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
            }
            floor.forEach(pos -> level.removeBlock(pos, false));
            forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        });
        level.setBlockAndUpdate(storedMount.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        @SuppressWarnings("unchecked")
        CompletableFuture<JsonObject>[] probe = new CompletableFuture[1];
        float[] real = new float[2];
        helper.startSequence().thenExecuteAfter(20, () -> {
            helper.assertTrue(mount.isRunning() && mount.getContraption() != null, "the cannon assembled aboard");
            real[0] = ((CannonMountAccess) mount).folkwaysYaw();
            real[1] = ((CannonMountAccess) mount).folkwaysPitch();
            var request = new Probe.Request(MechanismScan.structure(level, ship.getUniqueId()),
                new Probe.Input.Write(anchor, "aim", Optional.of(Value.of(real[0] + 30, 10))), 100);
            probe[0] = Sandbox.probe(player, request);
        }).thenWaitUntil(() -> helper.assertTrue(probe[0] != null && probe[0].isDone(), "the probe to finish"))
            .thenExecute(() -> {
                JsonObject data = probe[0].join();
                LOGGER.info("Hull cannon probe: {}", data);
                helper.assertFalse(data.has("error"), "the probe runs: " + data);
                helper.assertTrue(data.getAsJsonObject("input").get("result").getAsString().equals("written"),
                    "the copied cannon took the aim: " + data);
                var mountChange = data.getAsJsonArray("changes").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(change -> change.has("after")
                        && change.getAsJsonObject("after").has("yaw")).findFirst().orElse(null);
                helper.assertTrue(mountChange != null && Math.abs(mountChange.getAsJsonObject("after").get("yaw")
                    .getAsDouble() - (real[0] + 30)) < .5, "the copy's mount turned to the aim: " + data);
                helper.assertTrue(data.getAsJsonArray("structures").size() == 1
                    && data.getAsJsonArray("structures").get(0).getAsJsonObject().get("id").getAsString()
                        .equals(ship.getUniqueId().toString()), "the ship was copied whole: " + data);
                helper.assertTrue(((CannonMountAccess) mount).folkwaysYaw() == real[0]
                    && ((CannonMountAccess) mount).folkwaysPitch() == real[1], "the real cannon did not turn");
                ServerLevel sandbox = Sandbox.level(level.getServer()).orElseThrow();
                helper.assertTrue(SubLevelContainer.getContainer(sandbox).getAllSubLevels().isEmpty()
                    && !Sandbox.busy(), "no copy is left in the sandbox");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = FolkwaysCoreMod.CORE_ID, timeoutTicks = 800)
    public static void residentFiresCannonFromSable(GameTestHelper helper) {
        var level = helper.getLevel();
        // Sable physics uses floats, and GameTest templates can be far from the origin.
        var mountPos = new BlockPos(40, 100, 40);
        var deck = new ArrayList<BlockPos>();
        for (BlockPos pos : BlockPos.betweenClosed(mountPos.offset(-3, -1, -2), mountPos.offset(3, -1, 4))) {
            level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            deck.add(pos.immutable());
        }
        var ship = SubLevelAssemblyHelper.assembleBlocks(level, mountPos.below(), deck, BoundingBox3i.from(deck));
        var center = ship.getPlot().getCenterBlock();
        var localAnchor = new Vector3d(center.getX() + .5, center.getY() + .5, center.getZ() + .5);
        var storedMount = BlockPos.containing(ship.logicalPose().transformPositionInverse(Vec3.atCenterOf(mountPos)));
        ship.logicalPose().orientation().rotateY(Math.PI / 2);
        var physics = SubLevelPhysicsSystem.require(level);
        physics.updatePose(ship);
        physics.getPipeline().resetVelocity(ship);
        var fixed = physics.getPipeline().addConstraint(ship, null, new FixedConstraintConfiguration(localAnchor,
            ship.logicalPose().transformPosition(new Vector3d(localAnchor)),
            new Quaterniond(ship.logicalPose().orientation()).conjugate()));

        var target = ship.logicalPose().transformPosition(Vec3.atBottomCenterOf(storedMount.east(13)));
        var targetCell = BlockPos.containing(target);
        var floor = new ArrayList<BlockPos>();
        var forced = new ArrayList<Long>();
        try {
            var aboard = BlockPos.containing(ship.logicalPose().transformPosition(Vec3.atCenterOf(storedMount)));
            if (level.setChunkForced(aboard.getX() >> 4, aboard.getZ() >> 4, true)) {
                forced.add(ChunkPos.asLong(aboard.getX() >> 4, aboard.getZ() >> 4));
            }
            for (BlockPos pos : BlockPos.betweenClosed(targetCell.offset(-3, -1, -3), targetCell.offset(3, -1, 3))) {
                if (level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true)) {
                    forced.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                }
                level.setBlockAndUpdate(pos, Blocks.OBSIDIAN.defaultBlockState());
                floor.add(pos.immutable());
            }
            var mount = CbcGameTests.mount(level, storedMount);
            CbcGameTests.residentFires(helper, mount, target);
        } finally {
            Cleanup.on(helper, () -> {
                var container = SubLevelContainer.getContainer(level);
                fixed.remove();
                container.removeSubLevel(ship, SubLevelRemovalReason.REMOVED);
                floor.forEach(pos -> level.removeBlock(pos, false));
                forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk),
                    ChunkPos.getZ(chunk), false));
            });
        }
    }
}
