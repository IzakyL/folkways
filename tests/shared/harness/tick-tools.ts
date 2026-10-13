import { tick, type MinecraftServer } from "@izakyl/blockwright-minecraft";

export type SprintWaitOutcome = {
  matched: boolean;
  ticks: number;
  reason: string;
};

export async function sampleWhileSprinting<T>(
  server: MinecraftServer,
  options: {
    maxTicks: number;
    stepTicks: number;
    read: (ticks: number) => Promise<T>;
    done: (sample: T) => boolean;
    sampleAtZero?: boolean;
  },
): Promise<SprintWaitOutcome & { sample: T; samples: T[] }> {
  const run = await tick.sample(server, options);
  return { ...run, sample: run.sample as T };
}

export async function settledAll<T>(
  tasks: Array<() => Promise<T>>,
): Promise<Array<{ value: T } | { error: unknown }>> {
  return Promise.all(
    tasks.map((task) =>
      task().then((value) => ({ value }), (error: unknown) => ({ error })),
    ),
  );
}

export function unwrapSettled<T>(entry: { value: T } | { error: unknown }): T {
  if ("error" in entry) throw entry.error;
  return entry.value;
}
