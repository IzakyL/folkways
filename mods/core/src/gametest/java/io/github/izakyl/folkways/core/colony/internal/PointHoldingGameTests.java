package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PointHoldingGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "test/native_point");
    static { PointSites.register(OWNER, (level, pos) -> level.getBlockState(pos).is(Blocks.COMPOSTER) || level.getBlockState(pos).is(Blocks.CHEST)); }
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(PointHoldingGameTests.class); }

    @GameTest(template = "empty")
    public static void nativePointOwnershipPersistsWithoutABlockEntityAndAppearsInTheRealView(GameTestHelper helper) {
        var level = helper.getLevel(); var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState());
        ColonyData original = new ColonyData(); WorldPos at = WorldPos.of(level, cell);
        var claim = original.claims().hold(level.getServer(), OWNER, new Claimed.Point(at)).orElseThrow();
        ColonyData restored = ColonyData.load(Reader.of(original.save(new net.minecraft.nbt.CompoundTag()))).orElseThrow();
        restored.reconcile(level.getServer());
        helper.assertTrue(restored.claims().at(at).filter(saved -> saved.equals(claim)).isPresent(), "native point kind, owner, id and position survive actual colony serialization");
        helper.assertTrue(ColonySnapshot.of(restored, level).blocks(OWNER).contains(cell), "the real loaded colony view exposes point sites to plugins");
        helper.assertTrue(level.getBlockEntity(cell) == null, "a native point never adds a fake block entity");
        level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 8));
        restored.reconcile(level.getServer());
        helper.assertTrue(restored.claims().at(at).isPresent(), "native compost level changes keep satisfying the registered site predicate");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pointsAndMarkedBlocksAtTheSamePositionExcludeEachOtherAcrossRealColonies(GameTestHelper helper) {
        var level = helper.getLevel(); var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.CHEST.defaultBlockState()); WorldPos at = WorldPos.of(level, cell);
        var first = Colonies.mint(level.getServer()); var other = Colonies.mint(level.getServer());
        try {
            var point = first.hold(OWNER, new Claimed.Point(at)).orElseThrow();
            helper.assertTrue(ColonyMembership.owner(level.getBlockEntity(cell)).isEmpty(), "point ownership does not masquerade as a block-entity attachment");
            helper.assertTrue(other.hold(OWNER, new Claimed.Block(at)).isEmpty(), "another colony cannot claim a point's position through block ownership");
            helper.assertTrue(other.hold(OWNER, new Claimed.Point(at)).isEmpty(), "another colony cannot create a second point claim");
            first.release(point.id(), ClaimRelease.LET_GO);
            var block = other.hold(OWNER, new Claimed.Block(at)).orElseThrow();
            helper.assertTrue(first.hold(OWNER, new Claimed.Point(at)).isEmpty(), "an attached block also excludes ledger point ownership");
            other.release(block.id(), ClaimRelease.LET_GO);
            helper.assertTrue(first.hold(OWNER, new Claimed.Point(at)).isPresent(), "releasing the block frees exactly the one native position");
        } finally { first.raze(); other.raze(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aLoadedNativePointReleasesWhenItsDeclaredBlockPredicateStopsMatching(GameTestHelper helper) {
        var level = helper.getLevel(); var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState()); WorldPos at = WorldPos.of(level, cell);
        Claims claims = new Claims(UUID.randomUUID()); AtomicReference<ClaimRelease> why = new AtomicReference<>();
        claims.setReleasedListener((claim, release) -> why.set(release));
        claims.hold(level.getServer(), OWNER, new Claimed.Point(at)).orElseThrow();
        level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState()); claims.reconcile(level.getServer());
        helper.assertTrue(claims.at(at).isEmpty() && why.get() == ClaimRelease.GONE, "loaded invalid native contents release their position and notify the owner");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unloadedPointOwnershipSurvivesSaveReloadAndReconcileWithoutLoadingAChunk(GameTestHelper helper) {
        var level = helper.getLevel(); WorldPos at = WorldPos.of(level, new BlockPos(25_000_000, 80, 25_000_000));
        helper.assertTrue(!level.isLoaded(at.cell()), "the persisted native site starts in an actually unloaded chunk");
        Claims persisted = new Claims(UUID.randomUUID()); persisted.hold(null, OWNER, new Claimed.Point(at)).orElseThrow();
        Claims restored = new Claims(UUID.randomUUID()); restored.load(Reader.of(persisted.save())); restored.reconcile(level.getServer());
        helper.assertTrue(restored.at(at).isPresent(), "unloaded contents cannot invalidate a saved native point");
        helper.assertTrue(!level.isLoaded(at.cell()), "reconciliation does not force-load the native point's chunk");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRelocatedPointMovesItsExclusiveIndexAndKeepsThePointKindAfterSerialization(GameTestHelper helper) {
        var level = helper.getLevel(); var old = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        var moved = WorldPos.of(level, helper.absolutePos(new BlockPos(2, 1, 1)));
        Claims sites = new Claims(UUID.randomUUID()); sites.hold(null, OWNER, new Claimed.Point(old)).orElseThrow();
        sites.relocate(Map.of(old, moved));
        Claims restored = new Claims(UUID.randomUUID()); restored.load(Reader.of(sites.save()));
        helper.assertTrue(restored.at(old).isEmpty() && restored.at(moved).filter(claim -> claim.what().equals(new Claimed.Point(moved))).isPresent(), "realm relocation moves only the actual persisted point position");
        helper.assertTrue(restored.taken(new Claimed.Block(moved)), "relocated points retain position exclusion against attached block sites");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void publicPointAdmissionRejectsUnregisteredAndInvalidNativeSites(GameTestHelper helper) {
        var level = helper.getLevel(); var cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState()); WorldPos at = WorldPos.of(level, cell);
        var colony = Colonies.mint(level.getServer());
        try {
            helper.assertTrue(colony.hold(OWNER, new Claimed.Point(at)).isEmpty(), "new point admission validates the actual loaded native block");
            level.setBlockAndUpdate(cell, Blocks.COMPOSTER.defaultBlockState());
            helper.assertTrue(colony.hold(ResourceLocation.fromNamespaceAndPath("folkways", "test/unknown_point"), new Claimed.Point(at)).isEmpty(), "an unregistered point owner cannot claim arbitrary world blocks");
        } finally { colony.raze(); }
        helper.succeed();
    }
}
