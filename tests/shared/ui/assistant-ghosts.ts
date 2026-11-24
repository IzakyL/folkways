import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

export const DRAWING_GHOSTS = defineTask<void, { count: number; rows: number[] }>({
  name: "folkways.assistant.drawing-ghosts", side: "client",
  source: javaTask({ imports: ["io.github.izakyl.folkways.core.front.api.Ghost"], body: `
var preview = Class.forName("io.github.izakyl.folkways.core.front.client.ShapePreview");
var renderer = preview.getDeclaredField("GHOSTS");
renderer.setAccessible(true);
var shown = renderer.get(null).getClass().getDeclaredField("shown");
shown.setAccessible(true);
var ghosts = (List<?>) shown.get(renderer.get(null));
return Map.of("count", ghosts.size(), "rows", ghosts.stream().map(cell -> ((Ghost) cell).pos().getZ()).distinct().toList());
` }),
});
