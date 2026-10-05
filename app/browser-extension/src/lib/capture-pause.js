import { ext } from './ext.js';

/** Separate from preferences so saving settings cannot overwrite a temporary pause. */
export const CAPTURE_PAUSED_KEY = 'capturePaused';

export async function loadCapturePause() {
  const stored = await ext.storage.local.get(CAPTURE_PAUSED_KEY);
  return stored[CAPTURE_PAUSED_KEY] === true;
}

/** Persists until the user resumes, including across browser restarts. */
export async function setCapturePaused(paused) {
  await ext.storage.local.set({ [CAPTURE_PAUSED_KEY]: paused });
  return paused;
}
