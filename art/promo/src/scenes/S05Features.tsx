// S05 Full featured (global 690–960 at the time of writing, 270 frames). Headline, then a 3×2 bento
// of feature tiles, each a title, a caption and a crop of real UI that dissolves into the tile at
// its edges. Each tile gets a 30-frame spotlight in turn while the grid pushes in slowly; the grid
// then pulls back. Promo's lane wipe covers local 261–270, so the last frames show the whole grid.
// Cut in from S04 (hard): the copy and the tiles start before the scene does (negative delays), so
// on frame 0 the headline and the first tiles are up.
//
// Copy (both locales) lives in ./copy/S05.ts; this file picks the UI each tile shows.
//
// Local timing:
//   -8–20   eyebrow, headline (word by word)
//   -10–30  tiles rise in, reading order, 4 frames apart (20 frames each; 12-frame fade)
//   54–60   spotlight ramps in on tile 1
//   60–240  spotlights: tile i owns [60 + 30i, 90 + 30i), 12-frame crossfades on the cuts
//   54–240  grid push 1 → 1.025 (about its bottom centre, so it stays inside the safe area)
//   240–258 spotlight releases, grid pulls back to 1
//   258–269 whole grid at rest (the wipe starts covering at 261)
import React from 'react';
import {AbsoluteFill} from 'remotion';
import {useAssets, type AssetResolver, type NormRect} from '../assets';
import {Background} from '../components/Background';
import {TextBlock, TextRuns} from '../components/Text';
import {easeStandard, lerpClamp} from '../easing';
import {useCopy} from '../locale';
import {FONT, TYPE, alpha, mix, type Mode} from '../theme';
import {usePalette, useThemed} from '../theme-context';
import {useDesignFrame} from '../time';
import {FEATURE_IDS, S05_COPY, type FeatureId, type TileStrings} from './copy/S05';
import {SourceCrop, enterP, fadeP, type EdgeFade, type SourceRect} from './parts/b-kit';

// ---------------------------------------------------------------------------------------------
// Layout

const GX = 140;
const GY = 300;
const GW = 1640;
const GH = 990 - GY;
const GAP = 24;
const TW = (GW - 2 * GAP) / 3;
const TH = (GH - GAP) / 2;
const PAD = 28;
/**
 * LAYOUT: top of the image area inside a tile, below the title (30 px) and up to two caption
 * lines (20 px). Retune if a locale's caption needs a third line (better: shorten the caption).
 */
const IMG_TOP = 124;
const IMG_W = TW;
const IMG_H = TH - IMG_TOP;

// ---------------------------------------------------------------------------------------------
// Shots. Each tile shows one or more rects of a real still, in reference px (px at 2 px per dp,
// see assetRefScale: the 2560 × 1600 px of a 1280 × 800 dp still, whatever its density), at
// `scale` screen px per reference px, `left` px from the tile's left edge and vertically centred
// in the image area as a group. A rect without `w` runs to the tile's right edge. Rects stop at
// row boundaries, so no neighbouring row shows sliced; the UI's first text or control sits about
// PAD (28–36 px) in from the tile edge in every tile, and each rect dissolves into the tile at
// its edges.
//
// Rects are placed from the bounds the asset pipeline measures on each locale's still
// (PromoSnapshots.kt, AssetBounds), so a localized screen whose card grows or whose rows move is
// framed the same way; assetBoundsRef gives them in reference px, like the constants. What is
// left is marked LAYOUT: the room kept around each element (so the rows above and below stay
// out), the fallbacks used until a manifest records the bounds (measured on the English stills
// at 2 px per dp), and the Smart queue and Scheduling columns, which no bound covers yet. To
// retune one, open the still at full size, read the row's pixel bands (divided by the still's
// density / 2 when it is denser), then render the tile (S05 frames 60–240 spotlight each tile in
// turn).

interface Shot {
  rect: {x: number; y: number; w?: number; h: number};
  scale: number;
  /** Left edge of the rect's box, px from the tile's left edge. */
  left: number;
  /** Top of the box relative to the group's top (for stacked pieces). Default 0. */
  dy?: number;
  fade?: EdgeFade;
}

