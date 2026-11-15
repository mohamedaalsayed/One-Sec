// One Sec — shared by the content script and the popup.
// A plain script (no modules) so content scripts can load it before content.js.
'use strict';

const api = globalThis.browser ?? globalThis.chrome;

const OneSec = (() => {
  const DEFAULT_GUARDED = ['instagram.com', 'facebook.com', 'tiktok.com', 'x.com', 'twitter.com', 'reddit.com'];
  // Shown before a scroll session until the user writes their own
  const DEFAULT_ALTERNATIVES = ['Go for a walk', 'Read 10 pages', 'Text a friend', 'Stretch for 5 minutes'];

  const MAX_ALTERNATIVES = 5;
  const QUICK_MIN_MINUTES = 2;
  const QUICK_MAX_MINUTES = 5;
  const QUICK_DEFAULT_MINUTES = 3;
  const SCROLL_MINUTES = 30;

  const MINUTE = 60_000;
  const HOUR = 60 * MINUTE;

  // ---- Storage ----
  // Everything lives in storage.local: shared by every tab, never synced.

  async function get(key, fallback) {
    const items = await api.storage.local.get(key);
    return items[key] ?? fallback;
  }

  function set(key, value) {
    return api.storage.local.set({ [key]: value });
  }

  // Read-modify-write of one object-valued key
  async function update(key, fn) {
    const value = await get(key, {});
    fn(value);
    await set(key, value);
  }

  // ---- Days ----

  function dayStr(date) {
    return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
  }

  // Stats use the calendar day
  function today() {
    return dayStr(new Date());
  }

  // Limits reset at 4 AM rather than midnight, so late-night scrolling
  // counts as the same night
  function limitDay() {
    return dayStr(new Date(Date.now() - 4 * HOUR));
  }

  // ---- Guarded sites ----

  async function getGuardedSites() {
    return get('guarded', DEFAULT_GUARDED);
  }

  /** "https://www.Instagram.com/foo" -> "instagram.com" */
  function normalizeSite(input) {
    let s = input.trim().toLowerCase();
    s = s.replace(/^[a-z]+:\/\//, '').replace(/[/?#].*$/, '').replace(/:\d+$/, '');
    return s.replace(/^www\./, '');
  }

  /** The guarded site a hostname belongs to (subdomains included), or null. */
  async function guardedSiteFor(hostname) {
    const host = hostname.toLowerCase();
    const sites = await getGuardedSites();
    return sites.find((site) => host === site || host.endsWith('.' + site)) ?? null;
  }

  // ---- Settings ----

  async function getQuickMinutes() {
    const minutes = await get('quickMinutes', QUICK_DEFAULT_MINUTES);
    return Math.min(QUICK_MAX_MINUTES, Math.max(QUICK_MIN_MINUTES, minutes));
  }

  /** The user's list, or the defaults if they've never edited it. */
  async function getAlternatives() {
    return get('alternatives', DEFAULT_ALTERNATIVES);
  }

  /** What to suggest; the defaults if the user emptied the list. */
  async function getAlternativesToSuggest() {
    const list = await getAlternatives();
    return (list.length ? list : DEFAULT_ALTERNATIVES).slice(0, MAX_ALTERNATIVES);
  }

  // ---- Stats (per calendar day) ----

  async function count(name) {
    await update('stats', (stats) => {
      const day = (stats[today()] ??= {});
      day[name] = (day[name] ?? 0) + 1;
    });
  }

  async function todayStats() {
    const stats = await get('stats', {});
    return { pauses: 0, opened: 0, links: 0, kept: 0, ...(stats[today()] ?? {}) };
  }

  // ---- Limits (per 4 AM day) ----

  async function limitCount(name) {
    const limits = await get('limits', {});
    return limits[limitDay()]?.[name] ?? 0;
  }

  async function addLimitCount(name) {
    await update('limits', (limits) => {
      const day = (limits[limitDay()] ??= {});
      day[name] = (day[name] ?? 0) + 1;
    });
  }

  // ---- Quick check (per site) ----

  /** When the site's quick check ends, or 0 if none is running. */
  async function quickCheckUntil(site) {
    const until = (await get('quickChecks', {}))[site] ?? 0;
    return until > Date.now() ? until : 0;
  }

  async function startQuickCheck(site) {
    const until = Date.now() + (await getQuickMinutes()) * MINUTE;
    await update('quickChecks', (q) => { q[site] = until; });
    await addLimitCount('quick');
    return until;
  }

  async function endQuickCheck(site) {
    await update('quickChecks', (q) => { delete q[site]; });
  }

  // ---- Scroll session (all sites) ----
  // 30 minutes of wall-clock time shared by every guarded site. After each
  // session a cooldown blocks the next one, growing through the day: 1h after
  // the first, then 3h, 5h, 7h... Both reset at 4 AM.

  async function scrollEnd() {
    return get('scrollEnd', 0);
  }

  async function scrollActive() {
    return Date.now() < (await scrollEnd());
  }

  // 1h after the 1st session of the day, 3h after the 2nd, 5h after the 3rd...
  function cooldownMs(sessionsToday) {
    return (2 * sessionsToday - 1) * HOUR;
  }

  /** When the next session can start, or 0 if one can start now. */
  async function nextScrollAt() {
    if (await scrollActive()) return 0;
    const sessions = await limitCount('scroll');
    if (sessions === 0) return 0;
    const at = (await scrollEnd()) + cooldownMs(sessions);
    return at > Date.now() ? at : 0;
  }

  /** When the next session would unlock if one started now. */
  async function nextScrollAtIfStartedNow() {
    return Date.now() + SCROLL_MINUTES * MINUTE + cooldownMs((await limitCount('scroll')) + 1);
  }

  async function startScrollSession() {
    const end = Date.now() + SCROLL_MINUTES * MINUTE;
    await set('scrollEnd', end);
    await addLimitCount('scroll');
    return end;
  }

  /**
   * True once per session end, if it ended within the last hour: the
   * "30 minutes are up" prompt is still owed. Shared across tabs, so only
   * one of them shows it.
   */
  async function takeScrollEnd() {
    const end = await scrollEnd();
    const now = Date.now();
    if (!end || now < end || (await get('scrollEndHandled', 0)) === end) return false;
    await set('scrollEndHandled', end);
    return now - end < HOUR;
  }

  // ---- Leaving a site ----
  // Visible guarded tabs mark their site as seen every few seconds. If it
  // hasn't been seen for a while, the user left it, like switching apps.

  async function markSeen(site) {
    await update('lastSeen', (seen) => { seen[site] = Date.now(); });
  }

  async function lastSeen(site) {
    return (await get('lastSeen', {}))[site] ?? 0;
  }

  function formatTime(ms) {
    return new Date(ms).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  }

  return {
    MAX_ALTERNATIVES, QUICK_MIN_MINUTES, QUICK_MAX_MINUTES, SCROLL_MINUTES,
    get, set, normalizeSite, getGuardedSites, guardedSiteFor,
    getQuickMinutes, getAlternatives, getAlternativesToSuggest,
    count, todayStats, limitCount,
    quickCheckUntil, startQuickCheck, endQuickCheck,
    scrollEnd, scrollActive, nextScrollAt, nextScrollAtIfStartedNow, startScrollSession, takeScrollEnd,
    markSeen, lastSeen, formatTime,
  };
})();
