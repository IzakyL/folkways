// What a twin spec's aboard test adds to its title. The e2e suite leaves these tests out and the sable suite runs
// them, so the config files match on it; it stays out of stage.ts, which a config should not have to load.
export const ABOARD_TITLE = " · aboard a Sable ship";
export const ABOARD = new RegExp(ABOARD_TITLE.replace(/[.*+?^${}()|[\]\\]/g, "\\$&"));
