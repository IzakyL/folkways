import type { Vec } from "../shared/colony/colony-founding";

export const WAVE_SIZE = 8;
export const WAVE_SECONDS = 12;
export const BALCONY_CAPACITY = 2;
export const COURTYARD_CAPACITY = 18;

export function cookeryVisitorPlans(origin: Vec, count: number) {
  const at = (x: number, z: number, y = 0): Vec => ({ x: origin.x + x, y: origin.y + y, z: origin.z + z });
  return Array.from({ length: count }, (_, i) => {
    const balcony = i % WAVE_SIZE === WAVE_SIZE - 1;
    const group = Math.floor(i / WAVE_SIZE) % 3;
    const left = [-15, -11, -7][group];
    return {
      // Most patrons use the street terrace; two upstairs visitors at a time
      // preserve lift journeys without putting the dining stream in its queue.
      start: at(i % 2 === 0 ? -35 - (Math.floor(i / 2) % 10) * 2 : 25 + (Math.floor(i / 2) % 10) * 2,
        [-16, -17, -15][Math.floor(i / 20) % 3]),
      // Street diners first choose a real ground-floor meal outside the radius
      // of every upstairs table. Spread each wave between the three table groups.
      entrance: balcony ? at(-5, -5, 6) : at(left + (i % 2), -20),
      area: balcony ? "balcony" : "courtyard",
      tables: balcony
        ? [-16, -12, -8].flatMap(x => [0, 1].flatMap(dx => [-3, -2].map(z => at(x + dx, z, 6))))
        : [-15, -11, -7].flatMap(x => [0, 1].map(dx => at(x + dx, -12))),
      wave: Math.floor(i / WAVE_SIZE),
      release: Math.floor(i / WAVE_SIZE) * WAVE_SECONDS,
    };
  });
}

export function summarizeCookeryVisitors(samples: Array<{ seconds: number; [key: string]: any }>, workersUUIDs: string[]) {
  return workersUUIDs.map(uuid => {
    let outside: number | undefined, entered: number | undefined, ate: number | undefined, departed: number | undefined;
    let baseline = -24000, visits = 0;
    let last: any;
    let diningSamples = 0, mealActivitySamples = 0, upperFloorSamples = 0;
    const phases = new Set<string>();
    for (const sample of samples) {
      const person = sample.people?.find((one: any) => one.uuid === uuid);
      if (!person) continue;
      last = person;
      phases.add(person.phase);
      if (person.doing === "dining_eating") mealActivitySamples++;
      if (person.dining && person.seatedAtTable) diningSamples++;
      if (person.local?.[1] >= 5) upperFloorSamples++;
      if (outside === undefined && person.offscreen) { outside = sample.seconds; baseline = person.dined; }
      if (outside !== undefined && entered === undefined && sample.seconds > outside && person.insideInn) entered = sample.seconds;
      if (entered !== undefined && ate === undefined && person.dined > baseline && person.dined >= sample.started) ate = sample.seconds;
      if (ate !== undefined && sample.seconds > ate && person.offscreen && person.departures > 0) departed ??= sample.seconds;
      visits = Math.max(visits, person.departures ?? 0);
    }
    const incompleteReason = outside === undefined ? "never observed off camera"
      : entered === undefined ? "entrance walk not observed"
      : ate === undefined ? "no completed table meal observed"
      : departed === undefined ? "exit walk not completed off camera" : undefined;
    return { uuid, wave: last?.wave, area: last?.area, outside, entered, ate, departed, visits, diningSamples, mealActivitySamples, upperFloorSamples,
      phases: [...phases], lastPhase: last?.phase, lastDoing: last?.doing, lastLocal: last?.local,
      lastFood: last?.food, lastDined: last?.dined, walkEnding: last?.walkEnding,
      failure: last?.failure || undefined, lastFailure: last?.lastFailure || undefined,
      walkAttempts: last?.walkAttempts ?? 0, walkFailures: last?.walkFailures ?? 0,
      walkRetries: last?.walkRetries ?? 0, retryAt: last?.retryAt, incompleteReason,
      complete: outside !== undefined && entered !== undefined && ate !== undefined && departed !== undefined };
  });
}


/** Keep pre-roll arrivals as evidence, but count only meals and exits completed in the recorded shot. */
export function summarizeCookeryTakeVisits(initial: any, preRoll: Array<{ seconds: number; visitors: any }>,
  recording: Array<{ seconds: number; visitors: any }>, workersUUIDs: string[]) {
  const offset = preRoll.at(-1)?.seconds ?? 0;
  const samples = [{ ...initial, seconds: -offset },
    ...preRoll.map(state => ({ ...state.visitors, seconds: state.seconds - offset })),
    ...recording.map(state => ({ ...state.visitors, seconds: state.seconds }))];
  return summarizeCookeryVisitors(samples, workersUUIDs).map(visit => {
    const recorded = visit.ate !== undefined && visit.ate >= 0 && visit.departed !== undefined && visit.departed >= 0;
    return { ...visit, complete: visit.complete && recorded,
      incompleteReason: visit.complete && !recorded ? "meal or exit completed before recording" : visit.incompleteReason };
  });
}
