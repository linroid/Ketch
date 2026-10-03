import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { describe, test } from 'node:test';

const src = new URL('../src/', import.meta.url);
const read = (path) => readFileSync(new URL(path, src), 'utf8');
const readMessages = (locale) => JSON.parse(read(`_locales/${locale}/messages.json`));

const english = readMessages('en');
const translations = readdirSync(new URL('_locales/', src))
  .filter((locale) => locale !== 'en')
  .map((locale) => [locale, readMessages(locale)]);

/** `$NAME$` references in a message, lower case like the placeholder names they refer to. */
function placeholderNames(message) {
  return [...message.matchAll(/\$([\w@]+)\$/g)].map((match) => match[1].toLowerCase());
}

/** Placeholder names and the substitution each stands for. */
function placeholderContents(entry) {
  return Object.fromEntries(Object.entries(entry.placeholders ?? {})
    .map(([name, placeholder]) => [name.toLowerCase(), placeholder.content]));
}

/** The markup a message uses, tag by tag, with the text inside `<code>`. */
function markup(message) {
  return [...message.matchAll(/<code>[^<]*<\/code>|<\/?[a-z]+>/g)].map((match) => match[0]).sort();
}

describe('messages', () => {
  test('English describes every message', () => {
    for (const [key, entry] of Object.entries(english)) {
      assert.match(key, /^[a-z][a-z0-9_]*$/, key);
      assert.ok(entry.message, key);
      assert.ok(entry.description, `${key} has no description`);
    }
  });

  test('every language has the messages English has, and only those', () => {
    for (const [locale, messages] of translations) {
      assert.deepEqual(Object.keys(messages).sort(), Object.keys(english).sort(), locale);
    }
  });

  test('placeholders are defined, used and the same in every language', () => {
    for (const [locale, messages] of [['en', english], ...translations]) {
      for (const [key, entry] of Object.entries(messages)) {
        const where = `${locale} ${key}`;
        const defined = placeholderContents(entry);
        // Browsers refuse to load a message that uses an undefined placeholder.
        assert.deepEqual(
          [...new Set(placeholderNames(entry.message))].sort(),
          Object.keys(defined).sort(),
          where,
        );
        assert.deepEqual(defined, placeholderContents(english[key]), where);
        assert.doesNotMatch(entry.message.replace(/\$[\w@]+\$/g, ''), /\$/, where);
      }
    }
    for (const [key, entry] of Object.entries(english)) {
      const contents = Object.values(placeholderContents(entry)).sort();
      assert.deepEqual(contents, contents.map((_, index) => `$${index + 1}`), key);
    }
  });

  test('translations keep the markup and the code of English', () => {
    for (const [locale, messages] of translations) {
      for (const [key, entry] of Object.entries(messages)) {
        assert.deepEqual(markup(entry.message), markup(english[key].message), `${locale} ${key}`);
      }
    }
  });

  test('translations leave descriptions and examples to English', () => {
    for (const [locale, messages] of translations) {
      for (const [key, entry] of Object.entries(messages)) {
        assert.deepEqual(Object.keys(entry).filter((it) => it !== 'placeholders'), ['message'],
          `${locale} ${key}`);
        for (const placeholder of Object.values(entry.placeholders ?? {})) {
          assert.deepEqual(Object.keys(placeholder), ['content'], `${locale} ${key}`);
        }
      }
    }
  });

  test('messages have no stray whitespace or three-dot ellipses', () => {
    for (const [locale, messages] of [['en', english], ...translations]) {
      for (const [key, { message }] of Object.entries(messages)) {
        assert.equal(message, message.trim(), `${locale} ${key}`);
        assert.doesNotMatch(message, / {2}|\n|\.\.\./, `${locale} ${key}`);
      }
    }
  });

  test('each language names its own tag', () => {
    for (const [locale, messages] of [['en', english], ...translations]) {
      const tag = messages.language_tag.message;
      assert.equal(tag, locale.replace('_', '-'));
      assert.deepEqual(Intl.getCanonicalLocales(tag), [tag]);
    }
  });

  test('the name and description fit the stores', () => {
    for (const [locale, messages] of [['en', english], ...translations]) {
      assert.ok(messages.extension_name.message.length <= 45, locale);
      assert.ok(messages.extension_description.message.length <= 132, locale);
    }
  });
});

