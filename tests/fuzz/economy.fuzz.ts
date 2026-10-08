import { test } from "@playwright/test";
import { campaign, launchFuzzServers, verdict } from "./fuzz-kit";

// A colony's economy as it really runs (core/engine/plan, plugins/wares): real residents fetching, carrying,
// crafting, cooking and cutting goods for submitted goals; see LiveEconomy.
test("fuzz: economy", async ({}, testInfo) => {
  const fuzz = await launchFuzzServers("economy");
  try {
    const failed = verdict(await campaign(fuzz.servers, "economy", testInfo));
    if (failed) throw new Error(failed);
  } finally {
    await fuzz.close();
  }
});
