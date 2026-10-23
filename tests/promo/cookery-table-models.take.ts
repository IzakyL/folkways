import { expect, test } from "@playwright/test";
import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import { fixCamera, K, openScene } from "./cookery-scene";
import { take, type PromoPair } from "./promo-kit";

const MODELS = defineTask<void, Record<string, any>>({
  name: "folkways.promo.table-models", side: "client", timeoutMs: 20_000,
  source: javaTask({ imports: ["net.minecraft.client.Minecraft", "net.minecraft.core.registries.BuiltInRegistries",
    "net.minecraft.resources.ResourceLocation", "io.github.izakyl.folkways.cookery.dining.TableFoodModels",
    "com.github.ysbbbbbb.kaleidoscopecookery.client.render.block.TableBlockEntityRender"], body: `
var mc = (Minecraft) ctx.client();
var result = new LinkedHashMap<String, Object>();
boolean mixed = java.util.Arrays.stream(TableBlockEntityRender.class.getDeclaredMethods())
    .anyMatch(method -> method.getName().contains("folkways$renderPlacedDish"));
result.put("mixed", mixed);
result.put("loading", mc.getOverlay() != null);
for (String name : List.of("dark_cuisine", "dongpo_pork", "spicy_chicken", "braised_beef", "sweet_and_sour_pork")) {
    var stack = BuiltInRegistries.ITEM.get(ResourceLocation.parse("kaleidoscope_cookery:" + name)).getDefaultInstance();
    var state = TableFoodModels.placedDish(stack);
    result.put(name, state == null ? "item" : "block");
    if (state != null && mc.getBlockRenderer().getBlockModel(state) == mc.getModelManager().getMissingModel())
        throw new IllegalStateException("Missing placed dish model for " + name);
}
return result;
` }),
});

test("native table renders the real dish models and retains icon-only foods", async ({}, testInfo) => {
  let pair: PromoPair | undefined;
  try {
    const scene = await openScene("cookery-table-models");
    pair = scene.pair;
    for (const [i, id] of ["dark_cuisine", "dongpo_pork", "spicy_chicken", "braised_beef", "sweet_and_sour_pork"].entries()) {
      await scene.set(scene.at(i * 2, 0), K + `table_oak{ShowItems:{Size:4,Items:[{Slot:0,id:"${K + id}",count:1}]}}`);
    }
    await fixCamera(scene, scene.at(4, -5, 2), scene.at(4, 0, 0), 60);
    await expect.poll(async () => (await scene.client.run(MODELS)).loading, { timeout: 60_000 }).toBe(false);
    const models = await scene.client.run(MODELS);
    expect(models).toMatchObject({ mixed: true, dark_cuisine: "block", dongpo_pork: "block", spicy_chicken: "block",
      braised_beef: "item", sweet_and_sour_pork: "item" });
    await take("cookery-table-models").shot(scene.client, "placed-dishes-and-icons", {
      subject: "Three native 3D dishes, followed by two foods whose upstream assets only contain item icons", worldState: models,
    });
  } catch (error) { pair?.failing(error); throw error; }
  finally { await pair?.teardown(testInfo); }
});
