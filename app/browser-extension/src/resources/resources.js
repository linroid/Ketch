import { ext } from '../lib/ext.js';
import { cookieStoreIdForTab, sendToKetch } from '../lib/handoff.js';
import { localizePage, t } from '../lib/i18n.js';
import { collectPageResources, mergePageResources } from '../lib/page-resources.js';
import { reviewDestination } from '../lib/review.js';
import { loadSettings } from '../lib/settings.js';

const $ = (id) => document.getElementById(id);
const tabId = Number(new URL(location.href).searchParams.get('tab'));
let resources = [];
let busy = false;
let cookieStoreId;
const submitted = new Set();
localizePage();
$('scan').addEventListener('click', scan);
$('kind').addEventListener('change', render);
$('all').addEventListener('change', () => {
  for (const box of document.querySelectorAll('#resources input:not(:disabled)')) {
    box.checked = $('all').checked;
  }
  updateSelection();
});
$('resources').addEventListener('change', updateSelection);
$('send').addEventListener('click', sendSelected);

async function scan() {
  if (busy) return;
  busy = true;
  $('scan').disabled = true;
  try {
    if (!Number.isInteger(tabId) || tabId < 0) throw new Error(t('resources_unavailable'));
    const tab = await ext.tabs.get(tabId);
    if (!/^https?:/.test(tab.url ?? '') || tab.incognito) {
      throw new Error(t('resources_unavailable'));
    }
    cookieStoreId = await cookieStoreIdForTab(tab);
    const results = await ext.scripting.executeScript({
      target: { tabId, allFrames: true }, func: collectPageResources,
    });
    resources = mergePageResources(results);
    $('status').textContent = resources.length ? '' : t('resources_empty');
    render();
  } catch {
    resources = [];
    render();
    $('status').textContent = t('resources_unavailable');
  } finally {
    busy = false;
    $('scan').disabled = false;
  }
}

function render() {
  $('resources').replaceChildren();
  $('all').checked = false;
  for (const resource of resources) {
    if ($('kind').value !== 'all' && resource.kind !== $('kind').value) continue;
    const row = document.createElement('li');
    const label = document.createElement('label');
    const box = document.createElement('input');
    box.type = 'checkbox';
    box.value = resource.url;
    box.disabled = submitted.has(resource.url);
    const text = document.createElement('span');
    text.textContent = resource.name || resource.url;
    const status = document.createElement('small');
    status.className = 'muted';
    label.append(box, text);
    row.append(label, status);
    $('resources').append(row);
  }
  updateSelection();
}

function updateSelection() {
  $('send').disabled = busy || !document.querySelector('#resources input:checked');
}

async function sendSelected() {
  if (busy) return;
  const selected = [...document.querySelectorAll('#resources input:checked:not(:disabled)')];
  busy = true;
  for (const id of ['scan', 'kind', 'instance', 'all', 'send', 'folder']) $(id).disabled = true;
  try {
    const settings = await loadSettings();
    const instance = settings.instances.find((it) => it.id === $('instance').value);
    if (!instance) throw new Error(t('resources_unavailable'));
    for (const box of selected) {
      const resource = resources.find((it) => it.url === box.value);
      const status = box.closest('li').querySelector('small');
      try {
        const current = await loadSettings();
        const target = current.instances.find((it) => it.id === instance.id);
        if (!target || target.type !== instance.type || target.url !== instance.url ||
          target.token !== instance.token) throw new Error(t('resources_unavailable'));
        await sendToKetch(target, { url: resource.url, referrer: resource.pageUrl,
          fileName: resource.name || undefined, cookieStoreId,
          destination: reviewDestination({ url: resource.url, fileName: resource.name },
            resource.kind === 'stream' ? '' : resource.name, $('folder').value) }, current);
        submitted.add(resource.url);
        box.disabled = true;
        box.checked = false;
        status.textContent = t('notify_download_added');
      } catch (error) {
        status.textContent = error.message;
        if (error.kind === 'uncertain') {
          submitted.add(resource.url);
          box.disabled = true;
          box.checked = false;
        }
      }
    }
  } catch (error) {
    $('status').textContent = error.message;
  } finally {
    busy = false;
    for (const id of ['scan', 'kind', 'instance', 'all', 'folder']) $(id).disabled = false;
    updateSelection();
  }
}

loadSettings().then((settings) => {
  for (const instance of settings.instances) {
    const option = new Option(instance.name, instance.id);
    option.selected = instance.id === settings.defaultInstanceId;
    $('instance').append(option);
  }
  return scan();
}).catch(() => { $('status').textContent = t('resources_unavailable'); });
