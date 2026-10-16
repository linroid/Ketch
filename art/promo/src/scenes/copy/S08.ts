// S08 AI Discover: every word the scene shows, per locale. The scene file only lays it out and
// times it, so copy changes happen here. The Discover page itself is a clip of the real app
// (PromoSnapshots.kt renders its query, steps and results per locale).
import {defineCopy} from '../../locale';

export interface S08Strings {
  /** Discover is a Preview (STORYBOARD.md, "Do not claim"): the eyebrow always says so. */
  eyebrow: string;
  headline: string;
  sub: string;
  /**
   * The two qualifiers, top right. The first states what holds in every page-access mode
   * (allow, ask per site, ask every time): the clip's scripted agent opens a page without the
   * approval card, so "Asks before it opens a site" would contradict the picture. The second is
   * bring-your-own-model: providers as plain text, never logos, never "free AI".
   */
  chips: readonly [string, string];
}

export const S08_COPY = defineCopy<S08Strings>({
  en: {
    eyebrow: 'AI Discover · Preview',
    headline: 'Just describe it.',
    sub: 'An agent searches the web, checks the links and ranks the downloads it finds.',
    chips: ['You choose which sites it opens', 'Your model: OpenAI, Anthropic, Gemini or Ollama'],
  },
  'zh-Hans': {
    eyebrow: 'AI 搜索 · 预览版',
    headline: '想要什么，直接说。',
    sub: 'AI 帮你搜网页、查链接，再把靠谱的下载排好序。',
    chips: ['开哪些网站，你说了算', '模型随你选：OpenAI、Anthropic、Gemini、Ollama'],
  },
});
