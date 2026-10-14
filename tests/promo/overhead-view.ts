import { clickElement, panelElements } from "../ldlib2";
import { defineTask, input, javaTask, screen, type MinecraftClient } from "@izakyl/blockwright-minecraft";
import { clickUnverified, safeAction, safeScreen, screenName, softly } from "../shared/harness/bw-helpers";
import { REPO_ROOT } from "../shared/harness/out-paths";
import { sleepMs } from "./promo-kit";
import { readFileSync } from "node:fs";
import path from "node:path";

// The book's view from above, as the takes work it: the mouse and the keys a player has, and the tasks that tell a
// take where the camera is and what the ground is.

export type Vec = { x: number; y: number; z: number };
export type Px = { x: number; y: number };
export type Pose = { x: number; y: number; z: number; yaw: number; pitch: number; width: number; height: number };
export type Cell = [number, number];

// The game is in Chinese (blockwright.toml#promo-land): its buttons are found by what they say there.
export const SAYS: Record<string, string> = JSON.parse(
  readFileSync(path.join(REPO_ROOT, "src/main/resources/assets/folkways/lang/zh_cn.json"), "utf8"));

// The view's own numbers (Overhead.java): how fast the walking keys fly it, how far a wheel notch moves it along its
// line of sight, and how far a dragged pixel turns it.
const BLOCKS_A_SECOND = 24;
const BLOCKS_A_NOTCH = 4;
const DEGREES_A_PIXEL = 0.3;
/** How far down the view looks while a drawing is clicked out. */
export const PLAN = 72;
export const KEY = { W: 87, A: 65, S: 83, D: 68, V: 86, CTRL: 341, ALT: 342, END: 269, BACKSPACE: 259 };

/** A knob of the pattern's page, set as a player sets it: pressed so many times, or an option picked from its list. */
// A number is typed into its field, a tick box is clicked once, a choice is picked from its list, and each block
// named for a list of blocks is clicked in it: off if it was ticked, on if it was not.
export type Knob = { key: string; value: number } | { key: string; tick: true } | { key: string; option: string }
  | { key: string; toggle: string[] };

/** One drawing: its gesture, the cells clicked, and the knobs set on the pattern's page. */
export type Drawing = {
  gesture: "line" | "box";
  /** The cells clicked, in order: a line's points, or a box's two corners. */
  cells: Cell[];
  /**
   * The height each of a line's points is brought to with the wheel, where the cell the click landed on stands too
   * low or (on a tuft of grass) too high for the pattern, or where the line is to run under the ground or over it.
   * A point with none stays where the click put it.
   */
  lift?: Array<number | undefined>;
  /**
   * How high the mouse aims over each cell, where an earlier drawing has raised something on it by the time this one
   * is clicked: a branch line ends on the track it joins. A cell with none is clicked on its ground.
   */
  onto?: Array<number | undefined>;
  /** Whether the clicks must land on the very cells: the railway's stretches run straight along an axis or a diagonal. */
  exact?: boolean;
  knobs?: Knob[];
};

function mean(values: number[]) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

/** A key pressed and let go a moment later, long enough for the game to show it held. */
export async function holdKey(client: MinecraftClient, keyCode: number, ms = 160) {
  await safeAction(() => input.key(client, { keyCode, action: "press" }));
  await sleepMs(ms);
  await safeAction(() => input.key(client, { keyCode, action: "release" }));
}

/** The view from above, worked as a player works it: the mouse is moved, pressed, dragged and scrolled, and keys held. */
export class View {
  private mouse: Px = { x: 480, y: 270 };
  private gesture = "";

  constructor(private readonly client: MinecraftClient, private readonly ground: (cell: Cell) => Vec) {
  }

  async pose(points: Vec[] = []): Promise<Pose & { at: Px[] }> {
    return await this.client.run(EYE, { points: points.flatMap(point => [point.x, point.y, point.z]) }) as any;
  }

