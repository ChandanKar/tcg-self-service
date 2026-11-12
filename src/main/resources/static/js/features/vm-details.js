/**
 * VM Self-Service Platform - VM details side panel (U08-T05, VmDetails.dc.html)
 * "pay-api-01  Running since 08:05" under "Payments Dev · Application group · start order 2", with
 * Start or Stop for people who can operate, four quick stats (Size, CPU now, IP address, Disk used)
 * and the tabs Overview, Use, Storage, Idle and History. Use switches between 1 h, 6 h, 24 h and
 * 7 days and re-reads only the metrics. The instance ID and volume IDs show only when the server
 * sends them, which it does for administrators only (U01-T01). Cost this month, the purchase type
 * and "Could be one size smaller: m6i.medium" come from the VM's advice (U08-T06). The words and numbers are pure
 * (VmDetails.model), tested in src/test/js/vm-details.test.mjs.
 */
const VmDetails = (function() {
    'use strict';

    /** [key, label, sample period in seconds] for the Use tab. */
    const WINDOWS = [['1h', '1 h', 300], ['6h', '6 h', 300], ['24h', '24 h', 900], ['7d', '7 days', 3600]];
    const TABS = [['overview', 'Overview'], ['use', 'Use'], ['storage', 'Storage'], ['idle', 'Idle'], ['history', 'History']];
    const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const PANEL = '#dynamicSlideoutPanel[data-variant="vm-details"]';
    const NOT_COLLECTED = 'Not collected';
    const AGENT_NOTE = 'Needs the CloudWatch agent on the VM';

    const blank = v => v === null || v === undefined || v === '';
    const num = v => (blank(v) ? null : Number(v));

    function pct(value) {
        const n = num(value);
        return n === null || !Number.isFinite(n) ? '—' : `${Math.round(n)}%`;
    }

    /** "1.2 MB", "820 KB", "0 B": bytes in binary units, one decimal under 10. */
    function bytes(value) {
        const n = num(value);
        if (n === null || !Number.isFinite(n)) return '—';
        const units = ['B', 'KB', 'MB', 'GB', 'TB'];
        let v = Math.max(0, n), i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return `${i === 0 ? Math.round(v) : v >= 10 ? Math.round(v) : Math.round(v * 10) / 10} ${units[i]}`;
    }

    function toDate(ts) {
        if (blank(ts)) return null;
        const d = new Date(ts);
        return Number.isNaN(d.getTime()) ? null : d;
    }

    /** "14 Sep 2026". */
    function day(ts) {
        const d = toDate(ts);
        return d ? `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}` : '—';
    }

    /** "just now", "4 min ago", "2 h ago", "3 days ago"; "never" without a time. */
    function ago(ts, now = new Date()) {
        const d = toDate(ts);
        if (!d) return 'never';
        const minutes = Math.round((now.getTime() - d.getTime()) / 60000);
        if (minutes < 1) return 'just now';
        if (minutes < 60) return `${minutes} min ago`;
        const hours = Math.round(minutes / 60);
        if (hours < 48) return `${hours} h ago`;
        return `${Math.round(hours / 24)} days ago`;
    }

    /** "Payments Dev · Application group · start order 2" (the group's place in the start order). */
    function kicker(env, vm) {
        const parts = [];
        if (env && (env.displayName || env.name)) parts.push(env.displayName || env.name);
        const groups = ((env && env.groups) || []).map(g => g.group || g)
            .sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0));
        const index = groups.findIndex(g => g.groupId === vm.groupId);
        const group = index >= 0 ? groups[index] : null;
        const groupName = (group && (group.displayName || group.name)) || vm.groupName;
        if (groupName) parts.push(`${groupName} group`);
        if (index >= 0 && groups.length > 1) parts.push(`start order ${index + 1}`);
        return parts.join(' · ');
    }

    /** The state chip: "Running since 08:05" and its kind, as on the environment's table. */
    function stateChip(vm, now = new Date()) {
        if (typeof EnvVmTable !== 'undefined' && EnvVmTable.model && EnvVmTable.model.state) return EnvVmTable.model.state(vm, now);
        return { text: vm.status ? vm.status.charAt(0) + vm.status.slice(1).toLowerCase() : 'Unknown', kind: 'unknown' };
    }

    function latestOf(metrics) {
        if (!metrics) return null;
        const series = metrics.series || [];
        return metrics.latest || series[series.length - 1] || null;
    }

    /** Size, CPU now, IP address, Disk used. Disk use needs the CloudWatch agent, which nothing reads yet. */
    function stats(vm, inventory, metrics) {
        const latest = latestOf(metrics);
        return [
            { label: 'Size', value: (inventory && inventory.instanceType) || vm.instanceType || '—', mono: true },
            { label: 'CPU now', value: pct(latest && latest.cpuUtilization), mono: true },
            { label: 'IP address', value: (inventory && inventory.privateIp) || vm.privateIp || '—', mono: true },
            { label: 'Disk used', value: NOT_COLLECTED, title: AGENT_NOTE }
        ];
    }

    function purchaseText(option) {
        const o = String(option || '').toUpperCase();
        if (o === 'SPOT') return 'Spot';
        if (o === 'ON_DEMAND' || o === 'ONDEMAND') return 'On-demand';
        if (o === 'RESERVED') return 'Reserved';
        return o ? o.charAt(0) + o.slice(1).toLowerCase().replace(/_/g, ' ') : '—';
    }

    function money(value) {
        const n = num(value);
        return n === null || !Number.isFinite(n) ? '—' : `$${n.toFixed(2)}`;
    }

    /** The Overview list, in the board's order; cost and purchase type from the advice when it came. */
    function overview(vm, inventory, now = new Date(), advice = null) {
        const inv = inventory || {};
        const memory = num(inv.memoryMib);
        const cpus = num(inv.vcpuCount);
        const memoryText = memory === null ? null : `${Math.round(memory / 1024 * 10) / 10} GiB`;
        const vcpuMemory = cpus === null && !memoryText ? '—' : `${cpus === null ? '—' : cpus} / ${memoryText || '—'}`;
        // The zone ID (euw1-az3) is the same in every account; the name is this account's label (U19-T07).
        const zone = inv.availabilityZone ? inv.availabilityZone + (inv.availabilityZoneId ? ` (${inv.availabilityZoneId})` : '')
            : (vm.region || '—');
        return [
            { label: 'Size', value: inv.instanceType || vm.instanceType || '—', mono: true },
            { label: 'vCPU / memory', value: vcpuMemory, mono: true },
            { label: 'Region / zone', value: zone, mono: true },
            { label: 'Private IP', value: inv.privateIp || vm.privateIp || '—', mono: true },
            { label: 'Public IP', value: inv.publicIp || 'None', mono: !!inv.publicIp },
            { label: 'Purchase', value: purchaseText((advice && advice.purchaseOption) || vm.purchaseOption) },
            { label: 'Purpose', value: vm.purpose || '—', note: vm.remarks || '' },
            { label: 'Launched', value: day(inv.launchTime), mono: true },
            { label: 'State read from AWS', value: ago(vm.lastStateSyncAt, now) },
            { label: 'Cost this month', value: money(advice && advice.monthToDateCost), mono: true,
                note: advice && advice.priceApproximate ? 'Approximate price' : '' }
        ];
    }

    /**
     * The rightsizing note: {title, text, kind} such as "Could be one size smaller: m6i.medium" and
     * "CPU peaked at 18% and memory at 41%. Saves about $31 a month. AWS Compute Optimizer agrees.";
     * null without a recommendation.
     */
    function rightsizingNote(r) {
        if (!r || !r.recommendedType) return null;
        const title = r.direction === 'SCALE_UP' ? `Needs a bigger size: ${r.recommendedType}`
            : r.direction === 'MODERNIZE' ? `A newer generation fits: ${r.recommendedType}`
                : `Could be one size smaller: ${r.recommendedType}`;
        const parts = [];
        const cpu = num(r.cpuPeak), memory = num(r.memoryPeak);
        if (cpu !== null) parts.push(`CPU peaked at ${Math.round(cpu)}%${memory !== null ? ` and memory at ${Math.round(memory)}%` : ''}.`);
        const savings = num(r.monthlySavings);
        if (savings !== null && savings > 0) parts.push(`Saves about $${Math.round(savings)} a month.`);
        else if (savings !== null && savings < 0) parts.push(`Costs about $${Math.round(-savings)} more a month.`);
        if (r.optimizerAgrees) parts.push('AWS Compute Optimizer agrees.');
        return { title, text: parts.join(' '), kind: r.direction || 'SCALE_DOWN' };
    }

    /** The values of one field over the window, oldest first, without gaps. */
    function seriesOf(metrics, field) {
        return ((metrics && metrics.series) || []).map(s => num(s[field])).filter(v => v !== null && Number.isFinite(v));
    }

    /**
     * The Use tiles: CPU (peak), Memory (or that it needs the CloudWatch agent), Network in and out
     * and Disk read and write per second, each with its values over the window for a line.
     */
    function tiles(metrics) {
        const latest = latestOf(metrics) || {};
        const period = num(metrics && metrics.periodSeconds) || 300;
        const cpu = seriesOf(metrics, 'cpuUtilization');
        const memory = seriesOf(metrics, 'memoryUtilization');
        const perSecond = field => {
            const v = num(latest[field]);
            return v === null ? '—' : bytes(v / period);
        };
        const rate = (label, field, tone) => ({ label, caption: 'per second', value: perSecond(field), tone,
            values: seriesOf(metrics, field).map(v => v / period) });
        return [
            { label: 'CPU', caption: cpu.length ? `peak ${Math.round(Math.max(...cpu))}%` : '', value: pct(latest.cpuUtilization), tone: 'navy', values: cpu },
            memory.length
                ? { label: 'Memory', caption: memory.length ? `peak ${Math.round(Math.max(...memory))}%` : '', value: pct(latest.memoryUtilization), tone: 'navy', values: memory }
                : { label: 'Memory', caption: '', value: '—', tone: 'navy', values: [], note: AGENT_NOTE },
            rate('Network in', 'networkInBytes', 'green'),
            rate('Network out', 'networkOutBytes', 'green'),
            rate('Disk read', 'diskReadBytes', 'slate'),
            rate('Disk write', 'diskWriteBytes', 'slate')
        ];
    }

    /** SVG polyline points for values in a w×h box, highest at the top; '' for fewer than 2 values. */
    function points(values, w = 240, h = 40) {
        if (!values || values.length < 2) return '';
        const max = Math.max(...values), min = Math.min(...values);
        const span = max - min || 1;
        return values.map((v, i) => `${(i / (values.length - 1) * w).toFixed(1)},${(h - 3 - (v - min) / span * (h - 6)).toFixed(1)}`).join(' ');
    }

    /** The volumes: "Root disk" for the boot device, else "Data disk <device>"; IDs only when sent. */
    function volumes(inventory) {
        const list = (inventory && inventory.volumes) || [];
        return list.map((v, i) => {
            const device = v.deviceName || '';
            const root = /^\/dev\/(xvda|sda1?|nvme0n1)$/.test(device) || (!device && i === 0);
            return {
                name: root ? 'Root disk' : `Data disk${device ? ' ' + device : ''}`,
                type: v.volumeType || '—',
                size: blank(v.sizeGib) ? '—' : `${v.sizeGib} GiB`,
                volumeId: v.volumeId || null,
                encrypted: v.encrypted === true
            };
        });
    }

    /** "In use · CPU 31% now" or "Idle for 45 min" with the reason, from the metrics' idle summary. */
    function idleLine(metrics) {
        const idle = metrics && metrics.idle;
        const latest = latestOf(metrics);
        if (!idle && !latest) return { idle: false, text: 'No use measured yet', note: 'The platform reads CPU and network every few minutes once the VM runs.' };
        if (idle && idle.idle) {
            const m = num(idle.idleDurationMinutes) || 0;
            const h = Math.floor(m / 60), r = m % 60;
            return { idle: true, text: `Idle for ${h ? `${h} h${r ? ` ${r} min` : ''}` : `${r} min`}`, note: idle.reason || '' };
        }
        return { idle: false, text: 'In use', note: latest ? `CPU ${pct(latest.cpuUtilization)} now` : '' };
    }

    // ---------------------------------------------------------------- the panel

    let state = null;   // {envId, vmId, env, vm, inventory, metrics, tab, window, onDone, token}

    /**
     * Open the panel for a VM. opts: {env (with groups, for the start order and Start/Stop), tab,
     * window, onDone (after a start or stop from here)}.
     */
    function open(envId, vmId, opts = {}) {
        const token = Date.now() + Math.random();
        const tab = TABS.some(t => t[0] === opts.tab) ? opts.tab : 'overview';
        const win = WINDOWS.some(w => w[0] === opts.window) ? opts.window : '24h';
        state = { envId, vmId, env: opts.env || null, vm: null, inventory: undefined, metrics: undefined, advice: undefined,
            week: undefined, idleStop: undefined, history: undefined, loading: {},
            tab, window: win, onDone: opts.onDone || null, token };
        ApiClient.get(Config.API.vms.get(envId, vmId))
            .done(vm => {
                if (!state || state.token !== token) return;
                state.vm = vm;
                show();
                const keep = (key, request) => request
                    .done(data => { if (state && state.token === token) { state[key] = data || null; render(); } })
                    .fail(() => { if (state && state.token === token) { state[key] = null; render(); } });
                keep('inventory', ApiClient.get(Config.API.vms.inventory(envId, vmId), { suppressGlobalError: true }));
                keep('advice', ApiClient.get(Config.API.vms.advice(envId, vmId), { suppressGlobalError: true }));
                loadMetrics();
                ensureTab(state.tab);
            })
            .fail(() => Notifications.error('Could not load the VM details'));
    }

    function loadMetrics() {
        const { envId, vmId, token } = state;
        const [key, , period] = WINDOWS.find(w => w[0] === state.window);
        state.metrics = undefined;
        ApiClient.get(Config.API.vms.metrics(envId, vmId, key, period), { suppressGlobalError: true })
            .done(data => { if (state && state.token === token && state.window === key) { state.metrics = data || null; render(); } })
            .fail(() => { if (state && state.token === token && state.window === key) { state.metrics = null; render(); } });
    }

    /** Idle and History read their data the first time they are shown (U08-T07). */
    function ensureTab(key) {
        if (!state || state.loading[key]) return;
        const { envId, vmId, token } = state;
        const keep = (field, request) => request
            .done(data => { if (state && state.token === token) { state[field] = data || null; render(); } })
            .fail(() => { if (state && state.token === token) { state[field] = null; render(); } });
        if (key === 'idle') {
            state.loading.idle = true;
            keep('week', ApiClient.get(Config.API.vms.metrics(envId, vmId, '7d', 3600), { suppressGlobalError: true }));
            keep('idleStop', ApiClient.get(Config.API.idleStop.status(envId), { suppressGlobalError: true }));
        } else if (key === 'history') {
            state.loading.history = true;
            keep('history', ApiClient.get(`${Config.API.monitoring.vmHistory(vmId)}?page=0&size=6`, { suppressGlobalError: true }));
        }
    }

    function show() {
        const vm = state.vm;
        Slideout.open(vm.displayName || vm.name, '<div class="vm-panel"></div>', { variant: 'vm-details', width: 640 });
        render();
    }

    /** The header's extras: the line above the name, the state chip and Start or Stop. */
    function renderHeader() {
        const vm = state.vm;
        const $header = $(`${PANEL} .slideout-panel-header`);
        $header.find('.vm-head-extra').remove();
        const line = kicker(state.env, vm);
        if (line) $header.prepend(Utils.html`<div class="vm-head-kicker vm-head-extra">${line}</div>`);
        const chip = stateChip(vm);
        $header.find('h3').after(Utils.html`<span class="vm-state-chip vm-head-extra" data-kind="${chip.kind}">${chip.text}</span>`);
        // U22-T10: who started it, so nobody stops someone else's VM without knowing.
        const by = typeof EnvVmTable !== 'undefined' && EnvVmTable.model && EnvVmTable.model.startedByText
            ? EnvVmTable.model.startedByText(vm) : '';
        if (by) $header.find('.vm-state-chip').after(Utils.html`<span class="vm-started-by vm-head-extra">${by}</span>`);
        const action = headerAction(vm);
        if (action) {
            $header.find('.close-btn').before(Utils.html`<button type="button" class="btn btn-sm vm-head-extra vm-head-action ${action.op === 'STOP' ? 'btn-outline-danger' : 'btn-outline-success'}"
                data-vm-op="${action.op}">${action.label}</button>`);
        }
    }

    function headerAction(vm) {
        if (!state.env || state.env.viewerCanOperate !== true) return null;
        const eks = vm.provider === 'AWS_EKS';
        if (vm.status === 'RUNNING') return { op: 'STOP', label: eks ? 'Scale to 0' : 'Stop' };
        if (vm.status === 'STOPPED' || vm.status === 'ERROR') return { op: 'START', label: vm.status === 'ERROR' ? 'Retry' : 'Start' };
        return null;
    }

    function render() {
        if (!state || !state.vm) return;
        const $root = $(`${PANEL} .vm-panel`);
        if (!$root.length) return;
        renderHeader();
        const body = $root.find('.vm-panel-body')[0];
        const scroll = body ? body.scrollTop : 0;
        const focusedId = document.activeElement && $root[0].contains(document.activeElement) ? document.activeElement.id : null;
        const tabs = TABS.map(([key, label]) => ({ key, label }));
        $root.html(`
            <div class="vm-panel-stats">${stats(state.vm, state.inventory, state.metrics).map(s => Utils.html`
                <div class="vm-panel-stat"${s.title ? Utils.raw(Utils.html` title="${s.title}"`) : ''}><div class="vm-panel-stat-label">${s.label}</div>
                    <div class="vm-panel-stat-value${s.mono ? ' mono' : ''}">${s.value}</div></div>`).join('')}</div>
            ${Tabs.markup({ id: 'vm-tabs', label: 'VM details', tabs, active: state.tab })}
            <div class="vm-panel-body">
                ${Tabs.panel({ id: 'vm-tabs', key: 'overview', active: state.tab }, overviewHtml())}
                ${Tabs.panel({ id: 'vm-tabs', key: 'use', active: state.tab }, useHtml())}
                ${Tabs.panel({ id: 'vm-tabs', key: 'storage', active: state.tab }, storageHtml())}
                ${Tabs.panel({ id: 'vm-tabs', key: 'idle', active: state.tab }, idleHtml())}
                ${Tabs.panel({ id: 'vm-tabs', key: 'history', active: state.tab }, historyHtml())}
            </div>`);
        Tabs.bind($root[0], key => { state.tab = key; ensureTab(key); });
        const newBody = $root.find('.vm-panel-body')[0];
        if (newBody) newBody.scrollTop = scroll;
        if (focusedId) { const el = document.getElementById(focusedId); if (el) el.focus(); }
    }

    const loading = '<p class="vm-panel-empty">Loading…</p>';

    function overviewHtml() {
        if (state.inventory === undefined) return loading;
        const vm = state.vm;
        const fields = overview(vm, state.inventory, new Date(), state.advice || null).map(f => Utils.html`<div class="vm-panel-field"><dt>${f.label}</dt>
            <dd class="${f.mono ? 'mono' : ''}">${f.value}${Utils.raw(f.note ? Utils.html`<span class="vm-panel-note">${f.note}</span>` : '')}</dd></div>`).join('');
        // The server sends the instance ID to administrators only (U01-T01).
        const id = vm.providerVmId || (state.inventory && state.inventory.providerVmId);
        const instance = id ? Utils.html`<div class="vm-instance-id"><span>Instance ID <span class="vm-panel-note-inline">· Administrators only</span></span>
            <span class="mono">${id}</span></div>` : '';
        return `<dl class="vm-panel-fields">${fields}</dl>${instance}${adviceHtml()}`;
    }

    /** The rightsizing note; administrators get "Resize safely…", everyone else who to ask. */
    function adviceHtml() {
        const note = rightsizingNote(state.advice && state.advice.rightsizing);
        if (!note) return '';
        const admin = typeof Auth !== 'undefined' && Auth.isAdmin && Auth.isAdmin();
        const action = admin
            ? '<a class="vm-advice-action" href="#/cost-management?tab=rightsizing">Resize safely…</a>'
            : '<span class="vm-advice-hint">Ask an administrator to resize</span>';
        return Utils.html`<div class="vm-advice" data-kind="${note.kind}"><b>${note.title}</b>
            <span>${note.text}</span><span>${Utils.raw(action)}</span></div>`;
    }

    function useHtml() {
        const windows = WINDOWS.map(([key, label]) => Utils.html`<button type="button" class="vm-window" data-vm-window="${key}"
            aria-pressed="${String(key === state.window)}">${label}</button>`).join('');
        const label = (WINDOWS.find(w => w[0] === state.window) || WINDOWS[2])[1];
        let grid;
        if (state.metrics === undefined) grid = loading;
        else if (state.metrics === null) grid = '<p class="vm-panel-empty" role="alert">Couldn\'t load the use for this window.</p>';
        else {
            grid = `<div class="vm-tiles">${tiles(state.metrics).map(t => {
                const line = points(t.values);
                const chart = line
                    ? Utils.html`<svg class="vm-tile-line" data-tone="${t.tone}" viewBox="0 0 240 40" preserveAspectRatio="none" role="img"
                        aria-label="${t.label} over ${label}"><polyline points="${line}"></polyline></svg>`
                    : Utils.html`<div class="vm-tile-none">${t.note || 'No samples in this window'}</div>`;
                return Utils.html`<div class="vm-tile" data-metric="${t.label}"><div class="vm-tile-head"><span>${t.label}</span><span class="vm-tile-caption">${t.caption}</span></div>
                    <div class="vm-tile-value mono">${t.value}</div>${Utils.raw(chart)}</div>`;
            }).join('')}</div>`;
        }
        return `<div class="vm-windows" role="group" aria-label="Time window">${windows}</div>${grid}`;
    }

    function storageHtml() {
        if (state.inventory === undefined) return loading;
        const list = volumes(state.inventory);
        if (!list.length) return '<p class="vm-panel-empty">No disks read from AWS yet.</p>';
        return list.map(v => Utils.html`<div class="vm-volume">
                <div class="vm-volume-head"><b>${v.name}</b><span class="mono">${v.type} · ${v.size}</span></div>
                <div class="vm-volume-note">Space used needs the CloudWatch agent on the VM. Disks are billed also while the VM is stopped.</div>
                ${Utils.raw(v.volumeId ? Utils.html`<div class="vm-volume-id mono">${v.volumeId}${v.encrypted ? ' · encrypted' : ''}</div>` : '')}
            </div>`).join('');
    }

    function idleHtml() {
        if (state.metrics === undefined) return loading;
        const line = idleLine(state.metrics);
        const now = Utils.html`<div class="vm-idle-now" data-idle="${String(line.idle)}"><span class="vm-idle-dot" aria-hidden="true"></span>
            <b>${line.text}</b>${Utils.raw(line.note ? Utils.html`<span class="vm-panel-note-inline"> · ${line.note}</span>` : '')}</div>`;
        let chart;
        if (state.week === undefined) chart = loading;
        else if (state.week === null) chart = '<p class="vm-panel-empty">Idle hours are not available right now.</p>';
        else {
            const days = VmDetailsModel.idleHoursPerDay(state.week.series || [], 5);
            const bars = days.map((d, i) => {
                const h = Math.round(d.hours / 24 * 90);
                return `<rect class="vm-idle-bar${d.today ? ' today' : ''}" x="${i * 80}" y="${90 - h}" width="64" height="${Math.max(h, d.sampled ? 1 : 0)}" rx="3"></rect>`;
            }).join('');
            chart = Utils.html`<div class="vm-panel-note">Idle hours per day, last 7 days (idle means under 5% CPU for the hour)</div>
                <svg class="vm-idle-chart" viewBox="0 0 544 90" preserveAspectRatio="none" role="img" aria-label="${VmDetailsModel.idleLabel(days)}">${Utils.raw(bars)}</svg>
                <div class="vm-idle-days" aria-hidden="true">${Utils.raw(days.map(d => Utils.html`<span${Utils.raw(d.today ? ' class="today"' : '')}>${d.label}</span>`).join(''))}</div>`;
        }
        let rule = '';
        if (state.idleStop !== undefined && state.idleStop !== null) {
            const canReview = state.idleStop.featureEnabled !== false && typeof IdleStop !== 'undefined' && IdleStop.openReview && state.env;
            rule = Utils.html`<div class="vm-idle-rule"><span>${VmDetailsModel.idleStopLine(state.idleStop, state.vm.groupId)}</span>
                ${Utils.raw(canReview ? '<button type="button" class="btn btn-link btn-sm p-0" data-vm-idle-review>Idle auto-stop</button>' : '')}</div>`;
        }
        return now + chart + rule;
    }

    function historyHtml() {
        if (state.history === undefined) return loading;
        if (state.history === null) return '<p class="vm-panel-empty" role="alert">History is not available right now.</p>';
        const items = (state.history.content || []).map(i => VmDetailsModel.historyText(i));
        if (!items.length) return '<p class="vm-panel-empty">No starts, stops or changes recorded yet.</p>';
        return `<ol class="vm-history">${items.map(i => Utils.html`<li><span class="mono">${i.time}</span>
            <span class="vm-history-dot" data-tone="${i.tone}" aria-hidden="true"></span>
            <span>${i.text}${Utils.raw(i.by ? Utils.html`<span class="vm-panel-note">${i.by}</span>` : '')}</span></li>`).join('')}</ol>`;
    }

    function operate(op) {
        if (!state || !state.vm) return;
        const { envId, vmId, vm, onDone } = state;
        const name = vm.displayName || vm.name;
        const env = state.env || { environmentId: envId, groups: [] };
        Slideout.close();
        OperationConfirm.open({
            env, opType: op, scope: { level: 'vm', vmId, name },
            onConfirm: minutes => (op === 'STOP' ? VmOperations.stopVm(envId, vmId, name) : VmOperations.startVm(envId, vmId, name, minutes))
                .then(result => { if (onDone) onDone(result); return result; })
        });
    }

    if (typeof document !== 'undefined' && typeof $ !== 'undefined') {
        $(document)
            .on('click', `${PANEL} [data-vm-window]`, function() {
                if (!state) return;
                const key = this.getAttribute('data-vm-window');
                if (key === state.window) return;
                state.window = key;
                state.tab = 'use';
                loadMetrics();
                render();
                $(`${PANEL} [data-vm-window="${key}"]`).trigger('focus');
            })
            .on('click', `${PANEL} [data-vm-op]`, function() { operate(this.getAttribute('data-vm-op')); })
            .on('click', `${PANEL} [data-vm-idle-review]`, () => { Slideout.close(); IdleStop.openReview(); })
            // Another panel in the same slot must not keep this one's header extras.
            .on('slideout:opened', (e, panel) => {
                if (panel && panel.getAttribute('data-variant') !== 'vm-details') $(panel).find('.vm-head-extra').remove();
            });
    }

    return { open, model: { kicker, stats, overview, rightsizingNote, tiles, points, volumes, idleLine, purchaseText, money, bytes, ago, day } };
})();

if (typeof window !== 'undefined') window.VmDetails = VmDetails;
