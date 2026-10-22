import { expect } from "@playwright/test";
import { summarizeCookeryTakeVisits, summarizeCookeryUpstairs } from "./cookery-visitor-flow.ts";

export const KITCHEN_WORK = new Set(["preparing_food", "chopping_food", "cooking_food", "serving_food", "crafting"]);

type CookeryEvidence = {
  evidence: any[]; preRoll: any[]; visitors: any; visitorCrew: string[];
  cooks: string[]; marketCrew: string[]; diningAreas: Array<{ id: string }>;
  TABLE_MENU: string[]; SECONDS: number;
};

/** Validate the same saved native samples after capture or during offline evidence review. */
export function validateCookeryTake(input: CookeryEvidence, note: (key: string, value: any) => void) {
  const { evidence, preRoll, visitors, visitorCrew, cooks, marketCrew, diningAreas, TABLE_MENU, SECONDS } = input;
  const run = { note };
  expect(evidence.at(-1)?.seconds, "evidence must cover the complete take").toBeGreaterThanOrEqual(SECONDS - 3);
    const visits = summarizeCookeryTakeVisits(visitors.initial, preRoll, evidence, visitorCrew);
    run.note("visits", visits);
    expect(visits.filter(visit => visit.complete).length, "multiple visitors must finish a real table meal and walk back off camera during the shot").toBeGreaterThanOrEqual(12);
    for (const area of ["balcony", "courtyard"]) {
      expect(visits.filter(visit => visit.complete && visit.area === area).length,
        `${area} must serve multiple arriving and departing guests`).toBeGreaterThanOrEqual(area === "balcony" ? 2 : 10);
    }
    const service = diningAreas.map(area => {
      const samples = evidence.map(state => state.diningAreas.find((one: any) => one.id === area.id));
      let replenished = 0;
      for (let i = 1; i < samples.length; i++) for (let t = 0; t < samples[i].tables.length; t++) {
        const before = samples[i - 1].tables[t].slots;
        replenished += samples[i].tables[t].slots.filter((filled: boolean, slot: number) => filled && !before[slot]).length;
      }
      return { id: area.id, replenished, minimumPortions: Math.min(...samples.map(one => one.portions)) };
    });
    run.note("service", service);
    const servedFoods = new Set(evidence.flatMap(state => state.diningAreas.flatMap((area: any) =>
      area.tables.flatMap((table: any) => table.foods))) as string[]);
    run.note("servedFoods", [...servedFoods]);
    expect(servedFoods.size, "the random menu must put multiple different real dishes on tables").toBeGreaterThanOrEqual(Math.min(4, TABLE_MENU.length));
    for (const dish of servedFoods) expect(TABLE_MENU, `${dish} must belong to the configured table menu`).toContain(dish);
    for (const area of service) expect(area.replenished, `${area.id} must receive fresh servings during the recording`).toBeGreaterThan(0);
    const wineService = diningAreas.map(area => ({ id: area.id,
      served: evidence.some(state => state.diningAreas.find((one: any) => one.id === area.id)
        .tables.some((table: any) => table.drinks?.includes("kaleidoscope_tavern:wine"))),
      drinking: evidence.some(state => state.people.some((person: any) => person.drinking && person.seatedAtTable
        && Math.abs(person.position[1] - state.diningAreas.find((one: any) => one.id === area.id).tables[0].position[1]) < 2)),
    }));
    // Consumption is measured from native meal commits, excluding pre-roll meals and held-bottle gestures.
    const openingDrinks = new Map(evidence[0].people.map((person: any) => [person.uuid, person.tableDrinks ?? 0]));
    const drunk = evidence.at(-1).people.reduce((count: number, person: any) =>
      count + Math.max(0, (person.tableDrinks ?? 0) - Number(openingDrinks.get(person.uuid) ?? 0)), 0);
    run.note("wineService", { areas: wineService, consumedDuringTake: drunk });
    for (const area of wineService) expect(area.served, `${area.id} must receive real wine from the native tavern chain`).toBe(true);
    for (const area of wineService) expect(area.drinking, `${area.id} must show an actual seated diner holding wine`).toBe(true);
    const levels = evidence.flatMap(state => state.diningAreas.flatMap((area: any) =>
      area.tables.flatMap((table: any) => table.drinkLevels ?? []))) as number[];
    expect(levels.length, "native wine quality components must reach dining tables").toBeGreaterThan(0);
    expect(Math.min(...levels), "wine must finish the native wait for brewing level two").toBeGreaterThanOrEqual(2);
    expect(drunk, "several real table meals must consume wine during the take").toBeGreaterThanOrEqual(4);
    const density = evidence.map(state => ({ seconds: state.seconds,
      cooking: state.people.filter((one: any) => [...cooks, ...marketCrew].includes(one.uuid) && KITCHEN_WORK.has(one.action)).length,
      eating: state.people.filter((one: any) => one.seatedAtTable).length }));
    run.note("density", { samples: density, meanCooking: density.reduce((n, one) => n + one.cooking, 0) / density.length,
      meanEating: density.reduce((n, one) => n + one.eating, 0) / density.length });
    expect(density.filter(one => one.cooking >= 2).length / density.length,
      "multiple residents must actually cook regularly, excluding travel toward cooking").toBeGreaterThanOrEqual(0.25);
    expect(density.filter(one => one.cooking >= 1).length / density.length,
      "a real kitchen operation must be visible through most of the shot").toBeGreaterThanOrEqual(0.6);
    expect(density.filter(one => one.eating >= 6).length / density.length,
      "at least six residents must really sit at tables eating in most samples").toBeGreaterThanOrEqual(0.6);
    const upstairs = summarizeCookeryUpstairs(evidence);
    run.note("upstairsDensity", upstairs);
    expect(upstairs.meanEating, "the upstairs restaurant must visibly serve several seated diners throughout the shot").toBeGreaterThanOrEqual(2.3);
    expect(upstairs.samples.filter(one => one.eating >= 2).length / upstairs.samples.length,
      "at least two guests must really eat upstairs in most samples").toBeGreaterThanOrEqual(0.65);
    for (let start = 0; start < SECONDS; start += 60) {
      const minute = evidence.filter(state => state.seconds >= start && state.seconds < start + 60);
      expect(minute.some(state => state.people.some((one: any) =>
        [...cooks, ...marketCrew].includes(one.uuid) && KITCHEN_WORK.has(one.action))),
        `minute ${start / 60 + 1} must contain real cooking`).toBe(true);
      expect(minute.some(state => state.people.some((one: any) => one.doing === "setting_table")),
        `minute ${start / 60 + 1} must contain real table replenishment`).toBe(true);
      expect(minute.some(state => state.people.some((one: any) => one.seatedAtTable)),
        `minute ${start / 60 + 1} must contain a seated diner`).toBe(true);
      expect(upstairs.samples.some(sample => sample.seconds >= start && sample.seconds < start + 60 && sample.eating >= 2),
        `minute ${start / 60 + 1} must show multiple actual upstairs diners`).toBe(true);
    }
    run.note("farmProgress", evidence.at(-1)?.sideWork);
    expect(evidence.at(-1)?.sideWork.harvested, "the filmed farmer actually picks or harvests initially mature crops").toBeGreaterThan(0);
    run.note("yardProgress", evidence.at(-1)?.extraYard);
    expect(Math.max(...evidence.map(state => state.extraYard.caught)), "the fisher must land a real catch, independently of any transported raw ingredients").toBeGreaterThan(0);
    expect(Math.max(...evidence.map(state => state.extraYard.sheared)), "the herder must shear a real sheep during the take").toBeGreaterThan(0);
    expect(Math.max(...evidence.map(state => state.extraYard.milked)), "the milk delivery must come from actually milking the staged cow").toBeGreaterThan(0);
    run.note("marketProgress", evidence.at(-1)?.extraKitchen);
    expect(evidence.some(state => state.extraKitchen.spit.some((half: any) => half.powered && half.raw.count > 0 && half.ticks > 0)),
      "the rotating spit must really cook its meat").toBe(true);
    const simultaneous = evidence.filter(state => state.people.some((one: any) => visitorCrew.includes(one.uuid) && one.dining && one.seatedAtTable)
      && state.people.some((one: any) => cooks.includes(one.uuid) && KITCHEN_WORK.has(one.action)));
    run.note("simultaneous", { samples: simultaneous.length, total: evidence.length });
    const occupied = evidence.filter(state => state.lift.passengers.length > 0);
    run.note("standingCapacity", evidence.map(state => ({ seconds: state.seconds,
      capacity: state.lift.capacity, free: state.lift.free })));
    run.note("journeys", occupied.map(state => ({ seconds: state.seconds, contactY: state.lift.contactY, passengers: state.lift.passengers })));
    expect(occupied.length, "the same shot must contain verified standing cabin passengers, excluding nearby residents and diners on chairs").toBeGreaterThanOrEqual(3);
    const heights = occupied.map(state => Number(state.lift.contactY));
    expect(Math.max(...heights) - Math.min(...heights), "occupied elevator must actually travel between floors on camera").toBeGreaterThan(3);
    expect(simultaneous.length, "the shot must actually show seated diners alongside kitchen work").toBeGreaterThanOrEqual(5);
    const after = evidence.at(-1);
    expect(after.people.filter((one: any) => visitorCrew.includes(one.uuid) && one.dined >= 0).length,
      "foreground residents really finish table meals and receive the dining experience").toBeGreaterThanOrEqual(2);
}
