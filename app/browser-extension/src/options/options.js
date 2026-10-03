import { withEndpoint } from '../lib/connection.js';
import { ext } from '../lib/ext.js';
import { describeStatus, failureHint, withHint } from '../lib/format.js';
import { localizePage, t } from '../lib/i18n.js';
import { KetchClient } from '../lib/ketch-client.js';
import {
  isLoopbackUrl,
  loadSettings,
  localInstance,
  newInstanceId,
  normalizeServerUrl,
  onSettingsChanged,
  parseHostList,
  saveSettings,
} from '../lib/settings.js';

const CHECK_TIMEOUT_MS = 4_000;
const ALL_SITES = { origins: ['<all_urls>'] };

const $ = (id) => document.getElementById(id);
// Before anything clones the instance card templates.
localizePage();
const toggles = {
  interceptDownloads: $('intercept-downloads'),
  forwardCookies: $('forward-cookies'),
  captureMagnetLinks: $('capture-magnet-links'),
  notifications: $('notifications'),
};

/**
 * The page's form is the source of truth: every change is collected from it and saved. A server
 * card keeps the last valid address it was saved with in `dataset.savedUrl`; one without is a
 * draft that is not saved until its address is valid. The Ketch app's card has no address.
 */
init().catch((error) => console.error('Ketch: could not load the settings page', error));

async function init() {
  const settings = await loadSettings();
  for (const instance of settings.instances) {
    addCard(instance, instance.id === settings.defaultInstanceId);
  }
  fillPreferences(settings);
  updateInstanceButtons();
  // Opening this page doesn't start the Ketch app; its "Test connection" button does.
  document.querySelectorAll('.instance').forEach((card) => checkConnection(card));

  $('add-server').addEventListener('click', () => {
    const card = addCard({ id: newInstanceId(), type: 'server', name: '', url: '', token: '' });
    updateInstanceButtons();
    card.querySelector('.instance-url').focus();
  });
  $('add-app').addEventListener('click', () => {
    const taken = document.querySelector(`.instance[data-id="${localInstance().id}"]`);
    const card = addCard({ ...localInstance(), id: taken ? newInstanceId() : localInstance().id });
    persist();
    checkConnection(card);
  });
  for (const toggle of Object.values(toggles)) toggle.addEventListener('change', persist);
  $('min-file-size').addEventListener('change', persist);
  $('excluded-hosts').addEventListener('change', () => {
    $('excluded-hosts').value = parseHostList($('excluded-hosts').value).join('\n');
    persist();
  });
  // The popup can switch capturing on and off while this page is open.
  onSettingsChanged((changed) => fillPreferences(changed));

  $('grant-access').addEventListener('click', async () => {
    if (await ext.permissions.request(ALL_SITES)) $('permission-banner').hidden = true;
  });
  $('permission-banner').hidden = await ext.permissions.contains(ALL_SITES);
}

function fillPreferences(settings) {
  for (const [key, toggle] of Object.entries(toggles)) toggle.checked = settings[key];
  if (document.activeElement !== $('min-file-size')) {
    $('min-file-size').value = String(settings.minFileSizeMb);
  }
  if (document.activeElement !== $('excluded-hosts')) {
    $('excluded-hosts').value = settings.excludedHosts.join('\n');
  }
}

function addCard(instance, isDefault = false) {
  const app = instance.type === 'app';
  const template = $(app ? 'app-instance-template' : 'instance-template');
  const card = template.content.firstElementChild.cloneNode(true);
  card.dataset.id = instance.id;
  card.dataset.type = instance.type;
  const nameInput = card.querySelector('.instance-name');
  const defaultRadio = card.querySelector('input[type="radio"]');
  nameInput.value = instance.name;
  defaultRadio.checked = isDefault;
  nameInput.addEventListener('change', persist);
  defaultRadio.addEventListener('change', persist);
  card.querySelector('.remove').addEventListener('click', () => removeCard(card));
  card.querySelector('.test').addEventListener('click', () => {
    checkConnection(card, { launch: true });
  });
  if (!app) {
    card.dataset.savedUrl = instance.url;
    const urlInput = card.querySelector('.instance-url');
    const tokenInput = card.querySelector('.instance-token');
    urlInput.value = instance.url;
    tokenInput.value = instance.token;
    defaultRadio.disabled = !instance.url;
    updateNote(card);
    urlInput.addEventListener('change', () => onUrlChanged(card));
    tokenInput.addEventListener('change', () => {
      persist();
      checkConnection(card);
    });
  }
  $('instances').append(card);
  updateInstanceButtons();
  return card;
}

/** Whether the card is saved: the Ketch app always is, a server once its address is valid. */
function isSaved(card) {
  return card.dataset.type === 'app' || Boolean(card.dataset.savedUrl);
}