/** Fades for a tight box: soft sides, a little top and bottom. */
const SHOT_FADE: EdgeFade = {l: 0.08, r: 0.1, t: 0.12, b: 0.14};
/** Fades for a piece of a row. */
const PIECE_FADE: EdgeFade = {l: 0.03, r: 0.05, t: 0.14, b: 0.14};

/**
 * A rect from `left` px left of `box` (reference px) to the tile's right edge, with `above` and
 * `below` px of room over and under it.
 */
const around = (box: SourceRect, left: number, above: number, below: number): Shot['rect'] => ({
  x: box.x - left,
  y: box.y - above,
  h: box.h + above + below,
});

/**
 * LAYOUT: a settings card's text starts this many reference px in from its rim (both settings
 * shots place it 36 px in from the tile's edge).
 */
const CARD_TEXT_IN = 32;

/**
 * LAYOUT: the Speed mode card on settings-speed-dark, in fractions, until the manifest records
 * `bounds.speedMode`; English: x 930–2210, y 216–419.
 */
const SPEED_FALLBACK: NormRect = {x: 0.3633, y: 0.135, w: 0.5, h: 0.1269};

/**
 * settings-speed-dark: the Speed mode card ("Speed mode", "Slow lane on until 18:00.", the Full
 * speed / Slow lane / Auto control). LAYOUT: 16 px of room above it and 13 below keep "MODE"
 * (163–180 in English) and "LIMITS" (from 486) out.
 */
const speedShots = (assets: AssetResolver): Shot[] => {
  const card = assets.assetBoundsRef(assets.themed('settings-speed'), 'speedMode', SPEED_FALLBACK);
  return [{rect: around(card, 36 / 0.7 - CARD_TEXT_IN, 16, 13), scale: 0.7, left: 0}];
};

/**
 * LAYOUT: "Priority" and its Low / Normal / High / Urgent control on inspector-overview-dark, in
 * fractions, until the manifest records `bounds.priority`; English: x 1936–2460, y 973–1068.
 */
const PRIORITY_FALLBACK: NormRect = {x: 0.7562, y: 0.6081, w: 0.2047, h: 0.0594};

/**
 * inspector-overview-dark: only the Priority label and its control, larger. LAYOUT: 11 px of
 * room above and 20 below keep the Connections stepper above and the Start row below out; the
 * inspector's divider (x 1902) stays out on the left.
 */
const priorityShots = (assets: AssetResolver): Shot[] => {
  const priority = assets.assetBoundsRef(
    assets.themed('inspector-overview'),
    'priority',
    PRIORITY_FALLBACK,
  );
  return [{rect: around(priority, PAD / 0.9, 11, 20), scale: 0.9, left: 0}];
};

/**
 * LAYOUT: the scheduled download's table row on downloads-dark, in fractions, until the
 * manifest records `bounds.scheduledRow`; English: x 440–2544, y 768–848.
 */
const SCHEDULED_ROW_FALLBACK: NormRect = {x: 0.1719, y: 0.48, w: 0.8219, h: 0.05};

/**
 * downloads-dark: the scheduled download as one list row: its status dot, icon and name
 * ("local-model-8b-q4.gguf") with its status ("Starts today at 23:00 · in 8h 30m") under the
 * name. Both pieces come from the same table row; the empty Size column between them is left
 * out. LAYOUT: the pieces' columns are measured on the English still (status dot at x 478, name
 * text at 577, status text at 1489, up to 1858; the Chinese status ends at 1849), their tops
 * 14 and 20 px into the row.
 */
const scheduleShots = (assets: AssetResolver): Shot[] => {
  const row = assets.assetBoundsRef(
    assets.themed('downloads'),
    'scheduledRow',
    SCHEDULED_ROW_FALLBACK,
  );
  return [
    {
      rect: {x: 462, y: row.y + 14, w: 440, h: 52},
      scale: 0.9,
      left: PAD - (478 - 462) * 0.9,
      fade: PIECE_FADE,
    },
    // the status lines up under the name (name text at x 577, status text at 1489)
    {
      rect: {x: 1482, y: row.y + 20, w: 400, h: 44},
      scale: 0.9,
      left: PAD + (577 - 478) * 0.9 - (1489 - 1482) * 0.9,
      dy: 45.4,
      fade: PIECE_FADE,
    },
  ];
};

