import { expect, test } from "@playwright/test";
import { camera, defineTask, javaTask, screen, tick, world } from "@izakyl/blockwright-minecraft";
import { cameraLookingAt } from "@izakyl/blockwright-minecraft/internal";
import { copyFileSync, existsSync, mkdirSync } from "node:fs";
import path from "node:path";
import { launchPair, type E2EPair } from "../shared/harness/pair";
import { waitForGroundedPlayer, type Vec } from "../shared/colony/colony-founding";
import { VisualRun } from "../shared/capture/visual-kit";
import { outAbs, tracePath } from "../shared/harness/out-paths";
import { newRunId } from "../shared/harness/run-id";
import { BUDGET } from "../shared/harness/budgets";
import { frames, tryCommand } from "../shared/harness/bw-helpers";

const SHOTS = path.resolve(process.env.FOLKWAYS_WINEFOX_SHOTS?.trim() || outAbs("visual", "takes", "winefox-face"));
const MODELS = ["winefox", "winefox_tactics", "winefox_magical", "winefox_momo"];
const PORTRAIT_PHASE_TICKS = 40; // Two seconds: outside Magical's authored blink windows and head-turn sequence.

// Only stage entities and their ordinary state; use the production renderers and assets.
const STAGE = defineTask<{ x: number; y: number; z: number; models: string[] }, Record<string, any>>({
  name: "folkways.visual.winefox.stage", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.plugins.person.PersonBody",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
      "net.minecraft.resources.*",
      "net.minecraft.core.registries.BuiltInRegistries",
      "net.minecraft.world.entity.Mob",
      "com.github.tartaricacid.touhoulittlemaid.init.InitAttribute",
    ],
    body: `
var a=Args.of(ctx); var level=ctx.server().overworld();
double x=a.number("x"),y=a.number("y"),z=a.number("z");
var pool=ResidentLooks.pool(level.registryAccess());
var residents=new ArrayList<Map<String,Object>>();
int i=0;
for(var model:a.list("models")) {
    var id=ResourceLocation.parse("touhou_little_maid:maid/geckolib/"+model);
    if(!pool.containsKey(id)) throw new IllegalStateException("Look missing: "+id);
    var r=PersonBody.RESIDENT.get().create(level);
    r.setLookId(ResourceKey.create(ResidentLooks.REGISTRY,id));
    r.setNoAi(true);r.setPersistenceRequired();r.setInvulnerable(true);
    r.moveTo(x+i*5,y,z,180,0);r.setYHeadRot(180);r.setYBodyRot(180);
    r.setHealth(r.getMaxHealth());r.foodLevel(20);r.setOnGround(true);level.addFreshEntity(r);
    residents.add(Map.of("uuid",r.getUUID().toString(),"model",model,"look",id.toString(),"x",r.getX(),"y",r.getY(),"z",r.getZ()));i++;
}
var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("touhou_little_maid:maid"));
var maid=(Mob)type.create(level);
maid.getClass().getMethod("setModelId",String.class).invoke(maid,"geckolib:winefox");
maid.setNoAi(true);maid.setPersistenceRequired();maid.setInvulnerable(true);
maid.moveTo(x-2.2,y,z,180,0);maid.setYHeadRot(180);maid.setYBodyRot(180);
maid.setHealth(maid.getMaxHealth());maid.setOnGround(true);
maid.getAttribute(InitAttribute.MAID_HUNGER).setBaseValue(20);level.addFreshEntity(maid);
return Map.of("residents",residents,"maid",maid.getUUID().toString(),"pool",pool.keySet().stream().map(Object::toString).filter(s->s.contains("maid/geckolib")).sorted().toList());
`,
  }),
});

