import { defineConfig } from "@izakyl/blockwright-test";
import { DEFAULT_BUDGET } from "../shared/harness/budgets";
import { ABOARD } from "../shared/harness/aboard";

// Everything that needs Sable: the specs here, about what only a ship has, and the aboard test of every e2e twin spec.
export default defineConfig({
  globalSetup: "./global-setup.ts",
  outputDir: "../out/sable/playwright",
  timeout: DEFAULT_BUDGET,
  workers: 4,
  fullyParallel: true,
  retries: 0,
  projects: [
    { name: "sable", testDir: ".", testMatch: "**/*.spec.ts" },
    { name: "aboard", testDir: "../e2e", testMatch: "**/*.spec.ts", grep: ABOARD },
  ],
});
