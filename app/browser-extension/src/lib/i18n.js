/**
 * Text in the browser's language, from `_locales/<language>/messages.json`. The browser picks
 * the language and falls back to English, the manifest's `default_locale`, by itself.
 *
 * Outside a browser, as in the unit tests, the English messages are read from disk instead.
 */

import { ext } from './ext.js';

/** Tags that messages shown with `data-i18n-markup` may use. */
const MARKUP = /<(b|code)>([^<]*)<\/\1>/g;

/** Attributes filled from `data-i18n-<attribute>`. */
const ATTRIBUTES = ['title', 'placeholder', 'aria-label'];

let englishMessages;

/**
 * Returns the message `key`, with its placeholders filled from `substitutions` in order.
 *
 * @param {string} key a message name from `_locales/en/messages.json`
 * @param {...(string | number)} substitutions values for the placeholders `$1` to `$9`
 * @returns {string} the key itself when there is no such message
 */
export function t(key, ...substitutions) {
  const values = substitutions.map(String);
  const text = ext?.i18n
    ? ext.i18n.getMessage(key, values)
    : formatMessage(englishMessages ??= readEnglishMessages(), key, values);
  return text || key;
}

/** @returns {string} the BCP 47 tag of the language the messages are in, such as `pt-BR` */
export function languageTag() {
  return t('language_tag');
}

/**
 * Resolves `key` in `messages` the way `i18n.getMessage` does: named placeholders such as
 * `$NAME$` take their `content`, then `$1` to `$9` take the substitutions and `$$` becomes `$`.
 *
 * @param {Record<string, { message: string, placeholders?: Record<string, { content: string }> }>}
 *   messages the contents of a `messages.json`
 * @param {string} key
 * @param {string[]} [substitutions]
 * @returns {string} empty when there is no such message
 */
export function formatMessage(messages, key, substitutions = []) {
  const entry = Object.hasOwn(messages, key) ? messages[key] : undefined;
  if (!entry) return '';
  const placeholders = new Map(Object.entries(entry.placeholders ?? {})
    .map(([name, placeholder]) => [name.toLowerCase(), placeholder.content]));
  return entry.message
    .replace(/\$([\w@]+)\$/g, (match, name) => placeholders.get(name.toLowerCase()) ?? match)
    .replace(/\$(\$|[1-9])/g, (match, which) => {
      return which === '$' ? '$' : substitutions[Number(which) - 1] ?? '';
    });
}

/**
 * Splits a message with markup into text and the parts in `<b>` or `<code>`. Anything else
 * stays text, so a message can never add other elements to a page.
 *
 * @param {string} text
 * @returns {{ text: string, tag?: 'b' | 'code' }[]}
 */
export function markupParts(text) {
  const parts = [];
  let end = 0;
  for (const match of text.matchAll(MARKUP)) {
    if (match.index > end) parts.push({ text: text.slice(end, match.index) });
    parts.push({ text: match[2], tag: match[1] });
    end = match.index + match[0].length;
  }
  if (end < text.length) parts.push({ text: text.slice(end) });
  return parts;
}

/**
 * Fills a page with messages: an element's text from `data-i18n`, or from `data-i18n-markup`
 * for a message with bold text or code, and its `title`, `placeholder` and `aria-label` from
 * `data-i18n-title`, `data-i18n-placeholder` and `data-i18n-aria-label`. Templates are filled
 * too, so the elements cloned from them later need nothing else.
 *
 * The English text stays in the HTML, so the page reads as it will look.
 *
 * @param {Document | DocumentFragment} [root]
 */
export function localizePage(root = document) {
  const doc = root.ownerDocument ?? root;
  for (const element of root.querySelectorAll('[data-i18n]')) {
    element.textContent = t(element.getAttribute('data-i18n'));
  }
  for (const element of root.querySelectorAll('[data-i18n-markup]')) {
    const parts = markupParts(t(element.getAttribute('data-i18n-markup')));
    element.replaceChildren(...parts.map(({ text, tag }) => {
      if (!tag) return text;
      const child = doc.createElement(tag);
      child.textContent = text;
      return child;
    }));
  }
  for (const attribute of ATTRIBUTES) {
    for (const element of root.querySelectorAll(`[data-i18n-${attribute}]`)) {
      element.setAttribute(attribute, t(element.getAttribute(`data-i18n-${attribute}`)));
    }
  }
  for (const template of root.querySelectorAll('template')) localizePage(template.content);
  if (root.documentElement) root.documentElement.lang = languageTag();
}

/** Reads the English messages in Node, which has no `i18n` API. */
function readEnglishMessages() {
  const fs = globalThis.process?.getBuiltinModule?.('node:fs');
  if (!fs) return {};
  try {
    const file = new URL('../_locales/en/messages.json', import.meta.url);
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return {};
  }
}