const STATE = defineTask<{ uuid: string; mode: string; x: number; y: number; z: number }, Record<string, any>>({
  name: "folkways.visual.winefox.state", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.world.entity.PathfinderMob"],
    body: `
var a=Args.of(ctx);var r=(PathfinderMob)ctx.server().overworld().getEntity(UUID.fromString(a.string("uuid")));
double x=a.number("x"),y=a.number("y"),z=a.number("z");
r.getNavigation().stop();r.setNoAi(true);r.setDeltaMovement(0,0,0);
r.teleportTo(x,y,z);r.setOnGround(true);r.setYRot(180);r.setYHeadRot(180);r.setYBodyRot(180);
r.setHealth(r.getMaxHealth()*(a.string("mode").equals("low")?0.15f:1));
boolean walking=a.string("mode").equals("walk");
if(walking) {r.setNoAi(false);r.setOnGround(true);if(!r.getNavigation().moveTo(x,y,z-10,1))throw new IllegalStateException("No walking path");}
return Map.of("uuid",r.getUUID().toString(),"health",r.getHealth(),"maxHealth",r.getMaxHealth(),"walking",walking);
`,
  }),
});

const CLIENT_STATE = defineTask<{ uuid: string; mode: "read" | "prepare" | "align"; phaseTicks: number }, Record<string, any>>({
  name: "folkways.visual.winefox.client-state", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.minecraft.client.Minecraft", "net.minecraft.world.entity.LivingEntity", "net.minecraft.world.entity.Mob",
      "io.github.izakyl.folkways.plugins.person.ResidentEntity",
      "io.github.izakyl.folkways.plugins.person.ResidentGeoBackend",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLook",
      "io.github.izakyl.folkways.plugins.person.look.ResidentLooks",
      "io.github.izakyl.folkways.plugins.person.look.ResidentMolang",
      "com.github.tartaricacid.touhoulittlemaid.client.entity.GeckoMaidEntity",
      "com.github.tartaricacid.touhoulittlemaid.init.InitAttribute",
    ],
    members: `
private static java.lang.reflect.Field field(Object owner,String name) throws Exception {
    for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
        try {var f=type.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
    }
    throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
}
private static void align(Object controller,double clock,double phase) throws Exception {
    field(controller,"tickOffset").setDouble(controller,clock-phase);
    field(controller,"shouldResetTick").setBoolean(controller,false);
}
`,
    body: `
var a=Args.of(ctx);var wanted=UUID.fromString(a.string("uuid"));var mc=Minecraft.getInstance();
for(var e:mc.level.entitiesForRendering())if(e.getUUID().equals(wanted)) {
    var r=(LivingEntity)e;
    String mode=a.string("mode");double phase=a.number("phaseTicks");
    if(mode.equals("prepare")) {
        // NoAI skips vanilla travel physics. Grounding on the server before spawn alone is insufficient:
        // the add-entity packet does not carry onGround, and a stationary mob may send no movement packet.
        r.setOnGround(true);r.setDeltaMovement(0,0,0);
        r.setYRot(180);r.yRotO=180;r.setYHeadRot(180);r.yHeadRotO=180;r.setYBodyRot(180);r.yBodyRotO=180;
    }
    var result=new LinkedHashMap<String,Object>();
    result.put("uuid",wanted.toString());result.put("health",r.getHealth());result.put("maxHealth",r.getMaxHealth());
    result.put("hurtTime",r.hurtTime);result.put("sleeping",r.isSleeping());result.put("walkSpeed",r.walkAnimation.speed());
    result.put("onGround",r.onGround());result.put("noAi",r instanceof Mob mob&&mob.isNoAi());
    result.put("tickCount",r.tickCount);result.put("gameTime",mc.level.getGameTime());
    result.put("partialTick",mc.getTimer().getGameTimeDeltaPartialTick(false));
    result.put("x",r.getX());result.put("y",r.getY());result.put("z",r.getZ());
    result.put("velocity",List.of(r.getDeltaMovement().x,r.getDeltaMovement().y,r.getDeltaMovement().z));
    result.put("headYaw",r.yHeadRot);result.put("bodyYaw",r.yBodyRot);result.put("headPitch",r.getXRot());
    var controllers=new LinkedHashMap<String,Object>();var bones=new LinkedHashMap<String,Object>();
    var missing=new ArrayList<String>();
    var face=List.of("Head","EyeBrow","LeftEyebrow","RightEyebrow","Eyelid","RightEyelid","LeftEyelid",
        "RightEyelidBase","LeftEyelidBase","ysmGlowRightEyelidBase","ysmGlowLeftEyelidBase",
        "Mouth","kongju","jingya","weixiao","LeftArm");
    if(r instanceof ResidentEntity resident) {
        result.put("look",resident.lookId().orElseThrow().location().toString());result.put("foodLevel",resident.foodLevel());
        var model=(ResidentLook.Model)ResidentLooks.of(resident).appearance();
        result.put("renderedModel",model.geometry().toString());result.put("texture",model.texture().toString());
        if(!mode.equals("prepare")) {
            var instance=ResidentGeoBackend.Crew.class.getDeclaredField("INSTANCE");instance.setAccessible(true);
            var crew=(ResidentGeoBackend.Crew)instance.get(null);
            var manager=crew.getAnimatableInstanceCache().<ResidentGeoBackend.Crew>getManagerForId(resident.getId());
            var renderer=mc.getEntityRenderDispatcher().getRenderer(resident);
            var backend=(ResidentGeoBackend)field(renderer,"modelBackend").get(renderer);
            var rig=backend.getGeoModel();double clock=field(rig,"animTime").getDouble(rig);
            result.put("lifeTime",clock/20);result.put("closeEyes",ResidentMolang.CLOSE_EYES.value(r,0,0,clock));
            for(var c:manager.getAnimationControllers().values()) {
                var queued=c.getCurrentAnimation();if(queued==null)continue;
                String name=c.getName();var clip=queued.animation();
                if(mode.equals("align")&&(name.equals("move")||name.equals("pre_parallel3"))) {
                    if(name.equals("move")&&!clip.name().endsWith(".idle"))throw new IllegalStateException("Resident is not idle: "+clip.name());
                    if(!c.getAnimationState().toString().equals("RUNNING"))throw new IllegalStateException("Resident controller still transitioning: "+name);
                    align(c,clock,phase);
                }
                controllers.put(name,Map.of("clip",clip.name(),"state",c.getAnimationState().toString(),
                    "animTime",(clock-field(c,"tickOffset").getDouble(c))/20,"length",clip.length()/20));
            }
            if(mode.equals("align"))manager.updatedAt(-1); // Evaluate the changed phase again while ticks are frozen.
            for(String name:face) {
                var b=manager.getBoneSnapshotCollection().get(name);if(b==null){missing.add(name);continue;}
                bones.put(name,Map.of("position",List.of(b.getOffsetX(),b.getOffsetY(),b.getOffsetZ()),
                    "scale",List.of(b.getScaleX(),b.getScaleY(),b.getScaleZ()),
                    "rotation",List.of(b.getRotX(),b.getRotY(),b.getRotZ())));
            }
        }
    } else {
        result.put("look",String.valueOf(r.getClass().getMethod("getModelId").invoke(r)));
        var hunger=r.getAttribute(InitAttribute.MAID_HUNGER);result.put("foodLevel",hunger==null?20:(int)hunger.getValue());
        if(!mode.equals("prepare")) {
            var geo=(GeckoMaidEntity<?>)r.getData(GeckoMaidEntity.TYPE);
            double clock=geo.getSeekTime();result.put("lifeTime",clock/20);
            double blinkPhase=(clock+Math.abs(r.getUUID().getLeastSignificantBits())%10L)%90;
            result.put("closeEyes",r.isSleeping()||(85<blinkPhase&&blinkPhase<90)?1:0);
            result.put("renderedModel",geo.getModelLocation().toString());result.put("texture",geo.getTextureLocation().toString());
            for(var c:geo.getAnimationData().getAnimationControllers()) {
                var clip=c.getCurrentAnimation();if(clip==null)continue;
                String name=c.getName();
                if(mode.equals("align")&&(name.equals("main")||name.equals("pre_parallel_3_controller"))) {
                    if(name.equals("main")&&!clip.animationName.equals("idle"))throw new IllegalStateException("TLM is not idle: "+clip.animationName);
                    if(!c.getAnimationState().toString().equals("RUNNING"))throw new IllegalStateException("TLM controller still transitioning: "+name);
                    align(c,clock,phase);
                }
                controllers.put(name,Map.of("clip",clip.animationName,"state",c.getAnimationState().toString(),
                    "animTime",(clock-c.tickOffset)/20,"length",clip.animationLength/20));
            }
            if(mode.equals("align"))field(geo,"modelDirty").setBoolean(geo,true);
            var pose=geo.getCurrentModel();
            for(String name:face) {
                var b=pose==null?null:pose.bones().get(name);if(b==null){missing.add(name);continue;}
                bones.put(name,Map.of("position",List.of(b.getPositionX(),b.getPositionY(),b.getPositionZ()),
                    "scale",List.of(b.getScaleX(),b.getScaleY(),b.getScaleZ()),
                    "rotation",List.of(b.getRotationX(),b.getRotationY(),b.getRotationZ())));
            }
        }
    }
    result.put("queryInputs",Map.of("ysm.food_level",result.get("foodLevel"),
        "query.health",r.getHealth(),"query.max_health",r.getMaxHealth(),
        "ysm.head_yaw",net.minecraft.util.Mth.wrapDegrees(r.yHeadRot-r.yBodyRot),"ysm.head_pitch",r.getXRot(),
        "q.is_jumping",!r.isPassenger()&&!r.onGround()&&!r.isInWater()?1:0));
    result.put("controllers",controllers);result.put("faceBones",bones);result.put("missingBones",missing);
    return result;
}
throw new IllegalStateException("Entity not on client: "+wanted);
`,
  }),
});

