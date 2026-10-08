import { test } from "@playwright/test";
import { campaign, launchFuzzServers, verdict } from "./fuzz-kit";

// Construction as it really runs (plugins/build): real residents building made-up blueprints; see LiveBuild.
test("fuzz: build", async ({}, testInfo) => {
  // A blueprint the size of a village quarter takes its residents a good part of a game day (LiveBuild.LONGEST),
  // so the cases still running when the drawing time is up, and the shrinking, take longer than other targets'.
  testInfo.setTimeout(testInfo.timeout + 30 * 60_000);
  const fuzz = await launchFuzzServers("build");
  try {
    const failed = verdict(await campaign(fuzz.servers, "build", testInfo));
    if (failed) throw new Error(failed);
  } finally {
    await fuzz.close();
  }
});
