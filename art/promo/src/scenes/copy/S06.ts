// S06 Cross-platform: every word the scene shows, per locale, plus the text that stays the same
// in every locale (the terminal script and the browsers' URLs). The scene file only lays it out
// and times it, so copy changes happen here.
//
// Line breaks (`\n`) are part of the copy. Each headline is one line; each line of `subB` must
// fit the sub's column (SUB_WIDTH in S06CrossPlatform.tsx, 860 px), so check a still of Beat B
// in every locale after changing it (README.md, "Single frames").
import {FACTS} from '../../facts';
import {defineCopy} from '../../locale';

export interface S06Strings {
  eyebrow: string;
  /** Beat A (devices). One line. */
  headlineA: string;
  /** Platform pills under Beat A's stage. */
  platforms: readonly string[];
  /** Tertiary footnote under the iPhone (the storyboard's do-not-claim rule: no App Store). */
  footnote: string;
  /** Beat B (command line, server, extension, web app). One line. */
  headlineB: string;
  /** Two lines, broken with `\n`. */
  subB: string;
  /** Surface pills under Beat B's stage, in the order the sub names them. */
  surfaces: readonly string[];
}

export const S06_COPY = defineCopy<S06Strings>({
  en: {
    eyebrow: 'Cross-platform',
    headlineA: 'Every device you own.',
    platforms: ['macOS', 'Windows', 'Linux', 'Android', 'iOS'],
    footnote: 'iOS app: build from source.',
    headlineB: 'And wherever you work.',
    // break after "NAS," so "for your NAS" stays on one line
    subB: 'A native command line, a headless server for your NAS,\na browser extension and a web app.',
    surfaces: ['Command line', 'Server', 'Browser extension', 'Web app'],
  },
  'zh-Hans': {
    eyebrow: '跨平台',
    headlineA: '你的每台设备，都能用。',
    // product names stay as they are
    platforms: ['macOS', 'Windows', 'Linux', 'Android', 'iOS'],
    footnote: 'iOS 版需要自己从源码编译。',
    headlineB: '在哪干活，都顺手。',
    // break after the server, so the list splits into "what runs where" and "in the browser"
    subB: '原生命令行、给 NAS 用的服务器、\n浏览器扩展，还有网页版。',
    surfaces: ['命令行', '服务器', '浏览器扩展', '网页版'],
  },
});

/**
 * `ketch server`'s default port (ServerConfig.port in config/), which the web app's address and
 * the server's banner both show.
 */
const SERVER_PORT = 8642;

/** The brand-neutral site the terminal downloads from and the extension's page shows. */
const DOWNLOAD_HOST = 'downloads.example.com';
/** The file the terminal downloads. */
const DOWNLOAD_FILE = 'runtime-22.20-arm64.tar.gz';

/**
 * Text that is the same in every locale. The CLI is not localized, so the terminal stays English.
 */
export const S06_SHARED = {
  /**
   * The web app's address: `ketch server` serves the web UI on its port. Never the project's
   * website: the owner asked that no shot show linroid.com/Ketch.
   */
  webUrl: `nas.local:${SERVER_PORT}`,
  /** The page behind the extension popup. */
  extensionUrl: DOWNLOAD_HOST,
  /**
   * The terminal's transcript: two commands and their output, line for line as the real CLI
   * prints them (cli/src/main/kotlin/com/linroid/ketch/cli/Main.kt), abridged to fit the window:
   * `ketch <url>` also prints a header (`Downloading:`, `Destination:`, `Max concurrent:`) before
   * the progress line and a `Download completed: <path>` summary after `[Completed]`, and the
   * server's banner goes on. Without a destination the CLI saves into the working folder (`~`, by
   * the prompt) and prints the path relative to it, which is the bare file name. The progress line
   * is drawn live by cliProgressLine (parts/c-parts.tsx) from `sizeMiB` and `speedMiBps`.
   */
  terminal: {
    download: {
      command: `ketch https://${DOWNLOAD_HOST}/${DOWNLOAD_FILE}`,
      /** Size and speed in MiB (the CLI prints binary units as "MB"). */
      sizeMiB: 49,
      speedMiBps: 10.2,
      /** The line the CLI prints when the download finishes: `[Completed] Saved to <path>`. */
      completed: `[Completed] Saved to ${DOWNLOAD_FILE}`,
    },
    server: {
      command: 'ketch server',
      /**
       * The banner's first three lines, with the host a NAS listens on and the default port. The
       * labels are padded as the CLI pads them.
       */
      banner: [
        `Ketch Server v${FACTS.release}`,
        '  Host:          0.0.0.0',
        `  Port:          ${SERVER_PORT}`,
      ],
    },
  },
} as const;
