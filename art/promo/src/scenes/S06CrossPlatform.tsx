// S06 Cross-platform (300 frames at the time of writing; its start and length are in
// timing.json). Beat A (local 0–120): desktop, Android and iPhone assemble; platform pills. Beat
// B (local 120–300): terminal (CLI + server), browser extension popup and the web app in a
// browser; surface pills. The beats swap on the bar line at 120 with a short crossover (see T),
// so the screen never empties: Beat B's terminal lands over Beat A's fading stage, while the
// headlines, which share one slot, hand over without overlapping. The lane wipe into this scene
// is placed by Promo (local 0–9 is being revealed, so the entrances start under it); the scene
// leaves over its last frames, still fading on its last frame and gone on the cut (soft cut into
// S07: softCutExitAt, so no bare frame between the two scenes).
//
// Copy (both locales), the terminal script and the URLs live in ./copy/S06.ts.
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets} from '../assets';
import {Background} from '../components/Background';
import {BrowserFrame, browserScale, browserToolbarHeight} from '../components/BrowserFrame';
import {ChipRow} from '../components/Chip';
import {ClipPlayer} from '../components/ClipPlayer';
import {StillImage} from '../components/StillImage';
import {TerminalWindow, type TermLine, typedAt} from '../components/TerminalWindow';
import {Eyebrow, Headline, Sub, TEXT_OUT} from '../components/Text';
import {WindowFrame} from '../components/WindowFrame';
import {easeDecelerate, easeInOut, lerpClamp, progress} from '../easing';
import {useCopy} from '../locale';
import {FONT, SAFE, TEXT_ANCHOR} from '../theme';
import {usePalette} from '../theme-context';
import {useDesignFrame} from '../time';
import {HEIGHT, scene, softCutExitAt} from '../timing';
import {S06_COPY, S06_SHARED} from './copy/S06';
import {
  COPY_METRICS,
  MEBIBYTE,
  PhoneDevice,
  PopupBrowser,
  cliProgressLine,
  groupExitStyle,
  headlineHeight,
  phoneBodyWidth,
  popupHeight,
  stageStyle,
} from './parts/c-parts';

// ---------------------------------------------------------------------------------------------
// Timing (scene-local frames)

/** The scene's length; its exits follow it when the scene is retimed (npm run retime). */
const LEN = scene('S06').len;
/** The bar line the beats swap on; their stages cross over around it (T.outA, T.term). */
const SWAP = 120;

const T = {
  /**
   * The copy and Beat A's stage start under the lane wipe (Promo, local −9…9), so the wipe
   * uncovers a scene that is already landing instead of an empty canvas.
   */
  eyebrow: -6,
  headlineA: -2,
  /** Beat A's stage cascades in 8 frames apart: desktop, Android, iPhone. */
  desk: -4,
  android: 4,
  iphone: 12,
  pillsA: 40,
  /** The footnote fades in over 12 frames from here. */
  footnote: 62,
  /**
   * The swap. The stages cross over around SWAP, so no frame is near-empty (STORYBOARD.md, soft
   * cuts: no near-empty run over 3 frames). Beat A's stage fades evenly over `outALen` from
   * SWAP − 4 (a third gone on SWAP, gone on SWAP + 6): it carries the frame while the headlines
   * hand over and B's terminal shows little more than its prompt. B's terminal lands above it
   * from SWAP − 2, at full opacity within `termFade` frames, covering A's desktop. Measured with
   * the films check's edge scan (scripts/check-film.mjs) on stills of local 114–130: every frame
   * stays at 0.85 or more in both locales, against 0.6 for near-empty. Shorten the crossover only
   * with that check in hand.
   */
  outA: SWAP - 4,
  outALen: 10,
  term: SWAP - 2,
  termFade: 4,
  /**
   * Beat A's headline and pills use the text exit, gone on SWAP. Beat B's headline starts a
   * frame earlier, so its first word shows from SWAP (a word is invisible on its start frame):
   * the two headlines, which share one slot, never show at once, and the slot is never empty.
   */
  textAOut: SWAP - TEXT_OUT,
  headlineB: SWAP - 1,
  /**
   * The extension window enters on SWAP, dissolving in over the iPhone's fading place, and before
   * the web app behind it, so neither shows through.
   */
  ext: SWAP,
  web: SWAP + 6,
  /** The popup opens as the extension window lands, so it is never an empty box. */
  popupOpen: SWAP + 8,
  subB: SWAP + 14,
  pillsB: SWAP + 40,
  /**
   * The stage leaves over 8 frames (soft cut into S07), ending on the cut like the text: about
   * 37% on the last frame, never a bare canvas before S07 (see softCutExitAt).
   */
  stageEnd: LEN - 8,
  stageEndLen: 8,
  /** Text and pills leave over 10 frames (Text's fixed exit), ending on the cut. */
  end: softCutExitAt(LEN),
} as const;