/**
 * downloads-dark: a download with its own speed limit (sunrise-timelapse-8k.mov, "5 MB/s"), the
 * WAITING group header ("3 · in start order") and the first queued row (dataset-shard-04.tar):
 * rows y 472–685 between the hairlines; the server row above (text 422–442) and design-assets
 * below (text from 718) stay out. Status dots at x 478, the table's left border (440) stays out.
 * LAYOUT: measured on the English still (the Chinese one has the same rows). The manifest's
 * `bounds.waitingGroup` could anchor it, but today its top is the Waiting filter tab at the top
 * of the table (y 134), not the group's header.
 */
const SHOT_QUEUE: Shot[] = [{rect: {x: 478 - PAD / 0.85, y: 476, h: 206}, scale: 0.85, left: 0}];

/**
 * LAYOUT: the "Spread downloads across" card on settings-network-dark, in fractions, until the
 * manifest records `bounds.networks`; English: x 930–2210, y 216–411.
 */
const NETWORKS_FALLBACK: NormRect = {x: 0.3633, y: 0.135, w: 0.5, h: 0.1219};

/**
 * settings-network-dark: the "Spread downloads across" card with the interface chips (en0 and
 * en7 checked). LAYOUT: 16 px of room above it and 13 below keep "NETWORKS" (163–180 in English)
 * and "Resets when Laptop restarts." (from 432) out.
 */
const networkShots = (assets: AssetResolver): Shot[] => {
  const card = assets.assetBoundsRef(
    assets.themed('settings-network'),
    'networks',
    NETWORKS_FALLBACK,
  );
  return [{rect: around(card, 36 / 0.66 - CARD_TEXT_IN, 16, 13), scale: 0.66, left: 0}];
};

/**
 * LAYOUT: the command palette on palette-dark, in fractions, until the manifest records
 * `bounds.palette`; English: x 680–1880, y 144–1052.
 */
const PALETTE_FALLBACK: NormRect = {x: 0.2656, y: 0.09, w: 0.4688, h: 0.5675};

/**
 * palette-dark: the palette's query field, COMMANDS and its first two commands, from just
 * outside the palette's top-left corner (so its rim fades in with the shot's soft edges).
 * LAYOUT: the offsets from the palette's corner and the height (352 reference px, down to the
 * second command) are measured on the English still.
 */
const paletteShots = (assets: AssetResolver): Shot[] => {
  const palette = assets.assetBoundsRef(assets.themed('palette'), 'palette', PALETTE_FALLBACK);
  return [
    {
      rect: {x: palette.x - 9, y: palette.y - 10, h: 352},
      scale: 0.58,
      left: 0,
      fade: {l: 0.08, r: 0.1, t: 0.2, b: 0.24},
    },
  ];
};

/** What a tile shows under its copy: rects of one still, fixed or placed from its bounds. */
interface TileVisual {
  /** The still in every theme (`downloads`); each film shows its own (`downloads-dark`, …). */
  still: string;
  shots: Shot[] | ((assets: AssetResolver) => Shot[]);
}

/** Each feature's UI. The tiles' order and words come from the copy (FEATURE_IDS). */
const VISUALS: Record<FeatureId, TileVisual> = {
  speed: {still: 'settings-speed', shots: speedShots},
  priority: {still: 'inspector-overview', shots: priorityShots},
  schedule: {still: 'downloads', shots: scheduleShots},
  queue: {still: 'downloads', shots: SHOT_QUEUE},
  networks: {still: 'settings-network', shots: networkShots},
  palette: {still: 'palette', shots: paletteShots},
};

/** The shots of tile `id` on the image `assets` shows for its still (this locale's, or English). */
const shotsFor = (id: FeatureId, assets: AssetResolver): Shot[] => {
  const {shots} = VISUALS[id];
  return typeof shots === 'function' ? shots(assets) : shots;
};

