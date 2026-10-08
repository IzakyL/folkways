export type Point = { x: number; y: number; z: number };

export const BODY_HALF_WIDTH = 0.3;

export const OCCLUSION_CLEARANCE_RAD = (6 * Math.PI) / 180;

export const CHEST_HEIGHT = 0.9;

export function occluderIn(
  crowd: Array<{ uuid: string; position: Point }>,
  eye: Point,
  target: Point,
  subjectUuid: string,
): string | null {
  const view = { x: target.x - eye.x, y: target.y - eye.y, z: target.z - eye.z };
  const viewLength = Math.sqrt(view.x * view.x + view.y * view.y + view.z * view.z);
  if (viewLength <= 0) {
    return null;
  }
  const subjectRadius = Math.atan(BODY_HALF_WIDTH / viewLength);
  for (const entity of crowd) {
    if (entity.uuid === subjectUuid) continue;
    const chest = {
      x: entity.position.x - eye.x,
      y: entity.position.y + CHEST_HEIGHT - eye.y,
      z: entity.position.z - eye.z,
    };
    const chestLength = Math.sqrt(chest.x * chest.x + chest.y * chest.y + chest.z * chest.z);
    if (chestLength <= 0 || chestLength >= viewLength) continue;
    const cosine = (chest.x * view.x + chest.y * view.y + chest.z * view.z) / (chestLength * viewLength);
    const offAxis = Math.acos(Math.min(1, Math.max(-1, cosine)));
    const entityRadius = Math.atan(BODY_HALF_WIDTH / chestLength);
    if (offAxis < entityRadius + subjectRadius + OCCLUSION_CLEARANCE_RAD) {
      return entity.uuid.slice(0, 8);
    }
  }
  return null;
}
