// S08 AI Discover (global 1440–1740, 300 frames at the time of writing). The real Discover clip
// in a large window; a camera follows the action. The window hard-cuts in at full opacity on
// frame 0, on the music's filter snap, showing the whole page, and pushes toward the composer
// while the query is typed. As it sends, the window drops to make room for the copy (top left)
// and frames the thread column with the sidebar cropped off the left edge, pushing in a little
// on the agent's steps (as far as the window's header stays in the frame); it pulls back as the ranked results arrive, and at the end the copy leaves
// while the camera tilts down to the selection bar ("1 selected · Review & add 1"), landing as
// the lane wipe into S09 starts (the cut − 9, local 291–299; placed by Promo). The two chips sit
// top right. The end is timed back from the scene's length, so a retime moves the exit and the
// tilt with the cut.
//
// Copy (both locales) lives in ./copy/S08.ts. The clip is the current locale's (PromoSnapshots.kt
// renders the query, the steps and the results in each language); the camera aims at the UI
// through the clip's manifest bounds and times itself by its manifest events, so it follows a
// re-rendered or translated clip on its own.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets, type AssetResolver, type NormRect} from '../assets';
import {Background} from '../components/Background';
import {Chip} from '../components/Chip';
import {ClipPlayer} from '../components/ClipPlayer';
import {TextBlock} from '../components/Text';
import {WindowFrame} from '../components/WindowFrame';
import {easeDecelerate, easeInOut, progress} from '../easing';
import {useCopy} from '../locale';
import {useDesignFrame} from '../time';
import {WIDTH, scene} from '../timing';
import {S08_COPY} from './copy/S08';
import {StageSlot, checkStageKeys, stagePose, type StageKey} from './parts/d-stage';

/** The Discover clip in the film's theme (`discover-dark`, `discover-light`). */
const clipOf = (assets: AssetResolver): string => assets.themed('discover');

// ---------------------------------------------------------------------------------------------
// The clip: when things happen on it, and where

/**
 * Clip frame = local frame − CLIP_DELAY: the window lands before the typing starts (the clip
 * types from its frame 4, so typing shows from local 12).
 */
export const CLIP_DELAY = 8;

/**
 * The clip's events (PromoSnapshots.kt, DiscoverEvents), as the manifest records them. These
 * fallbacks are the harness's frames, used while the manifest has no `events`.
 */
const EVENTS = {
  /** The query is sent (it is typed over clip 4–58). */
  send: 66,
  /** The agent answers (after steps at 80/104/128/152): the results replace the skeleton rows. */
  results: 180,
  /** The top result is selected: the selection bar ("1 selected · Review & add 1") appears. */
  select: 255,
} as const;

/**
 * LAYOUT: the follow-up composer on the clip (manifest `bounds.composer`), in fractions. The
 * camera pushes onto its centre while the query is typed, and its centre x is the thread
 * column's (x .29–.875), the horizontal anchor once the sidebar is cropped off. The fallback,
 * used while the manifest has no bound, is the hand measurement the camera was tuned on on the
 * English clip (centre .5825, .882); the pipeline's own measure is within a pixel of it on
 * screen.
 */
const COMPOSER_FALLBACK: NormRect = {x: 0.2984, y: 0.832, w: 0.5682, h: 0.1};
/**
 * LAYOUT: the agent's steps row ("Understanding → Searched the web → …", manifest
 * `bounds.steps`; the fallback is the pipeline's English measure). The bound covers the step
 * titles only, so the camera centres STEPS_FOCUS_BELOW_TOP under its top: the titles and the
 * detail line under the latest step ("5 results from ubuntu.com"), y .156–.19 on the clip.
 */
const STEPS_FALLBACK: NormRect = {x: 0.2984, y: 0.1563, w: 0.4547, h: 0.0181};
const STEPS_FOCUS_BELOW_TOP = 0.0167;
/**
 * LAYOUT: the ends of the window's header, which shows above the steps: the page's title
 * ("Discover", manifest `bounds.pageTitle`) and the model button ("Ollama · qwen3",
 * `bounds.modelChip`). The camera keeps both inside the frame (STEPS_HEADER_EDGE); the
 * fallbacks are the pipeline's English measures.
 */
const PAGE_TITLE_FALLBACK: NormRect = {x: 0.1844, y: 0.0262, w: 0.0664, h: 0.0331};
const MODEL_CHIP_FALLBACK: NormRect = {x: 0.8688, y: 0.025, w: 0.1125, h: 0.035};

// ---------------------------------------------------------------------------------------------
// Scene timing (local frames)

