/**
 * Help page (E29-T07), #/help and #/help/<topic>: the topic list with search, and the chosen
 * topic. Topics meant for some roles are hidden from others. Content is static (help-content.js).
 * HelpLink renders the '?' button page headers use to open their topic.
 */
const Help = (function() {
    'use strict';

    let query = '';
    let current = null;

    function visible(topic) {
        if (!topic.roles || !topic.roles.length) return true;
        if (typeof Auth === 'undefined') return false;
        return topic.roles.some(r => (r === 'ADMIN' && Auth.isAdmin()) || (r === 'ENV_ADMIN' && Auth.isEnvAdmin()));
    }

    function text(topic) {
        return [topic.title, ...(topic.keywords || []), ...topic.sections.flatMap(s =>
            [s.heading, ...(s.paragraphs || []), ...(s.steps || [])])].join(' ').toLowerCase();
    }

    function topics() {
        const q = query.trim().toLowerCase();
        return HelpContent.all().filter(visible).filter(t => !q || text(t).includes(q));
    }

    function load(params) {
        const id = params && params.topic;
        const topic = id ? HelpContent.find(id) : null;
        current = topic && visible(topic) ? topic : (id ? 'missing' : null);
        $('#content-area').html(`
            <div class="content-view help-page" id="help-view">
                <div class="content-header">
                    <h1><i class="fas fa-life-ring me-2" aria-hidden="true"></i>Help</h1>
                    <p class="text-muted mb-0">How to use the platform, by topic</p>
                </div>
                <div class="help-layout">
                    <nav class="help-nav" aria-label="Help topics">
                        <label class="visually-hidden" for="help-search">Search help</label>
                        <input type="search" class="form-control form-control-sm mb-2" id="help-search" placeholder="Search help…"
                               data-action-input="help-search" autocomplete="off">
                        <ul class="help-topic-list list-unstyled mb-0" id="help-topic-list"></ul>
                    </nav>
                    <article class="help-topic card" id="help-topic" aria-live="polite"></article>
                </div>
            </div>`);
        $('#help-search').val(query);
        drawList();
        drawTopic();
    }

    function drawList() {
        const list = topics();
        $('#help-topic-list').html(list.length ? list.map(t => Utils.html`<li>
                <a href="#/help/${t.id}" class="help-topic-link${current && current.id === t.id ? ' active' : ''}"
                   ${current && current.id === t.id ? Utils.raw('aria-current="page"') : ''} data-topic="${t.id}">${t.title}</a>
            </li>`).join('')
            : '<li class="small text-muted" id="help-no-results">No topic matches your search.</li>');
    }

    function drawTopic() {
        const $t = $('#help-topic');
        if (current === 'missing') {
            $t.html('<div class="card-body"><h2 class="h5">Topic not found</h2><p class="text-muted mb-0">Pick a topic from the list.</p></div>');
            return;
        }
        if (!current) {
            $t.html('<div class="card-body"><h2 class="h5">Choose a topic</h2><p class="text-muted mb-0">Pick a topic from the list, or search for what you need.</p></div>');
            return;
        }
        $t.html(Utils.html`<div class="card-body">
            <h2 class="h4 mb-3" id="help-topic-title">${current.title}</h2>
            ${Utils.raw(current.optional ? '<p class="small text-muted help-optional"><i class="fas fa-info-circle" aria-hidden="true"></i> This feature may be switched off in your organisation.</p>' : '')}
            ${Utils.raw(current.sections.map(s => Utils.html`<section class="help-section">
                <h3 class="h6">${s.heading}</h3>
                ${Utils.raw((s.paragraphs || []).map(p => Utils.html`<p>${p}</p>`).join(''))}
                ${Utils.raw(s.steps && s.steps.length ? Utils.html`<ol>${Utils.raw(s.steps.map(st => Utils.html`<li>${st}</li>`).join(''))}</ol>` : '')}
            </section>`).join(''))}
        </div>`);
    }

    function search(value) {
        query = value || '';
        drawList();
    }

    return { load, search, visible };
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
