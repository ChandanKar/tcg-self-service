/**
 * VM Self-Service Platform - Environment insights side panel (U08-T08, EnvInsights.dc.html)
 * "Payments Dev insights" for the last 24 h or 7 days: What to do ("Stop pay-cache." $0.07/h with
 * Stop; "Resize pay-api-01 to m6i.medium." $31/mo with Details; "Stop on weekends too." with Edit
 * schedule), Idle now, Busiest by average CPU, By group, Storage, and unusual spend of the last
 * 90 days. Stop is for people who can operate the environment, Edit schedule for its
 * administrators; everyone sees the advice. The words are pure (EnvInsights.model), tested in
 * src/test/js/env-insights.test.mjs.
 */
const EnvInsights = (function() {
    'use strict';

    const PANEL = '#dynamicSlideoutPanel[data-variant="env-insights"]';
    const WINDOWS = [['24h', '24 h'], ['7d', '7 days']];
    const num = v => (v === null || v === undefined || v === '' ? null : Number(v));

    /** "1.2 TiB", "640 GiB". */
    function gib(value) {
        const n = num(value) || 0;
        return n >= 1024 ? `${Math.round(n / 1024 * 10) / 10} TiB` : `${n} GiB`;
    }

    function ago(ts, now = new Date()) {
        const t = ts ? new Date(ts).getTime() : NaN;
        if (Number.isNaN(t)) return null;
        const minutes = Math.round((now.getTime() - t) / 60000);
        if (minutes < 1) return 'just now';
        if (minutes < 60) return `${minutes} min ago`;
        const hours = Math.round(minutes / 60);
        return hours < 48 ? `${hours} h ago` : `${Math.round(hours / 24)} days ago`;
    }

    /** "7 VMs · 5 running · 1.2 TiB of disk · metrics up to 4 min ago". */
    function subtitle(ins, now = new Date()) {
        const parts = [`${ins.totalVms || 0} VM${ins.totalVms === 1 ? '' : 's'}`, `${ins.runningVms || 0} running`];
        if (num(ins.totalAllocatedStorageGib)) parts.push(`${gib(ins.totalAllocatedStorageGib)} of disk`);
        const seen = ago(ins.latestMetricSampleTime, now);
        parts.push(seen ? `metrics up to ${seen}` : 'no metrics yet');
        return parts.join(' · ');
    }

    const rate = v => (num(v) === null ? '' : `$${Number(v).toFixed(2)}/h`);
    const monthly = v => (num(v) === null || Number(v) <= 0 ? '' : `$${Math.round(Number(v))}/mo`);

    /**
     * The What to do lines: {title, detail, saves, tone, action: {kind: stop | details | schedule,
     * label, vmId} | null}. Stop needs canOperate, Edit schedule canAdminister; Details is for all.
     */
    function actionLines(actions, canOperate, canAdminister) {
        return (actions || []).slice(0, 3).map(a => {
            switch (a.kind) {
                case 'STOP_IDLE':
                    return { title: `Stop ${a.vmName}.`, detail: a.detail ? `${a.detail}.` : '', saves: rate(a.hourlyRate), tone: 'gold',
                        action: canOperate ? { kind: 'stop', label: 'Stop', vmId: a.vmId, name: a.vmName } : null };
                case 'RESIZE':
                    return { title: `Resize ${a.vmName} to ${a.recommendedType}.`, detail: a.detail ? `${a.detail}.` : '',
                        saves: monthly(a.monthlySavings), tone: 'plain', action: { kind: 'details', label: 'Details', vmId: a.vmId } };
                case 'SCHEDULE_WEEKENDS':
                    return { title: 'Stop on weekends too.', detail: a.detail ? `${a.detail}.` : '', saves: '', tone: 'plain',
                        action: canAdminister ? { kind: 'schedule', label: 'Edit schedule' } : null };
                default:
                    return null;
            }
        }).filter(Boolean);
    }

    /** "6 h 10 min". */
    function duration(minutes) {
        const m = Math.max(0, Math.round(num(minutes) || 0));
        const h = Math.floor(m / 60), r = m % 60;
        return h ? `${h} h${r ? ` ${r} min` : ''}` : `${r} min`;
    }

    function pct(v) {
        const n = num(v);
        return n === null ? '—' : `${n < 10 ? Math.round(n * 10) / 10 : Math.round(n)}%`;
    }

    /** Idle now, longest first, with what each costs an hour when the action knows it. */
    function idleRows(ins) {
        const rates = {};
        (ins.actions || []).filter(a => a.kind === 'STOP_IDLE').forEach(a => { rates[a.vmId] = a.hourlyRate; });
        return (ins.idleVmRows || []).slice(0, 2).map(r => ({ vmId: r.vmId, name: r.name, since: duration(r.idleDurationMinutes),
            cpu: pct(r.cpuUtilization), rate: rate(rates[r.vmId]) }));
    }

    /**
     * A long name cut in the middle, keeping its start and its distinguishing end (U23-T11): staging's three
     * busiest VMs all read "mcube_dev_poc_…"; now "mcube_dev…work1_100.112.1.162".
     */
    function middleCut(name, max = 29) {
        const text = String(name || '');
        if (text.length <= max) return text;
        const head = 9;
        return `${text.slice(0, head)}…${text.slice(text.length - (max - head - 1))}`;
    }

    /** Busiest by average CPU over the window, bars relative to the busiest. */
    function busiest(ins) {
        const rows = (ins.busiestVms || []).slice(0, 5).map(r => ({ vmId: r.vmId, name: r.name, short: middleCut(r.name),
            value: num(r.avgCpuWindow) ?? num(r.cpuUtilization) }));
        const max = Math.max(1, ...rows.map(r => r.value || 0));
        return rows.map(r => ({ ...r, cpu: pct(r.value), width: Math.round((r.value || 0) / max * 100) }));
    }

    /** Groups in start order with their running count and average CPU. */
    function groupRows(ins) {
        return (ins.groups || []).slice().sort((a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0))
            .map(g => ({ name: g.name, running: g.runningVms || 0, total: g.totalVms || 0,
                cpu: num(g.avgCpuUtilization) === null ? '—' : pct(g.avgCpuUtilization) }));
    }

    /** Volume types with their counts; gp2 gets the move-to-gp3 hint. */
    function storageRows(ins) {
        return Object.entries(ins.volumeTypeCounts || {}).sort((a, b) => b[1] - a[1]).map(([type, count]) => ({
            type: type === 'UNKNOWN' ? 'Unknown type' : type,
            hint: type === 'gp2' ? 'move to gp3, same speed for less' : '',
            count: `${count} disk${count === 1 ? '' : 's'}`
        }));
    }

    // ---------------------------------------------------------------- the panel

    let state = null;   // {env, window, data, anomalies, token}

    /** Open for an environment ({environmentId, displayName, viewerCanOperate, viewerCanAdminister, groups}). */
    function open(env, window = '24h') {
        state = { env, window: WINDOWS.some(w => w[0] === window) ? window : '24h', data: undefined, anomalies: undefined,
            token: Date.now() + Math.random() };
        Slideout.open(`${env.displayName || env.name} insights`, '<div class="env-insights"></div>', { variant: 'env-insights', width: 820 });
        borrowAbout();
        render();
        load();
        const token = state.token;
        ApiClient.get(Config.API.costAnomalies.forEnvironment(env.environmentId, 90), { suppressGlobalError: true })
            .done(list => { if (state && state.token === token) { state.anomalies = list || []; render(); } })
            .fail(() => { if (state && state.token === token) { state.anomalies = null; render(); } });
    }

    /**
     * A large environment has no About column (EnvDetailLarge.dc.html): its facts (lock, lease, schedule,
     * idle auto-stop, cost and budget) show at the top of Insights, the same elements with their actions,
     * and go back to the page when the panel closes.
     */
    function borrowAbout() {
        const about = document.querySelector('.env-body-wide #env-aside .env-about');
        if (!about) return;
        const holder = document.createElement('section');
        holder.className = 'ins-about';
        holder.setAttribute('aria-label', 'About this environment');
        holder.innerHTML = '<h3 class="ins-about-title">About this environment</h3>';
        holder.appendChild(about);
        const content = document.querySelector('#dynamicSlideoutPanel .slideout-panel-content');
        if (content) content.insertBefore(holder, content.firstChild);
        $(document).one('slideout:closed', () => {
            const aside = document.getElementById('env-aside');
            if (aside && about.isConnected) aside.appendChild(about);
            holder.remove();
        });
    }

    function load() {
        const { env, window, token } = state;
        state.data = undefined;
        ApiClient.get(Config.API.environments.insights(env.environmentId, window), { suppressGlobalError: true })
            .done(data => { if (state && state.token === token && state.window === window) { state.data = data || null; render(); } })
            .fail(() => { if (state && state.token === token && state.window === window) { state.data = null; render(); } });
    }

    function render() {
        const $root = $(`${PANEL} .env-insights`);
        if (!$root.length || !state) return;
        const $header = $(`${PANEL} .slideout-panel-header`);
        $header.find('.ins-head-extra').remove();
        const windows = WINDOWS.map(([key, label]) => Utils.html`<button type="button" class="vm-window" data-ins-window="${key}"
            aria-pressed="${String(key === state.window)}">${label}</button>`).join('');
        $header.find('.close-btn').before(`<div class="vm-windows ins-head-extra" role="group" aria-label="Time window">${windows}</div>`);
        const data = state.data;
        if (data) $header.find('h3').after(Utils.html`<div class="ins-sub ins-head-extra">${subtitle(data)}</div>`);

        const body = $root.closest('.slideout-panel-content')[0];
        const scroll = body ? body.scrollTop : 0;
        if (data === undefined) { $root.html('<p class="vm-panel-empty">Loading…</p>'); return; }
        if (data === null) { $root.html('<p class="vm-panel-empty" role="alert">Couldn\'t load the insights.</p>'); return; }
        const env = state.env;
        const lines = actionLines(data.actions, env.viewerCanOperate === true, env.viewerCanAdminister === true);
        const todo = lines.length ? lines.map(l => Utils.html`<div class="ins-todo" data-tone="${l.tone}">
                <span class="ins-todo-text"><b>${l.title}</b> <span>${l.detail}</span></span>
                <span class="ins-saves mono">${l.saves}</span>
                ${Utils.raw(l.action ? actionButton(l.action) : '')}</div>`).join('')
            : '<p class="vm-panel-empty">Nothing to do right now.</p>';
        const idle = idleRows(data);
        const busy = busiest(data);
        const groups = groupRows(data);
        const storage = storageRows(data);
        $root.html(`
            <section class="ins-section ins-wide" aria-labelledby="ins-todo-title"><h3 id="ins-todo-title">What to do</h3>${todo}</section>
            <section class="ins-section" aria-labelledby="ins-idle-title"><h3 id="ins-idle-title">Idle now</h3>
                ${idle.length ? idle.map(v => Utils.html`<div class="ins-row"><span><b>${v.name}</b><span class="ins-note">idle ${v.since} · CPU ${v.cpu}</span></span>
                    <span class="mono ins-muted">${v.rate}</span>
                    <button type="button" class="btn btn-link btn-sm p-0" data-ins-details="${v.vmId}">Details</button></div>`).join('')
                    : '<p class="vm-panel-empty">No VM is idle now.</p>'}</section>
            <section class="ins-section" aria-labelledby="ins-busy-title"><h3 id="ins-busy-title">Busiest, average CPU</h3>
                ${busy.length ? busy.map(b => Utils.html`<div class="ins-bar-row"><span class="ins-bar-name" title="${b.name}">${b.short}</span>
                    <span class="ins-bar" aria-hidden="true"><span style="width:${String(b.width)}%"></span></span><span class="mono">${b.cpu}</span></div>`).join('')
                    : '<p class="vm-panel-empty">No CPU measured in this window.</p>'}</section>
            <section class="ins-section" aria-labelledby="ins-groups-title"><h3 id="ins-groups-title">By group</h3>
                ${groups.length ? groups.map(g => Utils.html`<div class="ins-group-row"><span>${g.name}</span>
                    ${Utils.raw(typeof PowerStrip !== 'undefined' ? PowerStrip.counts(g.running, g.total, { showCount: false }) : '')}
                    <span class="mono ins-muted">${g.cpu} CPU</span></div>`).join('') : '<p class="vm-panel-empty">No groups.</p>'}</section>
            <section class="ins-section" aria-labelledby="ins-disk-title"><h3 id="ins-disk-title">Storage</h3>
                ${storage.length ? storage.map(st => Utils.html`<div class="ins-row"><span>${st.type}${Utils.raw(st.hint ? Utils.html` <span class="ins-note-inline">· ${st.hint}</span>` : '')}</span>
                    <span class="mono ins-muted">${st.count}</span></div>`).join('') : '<p class="vm-panel-empty">No disks read from AWS yet.</p>'}
                <div class="ins-note">${num(data.totalAllocatedStorageGib) ? `${gib(data.totalAllocatedStorageGib)} in all. ` : ''}Disks cost money while VMs are stopped.</div></section>
            <section class="ins-section ins-wide" aria-labelledby="ins-spend-title"><h3 id="ins-spend-title">Unusual spend, last 90 days</h3>
                ${anomaliesHtml()}</section>`);
        if (body) body.scrollTop = scroll;
    }

    function actionButton(action) {
        if (action.kind === 'stop') {
            return Utils.html`<button type="button" class="btn btn-sm btn-outline-danger" data-ins-stop="${action.vmId}" data-ins-name="${action.name}">${action.label}</button>`;
        }
        if (action.kind === 'details') {
            return Utils.html`<button type="button" class="btn btn-link btn-sm p-0" data-ins-details="${action.vmId}">${action.label}</button>`;
        }
        return Utils.html`<a class="btn btn-link btn-sm p-0" href="#/automation-rules">${action.label}</a>`;
    }

    function anomaliesHtml() {
        if (state.anomalies === undefined) return '<p class="vm-panel-empty">Loading…</p>';
        if (state.anomalies === null || typeof CostAnomalies === 'undefined') return '<p class="vm-panel-empty">Not available right now.</p>';
        return CostAnomalies.tableHtml(state.anomalies, false, 'No unusual spend in the last 90 days.');
    }

    if (typeof document !== 'undefined' && typeof $ !== 'undefined') {
        $(document)
            .on('click', `${PANEL} [data-ins-window]`, function() {
                if (!state) return;
                const key = this.getAttribute('data-ins-window');
                if (key === state.window) return;
                state.window = key;
                load();
                render();
                $(`${PANEL} [data-ins-window="${key}"]`).trigger('focus');
            })
            .on('click', `${PANEL} [data-ins-details]`, function() {
                if (!state) return;
                const env = state.env;
                Slideout.close();
                VmDetails.open(env.environmentId, this.getAttribute('data-ins-details'), { env });
            })
            .on('click', `${PANEL} [data-ins-stop]`, function() {
                if (!state) return;
                const env = state.env, vmId = this.getAttribute('data-ins-stop'), name = this.getAttribute('data-ins-name');
                Slideout.close();
                OperationConfirm.open({ env: env.groups ? env : Object.assign({ groups: [] }, env), opType: 'STOP',
                    scope: { level: 'vm', vmId, name }, onConfirm: () => VmOperations.stopVm(env.environmentId, vmId, name) });
            })
            .on('slideout:opened', (e, panel) => {
                if (panel && panel.getAttribute('data-variant') !== 'env-insights') $(panel).find('.ins-head-extra').remove();
            });
    }

    return { open, model: { subtitle, actionLines, idleRows, busiest, middleCut, groupRows, storageRows, duration } };
})();

if (typeof window !== 'undefined') window.EnvInsights = EnvInsights;
