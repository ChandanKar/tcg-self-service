// Run with: node --test "src/test/js/**/*.test.mjs"
// U15-T05: the Setup tab's feature states and the Unusual spend tab's dates.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';

const STATIC = fileURLToPath(new URL('../../main/resources/static/', import.meta.url));
const context = vm.createContext({});
context.window = context;
vm.runInContext(readFileSync(STATIC + 'js/features/cost-setup.js', 'utf8'), context);
vm.runInContext(readFileSync(STATIC + 'js/features/cost-anomalies.js', 'utf8'), context);
const S = context.CostSetup.model;
const A = context.CostAnomalies.model;
const feature = (key, enabled, checks) => ({ key, enabled, checks });
const check = (action, result, hint) => ({ action, result, hint: hint || null });

test('a feature is off, without permission, not sure, working or on but not checked', () => {
    assert.equal(S.featureState(feature('carbon', false, []), true).label, 'Off');
    const denied = S.featureState(feature('actuals', true, [check('ce:GetCostAndUsage', 'DENIED')]), true);
    assert.equal(denied.label, 'No permission');
    assert.equal(S.fixSentence(denied), 'AWS refused ce:GetCostAndUsage. Ask your AWS admin to add it to the platform role.');
    const two = S.featureState(feature('tagging', true, [check('ec2:CreateTags', 'DENIED'), check('eks:TagResource', 'DENIED')]), true);
    assert.equal(S.fixSentence(two), 'AWS refused ec2:CreateTags and eks:TagResource. Ask your AWS admin to add them to the platform role.');
    const unsure = S.featureState(feature('optimizer', true, [check('compute-optimizer:GetEnrollmentStatus', 'UNKNOWN', 'Could not simulate')]), true);
    assert.equal(unsure.label, 'Not sure');
    assert.equal(S.fixSentence(unsure), 'Could not simulate');
    assert.equal(S.featureState(feature('optimizer', true, [check('x', 'ALLOWED')]), true).label, 'Working');
    assert.equal(S.featureState(feature('optimizer', true, []), false).label, 'On');
    assert.equal(S.featureState(feature('snapshot', true, []), false).label, 'Working'); // needs no AWS permission
});

test('the summary line counts what works once checked, else what is on', () => {
    const features = [feature('snapshot', true, []), feature('actuals', true, [check('ce:GetCostAndUsage', 'DENIED')]),
        feature('optimizer', true, [check('x', 'ALLOWED')]), feature('carbon', false, [])];
    assert.equal(S.summaryLine(features, true), '2 of 4 cost features work');
    assert.equal(S.summaryLine(features, false), '3 of 4 cost features are on');
});

test('unusual spend dates: one day, a range in a month, across months, still open', () => {
    assert.equal(A.whenText({ anomalyStart: '2026-10-18', anomalyEnd: '2026-10-18' }), '18 Oct · 1 day');
    assert.equal(A.whenText({ anomalyStart: '2026-10-15', anomalyEnd: '2026-10-17' }), '15–17 Oct · 3 days');
    assert.equal(A.whenText({ anomalyStart: '2026-09-30', anomalyEnd: '2026-10-02' }), '30 Sep – 2 Oct · 3 days');
    assert.equal(A.whenText({ anomalyStart: '2026-10-18', anomalyEnd: null }), 'since 18 Oct');
    assert.deepEqual(JSON.parse(JSON.stringify(A.VERDICTS.map(v => v.label))), ['Unexpected', 'Planned', 'Not unusual']);
});
