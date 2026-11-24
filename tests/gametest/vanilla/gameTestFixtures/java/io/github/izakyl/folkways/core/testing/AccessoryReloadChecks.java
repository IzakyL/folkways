package io.github.izakyl.folkways.core.testing;

import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

/** Exercises the frameworks' real resource reload and their existing resident capabilities, not a test registry. */
public final class AccessoryReloadChecks {

    private AccessoryReloadChecks() {
    }

    public static void check(GameTestHelper helper, String framework) {
        var server = helper.getLevel().getServer();
        var body = PersonBody.RESIDENT.get().create(helper.getLevel());
        body.moveTo(helper.absolutePos(new net.minecraft.core.BlockPos(1, 1, 1)).getBottomCenter());
        helper.getLevel().addFreshEntity(body);
        var directory = server.getWorldPath(LevelResource.DATAPACK_DIR)
            .resolve("folkways-" + framework + "-reload-test");
        String folder = framework.equals("curios") ? "curios/slots/" : "accessories/slot/";
        String entities = framework.equals("curios") ? "curios/entities/" : "accessories/entity/";
        String amount = framework.equals("curios") ? "size" : "amount";
        String operation = framework.equals("curios") ? "SET" : "set";
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("pack.mcmeta"),
                "{\"pack\":{\"pack_format\":48,\"description\":\"Folkways slot reload regression\"}}");
            write(directory.resolve("data/folkways_reload/" + folder + "folkways_test.json"),
                "{\"" + amount + "\":5,\"operation\":\"" + operation + "\"}");
            write(directory.resolve("data/folkways_reload/" + folder + "folkways_reload.json"),
                "{\"" + amount + "\":1,\"operation\":\"" + operation
                    + "\",\"validators\":[\"" + framework + ":tag\"]}");
            write(directory.resolve("data/folkways_reload/" + entities + "residents.json"),
                "{\"entities\":[\"minecraft:player\"],\"slots\":[\"folkways_test\",\"folkways_reload\"]}");
        } catch (IOException broken) {
            throw new IllegalStateException(broken);
        }
        Cleanup.on(helper, () -> {
            body.discard();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException broken) {
                throw new IllegalStateException(broken);
            }
        });
        server.getPackRepository().reload();
        var selected = new ArrayList<>(server.getPackRepository().getSelectedIds());
        selected.add("file/" + directory.getFileName());
        CompletableFuture<Void> reloaded = server.reloadResources(selected);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(reloaded.isDone(), "native reload finishes"))
            .thenExecute(() -> {
                reloaded.join();
                var slot = GearSlots.slot(ResourceLocation.parse(framework + ":folkways_test/4")).orElseThrow();
                helper.assertTrue(GearSlots.slot(ResourceLocation.parse(framework + ":folkways_test/5")).isEmpty()
                    && GearSlots.slot(ResourceLocation.parse(framework + ":folkways_reload/0")).isPresent(),
                    "a reloaded data pack changes counts and adds player slots");
                slot.equip(body, new ItemStack(Items.DIAMOND));
                helper.assertTrue(slot.worn(body).is(Items.DIAMOND),
                    "a resident loaded before the reload owns the resized real capability");
            }).thenSucceed();
    }

    private static void write(java.nio.file.Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
