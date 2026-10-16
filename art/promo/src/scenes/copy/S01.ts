// S01 Intro: every word the scene shows, per locale. The scene file only lays it out and times
// it, so copy changes happen here.
//
// The tagline is one line in every locale (the scene centres it under the lock-up in a 1500 px
// column); keep a new one short enough not to wrap, and check a still (README.md, "Single
// frames"). The lock-up with the tagline is on screen from frame 0, so it is also the films'
// thumbnail and cover art (README.md, "Thumbnails and cover art"): render the films after a change.
import {defineCopy} from '../../locale';

export interface S01Strings {
  /** Under the lock-up. The end card (S12) repeats it. */
  tagline: string;
}

export const S01_COPY = defineCopy<S01Strings>({
  en: {
    tagline: 'A fast, open-source download manager for every device you own.',
  },
  'zh-Hans': {
    tagline: '又快又开源的下载工具，你的每台设备都能用。',
  },
});

/** Text that is the same in every locale. */
export const S01_SHARED = {
  /**
   * The app's name, as the wordmark. LAYOUT: S01Intro.tsx centres the lock-up on the ink of this
   * exact word (WORD_INK_W, WORD_INK_LEFT); re-measure those if it ever changes.
   */
  wordmark: 'Ketch',
} as const;
