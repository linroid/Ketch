// S01 Intro (the film's first scene, so local = global; 120 frames at the time of writing). The
// film opens on the finished Ketch lock-up (sail glyph, wordmark, tagline), so frame 0 is the
// logo: it is every player's default thumbnail, and the Thumbnail compositions (Root.tsx) render
// exactly this frame as the films' cover art. Then the sail's segments refill like download
// lanes. Promo lays the lane wipe into S02 over the cut − 9 to the cut + 9 (frames 111–129 at
// the time of writing).
//
// Local timing:
//   0       the settled lock-up, nothing in motion (= Thumbnail, = the cover art)
//   2–43    the sail refills (SailLogo mode 'refill', start SAIL_AT): segment k drains right→left
//           over 4 frames (SAIL_TIMING.drainLen) to a dim floor of its own colour, is empty at
//           6 + 3k, on the music's sparkle notes, and refills left→right over 10 frames
//           (SAIL_TIMING.refillLen); main rows top→bottom (row 0 in ember), then the mizzen. No
//           row ever vanishes and at most 5 of the 10 segments move at once, so the mark reads
//   16–53   each refilled segment gets a completion glint, a pale slanted band of light crossing
//           it over 10 frames (SAIL_TIMING.sheenLen); the last ends at 53 (SAIL_REFILL_END)
//   54      the wave has crossed the sail (WORD_AT); the music's low thump lands at 60
//   0–119   the whole lock-up pushes in slowly (scale 1 → 1.03), until the cut
//   0–119   wordmark and tagline hold at full, legible until the wipe
//
// Copy (both locales) lives in ./copy/S01.ts. The wordmark is the same in every locale; only the
// tagline changes, on one line under it.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {Background} from '../components/Background';
import {SAIL_ASPECT, SailLogo} from '../components/SailLogo';
import {TextRuns} from '../components/Text';
import {easeInOut, progress} from '../easing';
import {useCopy} from '../locale';
import {TYPE} from '../theme';
import {usePalette} from '../theme-context';
import {useDesignFrame} from '../time';
import {HEIGHT, WIDTH, scene} from '../timing';
import {S01_COPY, S01_SHARED} from './copy/S01';

/** Frame centre: the push-in's origin. */
const CX = WIDTH / 2;
const CY = HEIGHT / 2;
/** Glyph box height in the lock-up. */
const GLYPH_H = 150;
/** Wordmark size (Inter Display Bold). */
const WORD_SIZE = 150;
/** Gap between the glyph box and the wordmark's ink. */
const GAP = 36;
/**
 * LAYOUT: ink width of the wordmark ("Ketch", S01_SHARED) in Inter Display Bold 150 px, −0.04em,
 * measured on a render; the lock-up is centred on the ink, not the text box. Re-measure (crop a
 * still of frame 0 to the wordmark) if the wordmark, its size or its font changes.
 */
const WORD_INK_W = 368;
/** LAYOUT: the wordmark's ink starts this far into its box (the K's left side bearing). */
const WORD_INK_LEFT = 7;
/** Centre line of the lock-up. */
const LOCK_Y = 488;
/** LAYOUT: tagline top, for a one-line tagline in every locale (see ./copy/S01.ts). */
const TAGLINE_TOP = 612;
/** The tagline's column: wide enough that no locale's tagline wraps. */
const TAGLINE_W = 1500;
const TAGLINE_SIZE = 34;
/**
 * A transform that changes nothing on screen but is 3D, so Chrome draws the tagline as a layer of
 * its own and the push-in moves it by fractions of a pixel. Drawn in place, Chrome snapped the
 * small text's baseline to whole pixels, and the line stepped down 1 px at a time (three times
 * over the push) while the lock-up above it scaled smoothly. The wordmark, at 150 px, is drawn
 * another way and needs none. To check a change, track the tagline's vertical ink centroid on
 * stills of frames 0–119: it should move under 0.1 px a frame.
 */
const TAGLINE_LAYER = 'perspective(1000px) rotateX(0.001deg)';
/**
 * Refill start: segment k is empty and starts refilling at SAIL_AT + SAIL_TIMING.trackLead +
 * k × stagger (6, 9 … 33), where the music's sparkle bells land (picture tie SAIL_AT in
 * audio/score.py); it drains over the SAIL_TIMING.drainLen frames before, so frames 0–1 are
 * still.
 */
const SAIL_AT = 0;
/**
 * The refill wave and its last glint have crossed the sail (SAIL_AT + SAIL_REFILL_END = 53); the
 * music's low thump lands 6 frames later, on the bar line, as the lock-up holds. Picture tie
 * S01_WORD_AT in audio/score.py: the name is from the earlier opening, where the wordmark slid
 * out here; rename both sides together if it changes.
 */
const WORD_AT = 54;
/** Slow push-in of the whole lock-up, from frame 0 to the cut. */
const PUSH_TO = 1.03;
const PUSH_END = scene('S01').len;

// Lock-up centred on the ink: glyph box + gap + wordmark ink.
const GLYPH_W = GLYPH_H * SAIL_ASPECT;
const LOCK_W = GLYPH_W + GAP + WORD_INK_W;
const LOCK_LEFT = CX - LOCK_W / 2;
const WORD_LEFT = LOCK_LEFT + GLYPH_W + GAP - WORD_INK_LEFT;

export const S01Intro: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S01_COPY);
  const p = usePalette();
  const push = 1 + (PUSH_TO - 1) * progress(frame, 0, PUSH_END, easeInOut);

  return (
    <AbsoluteFill>
      <Background seed="s01" />
      <AbsoluteFill
        style={{
          transform: push === 1 ? undefined : `scale(${push})`,
          transformOrigin: `${CX}px ${CY}px`,
        }}
      >
        <div style={{position: 'absolute', left: LOCK_LEFT, top: LOCK_Y - GLYPH_H / 2}}>
          <SailLogo mode="refill" start={SAIL_AT} height={GLYPH_H} />
        </div>
        <div
          style={{
            ...TYPE.hero,
            position: 'absolute',
            left: WORD_LEFT,
            top: LOCK_Y - WORD_SIZE / 2,
            fontSize: WORD_SIZE,
            lineHeight: 1,
            color: p.primary,
            whiteSpace: 'nowrap',
          }}
        >
          {S01_SHARED.wordmark}
        </div>
        {/* The tagline never animates: plain text in Sub's style (TextRuns keeps CJK runs
            untracked), moved smoothly by TAGLINE_LAYER. */}
        <div
          style={{
            ...TYPE.sub,
            position: 'absolute',
            top: TAGLINE_TOP,
            left: 0,
            right: 0,
            margin: '0 auto',
            maxWidth: TAGLINE_W,
            fontSize: TAGLINE_SIZE,
            color: p.secondary,
            textAlign: 'center',
            transform: TAGLINE_LAYER,
          }}
        >
          <TextRuns text={copy.tagline} />
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};
