/**
 * The kinds of change a rightsizing suggestion makes (U15-T03): a smaller or larger size, a newer
 * generation, a move to Graviton (ARM), read from its direction and the two instance type names.
 * Mirrors RightsizingKind.java, which filters the list on the server. Pure, tested in
 * src/test/js/rightsizing-kind.test.mjs.
 */
const RightsizingKind = (function() {
    'use strict';

    /** "m6gd.large": class letters, generation digits, attribute letters, size. */
    const TYPE = /^([a-z]+)(\d+)([a-z-]*)\.(.+)$/;

    /** The Kind of change filter: value sent to the server, and its label. */
    const FILTERS = [['', 'All suggestions'], ['SMALLER', 'Smaller size'], ['NEWER', 'Newer generation'],
        ['GRAVITON', 'Graviton (ARM)'], ['LARGER', 'Larger size']];

    function parse(type) {
        return TYPE.exec(String(type || '').toLowerCase());
    }

    /** A Graviton family carries a "g" in its attribute letters: m6g, c7gn, r6gd, t4g, im4gn. */
    function isGraviton(type) {
        const m = parse(type);
        return !!m && m[3].includes('g');
    }

    function generation(type) {
        const m = parse(type);
        return m ? Number(m[2]) : 0;
    }

    /** The kinds of one suggestion, in the order the label reads them; [] without a suggested type. */
    function kindsOf(row) {
        const r = row || {};
        if (!r.suggestedInstanceType) return [];
        const kinds = [];
        if (r.direction === 'SCALE_DOWN') kinds.push('SMALLER');
        if (r.direction === 'SCALE_UP') kinds.push('LARGER');
        const from = generation(r.currentInstanceType);
        const to = generation(r.suggestedInstanceType);
        if (r.direction === 'MODERNIZE' || (from > 0 && to > from)) kinds.push('NEWER');
        if (isGraviton(r.suggestedInstanceType) && !isGraviton(r.currentInstanceType)) kinds.push('GRAVITON');
        return kinds;
    }

    /** "Smaller", "Smaller, newer", "Graviton", "Newer generation", "Larger": the line under the change. */
    function label(row) {
        const kinds = kindsOf(row);
        const size = kinds.includes('SMALLER') ? 'Smaller' : kinds.includes('LARGER') ? 'Larger' : '';
        const what = kinds.includes('GRAVITON') ? 'Graviton' : kinds.includes('NEWER') ? (size ? 'newer' : 'Newer generation') : '';
        if (size && what) return `${size}, ${what === 'Graviton' ? what : what.toLowerCase()}`;
        return size || what;
    }

    /** What AWS Compute Optimizer thinks, appended to the label ('' when only the local rule speaks). */
    const AGREEMENT = { AGREE: 'AWS agrees', ONLY_AWS: 'Suggested by AWS', DISAGREE: 'AWS disagrees' };

    function agreementText(row) {
        return AGREEMENT[(row || {}).agreement] || '';
    }

    return { FILTERS, isGraviton, generation, kindsOf, label, agreementText };
})();

if (typeof window !== 'undefined') window.RightsizingKind = RightsizingKind;
