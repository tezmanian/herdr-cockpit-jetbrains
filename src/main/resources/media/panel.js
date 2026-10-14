// Herdr sidebar webview. Renders the ViewState posted by src/panel.ts (shape: src/viewState.ts)
// and sends user actions back as { type: 'cmd', command, spaceId?, paneId? }.
// UI-only state (which cards and shell groups are open) lives in vscode.setState.
// @ts-check
(function () {
  // @ts-ignore provided by VS Code
  const vscode = acquireVsCodeApi();
  const saved = vscode.getState() || {};
  /** @type {{ open: Record<string, boolean>, shellsOpen: Record<string, boolean>, expanded: Record<string, boolean>, usageOpen?: boolean }} */
  const ui = { open: saved.open || {}, shellsOpen: saved.shellsOpen || {}, expanded: saved.expanded || {}, usageOpen: saved.usageOpen };
  const app = /** @type {HTMLElement} */ (document.getElementById('app'));
  /** @type {any} */
  let state;
  /** When the earliest "live output" shell stops counting as live; re-render then. */
  let liveUntil = Infinity;

  const STATUS = {
    blocked: { label: 'Blocked', icon: 'warning' },
    done: { label: 'Done', icon: 'pass-filled' },
    working: { label: 'Working', icon: 'sync' },
    idle: { label: 'Idle', icon: 'circle-filled' },
    unknown: { label: 'Unknown', icon: 'question' },
  };
  const ORDER = ['blocked', 'done', 'working', 'idle'];
  /** Agent logos keyed by Herdr agent name (media/agent-icons.js, generated). */
  // @ts-ignore set by agent-icons.js
  const LOGOS = /** @type {Record<string, string>} */ (window.HERDR_AGENT_ICONS || {});
  /** "claude" / "claude-code" / "antigravity_cli" -> logo SVG, or '' to fall back to a letter. */
  function agentLogo(/** @type {string|undefined} */ kind) {
    const k = String(kind || '').toLowerCase();
    return (k && (LOGOS[k] || LOGOS[k.split(/[^a-z0-9]/)[0]])) || '';
  }
  const LIVE_MS = 12000;

  const esc = (/** @type {unknown} */ s) =>
    String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] || c);
  const icon = (/** @type {string} */ name, cls = '') => `<span class="codicon codicon-${name} ${cls}"></span>`;
  const ctx = (/** @type {object} */ o) => esc(JSON.stringify({ ...o, preventDefaultContextMenuItems: true }));

  function ago(/** @type {number} */ ms) {
    const s = Math.max(0, Math.round(ms / 1000));
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m`;
    const h = Math.floor(m / 60);
    return h < 24 ? `${h}h${m % 60 ? ` ${m % 60}m` : ''}` : `${Math.floor(h / 24)}d`;
  }
  /** Time left, to the minute under a day: "24m", "1h 20m", "2d 23h". */
  function left(/** @type {number} */ ms) {
    const m = Math.max(0, Math.ceil(ms / 60000));
    if (m < 60) return `${m}m`;
    const h = Math.floor(m / 60);
    return h < 24 ? `${h}h${m % 60 ? ` ${m % 60}m` : ''}` : `${Math.floor(h / 24)}d${h % 24 ? ` ${h % 24}h` : ''}`;
  }
  /** Countdown that the ticker keeps current. */
  const until = (/** @type {number} */ t) => `<span class="t" data-until="${t}">${left(t - Date.now())}</span>`;
  const since = (/** @type {number|undefined} */ t) => (t ? `<span class="t" data-since="${t}">${ago(Date.now() - t)}</span>` : '');

  function btn(/** @type {string} */ act, /** @type {string} */ ic, /** @type {string} */ title, extra = '') {
    return `<button class="icon-btn" data-act="${act}" ${extra} title="${esc(title)}" aria-label="${esc(title)}">${icon(ic)}</button>`;
  }

  // ---------- pieces ----------
  function header() {
    const t = state.totals;
    const stats = ORDER.filter((s) => t[s])
      .map((s) => {
        const attention = s === 'blocked' || s === 'done';
        const tag = attention ? 'button' : 'span';
        const act = attention ? ` data-act="attention" title="Jump to the agent that needs you"` : ` title="${t[s]} ${s}"`;
        return `<${tag} class="stat st-${s}"${act}>${icon(STATUS[s].icon, s === 'working' ? 'spin' : '')}<span class="n">${t[s]}</span> ${s}</${tag}>`;
      })
      .join('');
    const quiet = `<span class="stat quiet">${state.spaces.length} spaces · ${t.agents} agents</span>`;
    return `<div class="top">
      <div class="stats">${stats || quiet}</div>
      <div class="tools">
        ${btn('toggleShellPanes', 'terminal', state.showShells ? 'Hide shell panes' : 'Show shell panes', `aria-pressed="${state.showShells}"`)}
      </div>
    </div>`;
  }

  function statePill(/** @type {any} */ p) {
    const s = p.status || 'unknown';
    const label = p.stateLabel || STATUS[s]?.label || s;
    const ic = s === 'working' ? icon('sync', 'spin') : '';
    return `<span class="state">${ic}${esc(label)}${p.since ? ' ' : ''}${since(p.since)}</span>`;
  }

  /** Chevron that shows/hides a row's details (persisted per pane). */
  function expander(/** @type {boolean} */ open) {
    return `<button class="icon-btn expander" data-act="toggleRow" aria-expanded="${open}" title="${open ? 'Hide details' : 'Show details'}">${icon(open ? 'chevron-up' : 'chevron-down')}</button>`;
  }

  /** Expanded-only extras: chips, then the working folder. */
  function extras(/** @type {any} */ p, /** @type {string[]} */ more = []) {
    const chips = [
      ...more,
      p.tab ? `<span class="chip">${icon('window')}${esc(p.tab)}</span>` : '',
      ...p.chips.map((/** @type {string} */ c) => `<span class="chip">${esc(c)}</span>`),
    ].join('');
    return `${chips ? `<div class="chips">${chips}</div>` : ''}${p.cwd ? `<div class="cwd" title="${esc(p.cwd)}">${icon('folder')}${esc(p.cwd)}</div>` : ''}`;
  }

  const lines = (/** @type {string[]} */ ls) => (ls.length ? `<div class="term">${ls.map((l) => `<div>${esc(l)}</div>`).join('')}</div>` : '');
  const hint = (/** @type {any} */ p) => `${p.attached ? 'Click to show its terminal tab' : 'Click to open it in a terminal tab'}`;

  /**
   * Two lines by default (name + status, then one line of context); the chevron reveals the rest.
   * opts.attention: a "Needs you" card, always expanded, with the space name.
   */
  function agentRow(/** @type {any} */ p, /** @type {{attention?: string}} */ opts = {}) {
    const s = esc(p.status || 'unknown');
    const logo = agentLogo(p.agentKind);
    const initial = esc((p.agentKind || p.name || '?').charAt(0).toUpperCase());
    const open = !!opts.attention || !!ui.expanded[p.id];
    // Blocked/done agents show their screen; others the gist of their last reply. Never both.
    const story = p.preview.length ? lines(p.preview) : p.headline && p.headline !== p.detail ? `<div class="headline">${esc(p.headline)}</div>` : '';
    const body = open
      ? `${p.detail ? `<div class="sub">${esc(p.detail)}</div>` : ''}${story}${extras(p, p.agentKind && p.agentKind.toLowerCase() !== p.name.toLowerCase() ? [`<span class="chip">${icon('hubot')}${esc(p.agentKind)}</span>`] : [])}`
      : p.detail || p.headline ? `<div class="sub">${esc(p.detail || p.headline)}</div>` : '';
    return `<div class="row agent st-${s}${p.focused ? ' herdr-focused' : ''}${open ? ' open' : ''}" tabindex="0" data-nav data-act="attach"
        data-space="${esc(p.spaceId)}" data-pane="${esc(p.id)}" data-key="pane:${esc(p.id)}"
        data-vscode-context="${ctx({ webviewSection: 'agent', spaceId: p.spaceId, paneId: p.id })}"
        title="${esc(`${p.name} · ${p.id}\n${hint(p)}`)}">
      <div class="avatar${logo ? ' logo' : ''}">${logo || initial}</div>
      <div class="main">
        <div class="line1"><span class="name">${esc(p.name)}</span>${statePill(p)}</div>
        ${opts.attention ? `<div class="where">${esc(opts.attention)}</div>` : ''}
        ${body}
      </div>
      <div class="side"><span class="actions">${btn('review', 'diff', 'Review changes')}</span>${opts.attention ? '' : expander(open)}</div>
    </div>`;
  }

  function shellState(/** @type {any} */ sh) {
    if (sh.state === 'running' && sh.changedAt && Date.now() - sh.changedAt < LIVE_MS) {
      liveUntil = Math.min(liveUntil, sh.changedAt + LIVE_MS);
      return 'live';
    }
    if (sh.state === 'running') return 'running';
    if (sh.state === 'prompt') return sh.failed ? 'failed' : 'prompt';
    return 'unknown';
  }

  function shellRow(/** @type {any} */ p) {
    const sh = p.shell;
    const st = shellState(sh);
    const pill = { live: 'live output', running: 'running', prompt: 'at prompt', failed: 'error', unknown: '…' }[st];
    const changed = sh.changedAt && (st === 'live' || st === 'running') ? ` ${since(sh.changedAt)}` : '';
    const open = !!ui.expanded[p.id];
    const cmdLine = (/** @type {string} */ sigil, /** @type {string} */ text, cls = '') =>
      `<div class="cmd ${cls}"><span class="sigil">${sigil}</span><span class="text">${esc(text)}</span></div>`;
    // One line of context: what runs now, else the last command, else what's typed.
    const main = sh.command ? cmdLine(sh.state === 'running' ? '▶' : '$', sh.command) : sh.typed ? cmdLine('›', sh.typed, 'typed') : '';
    const body = open ? `${main}${sh.command && sh.typed ? cmdLine('›', sh.typed, 'typed') : ''}${lines(sh.output)}${extras(p)}` : main;
    return `<div class="row shell sh-${st}${p.focused ? ' herdr-focused' : ''}${open ? ' open' : ''}" tabindex="0" data-nav data-act="attach"
        data-space="${esc(p.spaceId)}" data-pane="${esc(p.id)}" data-key="pane:${esc(p.id)}"
        data-vscode-context="${ctx({ webviewSection: 'shell', spaceId: p.spaceId, paneId: p.id })}"
        title="${esc(`${p.name} · ${p.id}\n${hint(p)}`)}">
      <div class="avatar">${icon('terminal')}</div>
      <div class="main">
        <div class="line1"><span class="name">${esc(p.name)}</span><span class="state">${pill}${changed}</span></div>
        ${body}
      </div>
      <div class="side">${expander(open)}</div>
    </div>`;
  }

  function shellGroup(/** @type {any} */ sp) {
    if (!sp.shells.length) return '';
    const open = ui.shellsOpen[sp.id] ?? sp.shells.length <= 3;
    const states = sp.shells.map((/** @type {any} */ p) => shellState(p.shell));
    const n = (/** @type {string} */ s) => states.filter((/** @type {string} */ x) => x === s).length;
    const sum = [
      n('live') + n('running') ? `<span class="sh-running">${n('live') + n('running')} running</span>` : '',
      n('failed') ? `<span class="sh-failed">${n('failed')} error${n('failed') > 1 ? 's' : ''}</span>` : '',
    ].join('');
    return `<div class="group${open ? '' : ' closed'}" tabindex="0" data-nav data-act="toggleShells" data-space="${esc(sp.id)}" data-key="shells:${esc(sp.id)}"
        aria-expanded="${open}">
        <span class="chev">${icon('chevron-down')}</span>Shells · ${sp.shells.length}<span class="sum">${sum}</span>
      </div>
      <div class="shells">${sp.shells.map(shellRow).join('')}</div>`;
  }

  /** Branch · last commit · upstream sync · working-tree state · Review. */
  function gitLine(/** @type {any} */ sp) {
    const g = sp.git;
    if (!g) return sp.path ? `<span class="m" title="${esc(sp.path)}">${icon('folder')}${esc(sp.path.split('/').pop())}</span>` : '';
    const n = (/** @type {number} */ k, /** @type {string} */ one, many = `${one}s`) => `${k} ${k === 1 ? one : many}`;
    const last = g.lastCommit ? `Last commit ${ago(Date.now() - g.lastCommit.at)} ago: ${g.lastCommit.subject}` : 'No commits yet';
    const branch = `<span class="m branch" title="${esc(`${sp.worktree ? 'Herdr worktree · ' : ''}Branch ${g.branch}\n${last}`)}">${icon(sp.worktree ? 'repo-forked' : 'git-branch')}<span class="bn">${esc(g.branch || '?')}</span>${
      g.lastCommit ? `<span class="age">${ago(Date.now() - g.lastCommit.at)}</span>` : ''
    }</span>`;
    const sync = g.detached
      ? ''
      : !g.upstream
        ? `<span class="m muted" title="This branch doesn't track a remote branch (never pushed, or pushed without -u)">${icon('cloud-upload')}no upstream</span>`
        : g.ahead || g.behind
          ? `<span class="m sync" title="${esc(`${g.ahead ? `${n(g.ahead, 'commit')} to push` : ''}${g.ahead && g.behind ? ', ' : ''}${g.behind ? `${n(g.behind, 'commit')} to pull` : ''} (${g.upstream})`)}">${
              g.ahead ? `${icon('arrow-up')}${g.ahead}` : ''}${g.behind ? `${icon('arrow-down')}${g.behind}` : ''}</span>`
          : `<span class="m muted" title="${esc(`Up to date with ${g.upstream}`)}">${icon('check')}synced</span>`;
    if (!g.changes) return branch + sync + `<span class="m clean" title="No uncommitted changes">${icon('pass')}clean</span>`;
    const parts = [
      g.conflicts ? `<span class="g-conflict">${icon('warning')}${n(g.conflicts, 'conflict')}</span>` : '',
      g.staged ? `<span class="g-staged">${icon('circle-filled')}${g.staged} staged</span>` : '',
      g.modified ? `<span class="g-modified">${icon('circle-filled')}${g.modified} modified</span>` : '',
      g.untracked ? `<span class="g-new">${icon('add')}${g.untracked} new</span>` : '',
    ].join('');
    return `${branch}${sync}<button class="m changes" data-act="review" title="Review uncommitted changes (HEAD ↔ working tree)">${parts}<span class="review">${icon('diff')}Review</span></button>`;
  }

  function spaceCard(/** @type {any} */ sp) {
    const attention = sp.counts.blocked + sp.counts.done > 0;
    const open = ui.open[sp.id] ?? (sp.mounted || sp.focused || attention);
    const st = esc(sp.status || 'none');
    const meta = gitLine(sp) + sp.tokens.map((/** @type {string} */ t) => `<span class="m">${esc(t)}</span>`).join('');
    const dots = sp.agents.map((/** @type {any} */ a) => `<span class="dot st-${esc(a.status)}" title="${esc(a.name)}: ${esc(a.status)}"></span>`).join('');
    const body = sp.agents.length || sp.shells.length
      ? sp.agents.map((/** @type {any} */ a) => agentRow(a)).join('') + shellGroup(sp)
      : `<div class="empty-note">No agents${state.showShells ? ' or shells' : ''} in this space</div>`;
    const add = `<button class="add-row" data-act="newTab" data-nav data-key="new:${esc(sp.id)}" title="New tab in ${esc(sp.label)}: a shell or an agent">${icon('add')}New tab</button>`;
    return `<section class="space st-${st}${open ? ' open' : ''}${sp.focused ? ' herdr-focused' : ''}" data-space="${esc(sp.id)}">
      <div class="space-head" tabindex="0" data-nav data-act="switch" data-space="${esc(sp.id)}" data-key="space:${esc(sp.id)}"
          data-vscode-context="${ctx({ webviewSection: sp.mounted ? 'space-mounted' : 'space', spaceId: sp.id })}"
          aria-expanded="${open}" title="${esc(`${sp.label}${sp.path ? `\n${sp.path}` : ''}\nClick to switch to this space`)}">
        <span class="chev" data-act="toggleSpace" title="${open ? 'Collapse' : 'Expand'}">${icon('chevron-down')}</span>
        <span class="space-name">${esc(sp.label)}</span>
        <span class="dots">${dots}</span>
      </div>
      <div class="space-meta">${meta}</div>
      <div class="space-body">${body}${add}</div>
    </section>`;
  }

  // ---------- usage ----------
  const tokens = (/** @type {number} */ n) => (n >= 1e6 ? `${(n / 1e6).toFixed(1)}M` : n >= 1e3 ? `${Math.round(n / 1e3)}k` : `${n}`);
  const clock = (/** @type {number} */ t) => new Date(t).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  /** Provider plan ids as their own apps name them (Codex reports ChatGPT Business as "team"). */
  /** @type {Record<string, string>} */
  const PLAN_NAMES = { team: 'Business', pro: 'Pro', plus: 'Plus', enterprise: 'Enterprise', edu: 'Edu', free: 'Free' };
  const level = (/** @type {number} */ pct) => (pct >= 85 ? 'high' : pct >= 60 ? 'warn' : 'ok');

  /** The number for the collapsed summary: the 5-hour window when there is one, else the first window. */
  function usageHeadline(/** @type {any} */ u) {
    const w = u.windows.find((/** @type {any} */ x) => x.label === '5h') ?? u.windows[0];
    if (w) return { text: `${w.label} ${Math.round(w.usedPercent)}%`, level: level(w.usedPercent) };
    if (u.credits) return { text: `${u.credits.month.toFixed(1)} cr`, level: 'ok' };
    if (u.block) return { text: `${tokens(u.block.tokens)} tok`, level: 'ok' };
    return { text: '—', level: 'ok' };
  }

  function meter(/** @type {any} */ w) {
    const pct = Math.round(w.usedPercent);
    const when = w.reset
      ? '<span class="reset" title="This window has reset since the agent last reported its usage">reset</span>'
      : w.resetsAt
        ? `<span class="reset" title="Resets at ${esc(new Date(w.resetsAt).toLocaleString())}">↻ ${until(w.resetsAt)}</span>`
        : '<span class="reset"></span>';
    return `<div class="meter lvl-${level(pct)}" title="${esc(`${w.label} window: ${pct}% used`)}">
      <span class="ml">${esc(w.label)}</span><span class="bar"><span class="fill" data-pct="${Math.min(100, pct)}"></span></span><span class="pct">${pct}%</span>${when}
    </div>`;
  }

  function usageRow(/** @type {any} */ u) {
    const logo = agentLogo(u.id);
    const fresh = u.live
      ? '<span title="Fetched from the provider just now">live</span>'
      : u.windows.length && u.asOf
        ? `<span title="When the agent last reported these numbers">as of ${since(u.asOf)} ago</span>`
        : '';
    const meta = [u.plan ? esc(PLAN_NAMES[u.plan] ?? u.plan.charAt(0).toUpperCase() + u.plan.slice(1)) : '', fresh].filter(Boolean).join(' · ');
    const tip = (/** @type {any} */ b) => `Tokens Claude Code used ${clock(b.startsAt)}–${clock(b.endsAt)} on this machine (input + output + cache writes). Cache reads: ${tokens(b.cacheRead)}.`;
    const block = !u.block
      ? ''
      : u.block.exact
        ? `<div class="ustat" title="${esc(tip(u.block))}">This session: <b>${tokens(u.block.tokens)}</b> tokens</div>`
        : `<div class="ustat" title="${esc(tip(u.block) + ' The window is estimated from your first message; turn on plan limits for the exact one.')}">5-hour block (est.): <b>${tokens(u.block.tokens)}</b> tokens · ends ${clock(u.block.endsAt)}</div>`;
    const c = u.credits;
    const fmt = (/** @type {number} */ n) => n.toLocaleString(undefined, { maximumFractionDigits: 2 });
    const credits = !c
      ? ''
      : `<div class="ustat" title="Kiro CLI credits used on this machine${c.limit ? ' (your allowance comes from the herdr.kiroMonthlyCredits setting)' : ''}"><b>${fmt(c.month)}</b>${
          c.limit ? ` / ${fmt(c.limit)}` : ''
        } credits this month · ${fmt(c.today)} today</div>${
          c.limit
            ? ''
            : `<button class="link-btn" data-act="setKiroCredits" title="Kiro doesn't share your plan's allowance with other tools; enter it once to see a bar">${icon('edit')}Set monthly credits…</button>`
        }`;
    const setup = u.canSetUpLimits
      ? `<button class="link-btn" data-act="setupClaudeUsage" title="Claude Code shares plan limits only with a status line; set one up">${icon('graph')}Show plan limits…</button>`
      : '';
    const none = !u.windows.length && !block && !credits ? `<div class="ustat">No usage recorded yet</div>` : '';
    return `<div class="usage-row">
      <div class="avatar logo small">${logo || esc(u.name.charAt(0))}</div>
      <div class="main">
        <div class="line1"><span class="name">${esc(u.name)}</span><span class="umeta">${meta}</span></div>
        ${u.windows.map(meter).join('')}${block}${credits}${none}${setup}
      </div>
    </div>`;
  }

  function usageSection() {
    if (!state.usage?.length) return '';
    const open = ui.usageOpen ?? true;
    const summary = state.usage
      .map((/** @type {any} */ u) => {
        const h = usageHeadline(u);
        return `<span class="usum lvl-${h.level}" title="${esc(u.name)}">${agentLogo(u.id) || esc(u.name.charAt(0))}${esc(h.text)}</span>`;
      })
      .join('');
    return `<div class="section-label usage-label" data-act="toggleUsage" tabindex="0" data-nav data-key="usage" aria-expanded="${open}">
        <span class="chev${open ? '' : ' closed'}">${icon('chevron-down')}</span>Usage${open ? '' : `<span class="usums">${summary}</span>`}
      </div>
      ${open ? `<div class="usage">${state.usage.map(usageRow).join('')}</div>` : ''}`;
  }

  /** Herdr isn't connected: say why and offer the right next step (reasons from src/connection.ts). */
  function disconnected() {
    const c = state.connection || { kind: 'connecting' };
    const b = (/** @type {string} */ act, /** @type {string} */ label, primary = false) =>
      `<button class="btn${primary ? '' : ' secondary'}" data-act="${act}">${esc(label)}</button>`;
    const path = (/** @type {string} */ label, /** @type {string|undefined} */ p) => (p ? `<p class="path">${esc(label)} <code>${esc(p)}</code></p>` : '');
    if (c.kind === 'connecting' || c.kind === 'starting' || c.kind === 'reconnecting')
      return `<div class="splash"><div class="big">${icon('sync', 'spin')}</div><h2>${c.kind === 'starting' ? 'Starting Herdr…' : 'Connecting to Herdr…'}</h2></div>`;
    /** @type {Record<string, {icon: string, title: string, text: string, buttons: string, extra?: string}>} */
    const screens = {
      'not-installed': {
        icon: 'cloud-download',
        title: "Herdr isn't installed",
        text: 'Herdr Cockpit shows and controls a Herdr server, and needs the <code>herdr</code> command.',
        buttons: b('openInstallDocs', 'Install Herdr', true) + b('refresh', 'Retry') + b('openSettings', 'Set herdr path'),
        extra: path('Looked for', c.binary),
      },
      'not-running': {
        icon: 'debug-disconnect',
        title: "Herdr isn't running",
        text: 'Your spaces and agents live in the Herdr server. Start it in the background, or open Herdr here.',
        buttons: b('startServer', 'Start Herdr', true) + b('openTui', 'Open Herdr TUI') + b('refresh', 'Retry'),
        extra: path(c.customSocket ? 'Socket (from herdr.socketPath):' : 'Socket:', c.socket) + (c.customSocket ? b('openSettings', 'Settings') : ''),
      },
      crashed: {
        icon: 'warning',
        title: 'Herdr stopped unexpectedly',
        text: 'Its socket was left behind; starting it again is safe. Herdr restores your spaces and tabs.',
        buttons: b('startServer', 'Start Herdr', true) + b('openTui', 'Open Herdr TUI') + b('refresh', 'Retry'),
        extra: path('Socket:', c.socket),
      },
      incompatible: {
        icon: 'versions',
        title: "This Herdr version isn't compatible",
        text: `Herdr ${esc(c.version || '?')} speaks protocol ${esc(c.protocol ?? '?')}; Herdr Cockpit expects ${esc(c.expectedProtocol)}. Update Herdr (<code>herdr update</code>) or Herdr Cockpit.`,
        buttons: b('refresh', 'Retry', true),
      },
      unreachable: {
        icon: 'debug-disconnect',
        title: "Herdr isn't answering",
        text: `The server is running but didn't respond${c.detail ? `: ${esc(c.detail)}` : ''}.`,
        buttons: b('refresh', 'Retry', true) + b('openTui', 'Open Herdr TUI'),
        extra: path('Socket:', c.socket),
      },
    };
    const sc = screens[c.reason || 'not-running'] || screens['not-running'];
    return `<div class="splash">
      <div class="big">${icon(sc.icon)}</div>
      <h2>${sc.title}</h2>
      <p>${sc.text}</p>
      <div class="btns">${sc.buttons}</div>
      ${sc.extra || ''}
      <p class="links"><a href="#" data-act="copyDiagnostics">Copy diagnostics</a> · <a href="#" data-act="setupHub">Set up Cockpit window</a> · <a href="#" data-act="openHelp">Help</a></p>
    </div>`;
  }

  /** A strip above the view while reconnecting, or when Herdr's protocol differs from this build's. */
  function banner() {
    const c = state.connection || {};
    if (c.kind === 'reconnecting') return `<div class="banner">${icon('sync', 'spin')}Reconnecting to Herdr…</div>`;
    if (c.kind === 'connected' && c.protocol && c.protocol !== c.expectedProtocol)
      return `<div class="banner warn" title="Herdr Cockpit was built against protocol ${esc(c.expectedProtocol)}">${icon('warning')}Herdr ${esc(c.version || '')} uses protocol ${esc(c.protocol)}; some details may be missing. Update Herdr Cockpit.</div>`;
    return '';
  }

  function render() {
    if (!state) return;
    liveUntil = Infinity;
    const focusKey = /** @type {HTMLElement|null} */ (document.activeElement)?.closest?.('[data-key]')?.getAttribute('data-key');
    if (!state.connected) {
      app.innerHTML = disconnected();
      return;
    }
    const where = (/** @type {any} */ a) => state.spaces.find((/** @type {any} */ s) => s.id === a.spaceId)?.label;
    const attention = state.attention.length
      ? `<div class="section-label">Needs you <span class="count">${state.attention.length}</span></div>
         <div class="attention">${state.attention.map((/** @type {any} */ a) => agentRow(a, { attention: where(a) })).join('')}</div>`
      : '';
    const spaces = state.spaces.length
      ? state.spaces.map(spaceCard).join('')
      : `<div class="splash"><div class="big">${icon('layers')}</div><h2>No spaces yet</h2><p>A space is a folder with its agents and shells.</p><div class="btns"><button class="btn" data-act="newSpace">New space</button></div></div>`;
    const spacesLabel = `<div class="section-label">Spaces<button class="icon-btn label-btn" data-act="newSpace" title="New space: a folder or a new git worktree" aria-label="New space">${icon('add')}</button></div>`;
    const stale = state.connection && state.connection.kind === 'reconnecting';
    app.innerHTML = `${header()}${banner()}<div class="${stale ? 'stale' : ''}">${usageSection()}${attention}${spacesLabel}<div class="spaces">${spaces}</div></div>`;
    for (const n of /** @type {NodeListOf<HTMLElement>} */ (app.querySelectorAll('.fill[data-pct]'))) n.style.width = `${n.dataset.pct}%`;
    // Re-rendering restarts CSS animations; offset them by wall-clock so spinners and pulses don't jump.
    const delay = `-${Date.now() % 1000}ms`;
    for (const n of /** @type {NodeListOf<HTMLElement>} */ (app.querySelectorAll('.spin, .avatar'))) n.style.animationDelay = delay;
    if (focusKey) /** @type {HTMLElement|null} */ (app.querySelector(`[data-key="${CSS.escape(focusKey)}"]`))?.focus();
  }

  const persist = () => vscode.setState(ui);

  // ---------- interaction ----------
  app.addEventListener('click', (e) => {
    const el = /** @type {HTMLElement|null} */ (/** @type {HTMLElement} */ (e.target).closest('[data-act]'));
    if (!el) return;
    e.stopPropagation();
    e.preventDefault(); // some actions are <a href="#"> links
    const act = el.dataset.act;
    const holder = /** @type {HTMLElement|null} */ (el.closest('[data-space]'));
    const spaceId = el.dataset.space || holder?.dataset.space;
    const paneId = el.dataset.pane || /** @type {HTMLElement|null} */ (el.closest('[data-pane]'))?.dataset.pane;
    if (act === 'toggleSpace' && spaceId) {
      const card = el.closest('.space');
      ui.open[spaceId] = !card?.classList.contains('open');
      persist();
      return render();
    }
    if (act === 'toggleUsage') {
      ui.usageOpen = !(ui.usageOpen ?? true);
      persist();
      return render();
    }
    if (act === 'toggleRow' && paneId) {
      ui.expanded[paneId] = !ui.expanded[paneId];
      persist();
      return render();
    }
    if (act === 'toggleShells' && spaceId) {
      ui.shellsOpen[spaceId] = el.classList.contains('closed');
      persist();
      return render();
    }
    if (act === 'switch' && spaceId) {
      // Focus on the chosen space: expand it, collapse the rest, bring it into view.
      for (const sp of state.spaces) ui.open[sp.id] = sp.id === spaceId;
      persist();
      render();
      app.querySelector(`.space[data-space="${CSS.escape(spaceId)}"]`)?.scrollIntoView({ block: 'start', behavior: 'smooth' });
    }
    vscode.postMessage({ type: 'cmd', command: act, spaceId, paneId });
  });

  app.addEventListener('keydown', (e) => {
    const el = /** @type {HTMLElement} */ (e.target);
    if ((e.key === 'Enter' || e.key === ' ') && el.matches('[data-nav]')) {
      e.preventDefault();
      el.click();
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      const all = /** @type {HTMLElement[]} */ ([...app.querySelectorAll('[data-nav]')].filter((n) => /** @type {HTMLElement} */ (n).offsetParent));
      const i = all.indexOf(/** @type {HTMLElement} */ (el.closest('[data-nav]')));
      const next = all[Math.max(0, Math.min(all.length - 1, i + (e.key === 'ArrowDown' ? 1 : -1)))];
      if (next) {
        e.preventDefault();
        next.focus();
      }
    } else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && el.matches('.row[data-pane]')) {
      const id = el.dataset.pane;
      if (id && !el.closest('.attention')) {
        ui.expanded[id] = e.key === 'ArrowRight';
        persist();
        render();
      }
    } else if ((e.key === 'ArrowLeft' || e.key === 'ArrowRight') && el.matches('.space-head')) {
      const id = el.dataset.space;
      if (id) {
        ui.open[id] = e.key === 'ArrowRight';
        persist();
        render();
      }
    }
  });

  window.addEventListener('message', (e) => {
    if (e.data?.type === 'state') {
      state = e.data.state;
      render();
    }
  });

  // Tick relative times without a full re-render.
  setInterval(() => {
    if (Date.now() >= liveUntil) return render();
    for (const n of /** @type {NodeListOf<HTMLElement>} */ (app.querySelectorAll('[data-since]')))
      n.textContent = ago(Date.now() - Number(n.dataset.since));
    for (const n of /** @type {NodeListOf<HTMLElement>} */ (app.querySelectorAll('[data-until]')))
      n.textContent = left(Number(n.dataset.until) - Date.now());
  }, 1000);

  vscode.postMessage({ type: 'ready' });
})();
