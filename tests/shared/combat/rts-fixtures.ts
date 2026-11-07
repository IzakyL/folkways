import { defineTask, javaTask } from "@izakyl/blockwright-minecraft";

export type Vec = { x: number; y: number; z: number };
export type Pixel = { x: number; y: number; left: number; top: number; right: number; bottom: number; width: number; height: number };
export type Button = {
  kind: string; slot: number; resident: string; x: number; y: number; width: number; height: number; texture: string;
};
export type ClientState = {
  combat: boolean; roster: number; selected: string[]; cachedPoints: number; cachedCommand: string;
  attackMode: boolean; replacementPending: boolean; routePending: boolean; controlPending: boolean;
  control: { request: number; accepted: boolean; operation: string; selection: string[]; groups: string[][] };
  route: { request: number; accepted: boolean; intent: string; points: Vec[]; cycleStart: number; command: string };
  state: { command: string; complete: boolean; stop: number; attackMove: boolean; phase: string; result: string };
  buttons: Button[]; barHeight: number; overlay: string;
  modeLabel: string; modeHint: string; modeColors: number[]; modeBold: boolean[];
  failure: string; failureKey: string; failureUntil: number; failureRemaining: number;
};

export const PREPARE_RESIDENTS = defineTask<{ colony: string } & Vec & { count: number }, string[]>({
  name: "folkways.combat-rts-input.prepare-flat-fixture", side: "server",
  source: javaTask({
    imports: ["java.util.UUID", "net.minecraft.server.MinecraftServer", "net.minecraft.world.entity.Mob",
      "io.github.izakyl.folkways.core.colony.api.Colonies"],
    body: `
var a=Args.of(ctx);var server=(MinecraftServer)ctx.server();
var colony=Colonies.of(server,UUID.fromString(a.string("colony"))).orElseThrow();
var ids=colony.residents().stream().map(r->r.id()).limit(a.integer("count")).toList();
for(int i=0;i<ids.size();i++){
  Mob mob=null;for(var level:server.getAllLevels()){var entity=level.getEntity(ids.get(i));if(entity instanceof Mob found){mob=found;break;}}
  if(mob==null)throw new IllegalStateException("missing fixture resident "+ids.get(i));
  mob.getNavigation().stop();
  double x = ids.size() <= 3 ? -4+i*4+0.5 : (i%5-2)*2+0.5;
  double z = ids.size() <= 3 ? 6.5 : 5.5+(i/5)*2;
  mob.moveTo(a.integer("x")+x,a.integer("y"),a.integer("z")+z,0,0);
  mob.setHealth(mob.getMaxHealth());
}
return ids.stream().map(Object::toString).toList();`,
  }),
});

