// Film timing. The numbers live in timing.json (shared with the music generator, which builds
// its cue sheet from the same file); this module types them, checks that they add up, and adds
// the helpers scenes use. Frames are global unless a name says local.
//
// To change a scene's length, edit its `len` in timing.json and shift every later `start`, the
// `total` and the `wipes` that sit on a moved cut (`node scripts/retime.mjs S05=285` does all of
// that), then regenerate the music (`npm run music`) and check the cuts. See README.md.
import timingJson from './timing.json';

export type SceneId =
  'S01' | 'S02' | 'S03' | 'S04' | 'S05' | 'S06' | 'S07' | 'S08' | 'S09' | 'S10' | 'S11' | 'S12';

/** Every scene id, in film order. */
export const SCENE_IDS: readonly SceneId[] = [
  'S01',
  'S02',
  'S03',
  'S04',
  'S05',
  'S06',
  'S07',
  'S08',
  'S09',
  'S10',
  'S11',
  'S12',
];

export interface SceneTiming {
  id: SceneId;
  /** Global start frame. */
  start: number;
  /** Length in frames. */
  len: number;
}

/** Shape of timing.json. */
export interface TimingFile {
  fps: number;
  width: number;
  height: number;
  /** Frames per beat of the music (120 BPM at 30 fps = 15). */
  beat: number;
  /** Length of the film in frames. */
  total: number;
  scenes: SceneTiming[];
  /** Cuts (global frames) that get the lane wipe. */
  wipes: number[];
}

/**
 * Throws with a readable message when timing.json does not add up: scenes must be the twelve
 * ids in order, back to back from frame 0, each a whole number of beats long, ending on `total`,
 * and every wipe must sit on a cut between two scenes.
 */
const checkTiming = (t: TimingFile): TimingFile => {
  const problems: string[] = [];
  const ids = t.scenes.map((s) => s.id).join(',');
  if (ids !== SCENE_IDS.join(','))
    problems.push(`scenes must be ${SCENE_IDS.join(',')} in order, got ${ids}`);
  let next = 0;
  for (const s of t.scenes) {
    if (s.start !== next)
      problems.push(`${s.id} starts at ${s.start}, expected ${next} (the previous scene's end)`);
    if (s.len <= 0 || s.len % t.beat !== 0)
      problems.push(`${s.id} is ${s.len} frames long, not a whole number of beats (${t.beat})`);
    next = s.start + s.len;
  }
  if (next !== t.total) problems.push(`scenes end at ${next} but total is ${t.total}`);
  const cuts = new Set(t.scenes.slice(1).map((s) => s.start));
  for (const w of t.wipes)
    if (!cuts.has(w)) problems.push(`wipe at ${w} is not on a cut between two scenes`);
  if (problems.length > 0)
    throw new Error(`src/timing.json does not add up:\n- ${problems.join('\n- ')}`);
  return t;
};

/** timing.json, checked. */
export const TIMING: TimingFile = checkTiming(timingJson as TimingFile);

export const FPS = TIMING.fps;
export const WIDTH = TIMING.width;
export const HEIGHT = TIMING.height;
/** Whole film (2310 frames = 77 s at the time of writing). */
export const TOTAL = TIMING.total;
/** One beat at 120 BPM. Every scene length is a multiple of it. */
export const BEAT = TIMING.beat;
/** One bar of four beats. */
export const BAR = 4 * BEAT;

export const SCENES: readonly SceneTiming[] = TIMING.scenes;

/** Cuts that get the lane wipe (placed by `Promo`, from cut - 9 to cut + 9). */
export const WIPES: readonly number[] = TIMING.wipes;
/** Lane wipe length; it is centred on the cut. A property of the wipe's design, not of the edit. */
export const WIPE_LEN = 18;

/** Timing of one scene by id. */
export const scene = (id: SceneId): SceneTiming => {
  const s = SCENES.find((x) => x.id === id);
  if (!s) throw new Error(`Unknown scene ${id}`);
  return s;
};

/**
 * Soft cuts (no lane wipe): where a scene's text, chips or other parts leave on the shared exit
 * (fade + rise, `progress(frame, at, 10, easeAccelerate)`, which reaches 0 on `at + 10`), they
 * start here, so the exit ends on the cut: the scene's last frame (len − 1) still shows them at
 * about 32%, and the next frame is the next scene, with its hero visual in place from local 0.
 * Gone a frame earlier, a scene where everything leaves (S06, S10) showed one bare canvas between
 * two pictures, a visible blink. A scene whose hero stays at full through the cut may clear its
 * text sooner, so the cut lands on the hero alone (S03: `softCutExitAt(len) - 4`).
 */
export const softCutExitAt = (len: number): number => len - 10;

/** Converts a global frame to a frame local to the scene. */
export const toLocal = (id: SceneId, globalFrame: number): number => globalFrame - scene(id).start;

/**
 * Clip frame for the desktop clip in S02/S03: clip frame = global frame − S02's start (120), so
 * S03 continues where S02 left off and the lanes never jump. The clip must be at least as long
 * as S02 + S03 (420 frames).
 */
export const desktopClipFrame = (globalFrame: number): number => globalFrame - scene('S02').start;
