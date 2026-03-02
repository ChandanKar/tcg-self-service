/**
 * VM Self-Service Platform - API Client
 * Base AJAX wrapper for API calls
 */

const ApiClient = (function() {
    'use strict';

    /**
     * Make an API request
     * @param {string} method - HTTP method
     * @param {string} url - API endpoint
     * @param {object} data - Request body (for POST/PUT)
     * @returns {Promise} - jQuery deferred promise
     */
    function request(method, url, data = null, options = {}) {
        const { suppressGlobalError = false } = options;

        const ajaxOptions = {
            url: url,
            method: method,
            contentType: 'application/json',
            dataType: 'json'
        };

        if (data && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
            ajaxOptions.data = JSON.stringify(data);
        }

        return $.ajax(ajaxOptions)
            .fail(function(xhr, status, error) {
                console.error(`API Error [${method} ${url}]:`, error);
                if (!suppressGlobalError) {
                    handleApiError(xhr, status, error);
                }
            });
    }

    /**
     * Handle API errors
     */
    function handleApiError(xhr, status, error) {
        let message = 'An error occurred';

        if (xhr.responseJSON && xhr.responseJSON.message) {
            message = xhr.responseJSON.message;
        } else if (xhr.status === 0) {
            message = 'Unable to connect to server';
        } else if (xhr.status === 401) {
            message = 'Session expired. Please login again.';
        } else if (xhr.status === 403) {
            message = 'You do not have permission for this action';
        } else if (xhr.status === 404) {
            message = 'Resource not found';
        } else if (xhr.status >= 500) {
            message = 'Server error. Please try again later.';
        }

        // Show notification if available
        if (typeof Notifications !== 'undefined') {
            Notifications.error(message);
        }
    }

    // Convenience methods
    function get(url, options) {
        return request('GET', url, null, options);
    }

    function post(url, data, options) {
        return request('POST', url, data, options);
    }

    function put(url, data, options) {
        return request('PUT', url, data, options);
    }

    function patch(url, data, options) {
        return request('PATCH', url, data, options);
    }

    function del(url, options) {
        return request('DELETE', url, null, options);
    }

    return {
        request,
        get,
        post,
        put,
        patch,
        delete: del
    };
})();