describe('message keys in the sources', () => {
  const files = (dir, extension) => readdirSync(new URL(dir, src), { recursive: true })
    .filter((path) => path.endsWith(extension))
    .map((path) => `${dir}${path}`);
  const scripts = files('', '.js').map((path) => [path, read(path)]);
  const pages = files('', '.html').map((path) => [path, read(path)]);
  const manifest = read('manifest.json');

  /** Calls of `t('key', ...)` with the number of substitutions each passes. */
  function messageCalls(source) {
    const calls = [];
    for (const match of source.matchAll(/\bt\(\s*'([\w@]+)'/g)) {
      let depth = 0;
      let commas = 0;
      let quote = null;
      for (let index = match.index + 1; index < source.length; index++) {
        const char = source[index];
        if (quote) {
          if (char === '\\') index++;
          else if (char === quote) quote = null;
        } else if (`'"\``.includes(char)) {
          quote = char;
        } else if ('([{'.includes(char)) {
          depth++;
        } else if (')]}'.includes(char)) {
          if (--depth === 0) break;
        } else if (char === ',' && depth === 1) {
          commas++;
        }
      }
      calls.push({ key: match[1], substitutions: commas });
    }
    return calls;
  }

  /**
   * Messages a page shows: the key, where it goes (`text`, `markup` or an attribute) and the
   * English text there.
   */
  function pageMessages(html) {
    const found = [];
    for (const tag of html.matchAll(/<([a-z0-9]+)\b([^>]*)>/g)) {
      const attributes = Object.fromEntries(
        [...tag[2].matchAll(/([\w-]+)="([^"]*)"/g)].map((match) => [match[1], match[2]]));
      for (const [name, key] of Object.entries(attributes)) {
        const match = /^data-i18n(?:-(.+))?$/.exec(name);
        if (!match) continue;
        const kind = match[1] ?? 'text';
        let text;
        if (kind === 'text' || kind === 'markup') {
          const start = tag.index + tag[0].length;
          text = html.slice(start, html.indexOf(`</${tag[1]}>`, start));
        } else {
          text = attributes[kind];
        }
        found.push({ key, kind, text: text?.replace(/\s+/g, ' ').trim() });
      }
    }
    return found;
  }

  const used = new Set([
    ...scripts.flatMap(([, source]) => messageCalls(source).map((call) => call.key)),
    ...pages.flatMap(([, html]) => pageMessages(html).map((found) => found.key)),
    ...[...manifest.matchAll(/__MSG_(\w+)__/g)].map((match) => match[1]),
  ]);

  test('every key used exists in English', () => {
    assert.ok(used.size > 0);
    for (const key of used) assert.ok(Object.hasOwn(english, key), `${key} is not in en`);
  });

  test('every English message is used', () => {
    for (const key of Object.keys(english)) assert.ok(used.has(key), `${key} is unused`);
  });

  test('scripts pass one substitution for each placeholder', () => {
    for (const [path, source] of scripts) {
      for (const { key, substitutions } of messageCalls(source)) {
        const placeholders = Object.keys(english[key]?.placeholders ?? {}).length;
        assert.equal(substitutions, placeholders, `${path}: t('${key}')`);
      }
    }
  });

  test('pages show the English text of their messages', () => {
    for (const [path, html] of pages) {
      const found = pageMessages(html);
      assert.ok(found.length > 0, path);
      for (const { key, kind, text } of found) {
        const entry = english[key];
        assert.ok(!entry.placeholders, `${path}: ${key} has placeholders`);
        assert.equal(kind === 'markup', markup(entry.message).length > 0, `${path}: ${key}`);
        assert.equal(text, entry.message, `${path}: ${key}`);
      }
    }
  });

  test('the manifest takes its name and its locale from the messages', () => {
    const parsed = JSON.parse(manifest);
    assert.equal(parsed.default_locale, 'en');
    assert.equal(parsed.name, '__MSG_extension_name__');
    assert.equal(parsed.description, '__MSG_extension_description__');
    assert.equal(parsed.action.default_title, '__MSG_extension_action_title__');
  });
});
