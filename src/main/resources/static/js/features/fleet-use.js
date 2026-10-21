/**
 * System health › Fleet use (U14-T09, SystemHealthFleet.dc.html), administrators only: the views the
 * old admin dashboard had, kept by the owner's choice. From /dashboard/summary, asked for only when the
 * tab opens: CPU, memory and network over the last 24 hours (a line with its "now" value and a note),
 * the fleet's state as one bar with counts, metrics coverage, and risk and compliance as rows that link
 * to where each is fixed ("Nothing needs a look." when there is nothing).
 */
const FleetUse = (function() {
    'use strict';

    const STATES = [
        ['Running', ['RUNNING'], 'run'],
        ['Stopped', ['STOPPED'], 'stop'],
        ['Starting or stopping', ['STARTING', 'STOPPING', 'PENDING'], 'move'],
        ['Failed', ['ERROR', 'NOT_FOUND', 'TERMINATED'], 'fail'],
        ['Unknown', ['UNKNOWN'], 'unknown']
    ];
    /** The server's risk rows in plain words, with where each is fixed. */
    const RISKS = {
        'EBS encryption': ['Unencrypted disks', 'EBS volumes without encryption', '#/waste', 'bad'],
        'State drift': ['Changed outside the platform', 'VMs whose state differs from what the platform expects', '#/vm-registry', 'warn'],
        'Metric coverage': ['No metrics', 'VMs without recent CloudWatch data', '#/system-health?tab=fleet', 'warn'],
        'Idle exposure': ['Idle VMs', 'Running VMs that are barely used', '#/waste', 'warn'],
        'Inventory gaps': ['Inventory gaps', 'Volumes or VMs the inventory knows too little about', '#/vm-registry', 'info']
    };

    // ---- pure model (node tests: src/test/js/fleet-use.test.mjs) ----

    function num(v) {
        if (v === null || v === undefined || v === '') return null; // no sample, not zero
        const n = Number(v);
        return Number.isFinite(n) ? n : null;
    }

    /** One chart: values, the last value, the peak and its label; null without data. */
    function series(points, pick) {
        const rows = (points || []).map(p => ({ label: p.label, value: pick(p) })).filter(r => r.value !== null);
        if (!rows.length) return null;
        const peak = rows.reduce((a, b) => (b.value > a.value ? b : a));
        return { values: rows.map(r => r.value), now: rows[rows.length - 1].value, first: rows[0].value, peak };
    }

    function charts(points) {
        const mb = p => {
            const total = (num(p.networkInBytes) || 0) + (num(p.networkOutBytes) || 0);
            return p.networkInBytes == null && p.networkOutBytes == null ? null : total / (1024 * 1024);
        };
        const cpu = series(points, p => num(p.cpuUtilization));
        const memory = series(points, p => num(p.memoryUtilization));
        const network = series(points, mb);
        const pct = v => `${Math.round(v)}%`;
        const mbText = v => `${Math.round(v).toLocaleString('en-US')} MB`;
        return [
            { key: 'cpu', title: 'CPU', data: cpu, max: 100, now: cpu && pct(cpu.now),
                note: cpu ? `Average of running VMs. Peak ${pct(cpu.peak.value)} at ${cpu.peak.label}.` : '',
                label: cpu ? `Average CPU over 24 hours, from ${pct(cpu.first)} to ${pct(cpu.now)}, peak ${pct(cpu.peak.value)}` : '' },
            { key: 'memory', title: 'Memory', data: memory, max: 100, now: memory && pct(memory.now),
                note: memory ? 'Needs the CloudWatch agent on the VM.' : '',
                label: memory ? `Average memory over 24 hours, from ${pct(memory.first)} to ${pct(memory.now)}` : '' },
            { key: 'network', title: 'Network', data: network, max: null, now: network && mbText(network.now),
                note: network ? 'In and out, all running VMs, for each part of the day.' : '',
                label: network ? `Network traffic over 24 hours, from ${mbText(network.first)} to ${mbText(network.now)}` : '' }
        ];
    }

    /** The fleet state bar: [{name, count, kind}] with counts above zero, and the total. */
    function states(counts) {
        const c = counts || {};
        const rows = STATES.map(([name, keys, kind]) => ({ name, kind, count: keys.reduce((sum, k) => sum + (Number(c[k]) || 0), 0) }))
            .filter(r => r.count > 0 || ['Running', 'Stopped'].includes(r.name));
        return { rows, total: rows.reduce((sum, r) => sum + r.count, 0) };
    }

    /** Risk rows with something to look at, in plain words, with their link. */
    function risks(rows) {
        return (rows || []).filter(r => Number(r.count) > 0).map(r => {
            const known = RISKS[r.title];
            return { title: known ? known[0] : r.title, description: known ? known[1] : r.description,
                href: known ? known[2] : '#/system-health', kind: known ? known[3] : 'info', count: Number(r.count) };
        });
    }

    // ---- drawing ----

    function lineSvg(chart) {
        const W = 300;
        const H = 80;
        const values = chart.data.values;
        const max = chart.max || Math.max(...values, 1) * 1.1;
        const x = i => (values.length === 1 ? W : (i * W) / (values.length - 1)).toFixed(1);
        const y = v => (H - (Math.min(v, max) / max) * (H - 8)).toFixed(1);
        const pts = values.map((v, i) => `${x(i)},${y(v)}`).join(' ');
        const last = values.length - 1;
        return `<svg class="fu-line fu-line-${chart.key}" viewBox="0 0 ${W} ${H}" preserveAspectRatio="none" role="img" aria-label="${Utils.escapeHtml(chart.label)}">
                <polygon class="fu-area" points="0,${H} ${pts} ${x(last)},${H}"></polygon>
                <polyline class="fu-stroke" points="${pts}"></polyline>
                <circle class="fu-end" cx="${x(last)}" cy="${y(values[last])}" r="3.5"></circle>
            </svg>`;
    }

    let token = null;

    /** Draws the tab into the container; asks for the summary each time it opens. */
    function render(container) {
        token = ContentRouter.token();
        const t = token;
        $(container).html('<p class="sh-note">Loading the fleet…</p>');
        ApiClient.get(Config.API.dashboard.summary, { suppressGlobalError: true }).done(data => {
            if (!ContentRouter.isCurrent(t)) return;
            draw(container, data || {});
        }).fail(() => {
            if (ContentRouter.isCurrent(t)) $(container).html('<p class="sh-note" role="alert">The fleet figures are not available right now.</p>');
        });
    }

    function draw(container, data) {
        const cards = charts(data.utilizationTrend).map(c => `<div class="fu-card">
                <div class="fu-card-head"><h2>${Utils.escapeHtml(c.title)}</h2>${c.data ? `<span class="fu-now">now <b>${Utils.escapeHtml(c.now)}</b></span>` : ''}</div>
                ${c.data ? lineSvg(c) : '<p class="sh-note fu-empty">No samples in the last 24 hours.</p>'}
                ${c.note ? `<p class="sh-note">${Utils.escapeHtml(c.note)}</p>` : ''}
            </div>`).join('');
        const st = states(data.vmStatusCounts);
        const label = st.rows.map(r => `${r.count} ${r.name.toLowerCase()}`).join(', ');
        const bar = st.total ? st.rows.filter(r => r.count).map(r => `<span class="fu-seg fu-${r.kind}" style="width:${(r.count / st.total * 100).toFixed(2)}%"></span>`).join('') : '';
        const coverage = Math.round(Number(data.coverage && data.coverage.metricsPercent) || 0);
        const rows = risks(data.riskCompliance);
        const most = Math.max(1, ...rows.map(r => r.count));
        const things = rows.reduce((sum, r) => sum + r.count, 0);
        $(container).html(`<div class="fu-page">
            <section class="fu-use" aria-label="Use across the fleet">${cards}</section>
            <div class="fu-lower">
                <section class="fu-card" aria-labelledby="fu-state-title" id="fu-state">
                    <div class="fu-card-head"><h2 id="fu-state-title">Fleet state</h2><span class="sh-note">${st.total.toLocaleString('en-US')} VMs</span></div>
                    <div class="fu-bar" role="img" aria-label="${Utils.escapeHtml(label || 'No VMs')}">${bar}</div>
                    ${st.rows.map(r => Utils.html`<div class="fu-legend" data-state="${r.name}"><span><span class="fu-dot fu-${Utils.raw(r.kind)}"></span>${r.name}</span><b class="sh-mono">${r.count}</b></div>`).join('')}
                </section>
                <section class="fu-card" aria-labelledby="fu-cov-title" id="fu-coverage">
                    <h2 id="fu-cov-title">Metrics coverage</h2>
                    <div class="fu-cov"><b class="sh-mono">${coverage}%</b><span class="sh-note">of VMs have recent metric samples</span></div>
                    <div class="fu-bar fu-cov-bar" role="img" aria-label="${coverage}% of VMs have recent metric samples"><span class="fu-seg fu-run" style="width:${coverage}%"></span></div>
                    <p class="sh-note">Without CloudWatch data, idle auto-stop and rightsizing can't judge those VMs. Memory needs the CloudWatch agent on the VM.</p>
                </section>
                <section class="fu-card" aria-labelledby="fu-risk-title" id="fu-risk">
                    <div class="fu-card-head"><h2 id="fu-risk-title">Risk and compliance</h2>${things ? `<span class="sh-note">${things} thing${things === 1 ? '' : 's'} to look at</span>` : ''}</div>
                    ${rows.length ? rows.map(r => Utils.html`<a class="fu-risk" href="${r.href}">
                        <span class="fu-risk-text"><b>${r.title}</b><span>${r.description}</span></span>
                        <span class="fu-risk-bar"><span class="fu-${Utils.raw(r.kind)}" style="width:${Utils.raw((r.count / most * 100).toFixed(1))}%"></span></span>
                        <b class="sh-mono">${r.count}</b></a>`).join('') : '<p class="sh-note fu-clear">Nothing needs a look.</p>'}
                </section>
            </div>
        </div>`);
    }

    return { render, model: { charts, states, risks } };
})();

if (typeof window !== 'undefined') window.FleetUse = FleetUse;
