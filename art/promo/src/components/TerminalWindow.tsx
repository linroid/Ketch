// Terminal window in the storyboard's style, with a tiny script API: typed commands, output
// lines that appear at given frames, live lines computed from the frame, and a blinking block
// cursor. Scenes that size a card around its lines use terminalHeight, not the chrome's numbers.
import React from 'react';
import {FONT, TERMINAL, TYPE, palette, windowShadow, type Mode} from '../theme';
import {useTheme} from '../theme-context';
import {useDesignFrame} from '../time';

/** A command typed after the prompt at `cps` characters per frame. */
export interface TermCommand {
  at: number;
  cmd: string;
  /** Characters per frame. Default 1.5. */
  cps?: number;
  /** Prompt text. Default '~ ❯ '. */
  prompt?: string;
}

/** Output that appears at `at`. `text` may be a function of frames since it appeared. */
export interface TermOutput {
  at: number;
  text: React.ReactNode | ((t: number, frame: number) => React.ReactNode);
  color?: string;
}

/** An empty prompt waiting for input (shows the blinking cursor). */
export interface TermPrompt {
  at: number;
  prompt?: string | true;
}

export type TermLine = TermCommand | TermOutput | TermPrompt;

const isCommand = (l: TermLine): l is TermCommand => 'cmd' in l;
const isOutput = (l: TermLine): l is TermOutput => 'text' in l;

export const DEFAULT_PROMPT = '~ ❯ ';
export const DEFAULT_CPS = 1.5;

/** The window's chrome at scale 1, the storyboard's terminal style (px). */
export const TERMINAL_METRICS = {
  /** Title bar height; a 1 px rule under it. */
  bar: 38,
  /** Body padding: top, sides, bottom. */
  padTop: 16,
  padX: 20,
  padBottom: 18,
  /** Text size and line height (a multiplier). */
  fontSize: 14,
  lineHeight: 1.6,
  radius: 12,
  /** Title text size. */
  titleSize: 13,
} as const;

/** Default chrome scale of TerminalWindow. */
export const TERMINAL_SCALE = 1.6;

/**
 * Outer height of a TerminalWindow showing `lines` lines at `scale`: its title bar and rule, the
 * body's padding and the lines. Pass `fontSize` and `lineHeight` when the window gets them.
 */
export const terminalHeight = (
  lines: number,
  scale: number = TERMINAL_SCALE,
  {
    fontSize = TERMINAL_METRICS.fontSize * scale,
    lineHeight = TERMINAL_METRICS.lineHeight,
  }: {fontSize?: number; lineHeight?: number} = {},
): number =>
  TERMINAL_METRICS.bar * scale +
  1 +
  (TERMINAL_METRICS.padTop + TERMINAL_METRICS.padBottom) * scale +
  lines * fontSize * lineHeight;

/** Frames it takes to type `text` at `cps` characters per frame. */
export const typingDuration = (text: string, cps = DEFAULT_CPS): number =>
  Math.ceil(text.length / cps);

/** Frame at which a command line finishes typing. */
export const typedAt = (line: TermCommand): number => line.at + typingDuration(line.cmd, line.cps);

export interface TerminalWindowProps {
  lines: TermLine[];
  /** Outer width in px. Default 960. */
  width?: number;
  /** Outer height in px. Default: fits the lines. */
  height?: number;
  /** Centred title. Default 'ketch'. */
  title?: string;
  /** Chrome scale: 1 = TERMINAL_METRICS, the storyboard's metrics. Default TERMINAL_SCALE. */
  scale?: number;
  /** Text size override in px. Default TERMINAL_METRICS.fontSize × scale. */
  fontSize?: number;
  /** Line height multiplier. Default TERMINAL_METRICS.lineHeight. */
  lineHeight?: number;
  /** Keep only the last N visible lines (scrolls like a real terminal). */
  maxLines?: number;
  /** Cursor blink period in frames (on half, off half). Default 30 (one blink per second). */
  blink?: number;
  /**
   * The window's own colours: dark (the default) in every film, as a terminal is; its shadow
   * suits the film's canvas.
   */
  mode?: Mode;
  shadow?: boolean;
  frame?: number;
  style?: React.CSSProperties;
}

