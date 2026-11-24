package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySites;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

final class ReadFixture implements AutoCloseable {

    int permission;

    final ServerPlayer player;

    final Colony colony;

    final ResidentEntity person;

    final BlockPos chest;

    ReadFixture(GameTestHelper helper) {
        this(helper, 0);
    }

    ReadFixture(GameTestHelper helper, int permission) {
        this.permission = permission;
        var level = helper.getLevel();
        player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "assistant-test"),
                ClientInformation.createDefault()) {
            @Override
            public boolean hasPermissions(int required) {
                return ReadFixture.this.permission >= required;
            }
        };
        colony = Charters.found(player, "Assistant fixture");
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(colony.id()));
        chest = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlockAndUpdate(chest.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        var delegation = ColonySites.accepting(level, chest).orElseThrow();
        colony.hold(delegation.id(), new Claimed.Block(WorldPos.of(level, chest))).orElseThrow();
        var container = (ChestBlockEntity) level.getBlockEntity(chest);
        container.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 101L);
        colony.stores(delegation.id(), level.dimension(), List.of(WorldPos.of(level, chest)));
        BlockPos bed = chest.offset(3, 0, 0);
        level.setBlockAndUpdate(bed.below(), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(bed, Blocks.RED_BED.defaultBlockState());
        ColonySites.accepting(level, bed).ifPresent(beds -> colony.hold(beds.id(),
                beds.shape() instanceof Shape.Point ? new Claimed.Point(WorldPos.of(level, bed))
                        : new Claimed.Block(WorldPos.of(level, bed))).orElseThrow());
        person = PersonBody.RESIDENT.get().create(level);
        person.setPos(chest.above().getCenter());
        level.addFreshEntity(person);
        colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
    }

    void warm() {
        // Populate Minecraft's small read cache without refreshing UNKNOWN chunk tickets.
        for (var holding : colony.claims()) {
            holding.what().singleCell().flatMap(at -> WorldSpaces.resolve(player.getServer(), at)).ifPresent(at ->
                    at.level().getChunkSource().getChunkNow(at.cell().getX() >> 4, at.cell().getZ() >> 4));
        }
    }

    JsonObject at(BlockPos pos) {
        JsonObject at = new JsonObject();
        at.addProperty("dimension", player.serverLevel().dimension().location().toString());
        at.addProperty("x", pos.getX());
        at.addProperty("y", pos.getY());
        at.addProperty("z", pos.getZ());
        return at;
    }

    @Override
    public void close() {
        colony.raze();
        person.discard();
    }
}