const REFERENCE = defineTask<{ uuid: string; model: string; x: number; y: number; z: number }, Record<string, any>>({
  name: "folkways.visual.winefox.reference", side: "server", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.world.entity.Mob", "com.github.tartaricacid.touhoulittlemaid.init.InitAttribute"],
    body: `
var a=Args.of(ctx);var maid=(Mob)ctx.server().overworld().getEntity(UUID.fromString(a.string("uuid")));
String model="geckolib:"+a.string("model");
maid.getClass().getMethod("setModelId",String.class).invoke(maid,model);
maid.teleportTo(a.number("x"),a.number("y"),a.number("z"));
maid.setOnGround(true);maid.setDeltaMovement(0,0,0);
maid.setYRot(180);maid.setYHeadRot(180);maid.setYBodyRot(180);
maid.getAttribute(InitAttribute.MAID_HUNGER).setBaseValue(20);
return Map.of("uuid",maid.getUUID().toString(),"model",model,"health",maid.getHealth(),"onGround",maid.onGround());
`,
  }),
});

const EXPRESSIONS = defineTask<undefined, Record<string, any>>({
  name: "folkways.visual.winefox.generated-expressions", side: "client", timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.resources.ResourceLocation", "com.google.gson.JsonParser"],
    body: `
var result=new LinkedHashMap<String,Object>();
var portable=Class.forName("io.github.izakyl.folkways.plugins.person.look.Molang").getDeclaredMethod("portable",String.class);
portable.setAccessible(true);
result.put("portableFoodQuery",portable.invoke(null,"ysm.food_level"));
result.put("portableHungryEyelid",portable.invoke(null,"ysm.food_level<=6?0.7:1"));
for(String model:List.of("winefox_tactics","winefox_magical")) {
    var file=ResourceLocation.parse("folkways:animations/resident_models/maid/geckolib/"+model+".animation.json");
    try(var reader=Minecraft.getInstance().getResourceManager().getResource(file).orElseThrow().openAsReader()) {
        var clips=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
        var bones=clips.getAsJsonObject("maid.geckolib."+model+".idle").getAsJsonObject("bones");
        var channels=new LinkedHashMap<String,String>();
        for(String bone:List.of("LeftArm","RightEyelidBase","LeftEyelidBase","EyeBrow","kongju","weixiao"))
            if(bones.has(bone))channels.put(bone,bones.get(bone).toString());
        String blink="maid.geckolib."+model+".pre_parallel3";
        if(clips.has(blink))channels.put("pre_parallel3",clips.get(blink).toString());
        result.put(model,channels);
    }
}
return result;
`,
  }),
});

