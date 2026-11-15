// One Sec — the pause, shown over guarded sites.
//
// Same flow as the Android app's InterventionActivity. Modes:
//  - PAUSE: site opened normally. 5-second breath, reason chips, then
//    go back / quick check / scroll session.
//  - QUICK_CONFIRM: "Is it that important?" before a quick check, with more
//    friction the more quick checks there have been today.
//  - SCROLL_CONFIRM: before a scroll session, the user's own alternatives and
//    when the next session would unlock; "scroll" stays locked for a while.
//  - LINK: opened from a link someone sent. Shorter breath, then watch just
//    this one or go back.
//  - CHECK_IN: the user moved past what they were sent.
//  - TIME_UP: a quick check ran out.
//  - SCROLL_END: a scroll session ran out.
'use strict';

(() => {
  if (window.top !== window) return;

  const PAUSE_SECONDS = 5;
  const LINK_PAUSE_SECONDS = 3;
  // Quick-check friction: from the 3rd of the day a wait before "yes",
  // from the 5th also typing what you're checking
  const QUICK_WAIT_FROM = 3;
  const QUICK_TYPE_FROM = 5;
  const QUICK_WAIT_SECONDS = 10;
  const QUICK_REASON_MIN_CHARS = 3;
  const SCROLL_CONFIRM_WAIT_SECONDS = 5;

  // "Just this one" ends after this long even without moving on
  const LINK_TIMEOUT_MS = 90_000;
  // ...or once scrolled this many screens past where it started
  const PAST_POST_SCREENS = 1.2;
  // No tab of the site visible for this long = the user left it, like
  // switching apps. Long enough to glance at another tab.
  const LEFT_AFTER_MS = 15_000;
  const SEEN_EVERY_MS = 5_000;
  const TICK_MS = 500;

  // Paths that point at one piece of content rather than a feed
  const CONTENT_PATH =
    /\/(p|reel|reels|tv|stories|video|videos|watch|posts|permalink|status|shorts|share|comments|photo)(\/|\.php|$)|\/story\.php/i;

  // Friendlier names for where links usually come from
  const SOURCE_NAMES = {
    'web.whatsapp.com': 'WhatsApp',
    'messenger.com': 'Messenger',
    'web.telegram.org': 'Telegram',
    'discord.com': 'Discord',
    'mail.google.com': 'Gmail',
    'outlook.live.com': 'Outlook',
    'outlook.office.com': 'Outlook',
    'teams.microsoft.com': 'Teams',
    'app.slack.com': 'Slack',
  };

  let site = null;
  let ui = null; // the overlay while it's shown
  let access = null; // {kind: 'scroll'} | {kind: 'quick', until} | {kind: 'link', ...}
  let busy = false;
  let lastSeenMark = 0;
  const pausedMedia = new Set();

  // Opened from a link rather than typed or bookmarked. Only true for the
  // page as first loaded; clicking around inside the site isn't a link open.
  let linkOpen = detectLinkOpen();

  async function start() {
    site = await OneSec.guardedSiteFor(location.hostname);
    if (!site) return;

    if (linkOpen && !linkOpen.source) linkOpen.source = await openerHost();

    document.addEventListener('visibilitychange', onVisibility);
    for (const type of ['keydown', 'keyup', 'keypress']) window.addEventListener(type, blockKeys, true);
    setInterval(tick, TICK_MS);
    await check();
  }

  // ---- Deciding ----

  /** No pause showing and no access yet: let the user in, or pause. */
  async function check() {
    if (busy || ui || access || document.visibilityState !== 'visible') return;
    busy = true;
    try {
      if (await OneSec.scrollActive()) {
        access = { kind: 'scroll' };
        return;
      }
      const until = await OneSec.quickCheckUntil(site);
      if (until) {
        access = { kind: 'quick', until };
        return;
      }

      await OneSec.count('pauses');
      await OneSec.count('p:' + site);
      let mode = 'PAUSE';
      let source = null;
      if (await OneSec.takeScrollEnd()) {
        mode = 'SCROLL_END';
      } else if (linkOpen) {
        mode = 'LINK';
        source = linkOpen.source;
      }
      linkOpen = null;
      show(mode, source);
    } finally {
      busy = false;
    }
  }

  async function tick() {
    if (busy || document.visibilityState !== 'visible') return;
    if (ui) {
      // SPAs can rebuild the page and drop us; media keeps trying to play
      if (!ui.host.isConnected) document.documentElement.append(ui.host);
      pauseMedia();
      return;
    }
    if (!access) return check();

    busy = true;
    try {
      const now = Date.now();
      if (now - lastSeenMark > SEEN_EVERY_MS) {
        lastSeenMark = now;
        await OneSec.markSeen(site);
      }

      if (access.kind === 'scroll' && !(await OneSec.scrollActive())) {
        access = null;
      } else if (access.kind === 'quick' && (now >= access.until || !(await OneSec.quickCheckUntil(site)))) {
        access = null;
        await OneSec.endQuickCheck(site);
        show('TIME_UP');
        return;
      } else if (access.kind === 'link' && movedOn(access)) {
        const source = access.source;
        access = null;
        show('CHECK_IN', source);
        return;
      }
    } finally {
      busy = false;
    }
    // A scroll session that just ended: check() asks how they feel
    if (!access) check();
  }

  async function onVisibility() {
    if (document.visibilityState === 'hidden') {
      if (access) await OneSec.markSeen(site);
      return;
    }

    // Back after a while without any tab of the site: they'd left it
    if (access && access.kind !== 'scroll' && Date.now() - (await OneSec.lastSeen(site)) > LEFT_AFTER_MS) {
      if (access.kind === 'quick') await OneSec.endQuickCheck(site);
      access = null;
    }

    if (ui) {
      // Another tab may have started a scroll session or quick check meanwhile
      if ((await OneSec.scrollActive()) || (await OneSec.quickCheckUntil(site))) {
        hideUi();
        await check();
        return;
      }
      // Don't let a countdown run out unseen
      if (['PAUSE', 'LINK', 'QUICK_CONFIRM', 'SCROLL_CONFIRM'].includes(ui.mode)) startFlow();
      return;
    }
    await check();
  }

  // ---- Links ----

  function detectLinkOpen() {
    let from = null;
    try {
      from = document.referrer ? new URL(document.referrer).hostname : null;
    } catch {}
    // From another site: WhatsApp Web, a search result, an email...
    if (from && baseDomain(from) !== baseDomain(location.hostname)) return { source: from };
    // A post or reel opened in a fresh tab, e.g. by a desktop app
    if (!from && history.length <= 1 && CONTENT_PATH.test(location.pathname)) return { source: null };
    return null;
  }

  function baseDomain(host) {
    return host.toLowerCase().split('.').slice(-2).join('.');
  }

  async function openerHost() {
    try {
      return (await api.runtime.sendMessage({ type: 'openerHost' })) ?? null;
    } catch {
      return null;
    }
  }

  function sourceName(host) {
    if (!host) return null;
    const bare = host.replace(/^www\./, '');
    return SOURCE_NAMES[bare] ?? bare;
  }

  function contentPath() {
    return location.pathname.replace(/\/+$/, '') || '/';
  }

  function scrollTop() {
    return document.scrollingElement?.scrollTop ?? 0;
  }

  /** Has the user moved on from what they were sent? */
  function movedOn(link) {
    if (Date.now() - link.startedAt > LINK_TIMEOUT_MS) return true;
    // Next reel/video, or another post: the address changes. Sub-paths
    // (e.g. a post's comments) are still the same thing.
    const path = contentPath();
    if (path !== link.path && !path.startsWith(link.path + '/')) return true;
    // Scrolled down past the post into whatever comes after it
    return scrollTop() - link.startScroll > innerHeight * PAST_POST_SCREENS;
  }

  // ---- Choices ----

  function grant(newAccess) {
    access = newAccess;
    lastSeenMark = Date.now();
    OneSec.markSeen(site);
    hideUi();
    resumeMedia();
  }

  async function startQuickCheck() {
    const until = await OneSec.startQuickCheck(site);
    await OneSec.count('opened');
    grant({ kind: 'quick', until });
  }

  async function startScrollSession() {
    await OneSec.startScrollSession();
    await OneSec.count('opened');
    grant({ kind: 'scroll' });
  }

  async function watchJustThisOne() {
    await OneSec.count('links');
    grant({
      kind: 'link',
      source: ui.source,
      // Taken now, after the pause, so redirects while loading don't count
      path: contentPath(),
      startScroll: scrollTop(),
      startedAt: Date.now(),
    });
  }

  async function keepScrolling() {
    await OneSec.count('kept');
    fullPause();
  }

  // More time is a fresh decision: full pause, reasons, the usual options
  function fullPause() {
    ui.mode = 'PAUSE';
    ui.source = null;
    startFlow();
  }

  /** Go back where they came from, or close the tab. */
  function leave() {
    let from = null;
    try {
      from = document.referrer ? new URL(document.referrer).hostname : null;
    } catch {}
    if (from && baseDomain(from) !== baseDomain(location.hostname) && history.length > 1) {
      // Came from another site in this tab: go back there. If "back" only
      // moves within the site (they'd clicked around), the page doesn't
      // unload; close the tab instead.
      const fallback = setTimeout(closeTab, 700);
      addEventListener('pagehide', () => clearTimeout(fallback), { once: true });
      history.back();
    } else {
      closeTab();
    }
  }

  function closeTab() {
    api.runtime.sendMessage({ type: 'closeTab' });
  }

  // ---- Media and keys ----

  function pauseMedia() {
    for (const media of document.querySelectorAll('video, audio')) {
      if (!media.paused) {
        media.pause();
        pausedMedia.add(media);
      }
    }
  }

  function resumeMedia() {
    for (const media of pausedMedia) {
      if (media.isConnected) media.play().catch(() => {});
    }
    pausedMedia.clear();
  }

  // Keep the site's keyboard shortcuts (next reel, like...) from acting
  // behind the pause; our own input still gets its keys
  function blockKeys(event) {
    if (ui && !event.composedPath().includes(ui.host)) event.stopImmediatePropagation();
  }

  // ---- Overlay ----

  function show(mode, source = null) {
    if (!ui) createUi();
    ui.mode = mode;
    ui.source = source;
    pauseMedia();
    startFlow();
  }

  function hideUi() {
    if (!ui) return;
    stopTimers();
    ui.host.remove();
    document.documentElement.style.overflow = ui.savedOverflow;
    ui = null;
  }

  function createUi() {
    const host = document.createElement('one-sec-pause');
    host.style.cssText = 'all: initial; position: fixed; inset: 0; z-index: 2147483647; display: block;';
    const root = host.attachShadow({ mode: 'closed' });
    root.innerHTML = `<style>${CSS}</style>${HTML}`;
    document.documentElement.append(host);

    const $ = (sel) => root.querySelector(sel);
    ui = {
      host,
      root,
      mode: 'PAUSE',
      source: null,
      flow: 0,
      timers: [],
      savedOverflow: document.documentElement.style.overflow,
      breathe: $('.breathe'),
      siteName: $('.site'),
      count: $('.count'),
      label: $('.label'),
      decide: $('.decide'),
      tag: $('.tag'),
      title1: $('.t1'),
      title2: $('.t2'),
      reasons: $('.reasons'),
      reason: $('.reason'),
      buttons: $('.buttons'),
    };
    ui.siteName.textContent = site;
    document.documentElement.style.overflow = 'hidden';

    for (const chip of root.querySelectorAll('.chip')) {
      chip.addEventListener('click', () => {
        for (const other of root.querySelectorAll('.chip')) other.classList.toggle('on', other === chip);
      });
    }
  }

  function stopTimers() {
    for (const id of ui.timers) clearInterval(id);
    ui.timers = [];
  }

  function every(ms, fn) {
    ui.timers.push(setInterval(fn, ms));
  }

  function startFlow() {
    stopTimers();
    ui.flow++;
    for (const chip of ui.root.querySelectorAll('.chip')) chip.classList.remove('on');
    ui.reason.value = '';
    ui.reason.oninput = null;
    ui.reason.hidden = true;

    switch (ui.mode) {
      case 'PAUSE':
        return breathe(PAUSE_SECONDS);
      case 'LINK':
        return breathe(LINK_PAUSE_SECONDS);
      // They already waited when they opened the site
      case 'CHECK_IN':
      case 'TIME_UP':
      case 'SCROLL_END':
        return decide();
      // Only reached from the PAUSE decision screen
      default:
        ui.mode = 'PAUSE';
        return breathe(PAUSE_SECONDS);
    }
  }

  function breathe(seconds) {
    ui.decide.hidden = true;
    ui.breathe.hidden = false;
    let left = seconds;
    let inhale = true;
    ui.count.textContent = left;
    ui.label.textContent = 'breathe in';
    every(1000, () => {
      left--;
      ui.count.textContent = left;
      if (left <= 0) decide();
    });
    every(2000, () => {
      inhale = !inhale;
      ui.label.textContent = inhale ? 'breathe in' : 'breathe out';
    });
  }

  /**
   * One button per [text, onClick, options]: primary looks filled; a
   * wait counts down in the label before the button unlocks.
   */
  function setButtons(specs) {
    ui.buttons.replaceChildren();
    for (const [text, onClick, opts = {}] of specs) {
      const button = document.createElement('button');
      button.className = opts.primary ? 'primary' : 'ghost';
      button.textContent = text;
      button.disabled = !!opts.disabled;
      button.addEventListener('click', () => {
        if (!button.disabled) onClick();
      });
      ui.buttons.append(button);
    }
    return [...ui.buttons.children];
  }

  async function decide() {
    stopTimers();
    const flow = ++ui.flow;
    const sourceLabel = sourceName(ui.source);
    const quickMinutes = await OneSec.getQuickMinutes();

    // Something else restarted the flow while we were reading storage
    const stale = () => flow !== ui?.flow;
    if (stale()) return;

    ui.reasons.hidden = true;
    switch (ui.mode) {
      case 'PAUSE': {
        const attempts = (await OneSec.todayStats())['p:' + site] ?? 0;
        const nextScrollAt = await OneSec.nextScrollAt();
        if (stale()) return;
        ui.tag.textContent = attempts <= 1 ? 'first time today' : `${attempts} times today`;
        ui.title1.textContent = 'Do you really';
        ui.title2.textContent = 'want to open this?';
        ui.reasons.hidden = false;
        setButtons([
          ['← Go back', leave, { primary: true }],
          [`Quick check · ${quickMinutes} min`, quickConfirm],
          nextScrollAt
            ? [`Next scroll session at ${OneSec.formatTime(nextScrollAt)}`, () => {}, { disabled: true }]
            : [`I just want to scroll · ${OneSec.SCROLL_MINUTES} min`, scrollConfirm],
        ]);
        break;
      }
      case 'LINK':
        ui.tag.textContent = sourceLabel ? `from ${sourceLabel}` : 'from a link';
        ui.title1.textContent = 'Someone sent you this.';
        ui.title2.textContent = 'Watch it, then head back.';
        setButtons([
          ['Watch just this one', watchJustThisOne, { primary: true }],
          [sourceLabel ? `Back to ${sourceLabel}` : 'Go back', leave],
        ]);
        break;
      case 'CHECK_IN':
        ui.tag.textContent = sourceLabel ? `from ${sourceLabel}` : 'from a link';
        ui.title1.textContent = 'That was the one.';
        ui.title2.textContent = sourceLabel ? `Back to ${sourceLabel}?` : 'Head back?';
        setButtons([
          [sourceLabel ? `Back to ${sourceLabel}` : 'Go back', leave, { primary: true }],
          ['Keep scrolling', keepScrolling],
        ]);
        break;
      case 'TIME_UP':
        ui.tag.textContent = `quick check · ${quickMinutes} min`;
        ui.title1.textContent = "Time's up.";
        ui.title2.textContent = 'Got what you needed?';
        setButtons([
          ['Done', leave, { primary: true }],
          ['I need more time', fullPause],
        ]);
        break;
      case 'SCROLL_END':
        ui.tag.textContent = `scroll session · ${OneSec.SCROLL_MINUTES} min`;
        ui.title1.textContent = `${OneSec.SCROLL_MINUTES} minutes are up.`;
        ui.title2.textContent = 'How do you feel?';
        setButtons([
          ["I'm done", leave, { primary: true }],
          ['Keep going', fullPause],
        ]);
        break;
    }
    reveal();
  }

  function reveal() {
    if (!ui.decide.hidden) return;
    ui.breathe.hidden = true;
    ui.decide.hidden = false;
    ui.decide.classList.remove('fade');
    void ui.decide.offsetWidth; // restart the fade-in
    ui.decide.classList.add('fade');
  }

  async function quickConfirm() {
    ui.mode = 'QUICK_CONFIRM';
    stopTimers();
    const flow = ++ui.flow;
    const minutes = await OneSec.getQuickMinutes();
    const number = (await OneSec.limitCount('quick')) + 1;
    if (flow !== ui?.flow) return;

    const mustType = number >= QUICK_TYPE_FROM;
    let waitLeft = number >= QUICK_WAIT_FROM ? QUICK_WAIT_SECONDS : 0;

    ui.tag.textContent = number === 1 ? 'quick check' : `quick check #${number} today`;
    ui.title1.textContent = 'Is it that';
    ui.title2.textContent = 'important?';
    ui.reasons.hidden = true;
    const [, yes] = setButtons([
      ['No, go back', leave, { primary: true }],
      [`Yes, ${minutes} minutes`, startQuickCheck],
    ]);

    const refreshYes = () => {
      const typedEnough = !mustType || ui.reason.value.trim().length >= QUICK_REASON_MIN_CHARS;
      yes.disabled = waitLeft > 0 || !typedEnough;
      yes.textContent = waitLeft > 0 ? `Yes, ${minutes} minutes (${waitLeft})` : `Yes, ${minutes} minutes`;
    };
    if (mustType) {
      ui.reason.hidden = false;
      ui.reason.oninput = refreshYes;
      ui.reason.focus();
    }
    if (waitLeft > 0) {
      every(1000, () => {
        if (waitLeft > 0) waitLeft--;
        refreshYes();
      });
    }
    refreshYes();
    reveal();
  }

  async function scrollConfirm() {
    ui.mode = 'SCROLL_CONFIRM';
    stopTimers();
    const flow = ++ui.flow;
    const number = (await OneSec.limitCount('scroll')) + 1;
    const nextAt = OneSec.formatTime(await OneSec.nextScrollAtIfStartedNow());
    const alternatives = await OneSec.getAlternativesToSuggest();
    if (flow !== ui?.flow) return;

    const minutes = OneSec.SCROLL_MINUTES;
    let waitLeft = SCROLL_CONFIRM_WAIT_SECONDS;

    ui.tag.textContent = `scroll session #${number} today · next one at ${nextAt}`;
    ui.title1.textContent = 'Before you scroll,';
    ui.title2.textContent = 'how about…';
    ui.reasons.hidden = true;
    // The alternatives take the main button's place, one each
    const buttons = setButtons([
      ...alternatives.map((alternative) => [alternative, leave, { primary: true }]),
      [`Scroll for ${minutes} min (${waitLeft})`, startScrollSession, { disabled: true }],
    ]);
    const scroll = buttons[buttons.length - 1];
    every(1000, () => {
      waitLeft--;
      scroll.textContent = waitLeft > 0 ? `Scroll for ${minutes} min (${waitLeft})` : `Scroll for ${minutes} min`;
      scroll.disabled = waitLeft > 0;
    });
    reveal();
  }

  // ---- Markup ----

  const HTML = `
    <div class="wrap">
      <section class="breathe">
        <div class="site"></div>
        <div class="ring">
          <div class="outer"></div>
          <div class="mid"></div>
          <div class="core"></div>
          <div class="count">5</div>
        </div>
        <div class="label">breathe in</div>
      </section>
      <section class="decide" hidden>
        <div class="tag"></div>
        <h1 class="t1"></h1>
        <h1 class="t2"></h1>
        <div class="reasons" hidden>
          <div class="why">WHY?</div>
          <div class="chips">
            <span class="chip">Bored</span>
            <span class="chip">Just habit</span>
            <span class="chip">Escape</span>
            <span class="chip">Procrastinating</span>
            <span class="chip">Intentional</span>
          </div>
        </div>
        <input class="reason" type="text" placeholder="What are you checking?" autocomplete="off" hidden>
        <div class="buttons"></div>
      </section>
    </div>`;

  const CSS = `
    :host { all: initial; }
    * { box-sizing: border-box; }
    [hidden] { display: none !important; }
    .wrap {
      position: fixed; inset: 0; overflow: auto;
      display: flex; align-items: center; justify-content: center;
      background: #0A0A0A; color: #F0F0F0;
      font: 300 15px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
      -webkit-font-smoothing: antialiased;
    }
    section { width: 100%; max-width: 420px; padding: 32px 28px; }
    .breathe { display: flex; flex-direction: column; align-items: center; }
    .site { color: #888; font-size: 13px; letter-spacing: .05em; margin-bottom: 60px; }
    .ring { position: relative; width: 200px; height: 200px; }
    .ring > div { position: absolute; border-radius: 50%; }
    .outer { inset: 0; border: 2px solid #E8E8E8; opacity: .3; animation: outer 4s ease-in-out infinite; }
    .mid { inset: 20px; border: 2px solid #E8E8E8; opacity: .5; animation: mid 4s ease-in-out infinite; }
    .core { inset: 60px; background: #2A2A2A; border: 2px solid #E8E8E8; animation: core 4s ease-in-out infinite; }
    .count {
      inset: 0; display: flex; align-items: center; justify-content: center;
      font-size: 36px; color: #F0F0F0;
    }
    @keyframes core { 0%, 100% { transform: scale(.6); } 50% { transform: scale(1); } }
    @keyframes mid { 0%, 100% { transform: scale(.8); } 50% { transform: scale(1); } }
    @keyframes outer { 0%, 100% { transform: scale(.6); opacity: .15; } 50% { transform: scale(.72); opacity: .31; } }
    .label { margin-top: 36px; color: #888; letter-spacing: .06em; }
    .decide.fade { animation: fade .3s ease-out; }
    @keyframes fade { from { opacity: 0; } to { opacity: 1; } }
    .tag {
      display: inline-block; margin-bottom: 12px; padding: 6px 14px;
      background: #1C1C1C; border-radius: 999px;
      color: #888; font-size: 11px; letter-spacing: .06em;
    }
    h1 { margin: 0 0 8px; font-size: 28px; font-weight: 300; color: #F0F0F0; }
    h1.t2 { margin-bottom: 40px; }
    .why { margin-bottom: 12px; color: #444; font-size: 10px; font-weight: 500; letter-spacing: .15em; }
    .chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 48px; }
    .chip {
      padding: 8px 16px; background: #1C1C1C; border-radius: 999px;
      color: #888; font-size: 13px; cursor: pointer; user-select: none;
    }
    .chip.on { background: #E8E8E8; color: #0A0A0A; }
    .reason {
      width: 100%; margin-bottom: 32px; padding: 12px 16px;
      background: #1C1C1C; border: none; border-radius: 999px; outline: none;
      color: #F0F0F0; font: inherit;
    }
    .reason::placeholder { color: #444; }
    .buttons { display: flex; flex-direction: column; gap: 10px; }
    button {
      width: 100%; padding: 14px; border-radius: 12px; cursor: pointer;
      font: 400 15px system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; letter-spacing: .05em;
    }
    button.primary { background: #E8E8E8; color: #0A0A0A; border: none; }
    button.ghost { background: transparent; color: #888; border: 1px solid #1C1C1C; }
    button:hover:not(:disabled) { filter: brightness(1.15); }
    button:disabled { opacity: .45; cursor: default; }
  `;

  start();
})();
