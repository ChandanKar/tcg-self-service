/**
 * VM Self-Service Platform - Capabilities (U02-T01)
 * The signed-in person's role and which optional features the server has switched on, loaded once
 * after sign-in. Pages and the router use it to open only what will work and to skip requests to
 * switched-off features. The server still checks every call; this is for clarity, not security.
 */
const Capabilities = (function() {
    'use strict';

    let state = { admin: false, envAdmin: false, features: {} };
    let loaded = null;

    /** Loads once; on failure every feature counts as off and the roles come from Auth. */
    function load() {
        if (!loaded) {
            loaded = new Promise(resolve => {
                ApiClient.request('GET', Config.API.users.capabilities, null, { suppressGlobalError: true })
                    .done(data => {
                        state = {
                            admin: !!(data && data.admin),
                            envAdmin: !!(data && data.envAdmin),
                            features: (data && data.features) || {}
                        };
                        resolve(state);
                    })
                    .fail(() => {
                        state = {
                            admin: typeof Auth !== 'undefined' && Auth.isAdmin(),
                            envAdmin: typeof Auth !== 'undefined' && Auth.isEnvAdmin(),
                            features: {}
                        };
                        resolve(state);
                    });
            });
        }
        return loaded;
    }

    /** True when the feature is switched on (false until load() has answered). */
    function has(feature) {
        return state.features[feature] === true;
    }

    function isAdmin() {
        return state.admin || (typeof Auth !== 'undefined' && Auth.isAdmin());
    }

    function isEnvAdmin() {
        return state.envAdmin || (typeof Auth !== 'undefined' && Auth.isEnvAdmin());
    }

    /** For tests and after a settings change: forget the loaded answer. */
    function reset() {
        loaded = null;
        state = { admin: false, envAdmin: false, features: {} };
    }

    return { load, has, isAdmin, isEnvAdmin, reset };
})();

window.Capabilities = Capabilities;