  /** Slides the pointer to a place on the screen. */
  async glide(to: Px, ms = 450) {
    const from = this.mouse;
    const steps = Math.max(2, Math.round(ms / 16));
    for (let step = 1; step <= steps; step++) {
      const t = step / steps, eased = t * t * (3 - 2 * t);
      await safeAction(() => input.move(this.client, { x: from.x + (to.x - from.x) * eased, y: from.y + (to.y - from.y) * eased }));
      await sleepMs(12);
    }
    this.mouse = to;
  }

  /** Leaves the pointer in the middle of the picture, clear of what it last pointed at. */
  async park() {
    const pose = await this.pose();
    await this.glide({ x: pose.width / 2, y: pose.height * 0.62 }, 300);
  }

  private async onScreen(spot: Vec): Promise<Px> {
    const pose = await this.pose([spot]);
    const at = pose.at[0];
    if (!(at.x > 12 && at.x < pose.width - 12 && at.y > 44 && at.y < pose.height - 12)) {
      throw new Error(`${JSON.stringify(spot)} is off the picture at ${JSON.stringify(at)} from ${JSON.stringify(pose)}`);
    }
    return at;
  }

  async hover(spot: Vec, ms = 450) {
    await this.glide(await this.onScreen(spot), ms);
  }

  private async tap(button: "left" | "right" | "middle" = "left", ms = 90) {
    await safeAction(() => input.button(this.client, { button, action: "press" }));
    await sleepMs(ms);
    await safeAction(() => input.button(this.client, { button, action: "release" }));
  }

  async click(spot: Vec, button: "left" | "right" = "left", ms = 450) {
    await this.hover(spot, ms);
    await sleepMs(160);
    await this.tap(button);
  }

  /** Presses one of the buttons along the top, named by its lang key. */
  async press(key: string) {
    const label = SAYS[key];
    let widget: any;
    for (let wait = 0; wait < 30 && !widget; wait++) {
      widget = ((await softly(() => screen.widgets(this.client))) as any[] ?? []).find?.((one: any) => one.label === label);
      if (!widget) await sleepMs(150);
    }
    if (!widget) throw new Error(`no ${label} button on the view from above`);
    await this.glide({ x: widget.x + widget.width / 2, y: widget.y + widget.height / 2 });
    await sleepMs(180);
    await this.tap();
    await sleepMs(250);
  }

  /** Holds walking keys for a while. */
  async fly(keys: number[], seconds: number) {
    if (seconds < 0.02) return;
    await this.client.run(HOLD, { keys, ms: Math.round(seconds * 1000) });
    await sleepMs(seconds * 1000 + 60);
  }

  /** Wheel notches: positive nearer what the view looks at. */
  async dolly(notches: number, everyMs = 170) {
    for (let notch = 0; notch < Math.abs(notches); notch++) {
      await safeAction(() => input.scroll(this.client, { yOffset: Math.sign(notches) }));
      await sleepMs(everyMs);
    }
  }

  /** A drag with the middle button: degrees to the right and down. */
  async turn(yaw: number, pitch: number, ms = 900) {
    if (Math.abs(yaw) < 0.2 && Math.abs(pitch) < 0.2) return;
    const dx = yaw / DEGREES_A_PIXEL, dy = pitch / DEGREES_A_PIXEL;
    const pose = await this.pose();
    await this.glide({ x: pose.width / 2 - dx / 2, y: pose.height / 2 - dy / 2 }, 280);
    await safeAction(() => input.button(this.client, { button: "middle", action: "press" }));
    await this.glide({ x: pose.width / 2 + dx / 2, y: pose.height / 2 + dy / 2 }, ms);
    await safeAction(() => input.button(this.client, { button: "middle", action: "release" }));
  }