/** Beat A is gone from here (its stage's fade ends); it unmounts. */
const A_GONE = T.outA + T.outALen;

// Layout (px). The copy is laid out by hand (the headlines swap under one eyebrow), by the
// copy's own lines (see copy/S06.ts), as TextBlock would lay it out.
/** Headline top: under the eyebrow line plus its gap. */
const HEADLINE_TOP = TEXT_ANCHOR.y + COPY_METRICS.eyebrow + COPY_METRICS.eyebrowGap;
/** Sub top under the headline `headline` (taller in CJK locales). */
const subTop = (headline: string): number =>
  HEADLINE_TOP + headlineHeight(headline) + COPY_METRICS.subGap;
/** LAYOUT: the sub's column, wide enough for each locale's first sub line (en "…for your NAS,"). */
const SUB_WIDTH = 860;
/** Bottom edge of both pill rows: the safe area's bottom. */
const PILLS_BOTTOM = HEIGHT - SAFE.y;
/** Lateral push between the beats. */
const PUSH = 70;

// ---------------------------------------------------------------------------------------------
// Beat A: devices

/**
 * LAYOUT: the cascade, with clear gaps (desktop 120–1032, Android 1064–1407, iPhone 1448–1776),
 * so a phone never slices through the desktop's toolbar or inspector text. Desktop `h` is the box
 * its window centres in (the window's height follows the clip's aspect). The phones' widths
 * follow from their clips' bodies (`phoneBodyWidth`), so re-check the gaps on a still if a phone
 * clip is re-framed: move ANDROID.x and IPHONE.x to keep ~30 px between the devices.
 */
const DESK = {x: 120, y: 300, w: 912, h: 600};
const ANDROID = {x: 1064, y: 172, h: 724};
const IPHONE = {x: 1448, y: 238, h: 688};
/**
 * LAYOUT: the footnote's box: right-aligned with the iPhone and `extraWidth` px wider than it to
 * its left (room for each locale's footnote on one line), its 20 px line centred on the pill row.
 */
const FOOTNOTE = {extraWidth: 200, top: PILLS_BOTTOM - 33};

/**
 * Desktop clip time at local 0 for a clip that plays `duration` design frames (clipDuration). S03
 * last showed the clip's end (frame 419 of 420), so instead of restarting at 0 (the iso jumping
 * back from 84% to 47%), Beat A plays the clip's last frames and reaches its end as the beat
 * leaves.
 */
const deskClipFrom = (duration: number): number => Math.max(0, duration - 1 - A_GONE);

