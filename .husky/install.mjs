import { existsSync } from "node:fs";

if (process.env.CI || process.env.NODE_ENV === "production" || !existsSync(".git")) {
  process.exit(0);
}

const { default: husky } = await import("husky");
const error = husky();
if (error) {
  console.error(error);
  process.exit(1);
}