// ---------------------------------------------------------------------------------------------
// Timing

/** The copy starts before the cut, so the headline is up on frame 0. */
const TEXT_AT = -8;
/** First tile; it starts before the cut, so the first tiles are up on frame 0. */
const TILES_AT = -10;
const TILE_STAGGER = 4;
const SPOT_AT = 60;
const SPOT_LEN = 30;
const SPOT_END = SPOT_AT + SPOT_LEN * FEATURE_IDS.length; // 240
const XFADE = 6; // half the crossfade between two spotlights

/** 0..1: how much tile i is the one in the spotlight. */
const spotOf = (frame: number, i: number): number => {
  const a = SPOT_AT + i * SPOT_LEN;
  const b = a + SPOT_LEN;
  return lerpClamp(frame, [a - XFADE, a + XFADE, b - XFADE, b + XFADE], [0, 1, 1, 0], easeStandard);
};

/** 0..1: how strongly the other tiles are dimmed. */
const dimOf = (frame: number): number =>
  lerpClamp(
    frame,
    [SPOT_AT - XFADE, SPOT_AT + XFADE, SPOT_END - XFADE, SPOT_END + 12],
    [0, 1, 1, 0],
    easeStandard,
  );

/** Grid scale: slow push during the spotlights, pull back after. */
const gridScale = (frame: number): number =>
  1 +
  0.025 * lerpClamp(frame, [SPOT_AT - XFADE, SPOT_END], [0, 1]) * (1 - fadeP(frame, SPOT_END, 18));

