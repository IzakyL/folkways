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
  const samples: T[] = [];
  let ticks = 0;
  let sample: T;

  if (options.sampleAtZero) {
    sample = await options.read(0);
    samples.push(sample);
    if (options.done(sample)) {
      return { matched: true, ticks: 0, reason: "matched", sample, samples };
    }
  }

  for (;;) {
    const step = Math.min(options.stepTicks, options.maxTicks - ticks);
    if (step <= 0) break;
    await tick.sprint(server, step);
    ticks += step;
    sample = await options.read(ticks);
    samples.push(sample);
    if (options.done(sample)) {
      return { matched: true, ticks, reason: "matched", sample, samples };
    }
  }

  return { matched: false, ticks, reason: "maxTicks", sample: samples[samples.length - 1]!, samples };
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