  /** Brings the view to look at a spot, turned to yaw and tilted, from off blocks away. */
  async goTo(spot: Vec, yaw: number, off: number, pitch = 45) {
    let pose = await this.pose();
    const swing = ((yaw - pose.yaw + 540) % 360) - 180;
    await this.turn(swing, pitch - pose.pitch, 400 + Math.abs(swing) * 9);
    const tilt = pitch * Math.PI / 180, heading = yaw * Math.PI / 180;
    // The height it will stand at, reached with the wheel before it flies: flying keeps the height.
    pose = await this.pose();
    await this.dolly(Math.round((pose.y - (spot.y + Math.sin(tilt) * off)) / (Math.sin(tilt) * BLOCKS_A_NOTCH)), 110);
    for (let pass = 0; pass < 3; pass++) {
      pose = await this.pose();
      // It flies to where, from its height, the spot is in the middle of the picture.
      const back = (pose.y - spot.y) / Math.tan(tilt);
      const eye = { x: spot.x + Math.sin(heading) * back, z: spot.z - Math.cos(heading) * back };
      const ahead = { x: -Math.sin(pose.yaw * Math.PI / 180), z: Math.cos(pose.yaw * Math.PI / 180) };
      const dx = eye.x - pose.x, dz = eye.z - pose.z;
      const forward = dx * ahead.x + dz * ahead.z, aside = dx * -ahead.z + dz * ahead.x;
      if (Math.hypot(forward, aside) < 1.5) break;
      const both = Math.min(Math.abs(forward), Math.abs(aside)), keys = [forward > 0 ? KEY.W : KEY.S, aside > 0 ? KEY.D : KEY.A];
      await this.fly(keys, both * Math.SQRT2 / BLOCKS_A_SECOND);
      await this.fly([Math.abs(forward) > Math.abs(aside) ? keys[0] : keys[1]],
        (Math.max(Math.abs(forward), Math.abs(aside)) - both) / BLOCKS_A_SECOND);
    }
  }

  /**
   * One drawing, start to finish: the gesture picked, the view brought over the ground, the cells clicked, and what
   * was drawn handed to the pattern with its knobs set. pace stretches or shortens the pauses between the steps.
   */
  async draft(pattern: string, draft: Drawing & { yaw: number }, pace: number) {
    await this.gestured(draft, pace);
    // Drawn looking almost straight down, where no rise in the ground hides a cell from the mouse, from far enough
    // off to have every cell in the picture.
    // The distance is kept from the highest cell, and grows with how far the others lie below it.
    const heights = draft.cells.map(cell => this.ground(cell).y);
    const middle = { x: mean(draft.cells.map(cell => cell[0])) + 0.5, y: Math.max(...heights), z: mean(draft.cells.map(cell => cell[1])) + 0.5 };
    const reach = Math.max(...draft.cells.map(cell => Math.hypot(cell[0] + 0.5 - middle.x, cell[1] + 0.5 - middle.z)));
    await this.goTo(middle, draft.yaw, Math.max(30, reach / 0.6 + 6) + (middle.y - Math.min(...heights)) * 0.6, PLAN);
    await sleepMs(400 * pace);
    return this.drawn(pattern, draft, pace);
  }

  /** One drawing from where the view stands: nothing moves but the mouse. */
  async drawn(pattern: string, draft: Drawing, pace: number) {
    const wait = (ms: number) => sleepMs(ms * pace);
    await this.gestured(draft, pace);
    const heights = draft.cells.map(cell => this.ground(cell).y);
    const middle = { x: mean(draft.cells.map(cell => cell[0])) + 0.5, y: Math.max(...heights), z: mean(draft.cells.map(cell => cell[1])) + 0.5 };
    for (const [index, cell] of draft.cells.entries()) {
      const spot = this.ground(cell);
      await this.click({ ...spot, y: draft.onto?.[index] ?? spot.y }, "left", 300 + 250 * pace);
      await wait(350);
      if (draft.gesture === "line") await this.lifted(index, cell, draft.lift?.[index], draft.exact);
    }
    await wait(500);
    const said = await this.commission(middle, pattern, draft.knobs ?? [], pace);
    return { cells: draft.cells, ...said };
  }

  private async gestured(draft: Drawing, pace: number) {
    if (this.gesture === draft.gesture) return;
    await this.press(`folkways.overhead.gesture.${draft.gesture}`);
    this.gesture = draft.gesture;
    await sleepMs(400 * pace);
  }

