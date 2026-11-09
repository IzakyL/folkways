import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

// Read-only evidence: compare live client storage with the server, independently of the screenshot.
function audit(side: "server" | "client") {
  return defineTask<Record<string, any>, Record<string, any>>({
    name: `folkways.promo.candb-sync-${side}`, side,
    source: javaTask({
      imports: ["net.minecraft.core.BlockPos", "mod.chiselsandbits.block.entities.ChiseledBlockEntity",
        ...(side === "client" ? ["net.minecraft.client.Minecraft"] : [])],
      body: `
var level=${side === "server" ? "ctx.server().overworld()" : "Minecraft.getInstance().level"};
var args=Args.of(ctx);var cells=new ArrayList<Object>();int empty=0;int stripped=0;
for(int x=(int)args.integer("minX");x<=(int)args.integer("maxX");x++){
  for(int z=(int)args.integer("minZ");z<=(int)args.integer("maxZ");z++){
    for(int y=55;y<=110;y++){
      var at=new BlockPos(x,y,z);
      if(level.getBlockEntity(at) instanceof ChiseledBlockEntity entity){
        var storage=entity.payload().storage();
        if(entity.getStatistics().isEmptyBlock()){empty++;}
        ${side === "server" ? `
        var adapter=io.github.izakyl.folkways.vanilla.build.SchematicPlacement.adapter();
        if(adapter.prepare(level,entity.getBlockState(),entity)==null){stripped++;}` : ""}
        cells.add(List.of(at.toShortString(),storage.getSize(),java.util.Arrays.hashCode(storage.getRawData()),
          storage.palette().paletteEntries().toString()));
      }
    }
  }
}
return Map.of("cells",cells,"empty",empty,"count",cells.size(),"adapterStrips",stripped);`,
    }),
  });
}

export const CANDB_SERVER = audit("server");
export const CANDB_CLIENT = audit("client");
