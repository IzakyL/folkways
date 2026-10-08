package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.plugins.build.draft.Chosen;
import io.github.izakyl.folkways.plugins.build.draft.Draft;
import io.github.izakyl.folkways.plugins.build.draft.Round;
import io.github.izakyl.folkways.plugins.build.draft.Hint;
import io.github.izakyl.folkways.plugins.build.draft.Pattern;
import io.github.izakyl.folkways.plugins.build.draft.Patterns;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class GrowingGameTests {

    private static final ResourceLocation GROWER =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "test_grower");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GrowingGameTests.class);
    }

    private record Rig(ServerLevel level, ColonyView view, AtomicReference<CompoundTag> saved, Colony colony) {
    }

    private static Rig rig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AtomicReference<CompoundTag> saved = new AtomicReference<>(new CompoundTag());
        ColonyView view = new ColonyView() {
            public ServerLevel level() {
                return level;
            }

            public ResourceKey<Level> dimension() {
                return level.dimension();
            }

            public List<Holding> holdings() {
                return List.of();
            }

            public Set<BlockPos> blocks(ResourceLocation owner) {
                return Set.of();
            }

            public List<Resident> residents() {
                return List.of();
            }

            public int rankOf(Resident resident, String perk) {
                return 0;
            }
        };
        Colony colony = (Colony) Proxy.newProxyInstance(GrowingGameTests.class.getClassLoader(),
            new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                case "kept" -> saved.get();
                case "keep" -> {
                    saved.set(((CompoundTag) args[1]).copy());
                    yield null;
                }
                case "views" -> List.of(view);
                case "view" -> view;
                default -> null;
            });
        return new Rig(level, view, saved, colony);
    }

    private static Growing begun(GameTestHelper helper, Rig rig) {
        Pattern pattern = Patterns.find(GROWER).orElseThrow(() -> new AssertionError("test_grower is not loaded"));
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        Hint hint = new Hint.Zone(start, start.offset(7, 0, 0));
        return Growing.begun(GROWER, "", hint, Chosen.load(pattern.knobs(), new CompoundTag()).save(), Direction.NORTH,
            0L, WorldPos.of(rig.level(), start), Optional.empty(), "");
    }

    private static int standing(Rig rig) {
        return rig.saved().get().getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND).size();
    }

    private static int growing(Rig rig) {
        return rig.saved().get().getList("growing", Tag.TAG_COMPOUND).size();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void eachFinishedRoundFilesTheNextUntilThePatternIsDone(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        Growing fresh = begun(helper, rig);
        helper.assertTrue(presence.grow(rig.level(), fresh).isEmpty(), "the first round is drawn and filed");
        helper.assertValueEqual(standing(rig), 1, "one order stands for the first round");
        BlockPos start = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int round = 0; round < 3; round++) {
            BlockPos step = start.offset(round, 0, 0);
            helper.assertTrue(rig.level().getBlockState(step).isAir(), "round " + round + " is not built for free");
            presence.settle(rig.level().getServer());
            helper.assertValueEqual(standing(rig), 1, "the order for round " + round + " waits to be built");
            rig.level().setBlock(step, Blocks.STONE.defaultBlockState(), 3);
            presence = new BuildPresence(rig.colony(), rig.level().getServer());
            presence.settle(rig.level().getServer());
        }
        helper.assertValueEqual(standing(rig), 0, "no order stands once the pattern answers nothing");
        helper.assertValueEqual(growing(rig), 0, "the growing retires when it is done");
        helper.assertTrue(rig.level().getBlockState(start.offset(3, 0, 0)).isAir(), "no fourth step");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void stoppingAGrowingCancelsItsStandingRound(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        Growing fresh = begun(helper, rig);
        helper.assertTrue(presence.grow(rig.level(), fresh).isEmpty(), "the first round is filed");
        presence.act(BuildContent.PAGE.id(), BuildAct.stopping(fresh.id()), Optional.empty(), rig.view());
        helper.assertValueEqual(growing(rig), 0, "the growing is stopped");
        presence.settle(rig.level().getServer());
        presence.settle(rig.level().getServer());
        helper.assertValueEqual(standing(rig), 0, "its round is cancelled");
        helper.assertValueEqual(growing(rig), 0, "and nothing grows again");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void cancellingTheStandingRoundStopsTheGrowing(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        helper.assertTrue(presence.grow(rig.level(), begun(helper, rig)).isEmpty(), "the first round is filed");
        CompoundTag order = rig.saved().get().getCompound("book").getList("buildOrders", Tag.TAG_COMPOUND)
            .getCompound(0);
        presence.act(BuildContent.PAGE.id(), BuildAct.cancelling(order.getUUID("id")), Optional.empty(), rig.view());
        presence.settle(rig.level().getServer());
        presence.settle(rig.level().getServer());
        helper.assertValueEqual(standing(rig), 0, "the round is cancelled");
        helper.assertValueEqual(growing(rig), 0, "and the growing with it");
        helper.succeed();
    }

    @GameTest(template = "empty_large", timeoutTicks = 100)
    public static void aPatternDrawnOnTheServerIsFiledWholeFarPastFiveHundredCells(GameTestHelper helper) {
        Rig rig = rig(helper);
        BuildPresence presence = new BuildPresence(rig.colony(), rig.level().getServer());
        Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "test_slab"))
            .orElseThrow(() -> new AssertionError("test_slab is not loaded"));
        BlockPos start = helper.absolutePos(new BlockPos(1, 2, 1));
        Hint hint = new Hint.Zone(start, start.offset(39, 0, 39));
        Rounds.Once once = Rounds.drawOnce(rig.level(), pattern, hint,
            Chosen.load(pattern.knobs(), new CompoundTag()), Direction.NORTH);
        if (!(once instanceof Rounds.Once.Drafted drafted)) {
            helper.fail("the slab should be drawn, got " + once);
            return;
        }
        helper.assertValueEqual(drafted.blueprint().blocks().size(), 1600, "every cell of the slab is kept");
        helper.assertValueEqual(drafted.corner(), start, "the slab lies where it was marked");
        helper.assertTrue(presence.file(rig.level(), drafted.blueprint(), drafted.corner(), "", Optional.empty(), "")
            .isPresent(), "and it is filed as one order");
        helper.assertTrue(rig.level().getBlockState(start).isAir(), "filing builds nothing for free");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void aCommissionCrossesTheWireIntact(GameTestHelper helper) {
        Hint hint = new Hint.Path(List.of(new BlockPos(1, 2, 3), new BlockPos(40, 5, -6)));
        CompoundTag settings = new CompoundTag();
        settings.putInt("width", 7);
        CommissionPacket sent = new CommissionPacket(java.util.UUID.randomUUID(),
            ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "arch_bridge"), hint.save(), settings,
            Direction.EAST, "西桥", true);
        net.minecraft.network.RegistryFriendlyByteBuf buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
            io.netty.buffer.Unpooled.buffer(), helper.getLevel().registryAccess());
        CommissionPacket.STREAM_CODEC.encode(buffer, sent);
        CommissionPacket read = CommissionPacket.STREAM_CODEC.decode(buffer);
        helper.assertValueEqual(read.colonyId(), sent.colonyId(), "the colony");
        helper.assertValueEqual(read.pattern(), sent.pattern(), "the pattern");
        helper.assertValueEqual(Hint.load(read.hint()).orElseThrow(), hint, "the mark");
        helper.assertValueEqual(read.settings().getInt("width"), 7, "the settings");
        helper.assertValueEqual(read.facing(), Direction.EAST, "the facing");
        helper.assertValueEqual(read.siteName(), "西桥", "the site's name");
        helper.assertTrue(read.raiseInCreative(), "whether to raise at once");
        helper.succeed();
    }

    private static final ResourceLocation MINE = ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "mine");

    private static CompoundTag defaults(Pattern pattern, String level) {
        CompoundTag picked = new CompoundTag();
        picked.putString("level", "pattern:" + level);
        Chosen loaded = Chosen.load(pattern.knobs(), picked);
        return Chosen.of(pattern.knobs(), new Settings() {
            public boolean flag(String key) {
                return loaded.flag(key);
            }

            public int count(String key) {
                return loaded.count(key);
            }

            public ResourceLocation choice(String key) {
                return loaded.choice(key);
            }

            public Optional<ItemSpec> items(String key) {
                for (Schema.Setting setting : pattern.knobs().settings()) {
                    if (setting instanceof Schema.Setting.Items slot && slot.key().equals(key)) {
                        return Optional.of(ItemSpec.of(slot.byDefault().get(0).id()));
                    }
                }
                return Optional.empty();
            }
        }).save();
    }

    @GameTest(template = "empty_large", timeoutTicks = 200)
    public static void aMineRaisesItsHeadframeAsAnOrderThenStartsItsStairOnceThatStands(GameTestHelper helper) {
        Rig rig = rig(helper);
        ServerLevel level = rig.level();
        BlockPos ground = helper.absolutePos(new BlockPos(10, 0, 10));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlock(ground.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        Pattern mine = Patterns.find(MINE).orElseThrow(() -> new AssertionError("the mine is not loaded"));
        BlockPos mark = ground.above();
        Hint hint = new Hint.Zone(mark, mark.offset(2, 0, 2));
        Growing fresh = Growing.begun(MINE, "", hint, defaults(mine, "shallow"), Direction.NORTH, 3L,
            WorldPos.of(level, mark), Optional.empty(), "");
        BuildPresence presence = new BuildPresence(rig.colony(), level.getServer());
        Optional<net.minecraft.network.chat.Component> refused = presence.grow(level, fresh);
        helper.assertTrue(refused.isEmpty(), "the headframe is filed, not " + refused.map(Object::toString).orElse(""));
        helper.assertValueEqual(standing(rig), 1, "one order stands for the headframe");
        if (!(Rounds.draw(level, fresh) instanceof Round.Grew head)) {
            helper.fail("the headframe draws again the same way");
            return;
        }
        int placed = 0;
        for (Draft.Cell cell : head.ready().draft().cells()) {
            BlockPos at = head.ready().corner().offset(cell.offset());
            if (!cell.state().isAir()) {
                helper.assertTrue(!level.getBlockState(at).equals(cell.state()),
                    "nothing of the headframe is there before it is built, at " + at.toShortString());
                placed++;
            }
        }
        helper.assertTrue(placed > 60, "a headframe of posts, deck, rails and roof, got " + placed + " blocks");
        for (Draft.Cell cell : head.ready().draft().cells()) {
            level.setBlock(head.ready().corner().offset(cell.offset()), cell.state(), 3);
        }
        presence = new BuildPresence(rig.colony(), level.getServer());
        presence.settle(level.getServer());
        helper.assertValueEqual(standing(rig), 1, "the stair is filed once the headframe stands");
        CompoundTag growing = rig.saved().get().getList("growing", Tag.TAG_COMPOUND).getCompound(0);
        helper.assertValueEqual(growing.getInt("round"), 2, "the mine waits on its first flight");
        helper.succeed();
    }
}