/** Scene length (timing.json). The end of the scene is timed back from it. */
const LEN = scene('S08').len;
/**
 * The last camera move, the tilt down to the selection bar: TILT_LEN frames, landing as the
 * lane wipe into S09 starts covering the frame (Promo: the cut − 9), at 292 for 300 frames. It
 * must start after the clip's `select` event, so the bar is there to tilt to (makePlan checks).
 */
const TILT_LEN = 24;
const TILT_END = LEN - 8;
const TILT_AT = TILT_END - TILT_LEN;
/**
 * The copy and the chips leave from here, just before the tilt, so it can use the top of the
 * frame (264 for 300 frames). The chips arrive as the results pull-back starts, so each reads
 * for about 2.3 s.
 */
const COPY_EXIT = TILT_AT - 4;
/** The results replace the skeleton rows in one clip frame; crossfade them over this many. */
const RESULTS_XFADE = 4;

// ---------------------------------------------------------------------------------------------
// Camera framing (px in the 1920×1080 frame)

/**
 * LAYOUT: window width as the camera settles on the steps, with the sidebar just off the left
 * edge (its labels end at x ≈ .16 of the window). It then pushes in until the header's title or
 * model button is STEPS_HEADER_EDGE px from the frame's edge, about 2300 px (stepsPushWidth):
 * past that, the title is cut mid-letter. Check stills of local 120 and 186 after the header
 * or the sidebar changes.
 */
const STEPS_FRAME_W = 2280;
/** How far inside the frame the header's title and model button stay during the push (px). */
const STEPS_HEADER_EDGE = 40;
/**
 * LAYOUT: results framing. The window's left edge on the text column (x 120) and its right edge
 * 120 px from the frame's, inside the safe area; its top at RESULTS_TOP, under the copy (the
 * English sub's two lines end near y 360), where all three ranked rows (manifest
 * `bounds.results`, down to y ≈ .56 of the window) fit above the frame's bottom.
 */
const RESULTS_W = 1680;
const RESULTS_TOP = 400;

/** Everything the scene times or aims from the clip, for one locale's clip. */
interface Plan {
  keys: StageKey[];
  /** Clip frame of the results (the skeleton crossfades out from here). */
  results: number;
  /** The copy enters as the window makes room for it. */
  textAt: number;
  /** The chips enter as the camera starts pulling back to the results. */
  chipsAt: number;
}

/** The scene's camera and cues for the clip `assets` resolves (the current locale's). */
const makePlan = (assets: AssetResolver): Plan => {
  const CLIP = clipOf(assets);
  const send = assets.clipEvent(CLIP, 'send', EVENTS.send);
  const results = assets.clipEvent(CLIP, 'results', EVENTS.results);
  const select = assets.clipEvent(CLIP, 'select', EVENTS.select);
  const at = (clipFrame: number) => clipFrame + CLIP_DELAY;
  if (at(select) > TILT_AT) {
    throw new Error(
      `S08: the clip selects its top result at local ${at(select)}, after the last tilt starts ` +
        `(${TILT_AT}); lengthen S08 or move SELECT_AT earlier in PromoSnapshots.kt`,
    );
  }

  const composer = assets.assetBounds(CLIP, 'composer', COMPOSER_FALLBACK);
  const steps = assets.assetBounds(CLIP, 'steps', STEPS_FALLBACK);
  const colX = composer.x + composer.w / 2;
  const composerY = composer.y + composer.h / 2;
  const stepsY = steps.y + STEPS_FOCUS_BELOW_TOP;
  // The push onto the steps keeps the window's point colX on the frame's centre, so it can grow
  // until either end of the header is STEPS_HEADER_EDGE from the frame's edge.
  const title = assets.assetBounds(CLIP, 'pageTitle', PAGE_TITLE_FALLBACK);
  const chip = assets.assetBounds(CLIP, 'modelChip', MODEL_CHIP_FALLBACK);
  const reach = WIDTH / 2 - STEPS_HEADER_EDGE;
  const stepsPushW = Math.min(reach / (colX - title.x), reach / (chip.x + chip.w - colX));
  const stepsFrameW = Math.min(STEPS_FRAME_W, stepsPushW);

  // Camera: which window point lands where, and how wide the window is on screen. The window
  // points come from the clip's bounds; LAYOUT: the screen points and widths are framing tuned
  // on the English clip and copy (re-check stills of local 0, 70, 120, 186, 216 and 299).
  const keys: StageKey[] = [
    // Cuts in on the whole page (its own empty state, "Describe it. Ketch finds the download.",
    // and the "Ollama · qwen3" header), then pushes gently toward the composer as the query is
    // typed, until the window fills the frame's width (a 20 px bleed each side) with its bottom
    // 40 px above the frame's.
    {at: 0, fx: 0.5, fy: 0.5, sx: 960, sy: 540, w: 1640},
    {at: at(send - 4), fx: 0.5, fy: composerY, sx: 960, sy: 895, w: 1960, easing: easeInOut},
    {at: at(send), fx: 0.5, fy: composerY, sx: 960, sy: 894, w: 1966, easing: (t) => t},
    // As it sends, the window drops to make room for the copy (its top edge at y ≈ 420, the
    // header revealed) and frames the thread column, centred, with the sidebar sliding off the
    // left edge (a bleed); then a slight push onto the steps while the agent works, as far as
    // the header's title and model button stay in the frame.
    {at: at(send) + 40, fx: colX, fy: stepsY, sx: 960, sy: 650, w: stepsFrameW},
    {
      at: at(results) - 2,
      fx: colX,
      fy: stepsY,
      sx: 960,
      sy: 650,
      w: stepsPushW,
      easing: easeInOut,
    },
    // Results: pull back to the whole window, aligned with the text column, so all three ranked
    // rows fit under the copy; then hold with a slow drift.
    {at: at(results) + 28, fx: 0, fy: 0, sx: 120, sy: RESULTS_TOP, w: RESULTS_W},
    {at: TILT_AT, fx: 0, fy: 0, sx: 120, sy: RESULTS_TOP - 8, w: RESULTS_W, easing: (t) => t},
    // The copy leaves and the camera tilts down (TILT_LEN) to the selection bar, the top result
    // just selected (clip event `select`): the scene ends on the whole window, from the query to
    // "1 selected · Review & add 1" and the composer, as it began on the empty page.
    {at: TILT_END, fx: 0, fy: 0, sx: 120, sy: 15, w: RESULTS_W, easing: easeInOut},
  ];
  checkStageKeys('S08', keys);
  return {keys, results, textAt: at(send) + 16, chipsAt: at(results) - 2};
};

