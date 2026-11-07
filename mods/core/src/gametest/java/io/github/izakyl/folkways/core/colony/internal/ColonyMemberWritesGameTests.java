package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.upgrade.DataVersion;
import io.github.izakyl.folkways.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ColonyMemberWritesGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "test/changed_site");

    static {
        PointSites.register(OWNER, (level, cell) -> level.getBlockState(cell).is(Blocks.COMPOSTER));
    }

    @GameTest(template = "empty")
    public static void colonySaveRejectsUnsupportedDataVersions(GameTestHelper helper) {
        var tag = new net.minecraft.nbt.CompoundTag();
        for (int version : List.of(DataVersion.BASELINE - 1, DataVersion.CURRENT + 1)) {
            boolean rejected = false;
            try {
                Upgrades.upgrade(Surface.COLONY_REGISTRY, tag, version);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            }
            helper.assertTrue(rejected, "unsupported data versions are rejected");
        }
        Upgrades.upgrade(Surface.COLONY_REGISTRY, tag, DataVersion.CURRENT);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rejectedRegistryCannotBeEditedOrSaved(GameTestHelper helper) {
        var tag = new net.minecraft.nbt.CompoundTag();
        tag.putInt("schemaVersion", DataVersion.BASELINE - 1);
        var original = tag.copy();
        var registry = ColonyRegistry.load(tag, helper.getLevel().registryAccess());
        registry.setDirty();
        helper.assertFalse(registry.isDirty(), "a rejected registry cannot schedule a write");
        for (Runnable mutation : List.<Runnable>of(
            () -> registry.mint(helper.getLevel().getServer()),
            () -> registry.raze(helper.getLevel().getServer(), UUID.randomUUID()),
            () -> registry.save(new net.minecraft.nbt.CompoundTag(), helper.getLevel().registryAccess()))) {
            boolean rejected = false;
            try {
                mutation.run();
            } catch (IllegalStateException expected) {
                rejected = expected.getMessage().contains("save version " + (DataVersion.BASELINE - 1) + " is unsupported");
            }
            helper.assertTrue(rejected, "an unsupported save refuses mutation with an explicit reason");
        }
        helper.assertValueEqual(tag, original, "loading a rejected save leaves its source intact");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unreadableColonyRowsSealTheWholeRegistry(GameTestHelper helper) {
        var provider = helper.getLevel().registryAccess();
        for (String missing : List.of("colonyId", "claims", "keeps")) {
            var colony = new ColonyData().save(new net.minecraft.nbt.CompoundTag());
            colony.remove(missing);
            var tag = Writer.of().children("colonies", List.of(colony), row -> row).uuids("razed", Set.of()).tag();
            tag.putInt("schemaVersion", DataVersion.CURRENT);
            var original = tag.copy();
            var registry = ColonyRegistry.load(tag, provider);
            registry.setDirty();
            helper.assertFalse(registry.isDirty(), "an unreadable row cannot schedule a write: " + missing);
            boolean refused = false;
            try {
                registry.save(new net.minecraft.nbt.CompoundTag(), provider);
            } catch (IllegalStateException expected) {
                refused = expected.getMessage().contains("unreadable or unsupported");
            }
            helper.assertTrue(refused, "an unreadable row must refuse saving: " + missing);
            helper.assertValueEqual(tag, original, "a failed load leaves every input row intact");
        }
        var malformed = Writer.of().uuids("razed", Set.of()).tag();
        malformed.putInt("schemaVersion", DataVersion.CURRENT);
        malformed.putString("colonies", "wrong type");
        var registry = ColonyRegistry.load(malformed, provider);
        registry.setDirty();
        helper.assertFalse(registry.isDirty(), "an exception reading the registry also seals it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void malformedClaimShapesSealTheWholeRegistry(GameTestHelper helper) {
        var at = WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
        Claims claims = new Claims(UUID.randomUUID());
        claims.hold(null, OWNER, new Claimed.Area(Set.of(at))).orElseThrow();
        claims.hold(null, OWNER, new Claimed.Line(List.of(at, at))).orElseThrow();
        CompoundTag current = claims.save();
        Claims restored = new Claims(UUID.randomUUID());
        restored.load(Reader.of(current));
        helper.assertValueEqual(restored.save(), current, "current area and line round trip without loss");
        for (int fault = 0; fault < 7; fault++) {
            CompoundTag colony = new ColonyData().save(new CompoundTag());
            CompoundTag held = current.copy();
            var entries = held.getList("held", Tag.TAG_COMPOUND);
            switch (fault) {
                case 0 -> held.remove("held");
                case 1 -> entries.getCompound(0).remove("owner");
                case 2 -> entries.getCompound(0).getList("area", Tag.TAG_COMPOUND).getCompound(0).remove("realm");
                case 3 -> entries.getCompound(0).getList("area", Tag.TAG_COMPOUND).getCompound(0).remove("cells");
                case 4 -> entries.getCompound(0).getList("area", Tag.TAG_COMPOUND).getCompound(0)
                    .putIntArray("cells", new int[]{1, 2});
                case 5 -> entries.getCompound(1).getList("line", Tag.TAG_COMPOUND).add(new CompoundTag());
                case 6 -> held.putString("held", "wrong type");
                default -> throw new AssertionError("unknown corruption");
            }
            colony.put("claims", held);
            CompoundTag saved = Writer.of().children("colonies", List.of(colony), row -> row)
                .uuids("razed", Set.of()).tag();
            saved.putInt("schemaVersion", DataVersion.CURRENT);
            ColonyRegistry registry = ColonyRegistry.load(saved, helper.getLevel().registryAccess());
            boolean refused = false;
            try {
                registry.save(new CompoundTag(), helper.getLevel().registryAccess());
            } catch (IllegalStateException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "malformed claims must seal the whole registry, fault " + fault);
        }
        helper.succeed();
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ColonyMemberWritesGameTests.class);
    }

    @GameTest(template = "empty")
    public static void demolishedBlocksAndPointsLeaveOnTheNextTickWithoutLosingNeighboursOrAreas(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var block = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        var point = WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 1)));
        var neighbourBlock = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 3)));
        var neighbourPoint = WorldPos.of(level, helper.absolutePos(new BlockPos(3, 1, 3)));
        level.setBlockAndUpdate(block.cell(), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(point.cell(), Blocks.COMPOSTER.defaultBlockState());
        level.setBlockAndUpdate(neighbourBlock.cell(), Blocks.CHEST.defaultBlockState());
        level.setBlockAndUpdate(neighbourPoint.cell(), Blocks.COMPOSTER.defaultBlockState());
        var blockHolding = colony.hold(OWNER, new Claimed.Block(block)).orElseThrow();
        var pointHolding = colony.hold(OWNER, new Claimed.Point(point)).orElseThrow();
        var neighbourBlockHolding = colony.hold(OWNER, new Claimed.Block(neighbourBlock)).orElseThrow();
        var neighbourPointHolding = colony.hold(OWNER, new Claimed.Point(neighbourPoint)).orElseThrow();
        var area = colony.hold(OWNER, new Claimed.Area(Set.of(block, point))).orElseThrow();
        AtomicInteger released = new AtomicInteger();
        ColonyData.find(level.getServer(), colony.id()).orElseThrow().claims().setReleasedListener((claim, why) -> {
            helper.assertTrue(why == ClaimRelease.GONE, "world changes report the gone lifecycle reason");
            released.incrementAndGet();
        });

        level.setBlockAndUpdate(block.cell(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(point.cell(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(released.get() == 0 && colony.claim(blockHolding.id()).isPresent()
            && colony.claim(pointHolding.id()).isPresent(), "block setters defer removal and callbacks until the tick boundary");
        level.setBlockAndUpdate(neighbourBlock.cell(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST));
        level.setBlockAndUpdate(neighbourPoint.cell(), Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 5));

        helper.runAfterDelay(2, () -> {
            helper.assertTrue(colony.claimAt(block).isEmpty() && colony.claimAt(point).isEmpty(),
                "the normal server tick clears both marked blocks and native points without a heartbeat or explicit reconcile");
            helper.assertTrue(released.get() == 2, "each demolished site is released once");
            helper.assertTrue(colony.claim(neighbourBlockHolding.id()).isPresent()
                && colony.claim(neighbourPointHolding.id()).isPresent(), "valid block state changes retain both kinds of site");
            helper.assertTrue(colony.claim(area.id()).isPresent(), "demolition does not erase a player's drawn area");
            level.setBlockAndUpdate(block.cell(), Blocks.CHEST.defaultBlockState());
            level.setBlockAndUpdate(point.cell(), Blocks.COMPOSTER.defaultBlockState());
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(colony.claimAt(block).isEmpty() && colony.claimAt(point).isEmpty(),
                        "placing fresh blocks after demolition does not revive either old registration");
                    helper.assertTrue(ColonyMembership.owner(level.getBlockEntity(block.cell())).isEmpty(),
                        "the replacement block entity has no inherited colony mark");
                    helper.succeed();
                } finally {
                    colony.raze();
                }
            });
        });
    }

    @GameTest(template = "empty")
    public static void changedUnloadedSitesRemainHeldWithoutLoadingTheirChunks(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var claims = ColonyData.find(level.getServer(), colony.id()).orElseThrow().claims();
        var block = WorldPos.of(level, new BlockPos(25_000_000, 80, 25_000_000));
        var point = WorldPos.of(level, new BlockPos(25_000_002, 80, 25_000_000));
        claims.hold(null, OWNER, new Claimed.Block(block)).orElseThrow();
        claims.hold(null, OWNER, new Claimed.Point(point)).orElseThrow();
        ColonyMemberWrites.blockChanged(level, block.cell());
        ColonyMemberWrites.blockChanged(level, point.cell());
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(claims.at(block).isPresent() && claims.at(point).isPresent(),
                    "an unloaded site is not evidence of demolition");
                helper.assertTrue(!level.isLoaded(block.cell()) && !level.isLoaded(point.cell()),
                    "dirty-site validation never loads absent chunks");
                helper.succeed();
            } finally {
                colony.raze();
            }
        });
    }

    @GameTest(template = "empty")
    public static void largeEditsFallBackToAReconcileInsteadOfLosingPendingRemovals(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var cell = WorldPos.of(level, helper.absolutePos(new BlockPos(1, 1, 1)));
        level.setBlockAndUpdate(cell.cell(), Blocks.CHEST.defaultBlockState());
        colony.hold(OWNER, new Claimed.Block(cell)).orElseThrow();
        ColonyMembership.leave(level.getBlockEntity(cell.cell()));
        for (int x = 0; x < 5000; x++) {
            ColonyMemberWrites.blockChanged(level, new BlockPos(24_000_000 + x, 80, 24_000_000));
        }
        helper.runAfterDelay(2, () -> {
            try {
                helper.assertTrue(colony.claimAt(cell).isEmpty(),
                    "bounded dirty tracking still reconciles invalid holdings after a large edit");
                helper.assertTrue(!level.isLoaded(new BlockPos(24_000_000, 80, 24_000_000)),
                    "overflow does not inspect or load every changed cell");
                helper.succeed();
            } finally {
                colony.raze();
            }
        });
    }
}