export const READ_CLIENT = defineTask<void, ClientState>({
  name: "folkways.combat-rts-input.observe-client-and-rendered-hits", side: "client",
  source: javaTask({
    imports: ["java.util.List", "java.util.Map", "java.util.Set", "java.util.LinkedHashMap", "java.util.ArrayList",
      "io.github.izakyl.folkways.front.api.ui.OverheadTools", "io.github.izakyl.folkways.rts.combat.net.CombatPackets"],
    body: `
var out=new LinkedHashMap<String,Object>();var control=CombatPackets.controls().orElse(null);var route=CombatPackets.route().orElse(null);var state=CombatPackets.state();
var gui = net.minecraft.client.Minecraft.getInstance().gui;
var overlay = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageString");
overlay.setAccessible(true);
var overlayTime = net.minecraft.client.gui.Gui.class.getDeclaredField("overlayMessageTime");
overlayTime.setAccessible(true);
var message = (net.minecraft.network.chat.Component) overlay.get(gui);
out.put("overlay", overlayTime.getInt(gui) > 0 && message != null ? message.getString() : "");
out.put("roster",CombatPackets.roster().size());out.put("controlPending",CombatPackets.controlPending());out.put("routePending",CombatPackets.routePending());
out.put("control",control==null?Map.of("request",0L,"accepted",false,"operation","","selection",List.of(),"groups",List.of()):
  Map.of("request",control.request(),"accepted",control.accepted(),"operation",control.operation().name(),
    "selection",control.selection().stream().map(Object::toString).toList(),"groups",control.groups().stream().map(g->g.stream().map(Object::toString).toList()).toList()));
out.put("route",route==null?Map.of("request",0L,"accepted",false,"intent","","points",List.of(),"cycleStart",-1,"command",""):
  Map.of("request",route.request(),"accepted",route.accepted(),"intent",route.intent().name(),"cycleStart",route.cycleStart(),
    "points",route.points().stream().map(p->Map.of("x",p.cell().getX(),"y",p.cell().getY(),"z",p.cell().getZ())).toList(),"command",String.valueOf(route.state().commandId())));
out.put("state",Map.of("command",String.valueOf(state.commandId()),"complete",state.routeComplete(),"stop",state.stop(),"attackMove",state.attackMove(),"phase",state.phase(),"result",state.result()));
var handler=OverheadTools.selectedHandler().orElse(null);boolean combat=handler!=null&&handler.getClass().getName().endsWith(".CombatController");out.put("combat",combat);
out.put("modeLabel", "");out.put("modeHint", "");out.put("modeColors", List.of());out.put("modeBold", List.of());
out.put("failure", "");out.put("failureKey", "");out.put("failureUntil", 0L);out.put("failureRemaining", 0L);
out.put("barHeight", combat ? handler.bottomInset() : 0);
out.put("selected",List.of());out.put("cachedPoints",0);out.put("cachedCommand","");out.put("attackMode",false);out.put("replacementPending",false);out.put("buttons",List.of());
if(combat){
  var type=handler.getClass();
  var visibleFailure = type.getDeclaredMethod("visibleFailure");
  visibleFailure.setAccessible(true);
  var failure = (net.minecraft.network.chat.Component) visibleFailure.invoke(handler);
  var deadline = type.getDeclaredField("failureUntil");
  deadline.setAccessible(true);
  long until = deadline.getLong(handler);
  out.put("failure", failure == null ? "" : failure.getString());
  out.put("failureKey", failure != null && failure.getContents() instanceof
    net.minecraft.network.chat.contents.TranslatableContents translated ? translated.getKey() : "");
  out.put("failureUntil", until);
  out.put("failureRemaining", Math.max(0, until - net.minecraft.Util.getMillis()));
  var selected=type.getDeclaredField("selected");selected.setAccessible(true);out.put("selected",((Set<?>)selected.get(handler)).stream().map(Object::toString).toList());
  var points=type.getDeclaredField("route");points.setAccessible(true);out.put("cachedPoints",((List<?>)points.get(handler)).size());
  var command=type.getDeclaredField("routeCommandId");command.setAccessible(true);out.put("cachedCommand",String.valueOf(command.get(handler)));
  var attackMode=type.getDeclaredField("attackMoveMode");attackMode.setAccessible(true);out.put("attackMode",attackMode.getBoolean(handler));
  var mode = type.getDeclaredMethod("movementMode");mode.setAccessible(true);
  out.put("modeLabel", ((net.minecraft.network.chat.Component) mode.invoke(handler)).getString());
  var hint = handler.cues().get(1).does();out.put("modeHint", hint.getString());
  var options = ((net.minecraft.network.chat.contents.TranslatableContents) hint.getContents()).getArgs();
  out.put("modeColors", java.util.Arrays.stream(options).map(option -> ((net.minecraft.network.chat.Component) option).getStyle().getColor().getValue()).toList());
  out.put("modeBold", java.util.Arrays.stream(options).map(option -> ((net.minecraft.network.chat.Component) option).getStyle().isBold()).toList());
  var pending=type.getDeclaredField("pendingReplacement");pending.setAccessible(true);out.put("replacementPending",pending.get(handler)!=null);
  var cardsField=type.getDeclaredField("cards");cardsField.setAccessible(true);var cards=cardsField.get(handler);
  var hitsField=cards.getClass().getDeclaredField("hits");hitsField.setAccessible(true);var buttons=new ArrayList<Map<String,Object>>();
  var pressed = cards.getClass().getDeclaredField("PRESSED");pressed.setAccessible(true);
  var normal = cards.getClass().getDeclaredField("BUTTON");normal.setAccessible(true);
  for(var hit:(List<?>)hitsField.get(cards)){
    var actionMethod=hit.getClass().getDeclaredMethod("action");actionMethod.setAccessible(true);var action=actionMethod.invoke(hit);
    var kindMethod=action.getClass().getDeclaredMethod("kind");kindMethod.setAccessible(true);var row=new LinkedHashMap<String,Object>();row.put("kind",kindMethod.invoke(action).toString());
    var slot = action.getClass().getDeclaredMethod("slot");slot.setAccessible(true);row.put("slot",slot.invoke(action));
    var resident = action.getClass().getDeclaredMethod("resident");resident.setAccessible(true);
    row.put("resident", String.valueOf(resident.invoke(action)));
    var texture = hit.getClass().getDeclaredMethod("texture");texture.setAccessible(true);
    var drawn = texture.invoke(hit);row.put("texture",drawn == pressed.get(null) ? "pressed" : drawn == normal.get(null) ? "normal" : "hover");
    for(var field:List.of("x","y","width","height")){var accessor=hit.getClass().getDeclaredMethod(field);accessor.setAccessible(true);row.put(field,accessor.invoke(hit));}buttons.add(row);
  }out.put("buttons",buttons);
}return out;`,
  }),
});

