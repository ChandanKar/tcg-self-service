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
     * Emails (and bell-notifies) everyone with active access to the environment. Not gated behind a
     * notification.email.* flag: the administrator's click is the opt-in.
     */
    function notifyMembers() {
        if (!current) return;
        const envId = current.environmentId;
        const envName = current.displayName || current.name;
        Modals.confirm(
            'Notify environment members',
            `Email everyone with access to <strong>${Utils.escapeHtml(envName)}</strong> that it's being stopped? This also adds an in-app notification for each of them.`,
            function() {
                ApiClient.post(Config.API.environments.notifyStop(envId), {})
                    .done(function(result) {
                        const n = result && result.emailedCount;
                        Notifications.success(n
                            ? `Emailed ${n} member${n === 1 ? '' : 's'}`
                            : 'No active members with an email address to notify');
                    })
                    .fail(function(xhr) {
                        Notifications.error(xhr.responseJSON?.message || 'Failed to send notification');
                    });
            },
            { confirmText: 'Notify', confirmClass: 'btn-primary', html: true }
        );
    }

    return { open, isAvailable, notifyMembers };
})();

if (typeof window !== 'undefined') window.EnvSettingsPanel = EnvSettingsPanel;
Actions.register('env-settings-notify', () => EnvSettingsPanel.notifyMembers());