test("winefox face healthy, walking, low health and other maid layers", async ({}, testInfo) => {
  test.setTimeout(BUDGET.heavy);
  const run = new VisualRun("winefox-face");
  mkdirSync(SHOTS, { recursive: true });
  const mirror = (file: string) => {
    if (path.resolve(run.dir) !== SHOTS) copyFileSync(path.join(run.dir, file), path.join(SHOTS, file));
  };
  const id = newRunId();
  let pair: E2EPair | undefined;
  try {
    pair = await launchPair({ config: "blockwright.toml#maids",
      server: { instance: `visual-winefox-server-${id}`, trace: tracePath("visual", "winefox-server") },
      client: { instance: `visual-winefox-client-${id}`, trace: tracePath("visual", "winefox-client") },
    });
    const { server, client } = pair;
    const grounded = await waitForGroundedPlayer(server);
    const p = { x: Math.floor(grounded.pos.x) + 4.5, y: Math.floor(grounded.pos.y), z: Math.floor(grounded.pos.z) + 4.5 };
    const cmd = (s: string) => world.command(server, s);
    await cmd(`gamemode spectator ${grounded.name}`);
    await cmd("gamerule doDaylightCycle false");
    await cmd("time set 1000");
    await cmd("weather clear");
    await cmd("gamerule doWeatherCycle false");
    await cmd("gamerule doMobSpawning false");
    await tryCommand(server, `fill ${Math.floor(p.x)-10} ${p.y} ${Math.floor(p.z)-15} ${Math.floor(p.x)+25} ${p.y+8} ${Math.floor(p.z)+5} air`);
    await cmd(`fill ${Math.floor(p.x)-10} ${p.y-1} ${Math.floor(p.z)-15} ${Math.floor(p.x)+25} ${p.y-1} ${Math.floor(p.z)+5} smooth_stone`);
    await cmd(`tp ${grounded.name} ${p.x} ${p.y+2} ${p.z-6} 0 0`);
    await tick.freeze(server, true);
    const staged = await server.run(STAGE, { ...p, models: MODELS });
    run.note("stage", staged);
    await screen.dismiss(client);
    await camera.hud(client, false);
    await tick.step(server, 40);
    await frames(client, 20);
    const stateOf = (uuid: string, mode: "read" | "prepare" | "align" = "read") =>
      client.run(CLIENT_STATE, { uuid, mode, phaseTicks: PORTRAIT_PHASE_TICKS });
    run.note("reference-before-client-grounding", await stateOf(staged.maid));
    // NoAI entities need the same grounding on both sides; see CLIENT_STATE.prepare.
    for (const uuid of [...staged.residents.map((r: any) => r.uuid), staged.maid]) await stateOf(uuid, "prepare");

    const shoot = async (name: string, subject: string, at: Vec, uuid: string, full = false, group = false, low = false, idle = false) => {
      const target = { x: at.x, y: at.y + (low && !full ? 0.35 : full ? 0.95 : 1.40), z: at.z };
      const eye = { x: at.x, y: target.y + (full ? 0.12 : 0), z: at.z - (low && !full ? 2.2 : full ? 4.1 : 2.7) };
      const pose = { dimension: "minecraft:overworld", position: eye, ...cameraLookingAt(eye, target), fov: group ? 43 : 32 };
      await camera.set(client, pose);
      await frames(client, 3);
      if (idle) {
        run.note(`${name}-before-grounding`, await stateOf(uuid));
        await stateOf(uuid, "prepare");
        await tick.step(server, 8); // Let both engines finish the ordinary transition to idle.
        await frames(client, 10);
        run.note(`${name}-phase-alignment`, await stateOf(uuid, "align"));
        await frames(client, 3);
      }
      const state = await stateOf(uuid);
      if (idle) {
        expect(state.onGround, `${name} must be grounded, otherwise TLM selects jump`).toBe(true);
        expect(state.foodLevel, `${name} must compare fed expressions`).toBe(20);
        const main = state.controllers.move ?? state.controllers.main;
        expect(main.clip, `${name} must use the actual idle clip`).toMatch(/(?:^|\.)idle$/);
        expect(main.animTime, `${name} idle phase`).toBeCloseTo(PORTRAIT_PHASE_TICKS / 20, 2);
        const blink = state.controllers.pre_parallel3 ?? state.controllers.pre_parallel_3_controller;
        if (blink) expect(blink.animTime, `${name} blink phase`).toBeCloseTo(PORTRAIT_PHASE_TICKS / 20, 2);
      }
      await run.shot(client, name, { subject, worldState: state, camera: pose });
      run.note(`${name}-after-capture`, await stateOf(uuid));
      mirror(`${name}.png`);
      console.log(name, JSON.stringify(state));
      return state;
    };
    const wine = staged.residents[0];
    const healthy = await shoot("01-winefox-healthy-idle-face", "Healthy Wine Fox resident, front face, awake and idle", p, wine.uuid, false, false, false, true);
    expect(healthy.health / healthy.maxHealth).toBe(1);
    await shoot("02-winefox-resident-left-tlm-maid-right", "Healthy resident left and real TLM geckolib:winefox maid right", { ...p, x: p.x - 1.1 }, wine.uuid, true, true);
    // Park the reference outside subsequent portraits.
    await server.run(REFERENCE, { ...p, x: p.x - 8, uuid: staged.maid, model: "winefox" });

    await server.run(STATE, { ...p, uuid: wine.uuid, mode: "walk" });
    await tick.step(server, 10);
    await frames(client, 3);
    const moving = await stateOf(wine.uuid);
    expect(moving.walkSpeed, "client must actually animate a walking resident").toBeGreaterThan(0.05);
    expect(moving.z).toBeLessThan(p.z - 0.1);
    await shoot("03-winefox-walking-front", "Same healthy Wine Fox resident walking toward camera", moving as Vec, wine.uuid, true);

    // The authored AllBody/FOX condition is strictly < 0.2, so use 15%, without transient red hurt tint.
    await server.run(STATE, { ...p, uuid: wine.uuid, mode: "low" });
    await tick.step(server, 30);
    await frames(client, 10);
    const low = await shoot("04-winefox-low-health-face", "Same Wine Fox resident at 15% health; camera lowered to its fox face", p, wine.uuid, false, false, true);
    expect(low.health / low.maxHealth).toBeLessThan(0.2);
    await shoot("05-winefox-low-health-full-form", "Low health full form: authored FOX should replace AllBody", p, wine.uuid, true);

    const resting = new Map<string, Record<string, any>>();
    for (const [index, resident] of staged.residents.slice(1).entries()) {
      resting.set(resident.model, await shoot(`0${index+6}-${resident.model}-idle`, `${resident.model}, fed grounded idle at two seconds`, resident as Vec, resident.uuid, true, false, false, true));
    }
    for (const [index, model] of MODELS.slice(1).entries()) {
      const at = { ...p, x: p.x - 8 };
      run.note(`reference-${model}`, await server.run(REFERENCE, { ...at, uuid: staged.maid, model }));
      await tick.step(server, 20);
      await frames(client, 10);
      const reference = await shoot(`${String(index+9).padStart(2, "0")}-${model}-real-tlm-reference`, `Real TLM ${model}, fed grounded idle at two seconds`, at, staged.maid, true, false, false, true);
      if (model === "winefox_magical") {
        const resident = resting.get(model)!;
        for (const bone of ["weixiao", "kongju"]) {
          expect(resident.faceBones[bone].position[2], `${bone} mouth depth must match TLM at the same state`)
            .toBeCloseTo(reference.faceBones[bone].position[2], 4);
        }
        for (const bone of ["RightEyelid", "LeftEyelid"]) {
          expect(resident.faceBones[bone].scale[1], `${bone} blink scale must match TLM at two seconds`)
            .toBeCloseTo(reference.faceBones[bone].scale[1], 4);
        }
      }
    }
    run.note("generated-expression-evidence", await client.run(EXPRESSIONS, undefined));
    run.expectTaken(["01-winefox-healthy-idle-face", "02-winefox-resident-left-tlm-maid-right", "03-winefox-walking-front", "04-winefox-low-health-face", "05-winefox-low-health-full-form", ...MODELS.slice(1).map((m, i) => `0${i+6}-${m}-idle`), ...MODELS.slice(1).map((m, i) => `${String(i+9).padStart(2, "0")}-${m}-real-tlm-reference`)]);
  } catch (error) {
    pair?.failing(error);
    throw error;
  } finally {
    if (existsSync(path.join(run.dir, "index.json"))) mirror("index.json");
    await pair?.teardown(testInfo);
  }
});
