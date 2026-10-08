import { defineConfig } from "@izakyl/blockwright-test";
import { BUDGET } from "./shared/budgets";

// Takes are timed shots: coverage's probes slow the mod's wall-clock budgets and starve the colony
// on camera, so the driver this run spawns never measures, whatever BLOCKWRIGHT_COVERAGE says.
process.env.BLOCKWRIGHT_COVERAGE = "0";

export default defineConfig({
  globalSetup: "./promo/global-setup.ts",
  testDir: "./promo",
  testMatch: /.*\.take\.ts$/,
  outputDir: "./out/promo/playwright",
  timeout: BUDGET.promo,
  workers: 1,
  retries: 0,
});