export const PROJECT = defineTask<Vec & { entity: string }, Pixel>({
  name: "folkways.combat-rts-input.observe-world-projection", side: "client",
  source: javaTask({
    imports: ["java.util.Map", "java.util.Optional", "java.util.UUID", "net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3", "net.minecraft.world.phys.AABB"],
    body: `
var a=Args.of(ctx);var mc=Minecraft.getInstance();var type=Class.forName("io.github.izakyl.folkways.rts.client.CombatProjection");
var constructor=type.getDeclaredConstructor(Minecraft.class);constructor.setAccessible(true);var projection=constructor.newInstance(mc);
double left,top,right,bottom;
if(!a.string("entity").isEmpty()){
  var id=UUID.fromString(a.string("entity"));net.minecraft.world.entity.Entity found=null;
  for(var entity:mc.level.entitiesForRendering()){if(entity.getUUID().equals(id)){found=entity;break;}}
  if(found==null)throw new IllegalStateException("fixture resident is not client-tracked: "+id);
  var bounds=type.getDeclaredMethod("bounds",AABB.class);bounds.setAccessible(true);var rect=((Optional<?>)bounds.invoke(projection,found.getBoundingBox())).orElseThrow();
  var l=rect.getClass().getDeclaredMethod("left");l.setAccessible(true);left=((Number)l.invoke(rect)).doubleValue();
  var t=rect.getClass().getDeclaredMethod("top");t.setAccessible(true);top=((Number)t.invoke(rect)).doubleValue();
  var r=rect.getClass().getDeclaredMethod("right");r.setAccessible(true);right=((Number)r.invoke(rect)).doubleValue();
  var b=rect.getClass().getDeclaredMethod("bottom");b.setAccessible(true);bottom=((Number)b.invoke(rect)).doubleValue();
}else{
  var point=type.getDeclaredMethod("point",Vec3.class);point.setAccessible(true);
  var pixel=((Optional<?>)point.invoke(projection,new Vec3(a.integer("x")+0.5,a.integer("y")+0.12,a.integer("z")+0.5))).orElseThrow();
  var x=pixel.getClass().getDeclaredMethod("x");x.setAccessible(true);left=right=((Number)x.invoke(pixel)).doubleValue();
  var y=pixel.getClass().getDeclaredMethod("y");y.setAccessible(true);top=bottom=((Number)y.invoke(pixel)).doubleValue();
}
return Map.of("x",(left+right)/2,"y",(top+bottom)/2,"left",left,"top",top,"right",right,"bottom",bottom,
  "width",mc.getWindow().getGuiScaledWidth(),"height",mc.getWindow().getGuiScaledHeight());`,
  }),
});

export const RESIZE = defineTask<{ width: number; height: number; scale: number }, boolean>({
  name: "folkways.combat-rts-input.resize-for-visual-review", side: "client",
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "org.lwjgl.glfw.GLFW"],
    body: `var a = Args.of(ctx);
var mc = Minecraft.getInstance();
GLFW.glfwSetWindowSize(mc.getWindow().getWindow(), (int) a.integer("width"), (int) a.integer("height"));
mc.options.guiScale().set((int) a.integer("scale"));
mc.resizeDisplay();
return true;`,
  }),
});
