package io.github.izakyl.folkways.core.front.engine.net;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyLooks;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyMetrics;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySites;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;

public final class FacilityCardReads {

    private FacilityCardReads() {
    }

    public static void assertPure(GameTestHelper helper, List<ResourceLocation> extraBlocks) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        var person = PersonBody.RESIDENT.get().create(level);
        var origin = helper.absolutePos(new BlockPos(1, 1, 1));
        List<BlockPos> blocks = new ArrayList<>();
        try {
            for (BlockPos at : BlockPos.betweenClosed(origin.below(), origin.offset(13, -1, 27))) {
                level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState());
            }
            var kinds = new ArrayList<>(List.of("minecraft:chest", "minecraft:barrel", "minecraft:furnace",
                "minecraft:crafting_table", "minecraft:composter", "minecraft:red_bed",
                "folkways_vanilla:creative_supply_box"));
            extraBlocks.forEach(id -> kinds.add(id.toString()));
            int index = 0;
            for (String id : kinds) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
                helper.assertTrue(block != Blocks.AIR, "fixture block exists: " + id);
                BlockPos at = origin.offset((index % 6) * 2, 0, (index / 6) * 3);
                index++;
                level.setBlockAndUpdate(at, block.defaultBlockState());
                blocks.add(at);
                ColonySites.accepting(level, at).ifPresent(delegation -> {
                    var cell = WorldPos.of(level, ColonySites.anchor(level, at));
                    colony.hold(delegation.id(), delegation.shape() instanceof Shape.Point
                        ? new Claimed.Point(cell) : new Claimed.Block(cell))
                        .orElseThrow();
                });
            }
            var chest = (ChestBlockEntity) level.getBlockEntity(blocks.getFirst());
            chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 101L);
            colony.stores(WaresContent.ID, level.dimension(), List.of(WorldPos.of(level, blocks.getFirst())));
            var front = ColonyFront.of(colony);
            int zone = 0;
            for (var declaration : Enrollments.delegations()) {
                if (declaration.shape() instanceof Shape.Volume) {
                    BlockPos at = origin.offset((zone % 6) * 2, 0, 14 + (zone / 6) * 2);
                    zone++;
                    front.addZone(level, declaration.id(), Set.of(at), ColonySettings.empty()).orElseThrow();
                } else if (declaration.shape() instanceof Shape.Path) {
                    BlockPos at = origin.offset((zone % 6) * 2, 0, 14 + (zone / 6) * 2);
                    zone++;
                    front.addPath(level, declaration.id(), List.of(at, at.south()), ColonySettings.empty())
                        .orElseThrow();
                }
            }
            person.setPos(origin.offset(1, 0, 1).getCenter());
            level.addFreshEntity(person);
            colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            for (String pass : List.of("cold", "warm")) {
                if (pass.equals("cold")) {
                    ColonyFront.forget(colony.id());
                }
                Purity.assertPure(helper, pass + " facility cards, looks, metrics and overview", () -> {
                    for (var facility : Operations.all(colony)) {
                        facility.facility().lines(level, Map.of());
                    }
                    ColonyLooks.of(colony, person, colony.views(level.getServer()));
                    ColonyMetrics.of(level.getServer(), colony);
                    FolkwaysUiNetworkEvents.buildOverview(level, colony);
                });
                helper.assertTrue(chest.getLootTable() != null, "facility cards and metrics retain unopened loot");
                if (pass.equals("cold")) {
                    ColonyFront.refresh(colony);
                    ColonyData.find(level.getServer(), colony.id()).orElseThrow()
                        .works().pulse(level.getServer());
                }
            }
        } finally {
            person.discard();
            colony.raze();
        }
    }
}
