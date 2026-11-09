package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.base.persist.upgrade.NamespaceIds;
import io.github.izakyl.folkways.core.base.persist.upgrade.Surface;
import io.github.izakyl.folkways.core.base.persist.upgrade.Upgrades;
import io.github.izakyl.folkways.carving.CandBConfig;
import io.github.izakyl.folkways.carving.CandBStructureCodec;
import io.github.izakyl.folkways.carving.FolkwaysCarvingMod;
import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import io.github.izakyl.folkways.vanilla.build.draft.DraftTemplate;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import io.github.izakyl.folkways.vanilla.build.draft.Templates;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCarvingMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_carving")
@PrefixGameTestTemplate(false)
public final class CarvingUpgradeGameTests {
    private CarvingUpgradeGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(CarvingUpgradeGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void releasedPatternRecipesMigrateInsideTheBuildKeep(GameTestHelper helper) {
        var names = List.of("curved_gallery", "gothic_cloister", "pavilion", "rampart", "rotunda",
            "stone_arch_bridge", "torii");
        var recipes = names.stream().map(name -> new Drafted(UUID.randomUUID(),
            ResourceLocation.parse("folkways_chiselsandbits:" + name),
            new Hint.Zone(BlockPos.ZERO, new BlockPos(10, 0, 10)),
            Writer.of().integer("width", 11).tag(), Direction.EAST)).toList();
        for (int version : List.of(1, 2)) {
            CompoundTag keeps = new CompoundTag();
            keeps.put("folkways:build", Writer.of().children("drafted", recipes, Drafted::save).tag());
            CompoundTag raw = Writer.of().children("colonies", List.of(keeps),
                value -> Writer.of().blob("keeps", value).tag()).tag();
            Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, version);
            var migrated = Reader.of(raw.getList("colonies", 10).getCompound(0).getCompound("keeps")
                .getCompound("folkways_vanilla:build")).requiredChildren("drafted");
            helper.assertValueEqual(migrated.size(), recipes.size(), "all released recipes survive v" + version);
            for (int index = 0; index < recipes.size(); index++) {
                Drafted before = recipes.get(index);
                Drafted after = Drafted.load(migrated.get(index)).orElseThrow();
                var current = ResourceLocation.parse("folkways_carving:" + names.get(index));
                helper.assertValueEqual(after, new Drafted(before.order(), current, before.hint(),
                    before.settings(), before.facing()), "recipe fields survive migration");
                helper.assertTrue(Patterns.find(after.pattern()).isPresent(), "the migrated recipe resolves");
                helper.assertValueEqual(NamespaceIds.current(before.pattern() + ".star"), current + ".star",
                    "the released resource id has an exact mapping too");
            }
            CompoundTag once = raw.copy();
            Upgrades.upgrade(Surface.COLONY_REGISTRY, raw, version);
            helper.assertValueEqual(raw, once, "migration is idempotent");
        }
        helper.assertValueEqual(NamespaceIds.current("folkways_chiselsandbits:unknown_pattern"),
            "folkways_chiselsandbits:unknown_pattern", "unknown ids are not inferred from the old prefix");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void exportedLegacyFilesNormalizeBeforeHandlerValidation(GameTestHelper helper) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("."), "carving-import-");
        Path file = directory.resolve("legacy.nbt");
        try {
            for (String descriptorKey : List.of("folkways:structure", StructureCompat.DESCRIPTOR)) {
                for (boolean compressed : List.of(true, false)) {
                    StateEntryStorage storage = new StateEntryStorage(8);
                    storage.setBlockInformation(0, 0, 0,
                        new BlockInformation(Blocks.STONE.defaultBlockState(), Optional.empty()));
                    CompoundTag data = CandBStructureCodec.encode(storage);
                    data.putBoolean("folkways:schematic", data.getBoolean("folkways_carving:schematic"));
                    data.remove("folkways_carving:schematic");
                    data.putInt("folkways:resolution", data.getInt("folkways_carving:resolution"));
                    data.remove("folkways_carving:resolution");
                    data.put(descriptorKey, Writer.of().string("id", "folkways:chiselsandbits")
                        .integer("version", 1).tag());
                    CompoundTag legacy = CandBTestData.structure(data);
                    if (compressed) {
                        NbtIo.writeCompressed(legacy, file);
                    } else {
                        try (var output = new DataOutputStream(Files.newOutputStream(file))) {
                            NbtIo.write(legacy, output);
                        }
                    }
                    CompoundTag read = Templates.read(file, () -> Files.newInputStream(file)).orElseThrow();
                    Blueprint blueprint = StructureNbt.toBlueprint(read, helper.getLevel().registryAccess(),
                        UUID.randomUUID(), "legacy", 0, StructureNbt.AirMeaning.UNCONSTRAINED, 16).orElseThrow();
                    CompoundTag normalized = blueprint.blocks().getFirst().data().tag();
                    helper.assertValueEqual(normalized.getCompound(StructureCompat.DESCRIPTOR).getString("id"),
                        "folkways_carving:chiselsandbits", "disk blueprint resolves the current handler");
                    helper.assertValueEqual(normalized.getInt("folkways_carving:resolution"), 8,
                        "legacy resolution is retained before decoding");
                    helper.assertTrue(normalized.getBoolean("folkways_carving:schematic"),
                        "legacy schematic marker is retained");
                    blueprint.prepare().join().confirm(helper.getLevel());
                    var piece = DraftTemplate.pieceOf(read);
                    StructurePayload payload = piece.payloads().get(BlockPos.ZERO);
                    StructureCompat.inspect(CandBTestData.state(), payload);
                    helper.assertValueEqual(CandBStructureCodec.decode(payload.tag()).getSize(), 8,
                        "the pattern-piece entry also decodes at the released resolution");
                    helper.assertValueEqual(read, legacy, "import does not mutate the source NBT");
                    helper.assertValueEqual(Templates.read(file, () -> Files.newInputStream(file)).orElseThrow(),
                        legacy, "the exported disk file stays unchanged");
                    CompoundTag saved = blueprint.save(helper.getLevel().registryAccess());
                    Blueprint.load(Reader.of(saved), helper.getLevel().registryAccess()).orElseThrow()
                        .confirm(helper.getLevel());
                }
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void serverConfigTakesTheModsOwnFilename(GameTestHelper helper) {
        var configs = ModConfigs.getModConfigs(FolkwaysCarvingMod.ID).stream()
            .filter(config -> config.getType() == ModConfig.Type.SERVER).toList();
        helper.assertValueEqual(configs.size(), 1, "one server config belongs to the renamed mod");
        ModConfig config = configs.getFirst();
        helper.assertValueEqual(config.getFileName(), "folkways_carving-server.toml",
            "the server config takes the mod's own filename; ConfigRenames moves the released one");
        helper.assertTrue(config.getSpec() == CandBConfig.SPEC && config.getLoadedConfig() != null
            && Files.isRegularFile(config.getFullPath()), "the carving spec loads its config file");
        helper.succeed();
    }
}
