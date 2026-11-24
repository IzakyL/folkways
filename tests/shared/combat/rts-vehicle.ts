import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";
import type { Vec } from "./rts-fixtures";

export const SPAWN = defineTask<{ type: string } & Vec, string>({
  name: "folkways.rts-vehicle-window.spawn-vehicle", side: "server",
  source: javaTask({
    imports: ["net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.resources.ResourceLocation",
      "net.minecraft.server.MinecraftServer"],
    body: `
var a=Args.of(ctx);var level=((MinecraftServer)ctx.server()).overworld();
var entity=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(a.string("type"))).create(level);
entity.moveTo(a.integer("x")+0.5,a.integer("y"),a.integer("z")+0.5,180,0);
level.addFreshEntity(entity);return entity.getUUID().toString();`,
  }),
});

export const SELECT_VEHICLES = defineTask<{ ids: string[] }, boolean>({
  name: "folkways.rts-vehicle-window.select-vehicles", side: "client",
  source: javaTask({
    imports: ["java.util.Set", "java.util.UUID", "java.util.List",
      "io.github.izakyl.folkways.core.front.api.ui.OverheadTools"],
    body: `
var a=Args.of(ctx);var ids=new java.util.ArrayList<UUID>();
for(String id:a.strings("ids"))ids.add(UUID.fromString(id));
var handler=OverheadTools.selectedHandler().orElseThrow();var type=handler.getClass();
var one=type.getDeclaredMethod("selectVehicle",UUID.class);one.setAccessible(true);one.invoke(handler,ids.get(0));
var add=type.getDeclaredMethod("selectVehicles",Set.class,boolean.class);add.setAccessible(true);
for(var id:ids.subList(1,ids.size()))add.invoke(handler,Set.of(id),true);
return true;`,
  }),
});
