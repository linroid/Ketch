import { ext } from '../lib/ext.js';
import { describeStatus, failureHint } from '../lib/format.js';
import { KetchClient } from '../lib/ketch-client.js';
import {
  isLoopbackUrl,
  loadSettings,
  newInstanceId,
  normalizeServerUrl,
  onSettingsChanged,
  parseHostList,
  saveSettings,
} from '../lib/settings.js';

const CHECK_TIMEOUT_MS = 4_000;
const ALL_SITES = { origins: ['<all_urls>'] };

const $ = (id) => document.getElementById(id);
const toggles = {
  interceptDownloads: $('intercept-downloads'),
  forwardCookies: $('forward-cookies'),
  captureMagnetLinks: $('capture-magnet-links'),
  notifications: $('notifications'),
};

/**
 * The page's form is the source of truth: every change is collected from it and saved. An
 * instance card keeps the last valid address it was saved with in `dataset.savedUrl`; a card
 * without one is a draft that is not saved until its address is valid.
 */
init().catch((error) => console.error('Ketch: could not load the settings page', error));

async function init() {
  const settings = await loadSettings();
  for (const instance of settings.instances) {
    addCard(instance, instance.id === settings.defaultInstanceId);
  }
  fillPreferences(settings);
  updateRemoveButtons();
  document.querySelectorAll('.instance').forEach(checkConnection);

  $('add-instance').addEventListener('click', () => {
    const card = addCard({ id: newInstanceId(), name: '', url: '', token: '' }, false);
    updateRemoveButtons();
    card.querySelector('.instance-url').focus();
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

function addCard(instance, isDefault) {
  const card = $('instance-template').content.firstElementChild.cloneNode(true);
  card.dataset.id = instance.id;
  card.dataset.savedUrl = instance.url;
  const nameInput = card.querySelector('.instance-name');
  const urlInput = card.querySelector('.instance-url');
  const tokenInput = card.querySelector('.instance-token');
  const defaultRadio = card.querySelector('input[type="radio"]');
  nameInput.value = instance.name;
  urlInput.value = instance.url;
  tokenInput.value = instance.token;
  defaultRadio.checked = isDefault;
  defaultRadio.disabled = !instance.url;
  updateNote(card);

  nameInput.addEventListener('change', persist);
  urlInput.addEventListener('change', () => onUrlChanged(card));
  tokenInput.addEventListener('change', () => {
    persist();
    checkConnection(card);
  });
  defaultRadio.addEventListener('change', persist);
  card.querySelector('.test').addEventListener('click', () => checkConnection(card));
  card.querySelector('.remove').addEventListener('click', () => removeCard(card));
  $('instances').append(card);
  return card;
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
    const next = [...document.querySelectorAll('.instance')].find((it) => it.dataset.savedUrl);
    if (next) next.querySelector('input[type="radio"]').checked = true;
  }
  updateRemoveButtons();
  persist();
}

/** The last saved instance can't be removed: captured downloads need somewhere to go. */
function updateRemoveButtons() {
  const saved = [...document.querySelectorAll('.instance')].filter((it) => it.dataset.savedUrl);
  document.querySelectorAll('.instance').forEach((card) => {
    card.querySelector('.remove').disabled = saved.length === 1 && saved[0] === card;
  });
}

function updateNote(card) {
  const url = card.dataset.savedUrl;
  card.querySelector('.instance-note').hidden =
    !url || url.startsWith('https:') || isLoopbackUrl(url);
}

async function checkConnection(card) {
  const url = card.dataset.savedUrl;
  const dot = card.querySelector('.status-dot');
  if (!url) {
    dot.removeAttribute('data-state');
    showCardStatus(card, 'info', 'Enter the address of a Ketch server.');
    return;
  }
  const check = String(Number(card.dataset.check ?? 0) + 1);
  card.dataset.check = check;
  dot.removeAttribute('data-state');
  showCardStatus(card, 'info', 'Connecting…');
  const instance = { url, token: card.querySelector('.instance-token').value.trim() };
  try {
    const status = await new KetchClient(instance, { timeoutMs: CHECK_TIMEOUT_MS }).status();
    if (card.dataset.check !== check) return;
    if (!status.version) throw new Error('This address does not look like a Ketch server');
    dot.dataset.state = 'online';
    showCardStatus(card, 'info', describeStatus(status, { withOs: true }));
    const nameInput = card.querySelector('.instance-name');
    if (!nameInput.value.trim() && status.name) {
      nameInput.value = status.name;
      persist();
    }
  } catch (error) {
    if (card.dataset.check !== check) return;
    dot.dataset.state = 'offline';
    const hint = failureHint(error, instance);
    showCardStatus(card, 'error', hint ? `${error.message}. ${hint}` : error.message);
  }
}

function showCardStatus(card, tone, text) {
  const status = card.querySelector('.instance-status');
  status.dataset.tone = tone;
  status.textContent = text;
}

let savedTimer;

async function persist() {
  const cards = [...document.querySelectorAll('.instance')].filter((it) => it.dataset.savedUrl);
  const defaultCard = cards.find((it) => it.querySelector('input[type="radio"]').checked);
  const saved = await saveSettings({
    instances: cards.map((card) => ({
      id: card.dataset.id,
      name: card.querySelector('.instance-name').value,
      url: card.dataset.savedUrl,
      token: card.querySelector('.instance-token').value,
    })),
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
  updateRemoveButtons();
  $('saved').hidden = false;
  clearTimeout(savedTimer);
  savedTimer = setTimeout(() => {
    $('saved').hidden = true;
  }, 1_500);
}
