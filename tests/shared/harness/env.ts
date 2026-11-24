import { existsSync } from "node:fs";
import { join } from "node:path";
import { REPO_ROOT } from "./out-paths";

// The checkout's git-ignored .env, for secrets such as live provider keys; see .env.example.
// Variables already in the environment are kept.
const file = join(REPO_ROOT, ".env");
if (existsSync(file)) {
  process.loadEnvFile(file);
}
