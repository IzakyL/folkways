package io.github.izakyl.folkways.core.shell.protection;

import com.mojang.authlib.GameProfile;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.Protection;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

// Loaded only when FTB Chunks is installed; its API types never escape this bridge.
public final class FtbProtection {

    private FtbProtection() {
    }

    public static boolean allowed(ServerLevel level, Worker who, WorldPos at, boolean edit) {
        var resolved = WorldSpaces.resolve(level.getServer(), at);
        if (resolved.isEmpty()) {
            return false;
        }
        ServerLevel target = resolved.get().level();
        Protection protection = edit ? Protection.EDIT_BLOCK : Protection.INTERACT_BLOCK;
        var world = WorldSpaces.world(target, at);
        if (world.isEmpty() || !check(target, who, BlockPos.containing(world.get()), protection, null)) {
            return false;
        }
        BlockPos storage = resolved.get().cell();
        if (!target.isLoaded(storage)) {
            return false;
        }
        var state = target.getBlockState(storage);
        if (!edit && state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = storage.relative(ChestBlock.getConnectedDirection(state));
            return target.isLoaded(other) && WorldSpaces.world(target, WorldSpaces.at(target, other))
                .map(pos -> check(target, who, BlockPos.containing(pos), protection, null)).orElse(false);
        }
        return true;
    }

    public static boolean fluid(ServerLevel level, Worker who, WorldPos at) {
        return WorldSpaces.resolve(level.getServer(), at)
            .flatMap(resolved -> WorldSpaces.world(resolved.level(), at)
                .map(world -> check(resolved.level(), who, BlockPos.containing(world),
                    Protection.EDIT_FLUID, null))).orElse(false);
    }

    public static boolean entity(ServerLevel level, Worker who, UUID id) {
        Entity entity = level.getEntity(id);
        return entity != null && check(level, who, entity.blockPosition(), Protection.INTERACT_ENTITY, entity);
    }

    private static boolean check(ServerLevel level, Worker who, BlockPos cell,
            Protection protection, Entity target) {
        if (!FTBChunksAPI.api().isManagerLoaded()) {
            return false;
        }
        Optional<UUID> founder = Bodies.of(who.body()).flatMap(body -> body.colonyId())
            .flatMap(id -> Colonies.of(level.getServer(), id))
            .flatMap(colony -> Charters.of(level.getServer(), colony).founder());
        if (founder.isEmpty()) {
            return false;
        }
        var actor = FakePlayerFactory.get(level, new GameProfile(founder.get(), "[Folkways]"));
        return !FTBChunksAPI.api().getManager().shouldPreventInteraction(actor,
            InteractionHand.MAIN_HAND, cell, protection, target);
    }
}