  /** Checks the point just clicked is the cell meant, and lifts it with Ctrl+Alt and the wheel if it is to stand higher. */
  private async lifted(index: number, cell: Cell, lift?: number, exact = false) {
    const read = async () => ((await this.client.run(PATH_POINTS, {})) as any).points[index] as number[] | undefined;
    let point = await read();
    // A step in the ground between the camera and a cell can take the click a block or two short of it.
    const astray = point ? Math.max(Math.abs(point[0] - cell[0]), Math.abs(point[2] - cell[1])) : 99;
    if (astray > (exact ? 0 : 3)) {
      throw new Error(`clicked for ${cell} and the line took ${JSON.stringify(point)} as its point ${index + 1}`);
    }
    if (lift === undefined || point[1] === lift) return;
    await safeAction(() => input.key(this.client, { keyCode: KEY.CTRL, action: "press" }));
    await safeAction(() => input.key(this.client, { keyCode: KEY.ALT, action: "press" }));
    try {
      let up = 1;
      for (let turnOfWheel = 0; turnOfWheel < 16 && point![1] !== lift; turnOfWheel++) {
        const before = Math.abs(lift - point![1]);
        await safeAction(() => input.scroll(this.client, { yOffset: Math.sign(lift - point![1]) * up }));
        await sleepMs(220);
        point = (await read())!;
        if (Math.abs(lift - point[1]) > before) up = -up;
      }
    } finally {
      await safeAction(() => input.key(this.client, { keyCode: KEY.ALT, action: "release" }));
      await safeAction(() => input.key(this.client, { keyCode: KEY.CTRL, action: "release" }));
    }
    if (point![1] !== lift) throw new Error(`point ${index + 1} stands at ${point![1]}, not lifted to ${lift}`);
  }

  /**
   * Right-clicks what was drawn, takes the offer to raise a building there, picks the pattern, sets its knobs,
   * previews it, and comes back to build it.
   */
  private async commission(spot: Vec, pattern: string, knobs: Knob[], pace: number) {
    const wait = (ms: number) => sleepMs(ms * pace);
    await this.click(spot, "right");
    const offer = await this.element(id => id.startsWith("folkways.zoneconfig.") && id.includes("draft"));
    await this.pick(offer);
    const picked = await this.element(id => id.startsWith("folkways.draft.pattern.") && id.endsWith(`.${pattern}`));
    await wait(400);
    await this.pick(picked);
    await wait(500);
    for (const knob of knobs) {
      const id = `folkways.draft.knob.${knob.key}`;
      if ("option" in knob) {
        await this.pick(id, true);
        const option = await this.element(one => one.startsWith("folkways.choice.option.") && one.endsWith(`.${knob.option}`));
        await wait(350);
        await this.pick(option);
        await wait(250);
        await this.pick("folkways.choice.confirm");
      } else if ("toggle" in knob) {
        await this.pick(id, true);
        for (const block of knob.toggle) {
          const row = await this.element(one => one === `folkways.choice.option.${block.replace(":", ".")}`);
          await this.pick(row, true);
          await wait(300);
        }
        await this.pick("folkways.choice.confirm");
      } else if ("value" in knob) {
        // The field holds its default: the caret goes to the end and the old digits are rubbed out first.
        await this.pick(id, true);
        await safeAction(() => input.key(this.client, { keyCode: KEY.END }));
        for (let rub = 0; rub < 3; rub++) await safeAction(() => input.key(this.client, { keyCode: KEY.BACKSPACE }));
        await safeAction(() => input.key(this.client, { text: String(knob.value) }));
      } else {
        await this.pick(id, true);
      }
      await wait(350);
    }
    await wait(500);
    // It is looked at where it would stand first, then the page opened again on it and built.
    await this.point("folkways.draft.preview");
    await clickUnverified(this.client, { id: "folkways.draft.preview" });
    await this.back(pattern);
    await wait(1500);
    await this.click(spot, "right");
    await this.element(id => id === "folkways.draft.confirm");
    await wait(500);
    await this.point("folkways.draft.confirm");
    await clickUnverified(this.client, { id: "folkways.draft.confirm" });
    await this.back(pattern);
    return { offer, picked };
  }

