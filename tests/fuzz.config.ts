import { defineConfig } from "@izakyl/blockwright-test";
import { BUDGET } from "./shared/budgets";
import { campaignMs } from "./fuzz/campaign";

// Each target launches its own server and runs its cases side by side on it.
export default defineConfig({
  globalSetup: "./fuzz/global-setup.ts",
  testDir: "./fuzz",
  testMatch: "**/*.fuzz.ts",
  // On top of the drawing time: the cases still running when it is up, and shrinking what broke.
  timeout: BUDGET.fuzz + campaignMs() + 10 * 60_000,
  outputDir: "./out/fuzz/playwright",
  workers: 3,
  retries: 0,
});
