import { ext } from '../lib/ext.js';
import { localizePage, t } from '../lib/i18n.js';
import { fileNameFromPath, isTorrentFile, isTorrentUrl } from '../lib/request.js';
import { readReview } from '../lib/review.js';
import { loadSettings } from '../lib/settings.js';

const $ = (id) => document.getElementById(id);
const id = new URL(location.href).searchParams.get('id');
localizePage();
$('form').addEventListener('submit', async (event) => {
  event.preventDefault();
  setBusy(true);
  try {
    const response = await ext.runtime.sendMessage({ type: 'review-send', id,
      instanceId: $('instance').value, fileName: $('name').value, folder: $('folder').value });
    if (!response?.ok) {
      $('status').textContent = response?.message || t('review_unavailable');
      if (response?.kind !== 'uncertain') setBusy(false);
      return;
    }
    $('status').textContent = t('notify_download_added');
    window.close();
  } catch {
    // If the background disappeared, do not offer a second submission without its state.
    $('status').textContent = t('error_handoff_uncertain');
  }
});
$('cancel').addEventListener('click', async () => {
  setBusy(true);
  await ext.runtime.sendMessage({ type: 'review-cancel', id });
  window.close();
});
function setBusy(busy) {
  for (const element of $('form').elements) element.disabled = busy;
}
async function init() {
  const [entry, settings] = await Promise.all([readReview(id), loadSettings()]);
  if (!entry || entry.state !== 'pending') throw new Error(t('review_unavailable'));
  $('url').textContent = entry.download.url;
  for (const instance of settings.instances) {
    const option = new Option(instance.name, instance.id);
    option.selected = instance.id === entry.instanceId;
    $('instance').append(option);
  }
  const torrent = isTorrentUrl(entry.download.url) || isTorrentFile(entry.download);
  $('name').closest('label').hidden = torrent;
  const media = /\.(m3u8|mpd)(?:[?#]|$)/i.test(entry.download.url);
  $('name').value = torrent || media ? '' : fileNameFromPath(entry.download.fileName);
  $('send').disabled = false;
}
init().catch((error) => { setBusy(true); $('status').textContent = error.message; });
