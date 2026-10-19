/**
 * VM Self-Service Platform - Power strip
 * The signature component of the style guide: one light per VM, groups separated and in start
 * order, left to right (css/components/power-strip.css).
 *
 *   PowerStrip.render(groups, {large, showCount})  groups = [{name, lights: ['R', 'B', 'S', 'U', 'E']}]
 *   PowerStrip.fromVmGroups(groupsWithVms)          GET /environments/{id}/vms -> groups
 *   PowerStrip.fromGroupStates(groupStates)         per-group counts -> groups
 *   PowerStrip.counts(running, total, opts)         rows that only know two numbers
 *
 * Lights: R running, B starting or stopping, E failed, U not read from AWS yet, S stopped.
 * A compact strip with more than 24 lights becomes a bar filled by the running share; the large
 * strip (environment page) always draws every light.
 */
const PowerStrip = (function() {
    'use strict';

    const MAX_COMPACT_LIGHTS = 24;
    const LIGHT_CLASS = { R: 'on', B: 'busy', E: 'err', U: 'unknown', S: '' };

    function esc(value) {
        const text = value == null ? '' : String(value);
        return (typeof Utils !== 'undefined' && Utils.escapeHtml) ? Utils.escapeHtml(text)
            : text.replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    /** VmStatus -> light. A VM never read from the cloud is "not read yet" whatever its stored status. */
    function lightFor(vm) {
        if (!vm) return 'U';
        if (vm.lastStateSyncAt === null) return 'U';
        switch (vm.status) {
            case 'RUNNING': return 'R';
            case 'STARTING': case 'STOPPING': return 'B';
            case 'STOPPED': return 'S';
            case 'ERROR': case 'NOT_FOUND': case 'TERMINATED': return 'E';
            default: return 'U';
        }
    }

    function tally(lights) {
        const t = { R: 0, B: 0, E: 0, U: 0, S: 0 };
        lights.forEach(l => { t[l] = (t[l] || 0) + 1; });
        return t;
    }

    /** "44 of 54 VMs running, 1 failed, 2 starting or stopping, 1 not read from AWS yet" */
    function label(t, total) {
        const parts = [`${t.R} of ${total} VMs running`];
        if (t.E) parts.push(`${t.E} failed`);
        if (t.B) parts.push(`${t.B} starting or stopping`);
        if (t.U) parts.push(`${t.U} not read from AWS yet`);
        return parts.join(', ');
    }

    function countHtml(running, total) {
        return `<span class="power-strip-count"><b>${running}</b>/${total}</span>`;
    }

    function barHtml(running, total) {
        const pct = total ? Math.round((running / total) * 100) : 0;
        return `<span class="power-bar" aria-hidden="true"><span style="width:${pct}%"></span></span>`;
    }

    function render(groups, opts = {}) {
        const large = !!opts.large;
        const showCount = opts.showCount !== false;
        const list = (groups || []).filter(g => g && (g.lights || []).length);
        const all = list.flatMap(g => g.lights);
        const t = tally(all);
        const name = label(t, all.length);
        const lights = (!large && all.length > MAX_COMPACT_LIGHTS) ? barHtml(t.R, all.length)
            : `<span class="power-strip-lights" aria-hidden="true">${list.map(g =>
                `<span class="power-strip-group"${g.name ? ` title="${esc(g.name)}"` : ''}>${
                    g.lights.map(l => `<span class="pip${LIGHT_CLASS[l] ? ' ' + LIGHT_CLASS[l] : ''}"></span>`).join('')
                }</span>`).join('')}</span>`;
        return `<span class="power-strip${large ? ' power-strip-lg' : ''}" role="img" aria-label="${esc(name)}" title="${esc(name)}">`
            + `${all.length ? lights : ''}${showCount ? countHtml(t.R, all.length) : ''}</span>`;
    }

    const bySequence = (a, b) => (a.sequencePosition ?? 0) - (b.sequencePosition ?? 0);

    /** Groups from GET /environments/{id}/vms ([{group, vms}]), in start order, VMs in their order. */
    function fromVmGroups(groupsWithVms) {
        return (groupsWithVms || []).slice()
            .sort((a, b) => bySequence(a.group || a, b.group || b))
            .map(g => {
                const group = g.group || g;
                return {
                    name: group.displayName || group.name || '',
                    lights: (g.vms || []).slice().sort(bySequence).map(lightFor),
                };
            });
    }

    /** Groups from per-group counts: running, starting/stopping, failed, not read, stopped, in that order. */
    function fromGroupStates(groupStates) {
        const n = v => Math.max(0, Number(v) || 0);
        return (groupStates || []).slice().sort(bySequence).map(g => ({
            name: g.displayName || g.name || '',
            lights: [].concat(
                Array(n(g.running)).fill('R'),
                Array(n(g.busy)).fill('B'),
                Array(n(g.problem)).fill('E'),
                Array(n(g.unknown)).fill('U'),
                Array(n(g.stopped)).fill('S')),
        }));
    }

    /** One unnamed group from two numbers: running lights first, the rest stopped. */
    function counts(running, total, opts = {}) {
        const t = Math.max(0, Number(total) || 0);
        const r = Math.min(t, Math.max(0, Number(running) || 0));
        return render([{ name: '', lights: [].concat(Array(r).fill('R'), Array(t - r).fill('S')) }], opts);
    }

    return { render, fromVmGroups, fromGroupStates, counts, lightFor, MAX_COMPACT_LIGHTS };
})();