export const TerminalWindow: React.FC<TerminalWindowProps> = ({
  lines,
  width = 960,
  height,
  title = 'ketch',
  scale = TERMINAL_SCALE,
  fontSize,
  lineHeight = TERMINAL_METRICS.lineHeight,
  maxLines,
  blink = 30,
  mode = 'dark',
  shadow = true,
  frame: f,
  style,
}) => {
  const current = useDesignFrame();
  const frame = f ?? current;
  const s = scale;
  const M = TERMINAL_METRICS;
  const fs = fontSize ?? M.fontSize * s;
  const p = palette(mode);
  const canvas = useTheme();
  const dark = mode === 'dark';
  const body = dark ? TERMINAL.body : '#FFFFFF';
  const bar = dark ? TERMINAL.bar : '#F3F4F7';
  const text = dark ? TERMINAL.text : '#141A26';
  const promptColor = dark ? TERMINAL.prompt : '#3C47B7';
  const cursorColor = dark ? TERMINAL.cursor : '#4B5466';

  const visible = lines.filter((l) => frame >= l.at);
  const shown = maxLines ? visible.slice(-maxLines) : visible;
  const last = visible[visible.length - 1];

  // Cursor: solid while typing, blinking when idle on a command or prompt line.
  const typing = last && isCommand(last) && frame < typedAt(last);
  const idleSince = last ? (isCommand(last) ? typedAt(last) : last.at) : 0;
  const blinkOn = typing || Math.floor((frame - idleSince) / (blink / 2)) % 2 === 0;
  const cursorLine = last && !isOutput(last) ? last : undefined;

  const cursor = (
    <span
      style={{
        display: 'inline-block',
        width: '0.6em',
        height: '1.18em',
        verticalAlign: '-0.22em',
        background: cursorColor,
        opacity: blinkOn ? 0.9 : 0,
        marginLeft: 1,
      }}
    />
  );

  const rows = shown.map((l, i) => {
    let content: React.ReactNode;
    if (isCommand(l)) {
      const n = Math.min(l.cmd.length, Math.floor((frame - l.at) * (l.cps ?? DEFAULT_CPS)));
      content = (
        <>
          <span style={{color: promptColor}}>{l.prompt ?? DEFAULT_PROMPT}</span>
          <span style={{color: text}}>{l.cmd.slice(0, n)}</span>
          {l === cursorLine && cursor}
        </>
      );
    } else if (isOutput(l)) {
      const v = typeof l.text === 'function' ? l.text(frame - l.at, frame) : l.text;
      content = <span style={{color: l.color ?? text}}>{v}</span>;
    } else {
      content = (
        <>
          <span style={{color: promptColor}}>
            {typeof l.prompt === 'string' ? l.prompt : DEFAULT_PROMPT}
          </span>
          {l === cursorLine && cursor}
        </>
      );
    }
    return (
      <div
        key={i}
        style={{
          whiteSpace: 'pre',
          height: fs * lineHeight,
          lineHeight: `${fs * lineHeight}px`,
          overflow: 'hidden',
        }}
      >
        {content}
      </div>
    );
  });

  const barH = M.bar * s;
  return (
    <div
      style={{
        position: 'relative',
        width,
        height,
        borderRadius: M.radius * s,
        background: body,
        boxShadow: shadow ? windowShadow(canvas, Math.min(1.4, width / 1280 + 0.3)) : undefined,
        overflow: 'hidden',
        fontFamily: FONT.mono,
        flex: 'none',
        ...style,
      }}
    >
      <div
        style={{
          height: barH,
          background: bar,
          position: 'relative',
          display: 'flex',
          alignItems: 'center',
          borderBottom: `1px solid ${dark ? 'rgba(255,255,255,0.05)' : '#E2E6EE'}`,
        }}
      >
        <div style={{display: 'flex', gap: 8 * s, marginLeft: 14 * s}}>
          {TERMINAL.lights.map((c) => (
            <div
              key={c}
              style={{
                width: 12 * s,
                height: 12 * s,
                borderRadius: '50%',
                background: c,
                opacity: 0.6,
              }}
            />
          ))}
        </div>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontFamily: FONT.sans,
            fontWeight: 500,
            fontSize: M.titleSize * s,
            color: dark ? TERMINAL.title : '#6E7687',
          }}
        >
          {title}
        </div>
      </div>
      <div
        style={{
          ...TYPE.mono,
          fontSize: fs,
          color: text,
          padding: `${M.padTop * s}px ${M.padX * s}px ${M.padBottom * s}px`,
        }}
      >
        {rows}
      </div>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          borderRadius: M.radius * s,
          boxShadow: `inset 0 0 0 1px ${p.windowRim}`,
          pointerEvents: 'none',
        }}
      />
    </div>
  );
};

/** Text progress bar for live lines, e.g. bar(0.42, 20) -> "████████░░░░░░░░░░░░". */
export const textBar = (fraction: number, cells = 20, full = '█', empty = '░'): string => {
  const n = Math.round(Math.min(1, Math.max(0, fraction)) * cells);
  return full.repeat(n) + empty.repeat(cells - n);
};
