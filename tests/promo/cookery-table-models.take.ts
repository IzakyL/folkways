import { expect, test } from "@playwright/test";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { fixCamera, K, openScene } from "./cookery-scene";
import { take, type PromoPair } from "./promo-kit";

const DISHES = ["dark_cuisine", "dongpo_pork", "spicy_chicken", "braised_beef", "sweet_and_sour_pork",
  "stir_fried_pork_with_peppers", "pork_bone_soup", "sashimi"];

const MODELS = defineTask<{ x: number; y: number; z: number }, Record<string, any>>({
  name: "folkways.promo.table-models", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation", "io.github.izakyl.folkways.cookery.dining.TableFoodModels",
    "com.github.ysbbbbbb.kaleidoscopecookery.client.render.block.TableBlockEntityRender",
    "com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.TableBlockEntity", "net.minecraft.core.BlockPos",
    "net.neoforged.neoforge.items.ItemStackHandler"], body: `
var mc = (Minecraft) ctx.client();
var args = Args.of(ctx);
var result = new LinkedHashMap<String, Object>();
boolean mixed = java.util.Arrays.stream(TableBlockEntityRender.class.getDeclaredMethods())
    .anyMatch(method -> method.getName().contains("folkways$renderPlacedDish"));
result.put("mixed", mixed);
result.put("loading", mc.getOverlay() != null);
for (String name : List.of("dark_cuisine", "dongpo_pork", "spicy_chicken", "braised_beef", "sweet_and_sour_pork",
    "stir_fried_pork_with_peppers", "pork_bone_soup", "sashimi")) {
    var stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse("kaleidoscope_cookery:" + name)).getDefaultInstance();
    var state = TableFoodModels.placedDish(stack);
    result.put(name, state == null ? "item" : "block");
    var model = state == null ? mc.getItemRenderer().getModel(stack, mc.level, mc.player, 0)
        : mc.getBlockRenderer().getBlockModel(state);
    if (model == mc.getModelManager().getMissingModel())
        throw new IllegalStateException("Missing dish model for " + name);
}
var table = (TableBlockEntity) mc.level.getBlockEntity(new BlockPos((int) args.integer("x"), (int) args.integer("y"), (int) args.integer("z") + 4));
if (table == null) throw new IllegalStateException("Sparse table has not synced");
var compact = java.util.Arrays.stream(TableBlockEntityRender.class.getDeclaredMethods())
    .filter(method -> method.getName().contains("folkways$visibleServings")).findFirst().orElseThrow();
compact.setAccessible(true);
var renderer = mc.getBlockEntityRenderDispatcher().getRenderer(table);
var visible = (ItemStackHandler) compact.invoke(renderer, table);
result.put("sparseVisible", !visible.getStackInSlot(0).isEmpty() && !visible.getStackInSlot(1).isEmpty());
result.put("sparseUnchanged", table.getItems().getStackInSlot(0).isEmpty()
    && !table.getItems().getStackInSlot(1).isEmpty() && !table.getItems().getStackInSlot(3).isEmpty());
return result;
` }),
});

test("native table renders the real dish models and retains icon-only foods", async ({}, testInfo) => {
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-table-models");
    pair = scene.pair;
    for (const [i, id] of DISHES.entries()) {
      await scene.set(scene.at((i % 4) * 2, Math.floor(i / 4) * 2), K + `table_oak{ShowItems:{Size:4,Items:[{Slot:0,id:"${K + id}",count:1}]}}`);
    }
    await scene.set(scene.at(0, 4), K + `table_oak{ShowItems:{Size:4,Items:[{Slot:1,id:"${K}dongpo_pork",count:1},{Slot:3,id:"${K}spicy_chicken",count:1}]}}`);
    await fixCamera(scene, scene.at(3, -6, 3), scene.at(3, 1, 0), 60);
    await expect.poll(async () => (await scene.client.run(MODELS, scene.o)).loading, { timeout: 60_000 }).toBe(false);
    const models = await scene.client.run(MODELS, scene.o);
    expect(models).toMatchObject({ mixed: true, sparseVisible: true, sparseUnchanged: true, dark_cuisine: "block", dongpo_pork: "block", spicy_chicken: "block",
      braised_beef: "item", sweet_and_sour_pork: "item", stir_fried_pork_with_peppers: "item",
      pork_bone_soup: "item", sashimi: "item" });
    await take("cookery-table-models").shot(scene.client, "placed-dishes-and-icons", {
      subject: "Three native 3D dishes and all five original promo foods with icon-only upstream assets, laid flat on the tables; rear sparse table retains both real dishes", worldState: models,
    });
  } catch (error) { pair?.failing(error); throw error; }
  finally { await pair?.teardown(testInfo); }
});
