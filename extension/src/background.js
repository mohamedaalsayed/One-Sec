// One Sec — tab actions the content script can't do itself.
'use strict';

const api = globalThis.browser ?? globalThis.chrome;

api.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const tab = sender.tab;
  if (!tab) return false;

  switch (message?.type) {
    // Where a link came from, when it was opened in a new tab
    case 'openerHost':
      openerHost(tab).then(sendResponse, () => sendResponse(null));
      return true;
    // "Go back": back to the tab the link came from, or just close this one
    case 'closeTab':
      closeTab(tab);
      return false;
  }
  return false;
});

async function openerHost(tab) {
  if (tab.openerTabId == null) return null;
  const opener = await api.tabs.get(tab.openerTabId);
  return opener.url ? new URL(opener.url).hostname : null;
}

async function closeTab(tab) {
  if (tab.openerTabId != null) {
    try {
      await api.tabs.update(tab.openerTabId, { active: true });
    } catch {
      // Opener already closed
    }
  }
  // Closing the last tab would close the window
  const tabs = await api.tabs.query({ windowId: tab.windowId });
  if (tabs.length <= 1) await api.tabs.create({ windowId: tab.windowId });
  await api.tabs.remove(tab.id);
}
