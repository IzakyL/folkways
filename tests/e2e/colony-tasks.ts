import { defineTask, javaTask, type MinecraftServer, type TaskTemplate } from "@izakyl/blockwright-minecraft";

// The colony's own entry points, run on the server as Blockwright tasks: each is a module-level template
// (`folkways.colony.*`) and `runColonyTask` runs one, timing the round trip and naming the task on failure.

const IMPORTS = [
  "io.github.izakyl.folkways.core.api.colony.Colony",
  "io.github.izakyl.folkways.core.engine.colony.ColonyMembership",
  "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
  "io.github.izakyl.folkways.front.engine.colony.Endorsements",
  "io.github.izakyl.folkways.front.engine.colony.MemberToggle",
  "io.github.izakyl.folkways.front.engine.item.ColonyBookItem",
  "io.github.izakyl.folkways.front.api.notice.Attempt",
  "io.github.izakyl.folkways.front.api.notice.Notice",
  "io.github.izakyl.folkways.plugins.person.PersonBody",
  "io.github.izakyl.folkways.plugins.person.PersonContent",
  "net.minecraft.core.BlockPos",
  "net.minecraft.resources.ResourceLocation",
  "net.minecraft.server.MinecraftServer",
  "net.minecraft.server.level.ServerLevel",
  "net.minecraft.server.level.ServerPlayer",
  "net.minecraft.world.item.ItemStack",
  "net.minecraft.world.level.block.entity.BlockEntity",
];

const LOOKUPS = `
private static ServerPlayer player(MinecraftServer server, String who) {
    ServerPlayer player = server.getPlayerList().getPlayerByName(who);
    if (player == null) {
        throw Fail.notFound("no online player named " + who);
    }
    return player;
}

private static Colony colony(MinecraftServer server, String colonyId) {
    return ColonyGround.of(server, UUID.fromString(colonyId))
        .orElseThrow(() -> Fail.notFound("no colony " + colonyId + " in the registry"));
}

private static String refusalKey(Attempt attempt) {
    Notice why = attempt.refusal().orElse(null);
    return why == null ? null : why.key();
}
`;

/** Binds the player's main-hand colony book to a new colony (or reports the one it already names). */
export const BIND_COLONY = defineTask<{ player: string }, ColonyTaskValue>({
  name: "folkways.colony.bind",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
ServerPlayer player = player(server, args.string("player"));

ItemStack book = player.getMainHandItem();
if (!(book.getItem() instanceof ColonyBookItem)) {
    throw Fail.invalid("main hand holds " + book.getItem() + ", not a folkways:colony_book");
}

Optional<UUID> already = ColonyBookItem.boundColonyId(book);
boolean minted = already.isEmpty();
UUID id = minted
    ? ColonyBookItem.bindNewColony(book, player.serverLevel())
    : already.get();

Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("ok", Boolean.TRUE);
out.put("minted", Boolean.valueOf(minted));
out.put("colony_id", String.valueOf(id));
out.put("bound_on_book", ColonyBookItem.boundColonyId(book)
    .map(bound -> String.valueOf(bound)).orElse(null));
out.put("registry_has_colony", Boolean.valueOf(
    ColonyGround.of(server, id).isPresent()));
return Sync.stamp(out);
`,
    members: LOOKUPS,
  }),
});

/** Opts each cell ({@code cells}: flat x, y, z triples) into the colony through MemberToggle, as the panel does. */
export const MARK_MEMBERS = defineTask<{ player: string; colony_id: string; cells: number[] }, ColonyTaskValue>({
  name: "folkways.colony.mark-members",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
String colonyId = args.string("colony_id");
ServerPlayer player = player(server, args.string("player"));
Colony colony = colony(server, colonyId);
ServerLevel level = player.serverLevel();
List<Object> flat = args.list("cells");
if (flat == null) {
    throw Fail.invalid("args.cells is required (flat x, y, z triples)");
}

List<Object> cells = new ArrayList<Object>();
int members = 0;
for (int i = 0; i + 2 < flat.size(); i += 3) {
    BlockPos at = new BlockPos(
        ((Number) flat.get(i)).intValue(),
        ((Number) flat.get(i + 1)).intValue(),
        ((Number) flat.get(i + 2)).intValue());
    Map<String, Object> one = new LinkedHashMap<String, Object>();
    one.put("cell", flat.get(i) + "," + flat.get(i + 1) + "," + flat.get(i + 2));
    one.put("block", String.valueOf(level.getBlockState(at).getBlock()));

    boolean was = ours(level, at, colonyId);
    one.put("was_member", Boolean.valueOf(was));
    if (was) {
        one.put("action", "already-member");
    } else {
        one.put("action", "toggle");
        one.put("refusal", refusalKey(MemberToggle.toggleAt(level, colony, at, player)));
    }

    boolean now = ours(level, at, colonyId);
    one.put("is_member", Boolean.valueOf(now));
    one.put("colony_holds", Boolean.valueOf(ColonyGround.holds(level, colony, at)));
    if (now) {
        members++;
    }
    cells.add(one);
}

Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("ok", Boolean.TRUE);
out.put("asked", Integer.valueOf(flat.size() / 3));
out.put("members", Integer.valueOf(members));
out.put("cells", cells);
if (members * 3 != flat.size()) {
    throw new TaskException("refused", members + " of " + (flat.size() / 3) + " cells are colony members", out);
}
return Sync.stamp(out);
`,
    members: `
private static boolean ours(ServerLevel level, BlockPos at, String colonyId) {
    BlockEntity blockEntity = level.getBlockEntity(at);
    if (blockEntity == null) {
        return false;
    }
    UUID owner = ColonyMembership.owner(blockEntity).orElse(null);
    return owner != null && colonyId.equals(String.valueOf(owner));
}
` + LOOKUPS,
  }),
});

