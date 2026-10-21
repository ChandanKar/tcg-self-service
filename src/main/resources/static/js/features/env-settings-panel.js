/**
 * VM Self-Service Platform - Environment settings panel (U07-T02)
 * The environment page's "Settings" side panel: Notify members, Spot, Teams channels and webhooks.
 * Nothing here is requested until the panel is opened, so the page itself makes no request for
 * these features. Each section renders through its own module into the same ids as before
 * (#env-spot-panel, #env-chat-panel, #env-webhook-panel).
 */
const EnvSettingsPanel = (function() {
    'use strict';

    function hasFeature(feature) {
        return typeof Capabilities !== 'undefined' && Capabilities.has(feature);
    }

    /** Whether the viewer has anything in the panel: administrators always, others only Spot's state. */
    function isAvailable(env) {
        return !!env && (env.viewerCanAdminister === true || hasFeature('spot'));
    }

    function notifyHtml(env) {
        if (env.viewerCanAdminister !== true) return '';
        return `
            <section class="env-settings-section" aria-labelledby="env-settings-notify-title">
                <h3 id="env-settings-notify-title">Tell everyone with access</h3>
                <p>Email and notify everyone who can see this environment, for example before you stop it.</p>
                <button type="button" class="btn btn-secondary btn-sm" data-action="env-settings-notify">Notify members</button>
            </section>`;
    }

    let current = null;

    function open(env) {
        if (!isAvailable(env)) return;
        current = env;
        const admin = env.viewerCanAdminister === true;
        const body = `
            <div class="env-settings">
                ${notifyHtml(env)}
                <div id="env-spot-panel"></div>
                ${admin ? '<div id="env-chat-panel"></div><div id="env-webhook-panel"></div>' : ''}
            </div>`;
        Slideout.open('Environment settings', body, { html: true });
        if (typeof SpotPanel !== 'undefined' && hasFeature('spot')) SpotPanel.render(env);
        if (admin && typeof ChatChannels !== 'undefined' && hasFeature('chat')) ChatChannels.renderEnvPanel(env);
        if (admin && typeof WebhooksPanel !== 'undefined' && hasFeature('webhooks')) WebhooksPanel.renderEnvPanel(env);
    }

    /**
     * "Tell everyone it's being stopped" (U11-T05): emails and bell-notifies everyone with access,
     * with an optional reason. Not gated behind a notification.email.* flag: the click is the opt-in.
     */
    function notifyMembers() {
        if (!current) return;
        RegistryDialogs.notifyStop(current);
    }

    return { open, isAvailable, notifyMembers };
})();

if (typeof window !== 'undefined') window.EnvSettingsPanel = EnvSettingsPanel;
Actions.register('env-settings-notify', () => EnvSettingsPanel.notifyMembers());
