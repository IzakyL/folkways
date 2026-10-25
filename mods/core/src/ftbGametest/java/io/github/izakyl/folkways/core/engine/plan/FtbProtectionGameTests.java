package io.github.izakyl.folkways.core.engine.plan;

import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.FTBChunksProperties;
import dev.ftb.mods.ftblibrary.math.ChunkDimPos;
import dev.ftb.mods.ftbteams.api.FTBTeamsAPI;
import dev.ftb.mods.ftbteams.api.property.PrivacyMode;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.WorkProtection;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.front.engine.charter.Charter;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_compat")
@PrefixGameTestTemplate(false)
public final class FtbProtectionGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void foreignClaimRefusesBeforeExtractionAndTracksPermissionChanges(GameTestHelper helper) throws Exception {
        var fixture = new PlanningFixture(helper);
        var level = helper.getLevel();
        var server = level.getServer();
        helper.assertTrue(FTBChunksAPI.api().isManagerLoaded(), "FTB Chunks manager is ready");
        var colony = Colonies.mint(server);
        Charters.keep(colony, Charter.founded("Protection test", UUID.randomUUID()));
        Bodies.of(fixture.worker.body()).orElseThrow().joinColony(colony.id());
        var team = FTBTeamsAPI.api().getManager().createServerTeam(server.createCommandSourceStack(),
            "Protection test " + UUID.randomUUID(), null, null);
        team.setProperty(FTBChunksProperties.BLOCK_EDIT_MODE, PrivacyMode.PRIVATE);
        team.setProperty(FTBChunksProperties.BLOCK_INTERACT_MODE, PrivacyMode.PRIVATE);
        var data = FTBChunksAPI.api().getManager().getOrCreateData(team);
        var chunk = new ChunkDimPos(level, fixture.source.cell());
        var claimed = data.claim(server.createCommandSourceStack(), chunk, false);
        helper.assertTrue(claimed != null && claimed.isSuccess(), "the test claims its source chunk");
        try {
            level.setBlockAndUpdate(fixture.source.cell(), Blocks.BARREL.defaultBlockState());
            var source = Containers.at(level, fixture.source.cell()).orElseThrow();
            source.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            var transfer = new TransferNode(UUID.randomUUID(),
                TransferNode.Endpoint.store(new Stash(fixture.source)), TransferNode.Endpoint.pack(),
                new Manifest(List.of(new ItemStack(Items.IRON_INGOT, 4)), List.of()),
                Stances.at(fixture.source), new WorkSite.AtBlock(fixture.source));
            var refused = Commit.run(level, transfer, fixture.worker);
            helper.assertTrue(refused instanceof Outcome.Failed failed && failed.why() == LaborRefusal.PROTECTED,
                "a foreign private claim refuses the offline founder's automation");
            helper.assertTrue(source.getItem(0).getCount() == 4 && fixture.worker.pack().isEmpty(),
                "refusal preserves source and destination inventories");
            team.setProperty(FTBChunksProperties.BLOCK_INTERACT_MODE, PrivacyMode.PUBLIC);

            helper.assertTrue(Commit.run(level, transfer, fixture.worker) instanceof Outcome.Done
                    && source.isEmpty() && fixture.worker.pack().getItem(0).getCount() == 4,
                "public interaction permits hauling while editing remains private");
            team.setProperty(FTBChunksProperties.BLOCK_EDIT_MODE, PrivacyMode.PRIVATE);
            helper.assertTrue(!WorkProtection.edit(level, fixture.worker, fixture.source.cell()),
                "edit permission is checked independently of public container access");
            helper.assertTrue(!WorkProtection.fluid(level, fixture.worker, fixture.source.cell()),
                "fluid edits respect private edit permission");
            var unowned = new PlanningFixture(helper);
            helper.assertTrue(!WorkProtection.interact(level, unowned.worker, fixture.source),
                "automation without a founder fails closed even with public container access");
            unowned.worker.body().discard();
            team.setProperty(FTBChunksProperties.BLOCK_INTERACT_MODE, PrivacyMode.PRIVATE);
            chestAcrossClaimBoundary(helper, fixture);
            movingFrameUsesWorldCoordinates(helper, fixture);
            targetDimensionUsesItsOwnClaim(helper, fixture, data);
        } finally {
            data.unclaim(server.createCommandSourceStack(), chunk, false, true);
            colony.raze();
            fixture.worker.body().discard();
        }
        helper.succeed();
    }

    private static void targetDimensionUsesItsOwnClaim(GameTestHelper helper, PlanningFixture fixture,
            ChunkTeamData data) {
        var level = helper.getLevel();
        var server = level.getServer();
        var target = server.getLevel(Level.NETHER);
        BlockPos cell = fixture.source.cell().atY(64);
        target.getChunkAt(cell);
        var before = target.getBlockState(cell);
        target.setBlock(cell, Blocks.BARREL.defaultBlockState(), 2);
        var at = WorldPos.of(target, cell);
        var chunk = new ChunkDimPos(target, cell);
        try {
            helper.assertTrue(WorkProtection.interact(level, fixture.worker, at),
                "the same founder uses the unclaimed target dimension despite the private origin claim");
            helper.assertTrue(data.claim(server.createCommandSourceStack(), chunk, false).isSuccess(),
                "the target dimension has its own private claim");
            helper.assertTrue(!WorkProtection.interact(level, fixture.worker, at),
                "the automation actor checks claims in the target dimension");
        } finally {
            data.unclaim(server.createCommandSourceStack(), chunk, false, true);
            target.setBlock(cell, before, 2);
        }
    }

    private static void chestAcrossClaimBoundary(GameTestHelper helper, PlanningFixture fixture) {
        var level = helper.getLevel();
        BlockPos inside = new BlockPos((fixture.source.cell().getX() & ~15) + 15,
            fixture.source.cell().getY(), fixture.source.cell().getZ());
        BlockPos outside = inside.east();
        level.getChunkAt(outside);
        var chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH);
        level.setBlock(inside, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
        level.setBlock(outside, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
        try {
            helper.assertTrue(Containers.at(level, outside).orElseThrow().getContainerSize() == 54,
                "the fixture is a double chest straddling the private claim");
            helper.assertTrue(!WorkProtection.interact(level, fixture.worker, WorldSpaces.at(level, outside)),
                "access from the unclaimed half cannot bypass the claimed half");
        } finally {
            level.setBlock(inside, Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(outside, Blocks.AIR.defaultBlockState(), 2);
        }
    }

    private static void movingFrameUsesWorldCoordinates(GameTestHelper helper, PlanningFixture fixture) {
        // A moving frame exercises the same coordinate contract as Sable without loading it in this suite.
        class MovingFrame implements WorldSpaces.Frame, WorldSpaces.Source {
            private final UUID id = UUID.randomUUID();
            private Vec3 anchor = Vec3.atLowerCornerOf(fixture.source.cell().offset(64, 0, 0));
            public UUID id() { return id; }
            public Level level() { return helper.getLevel(); }
            public BlockPos storageOrigin() { return fixture.source.cell(); }
            public Vec3 toWorld(Vec3 local) { return local.add(anchor); }
            public Vec3 toLocal(Vec3 world) { return world.subtract(anchor); }
            public Optional<MovingFrame> frame(Level level, UUID which) {
                return level == level() && id.equals(which) ? Optional.of(this) : Optional.empty();
            }
            public Optional<MovingFrame> containing(Level level, BlockPos storage) {
                return level == level() && storage.equals(storageOrigin()) ? Optional.of(this) : Optional.empty();
            }
            public Optional<MovingFrame> aboard(Entity entity) { return Optional.empty(); }
        }
        var frame = new MovingFrame();
        Runnable remove = WorldSpaces.install(frame);
        try {
            var at = WorldSpaces.at(helper.getLevel(), fixture.source.cell());
            helper.assertTrue(WorkProtection.interact(helper.getLevel(), fixture.worker, at),
                "unclaimed world position permits access even when storage coordinates are claimed");
            frame.anchor = Vec3.atLowerCornerOf(fixture.source.cell());
            helper.assertTrue(!WorkProtection.interact(helper.getLevel(), fixture.worker, at),
                "moving into the private claim rechecks the same frame address at its world position");
        } finally {
            remove.run();
        }
    }
}