/** Seeds the colony's settler queue with {@code count} and admits them one call at a time through Endorsements. */
export const ADMIT_SETTLERS = defineTask<{ player: string; colony_id: string; count: number }, ColonyTaskValue>({
  name: "folkways.colony.admit-settlers",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: IMPORTS,
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
String colonyId = args.string("colony_id");
ServerPlayer player = player(server, args.string("player"));
Colony colony = colony(server, colonyId);
ServerLevel level = player.serverLevel();
int want = (int) args.integer("count");

Object presence = colony.service(PersonContent.ID, Object.class).orElse(null);
if (presence == null) {
    throw Fail.unavailable("colony " + colonyId + " has no folkways:person presence settled on it;"
        + " nothing holds a settler queue, so nobody can be admitted");
}

Object queue = open(presence.getClass().getDeclaredField("settlers")).get(presence);
java.lang.reflect.Field waiting = open(queue.getClass().getDeclaredField("waiting"));
int cap = ((Number) open(queue.getClass().getDeclaredMethod("cap")).invoke(queue)).intValue();
int seeded = Math.min(Math.max(0, want), cap);
waiting.setInt(queue, seeded);

ResourceLocation kind = PersonBody.ID;
List<Object> rounds = new ArrayList<Object>();
int admitted = 0;
for (int round = 0; round < seeded; round++) {
    String refusal = refusalKey(Endorsements.called(level, colony, player, kind));
    Map<String, Object> one = new LinkedHashMap<String, Object>();
    one.put("round", Integer.valueOf(round));
    one.put("refusal", refusal);
    one.put("roster", Integer.valueOf(colony.residents().size()));
    rounds.add(one);
    if (refusal != null) {
        break;
    }
    admitted++;
}

Map<String, Object> out = new LinkedHashMap<String, Object>();
out.put("ok", Boolean.TRUE);
out.put("settler_cap", Integer.valueOf(cap));
out.put("seeded", Integer.valueOf(seeded));
out.put("admitted", Integer.valueOf(admitted));
out.put("waiting_left", Integer.valueOf(waiting.getInt(queue)));
out.put("roster", Integer.valueOf(colony.residents().size()));
out.put("rounds", rounds);
if (admitted != seeded) {
    throw new TaskException("refused", "admitted " + admitted + " of " + seeded + " settlers", out);
}
return Sync.stamp(out);
`,
    members: `
private static <T extends java.lang.reflect.AccessibleObject> T open(T member) {
    member.setAccessible(true);
    return member;
}
` + LOOKUPS,
  }),
});

/** What a colony task answered, plus how long the round trip took ({@code $task.runMs}, compile included on a first run). */
export type ColonyTaskValue = Record<string, any>;

/** Runs a server task and answers its (object) value, with `$task: {runMs}`; a failure names the task and says why. */
export async function runColonyTask<A>(
  server: MinecraftServer,
  template: TaskTemplate<A, unknown>,
  args: A,
): Promise<ColonyTaskValue> {
  let value: any;
  const started = performance.now();
  try {
    value = await server.run(template as TaskTemplate<unknown, unknown>, args as unknown, {
      timeoutMs: template.timeoutMs ?? 20_000,
    });
  } catch (error) {
    throw new Error(
      `task ${template.name} failed: ${fullMessage(error)}\n`
        + `-- the task is compiled and run inside the game JVM, so it breaks in one of three ways: it does not compile (javac's output is above; `
        + `tasks reference types directly, so a renamed entry point shows up as a compile error naming the method), `
        + `it threw (the task's own reason and detail are above), `
        + `or a field it looks up by name (the settler queue ADMIT_SETTLERS opens) was renamed.`,
    );
  }
  const runMs = Math.round(performance.now() - started);
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`task ${template.name} did not answer an object: ${JSON.stringify(value)}`);
  }
  return { ...value, $task: { runMs } };
}

function fullMessage(error: unknown): string {
  const detail = (error as any)?.detail;
  const suffix = detail === undefined || detail === null ? "" : ` detail=${JSON.stringify(detail)}`;
  if (error instanceof Error) {
    return error.message + suffix;
  }
  const message = (error as any)?.message ?? (error as any)?.reason;
  return (typeof message === "string" ? message : JSON.stringify(error)) + suffix;
}
