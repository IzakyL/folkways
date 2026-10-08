package io.github.izakyl.folkways.plugins.rail.domain;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleItem;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.foundation.utility.CreateLang;
import io.github.izakyl.folkways.core.api.colony.Books;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ConductorSeat {

    private ConductorSeat() {
    }

    public static boolean handle(Player player, InteractionHand hand, BlockPos localPos,
            AbstractContraptionEntity contraptionEntity) {
        if (hand != InteractionHand.MAIN_HAND) {
            return false;
        }
        if (!(contraptionEntity instanceof CarriageContraptionEntity carriageEntity)
            || !(carriageEntity.getContraption() instanceof CarriageContraption contraption)) {
            return false;
        }
        if (!contraption.conductorSeats.containsKey(localPos)) {
            return false;
        }
        Carriage carriage = carriageEntity.getCarriage();
        Train train = carriage == null ? null : carriage.train;
        if (train == null) {
            return false;
        }

        ItemStack held = player.getItemInHand(hand);
        boolean schedule = held.getItem() instanceof ScheduleItem;
        if (!schedule && !held.isEmpty()) {
            return false;
        }
        Optional<UUID> colony = Books.boundColony(player.getOffhandItem());
        if (colony.isEmpty()) {
            if (schedule && player.level().isClientSide) {
                player.displayClientMessage(Component.translatable("folkways.rail.no_book"), true);
            }
            return false;
        }
        if (player.level().isClientSide) {
            return true;
        }
        if (!(player instanceof ServerPlayer server)) {
            return false;
        }
        Optional<RailPresence> trains = Books.ofHeldBook(server, colony.get())
            .flatMap(works -> RailContent.presenceIn(works.service(RailContent.ID, Object.class)));
        if (trains.isEmpty()) {
            return true;
        }
        Optional<RailPresence> holder = RailContent.holderOf(server.getServer(), train.id);
        if (holder.isPresent() && holder.get() != trains.get()) {
            AllSoundEvents.DENY.playOnServer(server.level(), server.blockPosition(), 1, 1);
            server.displayClientMessage(
                Component.translatable(RailRefusal.ANOTHER_COLONY.translationKey()), true);
            return true;
        }
        return schedule
            ? takeOn(server, hand, train, trains.get(), held)
            : giveBack(server, hand, train, trains.get());
    }

    private static boolean takeOn(ServerPlayer player, InteractionHand hand, Train train,
            RailPresence trains, ItemStack held) {
        ScheduleRuntime runtime = train.runtime;
        if (runtime.getSchedule() != null) {
            AllSoundEvents.DENY.playOnServer(player.level(), player.blockPosition(), 1, 1);
            player.displayClientMessage(
                CreateLang.translateDirect("schedule.remove_with_empty_hand"), true);
            return true;
        }
        Schedule schedule = ScheduleItem.getSchedule(player.registryAccess(), held);
        if (schedule == null) {
            return true;
        }
        if (schedule.entries.isEmpty()) {
            AllSoundEvents.DENY.playOnServer(player.level(), player.blockPosition(), 1, 1);
            player.displayClientMessage(CreateLang.translateDirect("schedule.no_stops"), true);
            return true;
        }
        runtime.setSchedule(schedule, false);
        held.shrink(1);
        trains.take(train.id);
        AllSoundEvents.CONFIRM.playOnServer(player.level(), player.blockPosition(), 1, 1);
        player.displayClientMessage(
            Component.translatable("folkways.rail.taken_on").withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    private static boolean giveBack(ServerPlayer player, InteractionHand hand, Train train,
            RailPresence trains) {
        ScheduleRuntime runtime = train.runtime;
        boolean hadSchedule = runtime.getSchedule() != null;
        if (hadSchedule) {
            player.setItemInHand(hand, runtime.returnSchedule(player.registryAccess()));
            AllSoundEvents.playItemPickup(player);
        }
        if (trains.release(train.id)) {
            player.displayClientMessage(Component.translatable("folkways.rail.given_back"), true);
        } else if (hadSchedule) {
            player.displayClientMessage(CreateLang.translateDirect("schedule.removed_from_train"), true);
        }
        return true;
    }

}
