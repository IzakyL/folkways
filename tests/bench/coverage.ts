import { DEMANDS } from "./fixture.ts";

export const PLUGIN_SCOPES = ["person", "farming", "pasture", "fishing", "wares", "orders", "build", "rail", "dispatch", "golem", "patrol", "sable"];

// Hauling is the core's own work, reported beside the plugins because every plugin leans on it.
export const CORE_SCOPES = ["haul"];

export function coverageOf(report: any) {
  const completed: Record<string, number> = {};
  for (const beat of report.analysis?.claimed_kinds?.beats ?? [])
    for (const [key, n] of Object.entries(beat.completed ?? {})) completed[key] = (completed[key] ?? 0) + Number(n);
  const samples = report.samples ?? [];
  const done = (key: string) => (completed[key] ?? 0) > 0;
  const any = (read: (sample: any) => boolean) => samples.some(read);
  const features = {
    person: { admitted: (report.setup.human_population ?? report.setup.population) >= (report.scale.humans ?? report.scale.residents), eating: done("folkways:living/EatAction"),
      sleeping: done("folkways:living/SleepAction") },
    haul: { transfer: done("folkways:haul/TransferNode") },
    farming: { tilling: done("folkways:farming/TillNode"), planting: done("folkways:farming/PlantNode"), harvest: done("folkways:farming/HarvestNode"),
      felling: done("folkways:farming/FellNode") },
    pasture: { feeding: done("folkways:pasture/FeedNode"), culling: done("folkways:pasture/CullNode"), shearing: done("folkways:pasture/ShearNode"), milk: done("folkways:pasture/MilkNode"),
      eggs: done("folkways:pasture/GleanNode") },
    fishing: { catch: done("folkways:fishing/CastNode") },
    wares: { crafting: done("folkways:wares/PackCraftNode") || done("folkways:wares/CraftNode"),
      machine_loading: done("folkways:wares/LoadNode"), fuelling: done("folkways:wares/FuelNode"), cooked_output: done("folkways:wares/TakeCookedNode"),
      blast_furnace: any(s => s.busy_cookers?.blast_furnace > 0), smoker: any(s => s.busy_cookers?.smoker > 0),
      stores: done("folkways:haul/TransferNode") },
    orders: { requested_stock_arrived: any(s => DEMANDS.some((d, i) => (s.order_outputs?.[i]?.[d.item] ?? 0) > 0)) },
    build: { placement: (report.final_construction?.built ?? 0) > 0, demolition: any(s => (s.demolition?.cleared ?? 0) > 0),
      laying: done("folkways:build/LayNode"), stripping: done("folkways:build/StripNode"),
      town_wall: (report.final_construction?.wall?.built ?? 0) > 0 },
    rail: { conductor: any(s => s.rail?.conductor), moving: any(s => Math.abs(s.rail?.train?.speed ?? 0) > 0.001),
      complete_loop: (report.analysis?.engine?.rail_complete_laps ?? 0) > 0,
      passenger: any(s => s.rail?.seated_residents > 1), remote_construction: any(s => s.rail?.far_built > 0) },
    dispatch: { staffing: any(s => s.integrations?.dispatch?.seated > 0),
      supplier_used: any(s => s.integrations?.dispatch?.stock < report.setup.integrations?.before?.stock),
      finished_goods: any(s => s.integrations?.dispatch?.delivered > 0) },
    patrol: { watching: done("folkways:patrol/WatchNode"), striking: done("folkways:patrol/StrikeNode") },
    sable: {},
    golem: { admitted: (report.setup.integrations?.golems?.uuids?.length ?? 0) > 0, repair: done("folkways:golem/MendAction") },
  };
  return { completed_nodes: completed, plugins: [...CORE_SCOPES, ...PLUGIN_SCOPES].map(plugin => {
    const actions = Object.fromEntries(Object.entries(completed).filter(([key]) => key.startsWith(`folkways:${plugin}/`) || plugin === "person" && key.startsWith("folkways:living/")));
    let observed = Object.values(actions).some(n => n > 0);
    if (plugin === "person") observed = (report.setup.human_population ?? report.setup.population) >= (report.scale.humans ?? report.scale.residents);
    if (plugin === "golem") observed ||= samples.some((s: any) => s.integrations?.golem?.workers?.some((w: any) => w.doing !== "idle"));
    if (plugin === "orders") observed ||= samples.some((s: any) => DEMANDS.some((d, i) => (s.order_outputs?.[i]?.[d.item] ?? 0) > 0));
    if (plugin === "dispatch") observed ||= samples.some((s: any) => s.integrations?.dispatch?.delivered > 0);
    if (plugin === "rail") observed ||= samples.some((s: any) => s.rail?.conductor);
    return { plugin, status: observed ? "observed" : "not_observed", completed: actions, feature_checks: features[plugin as keyof typeof features] };
  }), limitations: ["Presence of workers proves person admission, not all person features.",
    "Fixed daylight leaves sleep to tiredness alone and nothing here frightens residents; appearance/UI/restart and moving-airship compatibility remain in separate suites.",
    "Node completion is stronger than a placed fixture, but does not imply every feature of its plugin passed."] };
}
