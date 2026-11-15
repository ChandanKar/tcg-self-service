/**
 * Help page (E29-T07, U09-T07 Help.dc.html), #/help and #/help/<topic>: search in the header,
 * topics in groups (Using environments, Access, Cost and more, and Manage / Administration for
 * those roles) with the current one marked, and the chosen topic. Topics meant for some roles are
 * hidden from others, and so is a group with nothing to show. The start/stop and environments
 * topics end with the power-strip legend. Content is static (help-content.js). HelpLink renders
 * the '?' button page headers use to open their topic.
 */
const Help = (function() {
    'use strict';

    const LEGEND = "Green is running, gold is starting or stopping, hollow is stopped, striped means the state hasn't been read from AWS yet.";
    let query = '';
    let current = null;

    /** The topic is for this person's role. */
    function forRole(topic) {
        if (!topic.roles || !topic.roles.length) return true;
        if (typeof Auth === 'undefined') return false;
        return topic.roles.some(r => (r === 'ADMIN' && Auth.isAdmin()) || (r === 'ENV_ADMIN' && Auth.isEnvAdmin()));
    }

    /** In the list: for this role, and not about a switched-off feature (U23-T13), once the switches are known. */
    function visible(topic) {
        if (topic.feature && typeof Capabilities !== 'undefined' && !Capabilities.has(topic.feature)) return false;
        return forRole(topic);
    }

    function text(topic) {
        return [topic.title, topic.summary || '', ...(topic.keywords || []), ...topic.sections.flatMap(s =>
            [s.heading, ...(s.paragraphs || []), ...(s.steps || []), ...(s.terms || []).flat()])].join(' ').toLowerCase();
    }

    /** The section's parts in order: paragraphs, numbered steps, then "what you see / what it means" pairs. */
    function sectionHtml(s) {
        const paragraphs = (s.paragraphs || []).map(p => Utils.html`<p>${p}</p>`).join('');
        const steps = s.steps && s.steps.length ? Utils.html`<ol>${Utils.raw(s.steps.map(st => Utils.html`<li>${st}</li>`).join(''))}</ol>` : '';
        const terms = s.terms && s.terms.length ? Utils.html`<dl class="help-terms">${Utils.raw(s.terms.map(([term, meaning]) =>
            Utils.html`<div class="help-term"><dt>${term}</dt><dd>${meaning}</dd></div>`).join(''))}</dl>` : '';
        return Utils.html`<section class="help-section"><h3>${s.heading}</h3>${Utils.raw(paragraphs + steps + terms)}</section>`;
    }

    /** "Related": links to the topics named in topic.related that this person may see. */
    function relatedHtml(topic) {
        const links = (topic.related || []).map(id => HelpContent.find(id)).filter(t => t && visible(t));
        if (!links.length) return '';
        return Utils.html`<nav class="help-related" aria-label="Related topics"><span class="help-related-title">Related</span>${Utils.raw(
            links.map(t => Utils.html`<a href="#/help/${t.id}" data-topic="${t.id}">${t.title}</a>`).join(''))}</nav>`;
    }

    function topics() {
        const q = query.trim().toLowerCase();
        return HelpContent.all().filter(visible).filter(t => !q || text(t).includes(q));
    }

    /** The visible topics by group, in the groups' order; empty groups left out. */
    function grouped(list) {
        return HelpContent.GROUPS.map(([key, name]) => ({ key, name, topics: list.filter(t => (t.group || 'cost') === key) }))
            .filter(g => g.topics.length);
    }

    function load(params) {
        const id = params && params.topic;
        const topic = id ? HelpContent.find(id) : null;
        // A page's "?" or a link still opens a switched-off feature's topic (it says the feature may be off);
        // only the list leaves it out. A topic for another role stays "not found" (U28).
        current = topic && forRole(topic) ? topic : (id ? 'missing' : null);
        const search = `<label class="help-search">
                <svg width="16" height="16" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8"
                     stroke-linecap="round" aria-hidden="true"><circle cx="9" cy="9" r="5.5"></circle><path d="m13.5 13.5 3.5 3.5"></path></svg>
                <span class="visually-hidden">Search help</span>
                <input type="search" id="help-search" placeholder="Search help" data-action-input="help-search" autocomplete="off">
            </label>`;
        $('#content-area').html(`
            <div class="content-view help-page" id="help-view">
                ${PageHeader.markup({ title: 'Help', actionsHtml: search,
                    summaryHtml: 'How the platform works, in short articles. Every "?" in the app opens the matching one.' })}
                <div class="help-layout">
                    <nav class="help-nav" aria-label="Help topics" id="help-topic-list"></nav>
                    <article class="help-topic" id="help-topic" aria-live="polite"></article>
                </div>
            </div>`);
        $('#help-search').val(query);
        drawList();
        drawTopic();
    }

    function drawList() {
        const groups = grouped(topics());
        const q = query.trim();
        $('#help-topic-list').html(groups.length ? groups.map(g => Utils.html`
            <div class="help-group" role="group" aria-labelledby="help-group-${g.key}">
                <div class="help-group-title" id="help-group-${g.key}">${g.name}</div>
                ${Utils.raw(g.topics.map(t => {
                    const here = current && current.id === t.id;
                    return Utils.html`<a href="#/help/${t.id}" class="help-topic-link${here ? ' active' : ''}"
                        ${here ? Utils.raw('aria-current="page"') : ''} data-topic="${t.id}">${t.title}</a>`;
                }).join(''))}
            </div>`).join('')
            : UiStates.filtered({ title: `No topic matches "${q}".`, text: 'Try another word, or clear the search to see every topic.',
                clearLabel: 'Clear search', clearAction: 'help-clear-search', titleId: 'help-no-results' }));
    }

    function legendHtml() {
        const pip = cls => `<span class="pip${cls ? ' ' + cls : ''}"></span>`;
        return `<div class="help-legend">
                <span class="power-strip" aria-hidden="true"><span class="power-strip-lights"><span class="power-strip-group">${
                    ['on', 'busy', '', 'unknown'].map(pip).join('')}</span></span></span>
                <span>${Utils.escapeHtml(LEGEND)}</span>
            </div>`;
    }

    function drawTopic() {
        const $t = $('#help-topic');
        if (current === 'missing') {
            $t.html('<h2 class="help-topic-title">Topic not found</h2><p class="help-muted">Pick a topic from the list.</p>');
            return;
        }
        if (!current) {
            $t.html('<h2 class="help-topic-title">Choose a topic</h2><p class="help-muted">Pick a topic from the list, or search for what you need.</p>');
            return;
        }
        $t.html(Utils.html`
            <h2 class="help-topic-title" id="help-topic-title">${current.title}</h2>
            ${Utils.raw(current.summary ? Utils.html`<p class="help-summary">${current.summary}</p>` : '')}
            ${Utils.raw(current.optional ? '<p class="help-optional">This feature may be switched off in your organisation.</p>' : '')}
            ${Utils.raw(current.sections.map(sectionHtml).join(''))}
            ${Utils.raw(current.legend ? legendHtml() : '')}
            ${Utils.raw(relatedHtml(current))}`);
        $t.scrollTop(0);
    }

    function search(value) {
        query = value || '';
        drawList();
    }

    return { load, search, visible, forRole, model: { grouped, sectionHtml, text }, LEGEND };
})();

/** The '?' button in page headers (E29-T07). */
const HelpLink = (function() {
    'use strict';

    function topicFor(page) {
        return HelpContent.PAGE_TOPICS[page] || null;
    }

    /** Header button for a page; '' when the page has no topic. */
    function button(page, pageLabel) {
        const topic = topicFor(page);
        if (!topic) return '';
        return Utils.html`<a class="btn btn-sm btn-ghost help-link" href="#/help/${topic}" data-help-topic="${topic}"
            aria-label="Help: ${pageLabel}" title="Help"><i class="fas fa-question-circle" aria-hidden="true"></i></a>`;
    }

    return { button, topicFor };
})();

window.Help = Help;
window.HelpLink = HelpLink;

Actions.register('help-search', el => Help.search(el.value), 'input');
// U17-T01: "Clear search" on the no-match message.
Actions.register('help-clear-search', () => {
    $('#help-search').val('').trigger('focus');
    Help.search('');
});
