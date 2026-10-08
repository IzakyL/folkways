package io.github.izakyl.folkways.plugins.person.look;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.api.Settings;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentModelGameTests {

    private static final String TEMPLATE = "empty";
    private static final String YSM_ANIMATIONS = """
        {"format_version":"1.8.0","animations":{"idle":{},"walk":{},"swing_hand":{},"sneak":{}}}
        """;
    private static final String GEOMETRY = """
        {"format_version":"1.12.0","minecraft:geometry":[{"description":{"identifier":"geometry.ranger"}}]}
        """;

    private ResidentModelGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentModelGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void everyTextureInAModelFolderIsItsOwnEntry(GameTestHelper helper) {
        DataPack files = new DataPack();
        files.model("ranger", YSM_ANIMATIONS, "green", "red");
        ModelLibrary library = ModelLibrary.read(files.held);

        helper.assertValueEqual(library.rejected(), List.of(), "nothing should have been refused");
        helper.assertValueEqual(library.entries().keySet(),
            Set.of(id("model/ranger/green"), id("model/ranger/red")),
            "one entry per texture, named after the directory and the png");
        helper.assertValueEqual(library.assets().size(), 4,
            "one geometry, one animation file and two textures; actual=" + library.assets().keySet());

        List<ResidentLook.Model> models = library.entries().values().stream()
            .map(ResidentLook::appearance)
            .map(ResidentLook.Model.class::cast)
            .sorted(Comparator.comparing(model -> model.texture().toString()))
            .toList();
        helper.assertValueEqual(models.get(0).geometry(), models.get(1).geometry(),
            "the two skins share the one geometry");
        helper.assertValueEqual(models.get(0).texture(),
            id("textures/entity/resident_models/ranger/green.png"), "the first skin's texture");
        helper.assertValueEqual(models.get(1).texture(),
            id("textures/entity/resident_models/ranger/red.png"), "the second skin's texture");
        helper.assertValueEqual(models.get(0).idleAnimation(), "idle", "the YSM idle name");
        helper.assertValueEqual(models.get(0).walkAnimation(), "walk", "the YSM walk name");
        helper.assertValueEqual(models.get(0).workAnimation(), Optional.of("swing_hand"),
            "the YSM work name, which a pack may also simply not have");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void animationNamesComeOutOfTheAnimationFile(GameTestHelper helper) {
        DataPack files = new DataPack();
        files.model("drifter", "{\"animations\":{\"model.idle_loop\":{},\"model.walk_loop\":{}}}", "main");
        ModelLibrary library = ModelLibrary.read(files.held);
        ResidentLook.Model model = onlyModel(helper, library);
        helper.assertValueEqual(model.idleAnimation(), "model.idle_loop", "the file's own idle name");
        helper.assertValueEqual(model.walkAnimation(), "model.walk_loop", "the file's own walk name");
        helper.assertValueEqual(model.workAnimation(), Optional.empty(),
            "this file has no work loop, and absent has to be sayable");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aManifestNamingAnAbsentAnimationIsRefusedWholeAndSaysSo(GameTestHelper helper) {
        DataPack files = new DataPack();
        files.model("sprinter", YSM_ANIMATIONS, "main");
        files.put("sprinter", "folkways.json", "{\"walk_animation\":\"sprint\"}");
        ModelLibrary library = ModelLibrary.read(files.held);
        helper.assertValueEqual(library.entries(), Map.of(), "nothing may reach the pool");
        helper.assertValueEqual(library.rejected().size(), 1, "exactly one line about it");
        helper.assertTrue(library.rejected().getFirst().contains("sprint"),
            "the line must name the animation that is missing; actual=" + library.rejected());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void anIncompleteDirectoryIsRefusedWithAReason(GameTestHelper helper) {
        DataPack files = new DataPack();
        files.put("bare", "main.json", GEOMETRY);
        ModelLibrary library = ModelLibrary.read(files.held);
        helper.assertValueEqual(library.entries(), Map.of(), "nothing may reach the pool");
        helper.assertTrue(library.rejected().stream().anyMatch(line -> line.contains("main.animation.json")),
            "the line must name what is missing; actual=" + library.rejected());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void noModelFilesIsAnEmptyLibrary(GameTestHelper helper) {
        ModelLibrary library = ModelLibrary.read(Map.of());
        helper.assertValueEqual(library, ModelLibrary.EMPTY, "a data pack without models reads to nothing");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aModelTakesTheNamespaceOfTheDataPackThatShipsIt(GameTestHelper helper) {
        DataPack files = new DataPack();
        files.modelIn("villagepack", "ranger", YSM_ANIMATIONS, "main");
        ModelLibrary library = ModelLibrary.read(files.held);
        ResidentLook.Model model = onlyModel(helper, library);
        helper.assertValueEqual(library.entries().keySet(),
            Set.of(ResourceLocation.fromNamespaceAndPath("villagepack", "model/ranger/main")),
            "the entry is named in the pack's own namespace");
        helper.assertValueEqual(model.geometry(),
            ResourceLocation.fromNamespaceAndPath("villagepack", "geo/resident_models/ranger.geo.json"),
            "and so are the assets it sends to the client");
        helper.assertTrue(library.assets().containsKey("assets/villagepack/geo/resident_models/ranger.geo.json"),
            "under that namespace in the client pack; actual=" + library.assets().keySet());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aModelEntryIsDrawnFromAndResolvesLikeAnyOther(GameTestHelper helper) {
        RegistryAccess registries = helper.getLevel().registryAccess();
        int before = ResidentLooks.pool(registries).size();
        DataPack files = new DataPack();
        files.model("ranger", YSM_ANIMATIONS, "main");
        files.put("ranger", "folkways.json", "{\"weight\":1000}");
        ModelLibrary library = ModelLibrary.read(files.held);
        ResourceKey<ResidentLook> key = ResourceKey.create(ResidentLooks.REGISTRY, id("model/ranger/main"));
        try {
            ResidentLooks.installRuntime(library.entries());
            helper.assertValueEqual(ResidentLooks.pool(registries).size(), before + 1,
                "the model half must join the registry half");
            helper.assertTrue(ResidentLooks.keysInPool(registries).contains(key),
                "the Settings list is built from these keys, so the entry has to be among them");
            helper.assertTrue(ResidentLooks.entry(registries, key).isPresent(), "and resolve by key");

            int drawn = 0;
            for (int i = 0; i < 20; i++) {
                UUID who = UUID.nameUUIDFromBytes(("folkways-model-draw-" + i).getBytes(StandardCharsets.UTF_8));
                if (ResidentLooks.draw(registries, who).equals(Optional.of(key))) {
                    drawn++;
                }
            }
            helper.assertTrue(drawn >= 15,
                "a weight of 1000 against a pool of " + before + " drew the model entry " + drawn
                    + " times out of 20");
            UUID pinned = UUID.nameUUIDFromBytes("folkways-model-pin".getBytes(StandardCharsets.UTF_8));
            helper.assertValueEqual(ResidentLooks.resolve(registries, Optional.of(key), pinned),
                library.entries().get(key.location()), "and a pin naming it must resolve to it");
            helper.assertValueEqual(ResidentLooks.modelsInPool(helper.getLevel()).size(), 1,
                "the renderer asks this one which models exist");
        } finally {
            ResidentLooks.forgetRuntime();
        }
        helper.assertValueEqual(ResidentLooks.pool(registries).size(), before,
            "the pool must be back to the registry half alone");
        helper.succeed();
    }

    private static ResidentLook.Model onlyModel(GameTestHelper helper, ModelLibrary library) {
        helper.assertValueEqual(library.rejected(), List.of(), "nothing should have been refused");
        helper.assertValueEqual(library.entries().size(), 1, "one texture, one entry");
        return (ResidentLook.Model) library.entries().values().iterator().next().appearance();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, path);
    }

    private static final class DataPack {
        final Map<ResourceLocation, IoSupplier<InputStream>> held = new LinkedHashMap<>();

        void model(String name, String animations, String... textures) {
            modelIn(FolkwaysMod.MOD_ID, name, animations, textures);
        }

        void modelIn(String namespace, String name, String animations, String... textures) {
            put(namespace, name, "main.json", GEOMETRY);
            put(namespace, name, "main.animation.json", animations);
            for (String texture : textures) {
                put(namespace, name, "textures/" + texture + ".png", "not really a png");
            }
        }

        void put(String name, String file, String content) {
            put(FolkwaysMod.MOD_ID, name, file, content);
        }

        void put(String namespace, String name, String file, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            held.put(ResourceLocation.fromNamespaceAndPath(namespace, ModelLibrary.FOLDER + "/" + name + "/" + file),
                () -> new ByteArrayInputStream(bytes));
        }
    }
}
