/**
 * VM Self-Service Platform - Environment settings panel (U07-T02, narrowed by U11-T07)
 * For people who do not administer the environment but may see its Spot state: the environment
 * page's Settings opens this side panel with Spot only. Its admins get the settings page instead
 * (env-settings.js), where Spot, Teams channels, webhooks and the stop notice now live.
 * Nothing here is requested until the panel is opened.
 */
const EnvSettingsPanel = (function() {
    'use strict';

    function hasFeature(feature) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(feature);
    }

    /** Whether the Settings button shows: its admins (the settings page), others only while Spot is on. */
    function isAvailable(env) {
        return !!env && (env.viewerCanAdminister === true || hasFeature('spot'));
    }

    function open(env) {
        if (!isAvailable(env)) return;
        Slideout.open('Environment settings', '<div class="env-settings"><div id="env-spot-panel"></div></div>', { html: true });
        if (typeof SpotPanel !== 'undefined' && hasFeature('spot')) SpotPanel.render(env);
    }

    return { open, isAvailable };
})();

if (typeof window !== 'undefined') window.EnvSettingsPanel = EnvSettingsPanel;