  /** Waits for the view's own screen to come back once the pattern's page has closed; a page left open has refused. */
  private async back(pattern: string) {
    let open = "";
    for (let wait = 0; wait < 60; wait++) {
      await sleepMs(150);
      open = screenName(await safeScreen(this.client));
      if (open.includes("OverheadScreen")) return;
    }
    const all = await softly(() => panelElements(this.client));
    const told = (Array.isArray(all) ? all : []).map((one: any) => one.text).filter(Boolean);
    throw new Error(`the ${pattern} page did not close (${open}): ${JSON.stringify(told).slice(0, 600)}`);
  }

  private async shown(): Promise<any[]> {
    const elements = await softly(() => panelElements(this.client));
    return (Array.isArray(elements) ? elements : [])
      .filter((one: any) => one.id && one.width > 0 && one.height > 0 && one.displayed !== false);
  }

  private async element(match: (id: string) => boolean, timeoutMs = 10_000): Promise<string> {
    const until = Date.now() + timeoutMs;
    let seen: string[] = [];
    while (Date.now() < until) {
      seen = (await this.shown()).map(one => String(one.id));
      const found = seen.find(match);
      if (found) return found;
      await sleepMs(150);
    }
    throw new Error(`no panel element matched; saw ${JSON.stringify(seen)}`);
  }

  private async point(id: string) {
    const element = (await this.shown()).find(one => one.id === id);
    if (element) await this.glide({ x: element.x + element.width / 2, y: element.y + element.height / 2 }, 320);
    await sleepMs(180);
  }

  private async pick(id: string, scrolled = false) {
    if (!scrolled) await this.point(id);
    await clickElement(this.client, { id }, scrolled ? { scrollIntoView: true } : {});
    if (scrolled) {
      const element = (await this.shown()).find(one => one.id === id);
      if (element) this.mouse = { x: element.x + element.width / 2, y: element.y + element.height / 2 };
    }
  }
}

// The recording reads the game's own picture, which has no pointer in it: this draws one where the mouse is, over
// whatever screen is open. It also clears the chat of what the mods said on joining.
export const CURSOR = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-cursor",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "net.neoforged.bus.api.EventPriority",
      "net.neoforged.neoforge.client.event.ScreenEvent",
      "net.neoforged.neoforge.common.NeoForge",
    ],
    body: `
net.minecraft.client.Minecraft.getInstance().gui.getChat().clearMessages(true);
if(System.getProperty("folkways.promo.cursor")!=null)return Map.of("ok",true,"already",true);
System.setProperty("folkways.promo.cursor","1");
NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ScreenEvent.Render.Post.class,event->{
  var graphics=event.getGuiGraphics();int x=event.getMouseX(),y=event.getMouseY();
  graphics.pose().pushPose();graphics.pose().translate(0.0F,0.0F,2000.0F);
  for(int row=0;row<ARROW.length;row++)for(int column=0;column<ARROW[row].length();column++){
    char c=ARROW[row].charAt(column);if(c==' ')continue;
    graphics.fill(x+column,y+row,x+column+1,y+row+1,c=='X'?0xFF101010:0xFFFFFFFF);
  }
  graphics.pose().popPose();
});
return Map.of("ok",true);
`,
    members: `
static final String[] ARROW={
  "X       ",
  "XX      ",
  "X.X     ",
  "X..X    ",
  "X...X   ",
  "X....X  ",
  "X.....X ",
  "X......X",
  "X...XXXX",
  "X.X..X  ",
  "XX X..X ",
  "X   XX  ",
};
`,
  }),
});

// Keys read as held for a while, as the view reads the walking keys: by asking whether they are down, frame by frame.
const HOLD = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-hold",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft"],
    body: `
var minecraft=Minecraft.getInstance();var args=Args.of(ctx);
var keys=new HashSet<Integer>();for(var key:args.list("keys"))keys.add(((Number)key).intValue());
long ms=args.integer("ms");
var claim=Hooks.KEY_DOWN.claim(key->keys.contains(key));
var release=new Thread(()->{
  try{Thread.sleep(ms);}catch(InterruptedException interrupted){}
  minecraft.execute(()->{try{claim.close();}catch(Exception ignored){}});
});
release.setDaemon(true);release.start();
return Map.of("ok",true);
`,
  }),
});