export const S05Features: React.FC = () => {
  const frame = useDesignFrame();
  const copy = useCopy(S05_COPY);
  const assets = useAssets();
  const dim = dimOf(frame);
  // How far the other tiles fade into the canvas: less on the light one, where a faded tile
  // washes out instead of sinking into the dark.
  const dimDepth = useThemed({dark: 0.45, light: 0.32});
  const scale = gridScale(frame);
  return (
    <AbsoluteFill>
      <Background seed="s05" />
      <TextBlock eyebrow={copy.eyebrow} headline={copy.headline} delay={TEXT_AT} />
      <div
        style={{
          position: 'absolute',
          left: GX,
          top: GY,
          width: GW,
          height: GH,
          transform: `scale(${scale.toFixed(5)})`,
          // about the bottom centre: the bottom row stays on the safe line, the sides end on it
          transformOrigin: '50% 100%',
        }}
      >
        {FEATURE_IDS.map((id, i) => {
          const col = i % 3;
          const row = Math.floor(i / 3);
          const k = spotOf(frame, i);
          const at = TILES_AT + i * TILE_STAGGER;
          const rise = enterP(frame, at, 20);
          const fade = fadeP(frame, at, 12);
          const opacity = fade * (1 - dimDepth * dim * (1 - k));
          const s = (0.97 + 0.03 * rise) * (1 + 0.03 * k);
          return (
            <div
              key={id}
              style={{
                position: 'absolute',
                left: col * (TW + GAP),
                top: row * (TH + GAP),
                width: TW,
                height: TH,
                opacity,
                transform: `translate3d(0, ${((1 - rise) * 36).toFixed(2)}px, 0) scale(${s.toFixed(5)})`,
                // grow inward: the grid's outer edges stay put under the spotlight
                transformOrigin: `${['0%', '50%', '100%'][col]} ${row === 0 ? '0%' : '100%'}`,
                zIndex: k > 0.01 ? 2 : 1,
              }}
            >
              <TileCard
                strings={copy.tiles[id]}
                still={assets.themed(VISUALS[id].still)}
                shots={shotsFor(id, assets)}
                k={k}
              />
            </div>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};

/**
 * A tile's look on each canvas: its rim (at rest, in the spotlight), drop shadow and spotlight
 * glow, the top sheen and the spotlight's lift. On the light canvas the tile is a white card with
 * a hairline and a soft shadow, and the sheen (white on white) is left out.
 */
interface TileLook {
  rim: [string, string];
  shadow: string;
  glow: (k: number) => string;
  sheen: (k: number) => number;
  lift: (k: number) => string;
}
const TILE_LOOK: Record<Mode, TileLook> = {
  dark: {
    rim: ['rgba(255,255,255,0.075)', 'rgba(142,155,255,0.42)'],
    shadow: alpha('#000000', 0.7),
    glow: (k: number) => alpha('#5563F0', 0.38 * k),
    sheen: (k: number) => 0.035 + 0.025 * k,
    lift: (k: number) => alpha('#8E9BFF', 0.033 * k),
  },
  light: {
    rim: ['rgba(20,26,38,0.09)', 'rgba(79,93,228,0.5)'],
    shadow: alpha('#0F172A', 0.16),
    glow: (k: number) => alpha('#4F5DE4', 0.22 * k),
    sheen: () => 0,
    lift: (k: number) => alpha('#4F5DE4', 0.025 * k),
  },
};

/** One bento tile; `k` (0..1) is how much it is in the spotlight. */
const TileCard: React.FC<{strings: TileStrings; still: string; shots: Shot[]; k: number}> = ({
  strings,
  still,
  shots,
  k,
}) => {
  const p = usePalette();
  const look = useThemed(TILE_LOOK);
  const rim = mix(look.rim[0], look.rim[1], k);
  const sheen = look.sheen(k);
  return (
    <div
      style={{
        position: 'absolute',
        inset: 0,
        borderRadius: 20,
        overflow: 'hidden',
        // the still's own surface, so a shot's box never shows; the spotlight lifts the tile and
        // its UI together with the overlay below
        background: p.surface,
        boxShadow: [
          `0 24px 48px -24px ${look.shadow}`,
          k > 0 ? `0 30px 80px -30px ${look.glow(k)}` : '',
        ]
          .filter(Boolean)
          .join(', '),
        fontFamily: FONT.sans,
      }}
    >
      {/* soft top sheen */}
      {sheen > 0 && (
        <div
          style={{
            position: 'absolute',
            inset: 0,
            background: `linear-gradient(180deg, ${alpha('#FFFFFF', sheen)} 0%, ${alpha('#FFFFFF', 0)} 42%)`,
          }}
        />
      )}
      <div style={{position: 'absolute', left: 0, right: 0, top: IMG_TOP, height: IMG_H}}>
        <Shots still={still} shots={shots} />
      </div>
      {/* spotlight lift (≈ #16181D → #1A1D24 on dark), over the UI too */}
      {k > 0 && <div style={{position: 'absolute', inset: 0, background: look.lift(k)}} />}
      <div style={{position: 'absolute', left: PAD, right: PAD, top: 24}}>
        <div
          style={{
            ...TYPE.headline,
            fontSize: 30,
            lineHeight: 1.1,
            letterSpacing: '-0.02em',
            color: p.primary,
          }}
        >
          <TextRuns text={strings.title} />
        </div>
        <div
          style={{
            ...TYPE.sub,
            fontSize: 20,
            lineHeight: 1.35,
            color: p.secondary,
            marginTop: 7,
            textWrap: 'balance',
          }}
        >
          <TextRuns text={strings.caption} />
        </div>
      </div>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: 20,
          boxShadow: `inset 0 0 0 1px ${rim}`,
          pointerEvents: 'none',
        }}
      />
    </div>
  );
};

/** A tile's shots, vertically centred as a group in the image area. */
const Shots: React.FC<{still: string; shots: Shot[]}> = ({still, shots}) => {
  const boxH = (sh: Shot) => sh.rect.h * sh.scale;
  const groupH = Math.max(...shots.map((sh) => (sh.dy ?? 0) + boxH(sh)));
  const top = (IMG_H - groupH) / 2;
  return (
    <>
      {shots.map((sh, i) => {
        const w = sh.rect.w ?? (IMG_W - sh.left) / sh.scale;
        return (
          <div key={i} style={{position: 'absolute', left: sh.left, top: top + (sh.dy ?? 0)}}>
            <SourceCrop
              name={still}
              rect={{...sh.rect, w}}
              scale={sh.scale}
              fade={sh.fade ?? SHOT_FADE}
            />
          </div>
        );
      })}
    </>
  );
};
