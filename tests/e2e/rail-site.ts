import { defineTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "./colony-tasks";
import { bounds, type Box, type Site, type Vec } from "./rail-footprint";

const HOLD_REACH = 12;
const SEARCH_REACH = 16;

export type Moved = { uuid: string; type: string; name: string; from: Vec; to: Vec };
export type Held = { uuid: string; type: string; noAi: boolean };

export type SiteClearing = {
  ok: boolean;
  error?: string;
  anchor?: Vec;
  moved: Moved[];
  held: Held[];
  cleared?: number;
  filled?: number;
  obstructions?: Array<{ at: Vec; block: string }>;
};

export type SiteCheck = {
  ok: boolean;
  stuck: Array<{ uuid: string; type: string; at?: Vec; why: string }>;
  checked: number;
};

function area(site: Site, anchor: Vec): Box {
  const all = bounds([...site.footprint, { min: anchor, max: anchor }]);
  return {
    min: { x: all.min.x - SEARCH_REACH, y: all.min.y - 4, z: all.min.z - SEARCH_REACH },
    max: { x: all.max.x + SEARCH_REACH, y: all.max.y + 4, z: all.max.z + SEARCH_REACH },
  };
}

export async function clearRailSite(server: MinecraftServer, site: Site, anchor: Vec): Promise<SiteClearing> {
  const out = await runColonyTask(server, SITE, {
    mode: "clear",
    anchor,
    area: area(site, anchor),
    footprint: site.footprint,
    structures: site.structures,
    build: [...site.envelope, ...site.structures],
    bed: site.bed,
    hold: HOLD_REACH,
  }) as any;
  return {
    ok: Boolean(out.clear), error: out.error, anchor: out.anchor, moved: out.moved ?? [], held: out.held ?? [],
    cleared: out.cleared, filled: out.filled, obstructions: out.obstructions,
  };
}

export async function releaseRailSite(server: MinecraftServer, site: Site, clearing: SiteClearing): Promise<SiteCheck> {
  const anchor = clearing.anchor ?? site.car.min;
  const out = await runColonyTask(server, SITE, {
    mode: "release",
    anchor,
    area: area(site, anchor),
    held: clearing.held,
    moved: clearing.moved.map((m) => m.uuid),
    car: site.car,
  }) as any;
  return { ok: Boolean(out.clear), stuck: out.stuck ?? [], checked: Number(out.checked ?? 0) };
}

/** Clears a rail corridor (moves and holds residents, clears blocks) or releases it and reports who is stuck. */
const SITE = defineTask<Record<string, unknown>, Record<string, any>>({
  name: "folkways.rail.site",
  side: "server",
  timeoutMs: 20_000,
  source: `
import blockwright.minecraft.core.Args;
import blockwright.minecraft.core.Sync;
import dev.blockwright.api.Context;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
public final class Task {
  record Box(int x0,int y0,int z0,int x1,int y1,int z1) {
    boolean has(int x,int y,int z,int d){return x>=x0-d&&x<=x1+d&&y>=y0-d&&y<=y1+d&&z>=z0-d&&z<=z1+d;}
    AABB aabb(){return new AABB(x0,y0,z0,x1+1,y1+1,z1+1);}
  }
  static int n(Object o){return ((Number)o).intValue();}
  static BlockPos pos(Object raw){var m=(Map<?,?>)raw;return new BlockPos(n(m.get("x")),n(m.get("y")),n(m.get("z")));}
  static Box box(Object raw){var m=(Map<?,?>)raw;var a=pos(m.get("min"));var b=pos(m.get("max"));return new Box(a.getX(),a.getY(),a.getZ(),b.getX(),b.getY(),b.getZ());}
  static List<Box> boxes(Object raw){var out=new ArrayList<Box>();for(var o:(List<?>)raw)out.add(box(o));return out;}
  static boolean in(List<Box> bs,int x,int y,int z,int d){for(var b:bs)if(b.has(x,y,z,d))return true;return false;}
  static boolean touches(List<Box> bs,AABB bb){for(var b:bs)if(b.aabb().intersects(bb))return true;return false;}
  static Map<String,Object> vec(BlockPos p){return Map.of("x",p.getX(),"y",p.getY(),"z",p.getZ());}
  static Map<String,Object> vec(Vec3 p){return Map.of("x",Math.round(p.x*100)/100.0,"y",Math.round(p.y*100)/100.0,"z",Math.round(p.z*100)/100.0);}
  static String type(Entity e){return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();}
  static boolean open(ServerLevel l,BlockPos p){var s=l.getBlockState(p);return s.getCollisionShape(l,p).isEmpty()&&s.getFluidState().isEmpty();}
  static boolean standable(ServerLevel l,BlockPos p){
    if(!open(l,p)||!open(l,p.above()))return false;
    var below=p.below();var shape=l.getBlockState(below).getCollisionShape(l,below);
    return !shape.isEmpty()&&shape.max(Direction.Axis.Y)>=0.5&&shape.max(Direction.Axis.Y)<=1.0;
  }
  static long key(BlockPos p){return p.asLong();}
  static List<BlockPos> reach(ServerLevel l,BlockPos start,Box area,List<Box> blocked){
    var seen=new HashSet<Long>();var order=new ArrayList<BlockPos>();var queue=new ArrayDeque<BlockPos>();
    seen.add(key(start));queue.add(start);
    while(!queue.isEmpty()&&order.size()<400000){
      var at=queue.poll();order.add(at);
      for(var dir:Direction.Plane.HORIZONTAL){
        for(int dy=1;dy>=-1;dy--){
          var next=at.relative(dir).offset(0,dy,0);
          if(!area.has(next.getX(),next.getY(),next.getZ(),0)||seen.contains(key(next)))continue;
          if(in(blocked,next.getX(),next.getY(),next.getZ(),1))continue;
          if(dy==1&&!open(l,at.above(2)))continue;
          if(dy==-1&&!open(l,next.above(2)))continue;
          if(!standable(l,next))continue;
          seen.add(key(next));queue.add(next);
        }
      }
    }
    return order;
  }
  static BlockPos startNear(ServerLevel l,BlockPos anchor,List<Box> blocked){
    BlockPos best=null;double bestD=Double.MAX_VALUE;
    for(var p:BlockPos.betweenClosed(anchor.offset(-3,-3,-3),anchor.offset(3,3,3))){
      if(in(blocked,p.getX(),p.getY(),p.getZ(),1)||!standable(l,p))continue;
      double d=p.distSqr(anchor);
      if(d<bestD){bestD=d;best=p.immutable();}
    }
    return best;
  }
  public static Object run(Context ctx){
    return Sync.stamp(site(ctx.server(),Args.of(ctx)));
  }
  static Object site(MinecraftServer server,Args args){
    var level=server.overworld();
    var out=new LinkedHashMap<String,Object>();
    var area=box(args.value("area"));
    var anchor=pos(args.value("anchor"));
    if("release".equals(args.string("mode")))return release(level,args,area,anchor,out);
    var footprint=boxes(args.value("footprint"));var structures=boxes(args.value("structures"));
    var build=boxes(args.value("build"));var bed=boxes(args.value("bed"));int hold=(int)args.integer("hold");
    var obstructions=new ArrayList<Object>();
    for(var b:build)for(var p:BlockPos.betweenClosed(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1())){
      if(level.getBlockEntity(p)!=null&&obstructions.size()<8)obstructions.add(Map.of("at",vec(p),"block",BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).toString()));
    }
    if(!obstructions.isEmpty()){
      out.put("ok",true);out.put("clear",false);out.put("obstructions",obstructions);
      out.put("error","the rail corridor holds block entities that the fixture will not clear; pick another railOrigin");
      return out;
    }
    var start=startNear(level,anchor,footprint);
    if(start==null){out.put("ok",true);out.put("clear",false);out.put("error","no standing spot beside the anchor "+anchor.toShortString()+" outside the rail footprint");return out;}
    out.put("anchor",vec(start));
    var candidates=new ArrayList<BlockPos>();
    for(var p:reach(level,start,area,footprint))if(!in(footprint,p.getX(),p.getY(),p.getZ(),2))candidates.add(p);
    var near=new ArrayList<Box>();
    for(var s:structures)near.add(new Box(s.x0()-hold,s.y0()-hold,s.z0()-hold,s.x1()+hold,s.y1()+hold,s.z1()+hold));
    var whole=area.aabb();
    var moved=new ArrayList<Object>();var held=new ArrayList<Object>();var taken=new HashSet<Long>();
    for(var e:level.getEntitiesOfClass(LivingEntity.class,whole,LivingEntity::isAlive)){
      boolean enclosed=touches(footprint,e.getBoundingBox());
      if(enclosed){
        var from=e.position();BlockPos best=null;double bestD=Double.MAX_VALUE;
        for(var c:candidates){
          if(taken.contains(key(c)))continue;
          double d=c.getCenter().distanceToSqr(from.x,from.y+0.5,from.z);
          if(d<bestD){bestD=d;best=c;}
        }
        if(best==null){out.put("ok",true);out.put("clear",false);out.put("error","no reachable standing spot outside the rail footprint for "+type(e)+" at "+e.blockPosition().toShortString());out.put("moved",moved);out.put("held",held);return out;}
        taken.add(key(best));
        e.stopRiding();e.teleportTo(best.getX()+0.5,best.getY(),best.getZ()+0.5);e.setDeltaMovement(Vec3.ZERO);
        if(e instanceof Mob m)m.getNavigation().stop();
        moved.add(Map.of("uuid",e.getStringUUID(),"type",type(e),"name",e.getName().getString(),"from",vec(from),"to",vec(best)));
      }
      if(e instanceof Mob m&&(enclosed||touches(near,e.getBoundingBox()))){
        held.add(Map.of("uuid",e.getStringUUID(),"type",type(e),"noAi",m.isNoAi()));
        m.setNoAi(true);
      }
    }
    int cleared=0,filled=0;
    for(var b:build)for(var p:BlockPos.betweenClosed(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1())){
      if(!level.getBlockState(p).isAir()){level.setBlock(p,Blocks.AIR.defaultBlockState(),3);cleared++;}
    }
    for(var b:bed)for(var p:BlockPos.betweenClosed(b.x0(),b.y0(),b.z0(),b.x1(),b.y1(),b.z1())){
      if(!level.getBlockState(p).isFaceSturdy(level,p,Direction.UP)){level.setBlock(p.immutable(),Blocks.STONE.defaultBlockState(),3);filled++;}
    }
    out.put("ok",true);out.put("clear",true);out.put("moved",moved);out.put("held",held);out.put("cleared",cleared);out.put("filled",filled);
    return out;
  }
  static Object release(ServerLevel level,Args args,Box area,BlockPos anchor,Map<String,Object> out){
    var moved=new HashSet<String>();for(var o:args.list("moved"))moved.add(String.valueOf(o));
    var car=box(args.value("car"));
    var reached=new HashSet<Long>();
    for(var p:reach(level,anchor,area,List.of()))reached.add(key(p));
    var stuck=new ArrayList<Object>();int checked=0;
    for(var o:args.list("held")){
      var row=(Map<?,?>)o;var id=UUID.fromString(String.valueOf(row.get("uuid")));
      var e=level.getEntity(id);
      if(e instanceof Mob m)m.setNoAi(Boolean.TRUE.equals(row.get("noAi")));
      if(e==null||!e.isAlive()||e.isPassenger())continue;
      checked++;
      String why=null;var bb=e.getBoundingBox().deflate(0.01);
      if(level.getBlockCollisions(e,bb).iterator().hasNext())why="walled in: blocks overlap its body";
      if(why==null&&car.aabb().intersects(bb))why="inside where the train car was built";
      if(why==null&&moved.contains(e.getStringUUID())&&!reached.contains(key(e.blockPosition())))why="cannot walk back to "+anchor.toShortString();
      if(why!=null)stuck.add(Map.of("uuid",e.getStringUUID(),"type",type(e),"at",vec(e.position()),"why",why));
    }
    out.put("ok",true);out.put("clear",stuck.isEmpty());out.put("stuck",stuck);out.put("checked",checked);
    return out;
  }
}
`,
});
