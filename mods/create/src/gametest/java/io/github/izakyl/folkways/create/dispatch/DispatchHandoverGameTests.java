package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonySnapshot;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DispatchHandoverGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DispatchHandoverGameTests.class);
    }

    // The book takes a network on only by its stock link or ticker, a chain net by any frogport or chain conveyor on
    // it, and leaves a postbox to be given as a member, and a packager, which stands where the kinds meet, to whatever
    // else would take it.
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void eachKindOfNetworkIsHandedOverByItsOwnBlocks(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        ServerLevel level = helper.getLevel();
        UUID network = UUID.randomUUID();
        BlockPos link = helper.absolutePos(new BlockPos(0, 1, 0));
        BlockPos packager = helper.absolutePos(new BlockPos(1, 1, 0));
        BlockPos chainA = helper.absolutePos(new BlockPos(0, 3, 2));
        BlockPos chainB = helper.absolutePos(new BlockPos(4, 3, 2));
        BlockPos onChain = helper.absolutePos(new BlockPos(1, 1, 2));
        BlockPos offChain = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos postbox = helper.absolutePos(new BlockPos(3, 1, 0));
        PackageNetworks.install((PackageNetwork) Proxy.newProxyInstance(PackageNetwork.class.getClassLoader(),
            new Class<?>[] {PackageNetwork.class}, (proxy, method, args) -> switch (method.getName()) {
                case "linkerAt" -> args[1].equals(link) ? Optional.of(network) : Optional.empty();
                case "chainAt" -> args[1].equals(chainA) || args[1].equals(chainB) ? Optional.of(args[1])
                    : args[1].equals(onChain) ? Optional.of(chainA) : Optional.empty();
                case "frogport" -> args[1].equals(onChain) || args[1].equals(offChain);
                case "chainNet" -> Set.of(chainA, chainB);
                case "mayAdministrate" -> true;
                default -> throw new AssertionError("unexpected network call: " + method.getName());
            }));
        try {
            AtomicReference<CompoundTag> kept = new AtomicReference<>(new CompoundTag());
            UUID colonyId = UUID.randomUUID();
            Colony colony = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(),
                new Class<?>[] {Colony.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "kept" -> kept.get().copy();
                    case "keep" -> { kept.set(((CompoundTag) args[1]).copy()); yield null; }
                    case "id" -> colonyId;
                    default -> throw new AssertionError(method.getName());
                });
            DispatchPresence dispatch = new DispatchPresence(colony);
            var view = ColonySnapshot.of(new ColonyData(), level);
            ServerPlayer player = FakePlayerFactory.getMinecraft(level);

            helper.assertTrue(dispatch.pointedAt(packager, view, player).isEmpty() && !dispatch.holds(network),
                "a packager hands over nothing of the networks it stands between");
            helper.assertTrue(went(dispatch.pointedAt(link, view, player)) && dispatch.holds(network),
                "the stock link takes the network on");
            helper.assertTrue(!dispatch.holdsChain(level, chainA),
                "taking the network on hands over none of what it ships on");
            helper.assertTrue(went(dispatch.pointedAt(onChain, view, player))
                    && dispatch.holdsChain(level, chainA) && dispatch.holdsChain(level, chainB),
                "a frogport on a chain takes the whole chain net on");
            helper.assertTrue(went(dispatch.pointedAt(chainB, view, player)) && !dispatch.holdsChain(level, chainA),
                "any chain conveyor of the net gives the whole of it back");
            helper.assertTrue(refusal(dispatch.pointedAt(offChain, view, player)).equals("off_chain"),
                "a frogport on no chain hands over nothing");
            helper.assertTrue(dispatch.pointedAt(postbox, view, player).isEmpty(),
                "a postbox is left to be given to the colony as a member");
            helper.assertTrue(went(dispatch.pointedAt(link, view, player)) && !dispatch.holds(network),
                "the same click on the link gives the network back");

            dispatch.pointedAt(onChain, view, player);
            DispatchPresence restored = new DispatchPresence(colony);
            helper.assertTrue(restored.holdsChain(level, chainB), "a chain net taken on outlasts a save");
            helper.succeed();
        } finally {
            PackageNetworks.install(original);
        }
    }

    private static boolean went(Optional<Attempt> attempt) {
        return attempt.isPresent() && attempt.get().refusal().isEmpty();
    }

    private static String refusal(Optional<Attempt> attempt) {
        return attempt.flatMap(Attempt::refusal).map(why -> {
            String key = why.key();
            return key.substring(key.lastIndexOf('.') + 1);
        }).orElse("");
    }
}
