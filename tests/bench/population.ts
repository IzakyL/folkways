export function distributePopulation(total: number) {
  if (!Number.isSafeInteger(total) || total < 1) throw new Error("Population must be a positive integer");
  const golems = Math.round(total * 0.2);
  const metal = Math.floor(golems * 0.4), humanoid = Math.floor(golems * 0.4);
  return { total, humans: total - golems, metal, humanoid, dog: golems - metal - humanoid };
}

export function populationSlots(total: number) {
  const population = distributePopulation(total);
  const names = [...Array(population.metal).fill("metal"), ...Array(population.humanoid).fill("humanoid"), ...Array(population.dog).fill("dog")];
  const golems = names.map((kind, i) => ({ kind, index: Math.floor((i + 0.5) * total / names.length) }));
  const occupied = new Set(golems.map(g => g.index));
  return { ...population, golems, humanIndices: Array.from({ length: total }, (_, i) => i).filter(i => !occupied.has(i)) };
}
