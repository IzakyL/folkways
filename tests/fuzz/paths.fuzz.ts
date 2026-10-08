import { test } from "@playwright/test";
import { campaign, launchFuzzServers, verdict } from "./fuzz-kit";

// Getting about as it really runs (core/engine/travel): real residents walking made-up ground that changes; see LivePaths.
test("fuzz: paths", async ({}, testInfo) => {
  const fuzz = await launchFuzzServers("paths");
  try {
    const failed = verdict(await campaign(fuzz.servers, "paths", testInfo));
    if (failed) throw new Error(failed);
  } finally {
    await fuzz.close();
  }
});