// Where the camera is, and where on the screen (in GUI pixels) it shows each of the given points.
const EYE = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-eye",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.client.Minecraft", "net.minecraft.world.phys.Vec3", "org.joml.Vector3f"],
    body: `
var minecraft=Minecraft.getInstance();var args=Args.of(ctx);
var camera=minecraft.gameRenderer.getMainCamera();var window=minecraft.getWindow();
double half=Math.tan(Math.toRadians(minecraft.options.fov().get())/2.0D);
double wide=half*window.getScreenWidth()/window.getScreenHeight();
var look=new Vec3(camera.getLookVector());var left=new Vec3(camera.getLeftVector());var up=new Vec3(camera.getUpVector());
var at=new ArrayList<Object>();var flat=args.list("points");
for(int i=0;flat!=null&&i+2<flat.size();i+=3){
  var to=new Vec3(((Number)flat.get(i)).doubleValue(),((Number)flat.get(i+1)).doubleValue(),((Number)flat.get(i+2)).doubleValue())
    .subtract(camera.getPosition());
  double depth=to.dot(look);
  double across=depth<=0.01D?-9.0D:-to.dot(left)/depth/wide,rise=depth<=0.01D?-9.0D:to.dot(up)/depth/half;
  at.add(Map.of("x",(across+1.0D)/2.0D*window.getGuiScaledWidth(),"y",(1.0D-rise)/2.0D*window.getGuiScaledHeight()));
}
var where=camera.getPosition();
return Map.of("x",where.x,"y",where.y,"z",where.z,"yaw",(double)camera.getYRot(),"pitch",(double)camera.getXRot(),
  "width",window.getGuiScaledWidth(),"height",window.getGuiScaledHeight(),"at",at);
`,
  }),
});

// The ground at each cell, under any tree; a tree standing on one is felled and the grass and flowers round it
// pulled, for the mouse to reach the ground from a slanted view.
export const TOPS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-tops",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos", "net.minecraft.tags.BlockTags", "net.minecraft.world.level.block.Blocks",
      "net.minecraft.world.level.levelgen.Heightmap"],
    body: `
var level=ctx.server().overworld();var args=Args.of(ctx);
var tops=new ArrayList<Object>();int felled=0;
for(var raw:args.list("cells")){var m=(Map<?,?>)raw;
  int x=((Number)m.get("x")).intValue(),z=((Number)m.get("z")).intValue();
  level.getChunk(x>>4,z>>4);
  for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
    var at=new BlockPos.MutableBlockPos(x+dx,level.getHeight(Heightmap.Types.MOTION_BLOCKING,x+dx,z+dz)-1,z+dz);
    while(at.getY()>level.getMinBuildHeight()){
      var state=level.getBlockState(at);
      if(!state.is(BlockTags.LOGS)&&!state.is(BlockTags.LEAVES))break;
      level.setBlock(at,Blocks.AIR.defaultBlockState(),2);felled++;at.move(0,-1,0);
    }
    for(int up=1;up<=2;up++){
      var plant=at.above(up);var state=level.getBlockState(plant);
      if(!state.isAir()&&state.getFluidState().isEmpty()&&state.getCollisionShape(level,plant).isEmpty())
        level.setBlock(plant,Blocks.AIR.defaultBlockState(),2);
    }
  }
  tops.add(level.getHeight(Heightmap.Types.OCEAN_FLOOR,x,z)-1);}
return Sync.stamp(Map.of("ok",true,"tops",tops,"felled",felled));
`,
  }),
});

// The points of the line being drawn, as the book holds them.
const PATH_POINTS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.overhead-path",
  side: "client",
  timeoutMs: 20_000,
  source: javaTask({
    imports: ["net.minecraft.core.BlockPos"],
    body: `
var field=Class.forName("io.github.izakyl.folkways.front.client.PathSelection").getDeclaredField("POINTS");
field.setAccessible(true);
var points=new ArrayList<Object>();
for(var raw:(List<?>)field.get(null)){var at=(BlockPos)raw;points.add(List.of(at.getX(),at.getY(),at.getZ()));}
return Map.of("ok",true,"points",points);
`,
  }),
});
