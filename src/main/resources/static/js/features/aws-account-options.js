/**
 * The active AWS accounts for environment and VM forms (E28-T10), loaded once a minute from
 * /aws-accounts/options (admins and environment admins). With a single account every account
 * control stays hidden, so a single-account installation looks as before.
 */
const AwsAccountOptions = (function() {
    'use strict';

    const TTL_MS = 60000;
    let cache = null;
    let loadedAt = 0;

    function load() {
        if (cache && Date.now() - loadedAt < TTL_MS) return Promise.resolve(cache);
        if (typeof Auth === 'undefined' || !Auth.isEnvAdmin || !Auth.isEnvAdmin()) return Promise.resolve([]);
        return new Promise(resolve => {
            ApiClient.get(Config.API.awsAccounts.options, { suppressGlobalError: true })
                .done(list => { cache = list || []; loadedAt = Date.now(); resolve(cache); })
                .fail(() => resolve([]));
        });
    }

    function isMulti(list) {
        return Array.isArray(list) && list.length > 1;
    }

    /** The last loaded list, or [] (for synchronous rendering after load()). */
    function cached() {
        return cache || [];
    }

    function nameOf(ref, list) {
        const a = (list || cached()).find(x => x.ref === ref);
        return a ? a.name : null;
    }

    function optionsHtml(list, selectedRef) {
        const selected = selectedRef || (list.find(a => a.isDefault) || {}).ref;
        return list.map(a => Utils.html`<option value="${a.ref}" ${a.ref === selected ? 'selected' : ''}>${a.name}</option>`).join('');
    }

    /** Fills a select and shows its row when there is a choice; resolves to whether it is shown. */
    function fill($row, $select, selectedRef) {
        return load().then(list => {
            const multi = isMulti(list);
            if (multi) $select.html(optionsHtml(list, selectedRef));
            $row.prop('hidden', !multi);
            return multi;
        });
    }

    function invalidate() {
        cache = null;
    }

    return { load, isMulti, cached, nameOf, optionsHtml, fill, invalidate };
})();

window.AwsAccountOptions = AwsAccountOptions;
