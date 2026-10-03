import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { formatMessage, languageTag, localizePage, markupParts, t } from '../src/lib/i18n.js';

const messages = {
  sent_to: {
    message: 'Sent to $NAME$',
    placeholders: { name: { content: '$1' } },
  },
  progress: {
    message: '$DONE$ of $TOTAL$ · $Speed$, $$5 off',
    placeholders: {
      done: { content: '$1' },
      total: { content: '$2' },
      speed: { content: '$3' },
    },
  },
  product: {
    message: 'Get $app$',
    placeholders: { APP: { content: 'Ketch' } },
  },
};

describe('formatMessage', () => {
  test('fills named placeholders from the substitutions they stand for', () => {
    assert.equal(formatMessage(messages, 'sent_to', ['NAS']), 'Sent to NAS');
    assert.equal(
      formatMessage(messages, 'progress', ['1 MB', '2 MB', '3 KB/s']),
      '1 MB of 2 MB · 3 KB/s, $5 off',
    );
  });

  test('placeholder names ignore case and may hold fixed text', () => {
    assert.equal(formatMessage(messages, 'product'), 'Get Ketch');
  });

  test('missing substitutions are left empty and substitutions are not read again', () => {
    assert.equal(formatMessage(messages, 'sent_to'), 'Sent to ');
    assert.equal(formatMessage(messages, 'sent_to', ['$1 $NAME$']), 'Sent to $1 $NAME$');
  });

  test('an unknown key gives nothing', () => {
    assert.equal(formatMessage(messages, 'missing'), '');
    assert.equal(formatMessage(messages, 'toString'), '');
  });
});

describe('t outside a browser', () => {
  test('reads the English messages', () => {
    assert.equal(t('popup_add_button'), 'Download');
    assert.equal(t('notify_sent_to', 'NAS'), 'Sent to NAS');
    assert.equal(t('error_http_status', 404), 'Ketch answered with HTTP 404');
    assert.equal(languageTag(), 'en');
  });

  test('falls back to the key for an unknown message', () => {
    assert.equal(t('no_such_message'), 'no_such_message');
  });
});

describe('markupParts', () => {
  test('splits bold text and code from plain text', () => {
    assert.deepEqual(markupParts('Turn on <b>Server</b>, or run <code>ketch server</code>.'), [
      { text: 'Turn on ' },
      { text: 'Server', tag: 'b' },
      { text: ', or run ' },
      { text: 'ketch server', tag: 'code' },
      { text: '.' },
    ]);
  });

  test('keeps other tags and unclosed tags as text', () => {
    assert.deepEqual(markupParts('<img src=x onerror=alert(1)> <b>bold'), [
      { text: '<img src=x onerror=alert(1)> <b>bold' },
    ]);
  });
});

describe('localizePage', () => {
  /** Just enough of a DOM: elements with attributes, found by attribute name. */
  function fakeElement(tag, attributes = {}) {
    return {
      tag,
      attributes: { ...attributes },
      textContent: 'English',
      children: [],
      getAttribute(name) {
        return this.attributes[name] ?? null;
      },
      setAttribute(name, value) {
        this.attributes[name] = value;
      },
      replaceChildren(...children) {
        this.children = children;
      },
    };
  }

  function fakeRoot(elements, templates = []) {
    return {
      documentElement: { lang: 'en' },
      createElement: (tag) => fakeElement(tag),
      querySelectorAll(selector) {
        if (selector === 'template') return templates;
        const name = /^\[([\w-]+)\]$/.exec(selector)[1];
        return elements.filter((element) => name in element.attributes);
      },
    };
  }

  test('fills text, markup and attributes, also inside templates', () => {
    const label = fakeElement('span', { 'data-i18n': 'popup_capture' });
    const help = fakeElement('dd', { 'data-i18n-markup': 'options_help_command_line_detail' });
    const input = fakeElement('input', {
      'data-i18n-placeholder': 'popup_add_placeholder',
      'data-i18n-aria-label': 'popup_add_label',
    });
    const inTemplate = fakeElement('button', { 'data-i18n': 'instance_remove' });
    const template = { content: { ...fakeRoot([inTemplate]), documentElement: undefined } };
    const root = fakeRoot([label, help, input], [template]);

    localizePage(root);

    assert.equal(label.textContent, 'Capture browser downloads');
    assert.equal(input.attributes.placeholder, 'Paste a link or magnet link');
    assert.equal(input.attributes['aria-label'], 'Link to download');
    assert.equal(inTemplate.textContent, 'Remove');
    assert.equal(root.documentElement.lang, 'en');
    const [, code] = help.children;
    assert.equal(help.children[0], 'To use ');
    assert.equal(code.tag, 'code');
    assert.equal(code.textContent, 'ketch server');
  });
});