const BeatA: React.FC<{frame: number}> = ({frame}) => {
  const copy = useCopy(S06_COPY);
  const assets = useAssets();
  const p = usePalette();
  const desk = assets.themed('desktop');
  const android = assets.themed('android-devices');
  const iphone = assets.themed('ios-lanes');
  const deskH = DESK.w / assets.assetAspect(desk);
  const deskClipAt = deskClipFrom(assets.clipDuration(desk));
  const iphoneW = phoneBodyWidth(assets, iphone, IPHONE.h);
  return (
    // an even fade (not the accelerating exit), so A still carries the frame as B lands over it
    <AbsoluteFill
      style={groupExitStyle(frame, T.outA, {dx: -PUSH, dy: 0, len: T.outALen, easing: easeInOut})}
    >
      <div
        style={{
          position: 'absolute',
          left: DESK.x,
          top: DESK.y + (DESK.h - deskH) / 2,
          ...stageStyle(frame, T.desk, {drift: {x: 0, y: -8}}),
        }}
      >
        <WindowFrame width={DESK.w} height={deskH}>
          <ClipPlayer name={desk} frame={deskClipAt + frame} />
        </WindowFrame>
      </div>
      <div
        style={{
          position: 'absolute',
          left: ANDROID.x,
          top: ANDROID.y,
          ...stageStyle(frame, T.android, {drift: {x: 0, y: -14}}),
        }}
      >
        <PhoneDevice name={android} frame={frame} bodyHeight={ANDROID.h} />
      </div>
      <div
        style={{
          position: 'absolute',
          left: IPHONE.x,
          top: IPHONE.y,
          ...stageStyle(frame, T.iphone, {drift: {x: 0, y: -20}}),
        }}
      >
        <PhoneDevice name={iphone} frame={frame} bodyHeight={IPHONE.h} />
      </div>
      {/* footnote under the iPhone, right-aligned with it, centred on the pill row */}
      <div
        style={{
          position: 'absolute',
          left: IPHONE.x - FOOTNOTE.extraWidth,
          width: iphoneW + FOOTNOTE.extraWidth,
          top: FOOTNOTE.top,
          textAlign: 'right',
          fontFamily: FONT.sans,
          fontWeight: 500,
          fontSize: 16,
          lineHeight: '20px',
          color: p.tertiary,
          opacity: lerpClamp(frame, [T.footnote, T.footnote + 12], [0, 1]),
        }}
      >
        {copy.footnote}
      </div>
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------------------------------------
// Beat B: command line, server, browser extension, web app

/**
 * Terminal script, frames relative to the terminal's own start (T.term). Its text (commands and
 * output) is in copy/S06.ts; this only times it.
 */
const TERM_SCRIPT = (() => {
  const {download, server} = S06_SHARED.terminal;
  // the prompt shows as the window lands (on SWAP), and the first command types at once
  const cmd1 = {at: SWAP - T.term, cmd: download.command, cps: 2.6};
  const progressAt = typedAt(cmd1) + 3;
  /** The progress line counts 0→100% over this many frames. */
  const progressLen = 54;
  const doneAt = progressAt + progressLen + 2;
  const cmd2 = {at: doneAt + 4, cmd: server.command, cps: 1};
  const serverAt = typedAt(cmd2) + 4;
  const total = download.sizeMiB * MEBIBYTE;
  const speed = download.speedMiBps * MEBIBYTE;
  const lines: TermLine[] = [
    cmd1,
    {
      at: progressAt,
      // a steady transfer that eases into the last few percent
      text: (t) =>
        cliProgressLine(
          progress(t, 0, progressLen, (x) => 1 - Math.pow(1 - x, 1.5)),
          total,
          speed,
        ),
    },
    {at: doneAt, text: download.completed},
    cmd2,
    // the banner prints line by line, 2 frames apart
    ...server.banner.map((text, i) => ({at: serverAt + 2 * i, text})),
  ];
  return lines;
})();

/**
 * LAYOUT: the terminal, wide enough for the `ketch <url>` command line at 1.5× (14 px mono), tall
 * enough for the script's seven lines, between the sub and the pills.
 */
const TERM = {x: 120, y: 466, w: 920, h: 366, scale: 1.5};
/** The web app's browser at the top right; its height follows the still's aspect. */
const WEB = {x: 1110, y: 110, w: 690};
/**
 * The extension's browser over the web app's lower left, its bottom edge fixed so a taller popup
 * (another locale's) grows it upward, never into the bottom margin; its height fits the popup.
 * The popup is 360 CSS px wide, so `popupW` 360 shows it at 1:1.
 */
const EXT = {x: 1362, bottom: 949, w: 438, popupW: 360};

const BeatB: React.FC<{frame: number}> = ({frame}) => {
  const assets = useAssets();
  const web = assets.themed('web');
  const webScale = browserScale(WEB.w);
  const webH = browserToolbarHeight(webScale) + WEB.w / assets.assetAspect(web);
  // toolbar + the popup's 3 px offset under it + the popup + a 24 px margin below it
  const extH = browserToolbarHeight(browserScale(EXT.w)) + 3 + popupHeight(assets, EXT.popupW) + 24;
  const open = progress(frame, T.popupOpen, 12, easeDecelerate);
  const enter = (delay: number, drift: {x: number; y: number}, fadeLen?: number) =>
    stageStyle(frame, delay, {dx: PUSH, dy: 0, len: 28, fadeLen, drift, driftLen: LEN - delay});
  return (
    <AbsoluteFill style={groupExitStyle(frame, T.stageEnd, {len: T.stageEndLen})}>
      <div style={{position: 'absolute', left: WEB.x, top: WEB.y, ...enter(T.web, {x: -10, y: 0})}}>
        <BrowserFrame width={WEB.w} height={webH} url={S06_SHARED.webUrl} scale={webScale}>
          <StillImage name={web} />
        </BrowserFrame>
      </div>
      <div
        style={{
          position: 'absolute',
          left: EXT.x,
          top: EXT.bottom - extH,
          ...enter(T.ext, {x: 0, y: -12}),
        }}
      >
        <PopupBrowser
          width={EXT.w}
          height={extH}
          popupWidth={EXT.popupW}
          open={open}
          url={S06_SHARED.extensionUrl}
        />
      </div>
      <div
        style={{
          position: 'absolute',
          left: TERM.x,
          top: TERM.y,
          ...enter(T.term, {x: 0, y: -8}, T.termFade),
        }}
      >
        <TerminalWindow
          lines={TERM_SCRIPT}
          width={TERM.w}
          height={TERM.h}
          scale={TERM.scale}
          frame={frame - T.term}
        />
      </div>
    </AbsoluteFill>
  );
};

// ---------------------------------------------------------------------------------------------

export const S06CrossPlatform: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S06_COPY);
  return (
    <AbsoluteFill style={{fontFamily: FONT.sans}}>
      <Background seed="s06" />
      {/* B renders above A, so the terminal covers A's fading desktop during the crossover */}
      {frame < A_GONE && <BeatA frame={frame} />}
      {frame >= T.term && <BeatB frame={frame} />}

      <Eyebrow
        text={copy.eyebrow}
        delay={T.eyebrow}
        exitAt={T.end}
        style={{position: 'absolute', left: TEXT_ANCHOR.x, top: TEXT_ANCHOR.y}}
      />
      {frame < T.textAOut + TEXT_OUT && (
        <>
          <Headline
            text={copy.headlineA}
            delay={T.headlineA}
            exitAt={T.textAOut}
            style={{position: 'absolute', left: TEXT_ANCHOR.x, top: HEADLINE_TOP}}
          />
          <ChipRow
            labels={[...copy.platforms]}
            delay={T.pillsA}
            stagger={4}
            exitAt={T.textAOut}
            style={{position: 'absolute', left: TEXT_ANCHOR.x, bottom: SAFE.y}}
          />
        </>
      )}
      {frame >= T.headlineB && (
        <>
          <Headline
            text={copy.headlineB}
            delay={T.headlineB}
            exitAt={T.end}
            style={{position: 'absolute', left: TEXT_ANCHOR.x, top: HEADLINE_TOP}}
          />
          <Sub
            text={copy.subB}
            delay={T.subB}
            exitAt={T.end}
            width={SUB_WIDTH}
            style={{position: 'absolute', left: TEXT_ANCHOR.x, top: subTop(copy.headlineB)}}
          />
          <ChipRow
            labels={[...copy.surfaces]}
            delay={T.pillsB}
            stagger={4}
            exitAt={T.end}
            style={{position: 'absolute', left: TEXT_ANCHOR.x, bottom: SAFE.y}}
          />
        </>
      )}
    </AbsoluteFill>
  );
};
