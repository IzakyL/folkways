package io.github.izakyl.folkways.plugins.cookery;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Hold;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class KitchenBookingsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(KitchenBookingsGameTests.class);
    }

    @GameTest(template = "empty")
    public static void withdrawingAndReadmittingNeverRevivesAnOldUntakenHold(GameTestHelper helper) {
        KitchenBookings bookings = new KitchenBookings();
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        Hold old = bookings.hold(at, UUID.randomUUID());
        bookings.withdraw(at);
        helper.assertTrue(!bookings.free(at), "an unmarked kitchen is not available for recovery");
        Hold whileWithdrawn = bookings.hold(at, UUID.randomUUID());
        helper.assertTrue(whileWithdrawn.take().isPresent(), "a withdrawn kitchen rejects newly proposed work");
        bookings.admit(at);
        helper.assertTrue(old.take().isPresent(), "a hold from before withdrawal cannot be taken again");
        helper.assertTrue(whileWithdrawn.take().isPresent(), "a hold created while withdrawn cannot revive either");
        Hold current = bookings.hold(at, UUID.randomUUID());
        helper.assertTrue(current.take().isEmpty(), "a fresh hold after re-marking can be taken");
        current.release(Ending.DONE);
        helper.assertTrue(bookings.free(at), "fresh work releases the new generation normally");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void staleRecoveryIdsCannotReleaseOrBorrowTheNewGeneration(GameTestHelper helper) {
        KitchenBookings bookings = new KitchenBookings();
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        UUID stable = UUID.randomUUID();
        long oldGeneration = bookings.generation(at);
        Hold old = bookings.hold(at, stable);
        helper.assertTrue(old.take().isEmpty(), "the original recovery turn is taken");
        bookings.withdraw(at);
        bookings.admit(at);
        long currentGeneration = bookings.generation(at);
        Hold current = bookings.hold(at, stable);
        helper.assertTrue(current.take().isEmpty(), "the stable id can represent a new recovery generation");
        helper.assertTrue(bookings.cancelled(at, stable, oldGeneration), "the old node cannot borrow its replacement's turn");
        helper.assertTrue(bookings.valid(at, stable, currentGeneration), "the new node's generation is valid");
        helper.assertTrue(old.take().isPresent(), "the old taken hold is still rejected after re-marking");
        old.release(Ending.REVOKED);
        helper.assertTrue(bookings.first(at, stable), "releasing the old hold cannot remove the new same-id hold");
        current.release(Ending.DONE);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void queuedTurnsRemainValidUntilTheirOwnStationIsWithdrawn(GameTestHelper helper) {
        KitchenBookings bookings = new KitchenBookings();
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        WorldPos other = at.at(at.cell().east());
        UUID first = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        Hold one = bookings.hold(at, first);
        Hold two = bookings.hold(at, next);
        helper.assertTrue(one.take().isEmpty() && two.take().isEmpty(), "an unmanaged test station allows a queue");
        long generation = bookings.generation(at);
        bookings.admit(at);
        bookings.admit(at);
        helper.assertValueEqual(bookings.generation(at), generation, "repeated admission preserves accepted work");
        helper.assertTrue(bookings.valid(at, next, generation) && !bookings.first(at, next),
            "a queued turn waits normally without being canceled");
        helper.assertTrue(two.after().contains(first), "the queued turn retains its predecessor");
        bookings.withdraw(other);
        helper.assertTrue(bookings.valid(at, first, generation), "withdrawing another station does not cancel this one");
        bookings.withdraw(at);
        helper.assertTrue(bookings.cancelled(at, first, generation) && bookings.cancelled(at, next, generation),
            "withdrawing this station cancels every queued turn");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void clearingBookingsInvalidatesOldHoldsWithoutDisablingNewUnmanagedStations(GameTestHelper helper) {
        KitchenBookings bookings = new KitchenBookings();
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        UUID stable = UUID.randomUUID();
        Hold old = bookings.hold(at, stable);
        long generation = bookings.generation(at);
        helper.assertTrue(old.take().isEmpty(), "the old session has an accepted turn");
        bookings.clear();
        Hold current = bookings.hold(at, stable);
        helper.assertTrue(current.take().isEmpty(), "the new session remains usable without a managed presence");
        helper.assertTrue(bookings.cancelled(at, stable, generation), "the old session's node is no longer valid");
        helper.assertTrue(old.take().isPresent(), "the old session's hold cannot be retaken");
        old.release(Ending.DROPPED);
        helper.assertTrue(bookings.first(at, stable), "old cleanup cannot release the new session's turn");
        current.release(Ending.DONE);
        helper.succeed();
    }
}
