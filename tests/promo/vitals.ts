import { defineTask, javaTask, type MinecraftServer } from "@izakyl/blockwright-minecraft";
import { runColonyTask } from "../e2e/colony-tasks";

// A fresh colony starts everyone fed, rested and whole, so every card reads the same three full rows.
// These spread the vitals out, but keep each resident above the marks where they would drop work to eat
// or sleep (food 14, alertness 400), and below food 18 whenever they are hurt, so nothing heals it back.
const HEALTH = [1.0, 0.7, 0.85, 0.55, 1.0, 0.8, 0.95];
const FOOD = [19, 16, 15, 17, 18, 15, 17];
const TIRED = [150, 500, 300, 700, 600, 250, 450];

export async function varyVitals(server: MinecraftServer, colony_id: string) {
  return runColonyTask(server, VARY_VITALS, { colony_id, health: HEALTH, food: FOOD, tired: TIRED });
}

const VARY_VITALS = defineTask<Record<string, any>, Record<string, any>>({
  name: "folkways.promo.vary-vitals",
  side: "server",
  timeoutMs: 20_000,
  source: javaTask({
    imports: [
      "io.github.izakyl.folkways.core.api.colony.Colony",
      "io.github.izakyl.folkways.core.api.resident.Resident",
      "io.github.izakyl.folkways.front.engine.colony.ColonyGround",
      "io.github.izakyl.folkways.plugins.person.living.LivingContent",
      "java.lang.reflect.AccessibleObject",
      "java.lang.reflect.Constructor",
      "java.util.ArrayList",
      "java.util.LinkedHashMap",
      "java.util.List",
      "java.util.Map",
      "java.util.UUID",
      "net.minecraft.server.MinecraftServer",
      "net.minecraft.server.level.ServerLevel",
      "net.minecraft.world.entity.Entity",
      "net.minecraft.world.entity.LivingEntity",
    ],
    body: `
MinecraftServer server = ctx.server();
Args args = Args.of(ctx);
String colonyId = args.string("colony_id");
Colony colony = ColonyGround.of(server, UUID.fromString(colonyId))
    .orElseThrow(() -> Fail.notFound("no colony " + colonyId));
Object presence = colony.service(LivingContent.ID, Object.class).orElse(null);
if (presence == null) {
    throw Fail.unavailable("the colony has no folkways:living presence");
}
List<?> health = args.list("health");
List<?> food = args.list("food");
List<?> tired = args.list("tired");

@SuppressWarnings("unchecked")
Map<UUID, Object> bellies = (Map<UUID, Object>) open(presence.getClass().getDeclaredField("bellies")).get(presence);
@SuppressWarnings("unchecked")
Map<UUID, Object> wear = (Map<UUID, Object>) open(presence.getClass().getDeclaredField("wear")).get(presence);
Class<?> hungerType = Class.forName("io.github.izakyl.folkways.plugins.person.living.Hunger");
Class<?> restType = Class.forName("io.github.izakyl.folkways.plugins.person.living.Rest");
Constructor<?> newHunger = open(hungerType.getDeclaredConstructor());
Constructor<?> newRest = open(restType.getDeclaredConstructor());

List<Object> set = new ArrayList<Object>();
int i = 0;
for (Resident resident : colony.residents()) {
    UUID id = resident.id();
    int slot = i++;
    double wholeness = ((Number) health.get(slot % health.size())).doubleValue();
    int fed = ((Number) food.get(slot % food.size())).intValue();
    double spent = ((Number) tired.get(slot % tired.size())).doubleValue();

    Object belly = bellies.computeIfAbsent(id, key -> make(newHunger));
    open(hungerType.getDeclaredField("foodLevel")).setInt(belly, fed);
    open(hungerType.getDeclaredField("saturationLevel")).setFloat(belly, 0.0F);
    Object rest = wear.computeIfAbsent(id, key -> make(newRest));
    open(restType.getDeclaredField("tired")).setDouble(rest, spent);

    Float hp = null;
    for (ServerLevel level : server.getAllLevels()) {
        Entity body = level.getEntity(id);
        if (body instanceof LivingEntity living) {
            living.setHealth((float) Math.max(1.0D, Math.round(living.getMaxHealth() * wholeness)));
            hp = Float.valueOf(living.getHealth());
            break;
        }
    }
    Map<String, Object> one = new LinkedHashMap<String, Object>();
    one.put("uuid", String.valueOf(id));
    one.put("health", hp);
    one.put("food", Integer.valueOf(fed));
    one.put("tired", Double.valueOf(spent));
    set.add(one);
}
return Sync.stamp(Map.of("ok", Boolean.TRUE, "set", set));
`,
    members: `
private static Object make(Constructor<?> constructor) {
    try {
        return constructor.newInstance();
    } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
    }
}

private static <T extends AccessibleObject> T open(T member) {
    member.setAccessible(true);
    return member;
}
`,
  }),
});