const PLANS = new Map<string, Plan>();
/** makePlan, once per locale and film's theme. */
const planFor = (assets: AssetResolver): Plan => {
  const key = `${assets.locale}/${assets.theme}`;
  let plan = PLANS.get(key);
  if (!plan) PLANS.set(key, (plan = makePlan(assets)));
  return plan;
};

export const S08Discover: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S08_COPY);
  const assets = useAssets();
  const CLIP = clipOf(assets);
  const plan = planFor(assets);
  const pose = stagePose(plan.keys, frame, assets.assetAspect(CLIP));
  const clipFrame = frame - CLIP_DELAY;

  // Window entrance: a hard cut on the music's filter snap, at full opacity on frame 0, rising in.
  const rise = progress(frame, 0, 26, easeDecelerate);
  // The skeleton frame (the one before the results) fading out over the first results frames.
  const skeleton = 1 - progress(clipFrame, plan.results, RESULTS_XFADE, easeInOut);

  return (
    <AbsoluteFill>
      <Background seed="s08" />
      <StageSlot
        pose={pose}
        style={{transform: `translate3d(0, ${((1 - rise) * 56).toFixed(2)}px, 0)`}}
      >
        <WindowFrame width={pose.width} height={pose.height}>
          <ClipPlayer name={CLIP} frame={clipFrame} />
          {clipFrame >= plan.results && skeleton > 0 && (
            <ClipPlayer
              name={CLIP}
              frame={plan.results - 1}
              style={{position: 'absolute', inset: 0, opacity: skeleton}}
            />
          )}
        </WindowFrame>
      </StageSlot>
      <TextBlock
        eyebrow={copy.eyebrow}
        headline={copy.headline}
        sub={copy.sub}
        delay={plan.textAt}
        exitAt={COPY_EXIT}
      />
      {/* LAYOUT: the chips' column, right-aligned to the safe area, its top on the headline's
          first line (eyebrow at 110). Why the first chip does not say "Asks before it opens a
          site": see copy/S08.ts. */}
      <div
        style={{
          position: 'absolute',
          right: 120,
          top: 172,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-end',
          gap: 14,
        }}
      >
        <Chip
          label={copy.chips[0]}
          delay={plan.chipsAt}
          exitAt={COPY_EXIT}
          style={{transformOrigin: 'right center'}}
        />
        <Chip
          label={copy.chips[1]}
          delay={plan.chipsAt + 8}
          exitAt={COPY_EXIT + 2}
          style={{transformOrigin: 'right center'}}
        />
      </div>
    </AbsoluteFill>
  );
};
