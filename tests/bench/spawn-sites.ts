import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";
import type { Vec } from "./town";

// Validate the finished world, after roads, trees, roofs and railway earthworks exist.
// A free block at the feet is not sufficient for a metal golem beside a wall.
export async function safeSpawnSites(server: MinecraftServer, candidates: Array<Vec & { kind: string }>) {
  const result = await runColonyTask(server, SAFE_SPAWNS, { candidates });
  return { positions: result.positions as Vec[], moved: Number(result.moved), checked: candidates.length };
}

const SAFE_SPAWNS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.bench.safe-spawns",
  side: "server",
  timeoutMs: 30_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.core.Direction", "net.minecraft.world.phys.AABB"],
    body: `
var level=ctx.server().overworld();var positions=new ArrayList<Object>();
var taken=new HashSet<BlockPos>();int moved=0;
for(var raw:Args.of(ctx).list("candidates")){
  var c=(Map<?,?>)raw;int ox=((Number)c.get("x")).intValue(),oy=((Number)c.get("y")).intValue(),oz=((Number)c.get("z")).intValue();
  boolean large="metal".equals(c.get("kind"));double half=large?0.95:0.35,height=large?3.0:2.0;
  BlockPos found=null;
  search:for(int radius=0;radius<=12;radius++)for(int dy:new int[]{0,1,-1,2,-2})
    for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
      if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
      var at=new BlockPos(ox+dx,oy+dy,oz+dz);if(taken.contains(at))continue;
      double x=at.getX()+0.5,z=at.getZ()+0.5;
      var box=new AABB(x-half,at.getY(),z-half,x+half,at.getY()+height,z+half);
      if(!level.noCollision(box)||level.containsAnyLiquid(box))continue;
      boolean supported=true;
      for(int fx=(int)Math.floor(box.minX);fx<Math.ceil(box.maxX);fx++)
        for(int fz=(int)Math.floor(box.minZ);fz<Math.ceil(box.maxZ);fz++){
          var floor=new BlockPos(fx,at.getY()-1,fz);
          if(!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP))supported=false;
        }
      if(!supported)continue;
      found=at;break search;
    }
  if(found==null)throw new IllegalStateException("No safe spawn near "+ox+","+oy+","+oz+" for "+c.get("kind"));
  taken.add(found);if(!found.equals(new BlockPos(ox,oy,oz)))moved++;
  positions.add(Map.of("x",found.getX(),"y",found.getY(),"z",found.getZ()));
}
return Map.of("ok",true,"positions",positions,"moved",moved);
`,
  }),
});
