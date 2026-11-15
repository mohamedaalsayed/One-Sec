// One Sec — toolbar popup: today's stats and settings.
'use strict';

const $ = (id) => document.getElementById(id);
const ALL_SITES = { origins: ['*://*/*'] };

refresh();

async function refresh() {
  await Promise.all([refreshStats(), refreshGrant(), refreshSites(), refreshQuickMinutes(), refreshAlternatives()]);
}

// ---- Stats ----

async function refreshStats() {
  const stats = await OneSec.todayStats();
  const lines = [];
  if (stats.pauses > 0) lines.push(`${stats.pauses} pauses today  ·  ${stats.opened} opened`);
  if (stats.links > 0) lines.push(`${stats.links} links watched  ·  ${stats.kept} kept scrolling`);

  const quickChecks = await OneSec.limitCount('quick');
  const nextScroll = await OneSec.nextScrollAt();
  let scrollStatus = null;
  if (await OneSec.scrollActive()) {
    scrollStatus = 'scroll session until ' + OneSec.formatTime(await OneSec.scrollEnd());
  } else if (nextScroll) {
    scrollStatus = 'next scroll session ' + OneSec.formatTime(nextScroll);
  }
  const limits = [quickChecks > 0 ? `${quickChecks} quick checks` : null, scrollStatus].filter(Boolean);
  if (limits.length) lines.push(limits.join('  ·  '));

  $('stats').textContent = lines.join('\n');
}

// ---- Site access (Firefox grants it separately from installing) ----

async function refreshGrant() {
  $('grant').hidden = await api.permissions.contains(ALL_SITES);
}

$('grant').addEventListener('click', async () => {
  await api.permissions.request(ALL_SITES);
  refreshGrant();
});

// ---- Guarded sites ----

async function refreshSites() {
  const sites = await OneSec.getGuardedSites();
  $('sites').replaceChildren(...sites.map((site) => listItem(site, async () => {
    await OneSec.set('guarded', (await OneSec.getGuardedSites()).filter((s) => s !== site));
    refreshSites();
  })));
}

$('addSite').addEventListener('submit', async (event) => {
  event.preventDefault();
  const site = OneSec.normalizeSite($('siteInput').value);
  if (!site || !site.includes('.')) return;
  const sites = await OneSec.getGuardedSites();
  if (!sites.includes(site)) await OneSec.set('guarded', [...sites, site]);
  $('siteInput').value = '';
  refreshSites();
});

// ---- Quick check length ----

async function refreshQuickMinutes() {
  const selected = await OneSec.getQuickMinutes();
  const chips = [];
  for (let minutes = OneSec.QUICK_MIN_MINUTES; minutes <= OneSec.QUICK_MAX_MINUTES; minutes++) {
    const chip = document.createElement('button');
    chip.className = 'chip' + (minutes === selected ? ' on' : '');
    chip.textContent = `${minutes} min`;
    chip.addEventListener('click', async () => {
      await OneSec.set('quickMinutes', minutes);
      refreshQuickMinutes();
    });
    chips.push(chip);
  }
  $('quickMinutes').replaceChildren(...chips);
}

// ---- Things to do instead (at most MAX_ALTERNATIVES, so they all fit) ----

async function refreshAlternatives() {
  const alternatives = await OneSec.getAlternatives();
  $('alternatives').replaceChildren(...alternatives.map((alternative) => listItem(alternative, async () => {
    await OneSec.set('alternatives', (await OneSec.getAlternatives()).filter((a) => a !== alternative));
    refreshAlternatives();
  })));

  const full = alternatives.length >= OneSec.MAX_ALTERNATIVES;
  $('alternativeInput').disabled = full;
  $('addAlternative').querySelector('button').disabled = full;
  $('alternativeInput').placeholder = full
    ? `Up to ${OneSec.MAX_ALTERNATIVES}. Remove one to add another`
    : "Add something you'd rather do";
}

$('addAlternative').addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = $('alternativeInput').value.trim();
  const alternatives = await OneSec.getAlternatives();
  if (!text || alternatives.length >= OneSec.MAX_ALTERNATIVES || alternatives.includes(text)) return;
  await OneSec.set('alternatives', [...alternatives, text]);
  $('alternativeInput').value = '';
  refreshAlternatives();
});

function listItem(text, onRemove) {
  const li = document.createElement('li');
  const label = document.createElement('span');
  label.textContent = text;
  const remove = document.createElement('button');
  remove.textContent = '×';
  remove.title = 'Remove';
  remove.addEventListener('click', onRemove);
  li.append(label, remove);
  return li;
}