function onUrlChanged(card) {
  const input = card.querySelector('.instance-url');
  try {
    input.value = normalizeServerUrl(input.value);
  } catch (error) {
    input.setAttribute('aria-invalid', 'true');
    showCardStatus(card, 'error', error.message);
    return;
  }
  input.removeAttribute('aria-invalid');
  card.dataset.savedUrl = input.value;
  card.querySelector('input[type="radio"]').disabled = false;
  updateNote(card);
  persist();
  checkConnection(card);
}

function removeCard(card) {
  const wasDefault = card.querySelector('input[type="radio"]').checked;
  card.remove();
  if (wasDefault) {
    const next = [...document.querySelectorAll('.instance')].find(isSaved);
    if (next) next.querySelector('input[type="radio"]').checked = true;
  }
  updateInstanceButtons();
  persist();
}

/**
 * The last saved instance can't be removed, since captured downloads need somewhere to go, and
 * the Ketch app can be added back once removed.
 */
function updateInstanceButtons() {
  const cards = [...document.querySelectorAll('.instance')];
  const saved = cards.filter(isSaved);
  for (const card of cards) {
    card.querySelector('.remove').disabled = saved.length === 1 && saved[0] === card;
  }
  $('add-app').hidden = cards.some((card) => card.dataset.type === 'app');
}

function updateNote(card) {
  const url = card.dataset.savedUrl;
  card.querySelector('.instance-note').hidden =
    !url || url.startsWith('https:') || isLoopbackUrl(url);
}

/** Shows whether the card's instance answers; only `launch` starts the Ketch app. */
async function checkConnection(card, { launch = false } = {}) {
  const app = card.dataset.type === 'app';
  const url = card.dataset.savedUrl;
  const dot = card.querySelector('.status-dot');
  if (!app && !url) {
    dot.removeAttribute('data-state');
    showCardStatus(card, 'info', t('instance_enter_address'));
    return;
  }
  const check = String(Number(card.dataset.check ?? 0) + 1);
  card.dataset.check = check;
  dot.removeAttribute('data-state');
  showCardStatus(card, 'info', launch && app ? t('status_opening_app') : t('status_connecting'));
  const instance = app
    ? { type: 'app' }
    : { type: 'server', url, token: card.querySelector('.instance-token').value.trim() };
  try {
    const status = await withEndpoint(instance, (endpoint) => {
      return new KetchClient(endpoint, { timeoutMs: CHECK_TIMEOUT_MS }).status();
    }, { launch });
    if (card.dataset.check !== check) return;
    if (!status.version) throw new Error(t('error_not_ketch'));
    dot.dataset.state = 'online';
    showCardStatus(card, 'info', describeStatus(status, { withOs: true }));
    const nameInput = card.querySelector('.instance-name');
    if (!app && !nameInput.value.trim() && status.name) {
      nameInput.value = status.name;
      persist();
    }
  } catch (error) {
    if (card.dataset.check !== check) return;
    dot.dataset.state = 'offline';
    showCardStatus(card, 'error', withHint(error.message, failureHint(error, instance)));
  }
}

function showCardStatus(card, tone, text) {
  const status = card.querySelector('.instance-status');
  status.dataset.tone = tone;
  status.textContent = text;
}

let savedTimer;

async function persist() {
  const cards = [...document.querySelectorAll('.instance')].filter(isSaved);
  const defaultCard = cards.find((it) => it.querySelector('input[type="radio"]').checked);
  const saved = await saveSettings({
    instances: cards.map((card) => {
      const common = { id: card.dataset.id, name: card.querySelector('.instance-name').value };
      if (card.dataset.type === 'app') return { ...common, type: 'app' };
      return {
        ...common,
        type: 'server',
        url: card.dataset.savedUrl,
        token: card.querySelector('.instance-token').value,
      };
    }),
    defaultInstanceId: defaultCard?.dataset.id,
    interceptDownloads: toggles.interceptDownloads.checked,
    forwardCookies: toggles.forwardCookies.checked,
    captureMagnetLinks: toggles.captureMagnetLinks.checked,
    notifications: toggles.notifications.checked,
    minFileSizeMb: Number($('min-file-size').value),
    excludedHosts: parseHostList($('excluded-hosts').value),
  });
  // Normalization may have filled a blank name or picked a new default.
  for (const instance of saved.instances) {
    const card = document.querySelector(`.instance[data-id="${CSS.escape(instance.id)}"]`);
    card.querySelector('.instance-name').value = instance.name;
    card.querySelector('input[type="radio"]').checked = instance.id === saved.defaultInstanceId;
  }
  updateInstanceButtons();
  $('saved').hidden = false;
  clearTimeout(savedTimer);
  savedTimer = setTimeout(() => {
    $('saved').hidden = true;
  }, 1_500);
}
