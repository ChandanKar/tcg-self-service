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
    function request(method, url, data = null) {
        const options = {
            url: url,
            method: method,
            contentType: 'application/json',
            dataType: 'json'
        };

        if (data && (method === 'POST' || method === 'PUT' || method === 'PATCH')) {
            options.data = JSON.stringify(data);
        }

        return $.ajax(options)
            .fail(function(xhr, status, error) {
                console.error(`API Error [${method} ${url}]:`, error);
                handleApiError(xhr, status, error);
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
    function get(url) {
        return request('GET', url);
    }

    function post(url, data) {
        return request('POST', url, data);
    }

    function put(url, data) {
        return request('PUT', url, data);
    }

    function patch(url, data) {
        return request('PATCH', url, data);
    }

    function del(url) {
        return request('DELETE', url);
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

