// Help page: fills [data-logo] avatars with agent logos and turns [data-cmd] buttons into commands.
// @ts-check
(function () {
  // @ts-ignore provided by VS Code
  const vscode = acquireVsCodeApi();
  // @ts-ignore set by agent-icons.js
  const logos = window.HERDR_AGENT_ICONS || {};
  for (const el of /** @type {NodeListOf<HTMLElement>} */ (document.querySelectorAll('[data-logo]')))
    el.innerHTML = logos[el.dataset.logo || ''] || '';
  document.addEventListener('click', (e) => {
    const b = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('[data-cmd]'));
    if (b) vscode.postMessage({ type: 'cmd', command: b.dataset.cmd });
  });
})();
