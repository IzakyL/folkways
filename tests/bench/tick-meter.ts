import { tick, type MinecraftServer } from "@izakyl/blockwright-minecraft";

// Blockwright's tick meter keeps every normally run server tick, from the moment it is started until the game
// stops: the game time it ran, the wall clock it began at, and how long it took. Nothing is summed or dropped.

export async function installTickMeter(server: MinecraftServer): Promise<number> {
  return (await tick.meter.start(server)).ticks;
}

// Writes every tick kept so far to a CSV file, as it was kept.
export async function dumpTicks(server: MinecraftServer, path: string) {
  return tick.meter.dump(server, path);
}

export const TICK_METER_NOTE = "One row per normally run server tick from launch to the end of the run, nothing summed or left out. "
  + "game_time = overworld game time; wall_ns = System.nanoTime() at ServerTickEvent.Pre (HIGHEST); "
  + "full_ns = Pre (HIGHEST) to the end of Post (LOWEST), the whole tick including the NeoForge Post phase where Folkways labor runs; "
  + "post_ns = the Post phase alone; normal_ns = the NORMAL-priority band of Post (between the meter's HIGH and LOW listeners), "
  + "where Folkways labor runs beside any other mod listening at NORMAL; epoch_ms = System.currentTimeMillis() at Pre, the same clock the film's wall_start_ms is read on. Frozen ticks are not kept.";
