/**
 * VM Self-Service Platform - API Client
 * Base AJAX wrapper for API calls
 */

const ApiClient = (function() {
    'use strict';

    // Contextual error messages by status code and error type (TASK-030)
    const ERROR_MESSAGES = {
        400: {
            default: 'Invalid request. Please check your input.',
            validation_failed: 'Some fields have invalid values. Please correct them.',
            invalid_json: 'Invalid data format. Please try again.'
        },
        401: {
            default: 'Your session has expired. Please log in again.',
            invalid_token: 'Authentication failed. Please log in again.',
            token_expired: 'Your session has expired. Please log in again.'
        },
        403: {
            default: 'You don\'t have permission to perform this action.',
            lock_required: 'You must acquire a lock before performing this operation.',
            not_lock_owner: 'This environment is locked by another user.',
            access_denied: 'Access denied. You don\'t have permission to access this resource.',
            insufficient_role: 'Your role does not allow this action.'
        },
        404: {
            default: 'The requested resource was not found.',
            environment_not_found: 'This environment no longer exists.',
            vm_not_found: 'This VM no longer exists.',
            user_not_found: 'User not found.',
            group_not_found: 'VM group not found.'
        },
        409: {
            default: 'A conflict occurred. Please refresh and try again.',
            lock_held: 'This environment is already locked by another user.',
            operation_in_progress: 'An operation is already in progress. Please wait.',
            duplicate_name: 'An item with this name already exists.',
            concurrent_modification: 'This resource was modified by another user. Please refresh.'
        },
        422: {
            default: 'The request could not be processed.',
            invalid_state: 'Cannot perform this operation in the current state.',
            vm_not_responding: 'The VM is not responding. Please try again later.'
        },
        500: {
            default: 'An unexpected error occurred. Please try again later.',
            internal_error: 'Internal server error. Please contact support if this persists.'
        },
        502: {
            default: 'The server is temporarily unavailable. Please try again.',
            bad_gateway: 'Unable to reach cloud provider. Please try again.'
        },
        503: {
            default: 'The service is temporarily unavailable. Please try again in a few minutes.',
            maintenance: 'The system is undergoing maintenance. Please try again later.'
        }
    };

    /**
     * Get contextual error message based on status and error code
     */
    function getErrorMessage(status, errorCode) {
        const statusMessages = ERROR_MESSAGES[status] || {};
        return statusMessages[errorCode] || statusMessages.default || 'An error occurred.';
    }

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
            dataType: 'json',
            dataFilter: function(data, type) {
                if (type === 'json' && (!data || data.trim() === '')) {
                    return 'null';
                }
                return data;
            }
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
     * Handle API errors with contextual messages
     */
    function handleApiError(xhr, status, error) {
        let message = 'An error occurred';
        let errorCode = null;

        // Try to extract error code from response
        if (xhr.responseJSON) {
            if (xhr.responseJSON.message) {
                message = xhr.responseJSON.message;
            }
            errorCode = xhr.responseJSON.error || xhr.responseJSON.code || null;
        }

        // Use contextual message if no specific message from server
        if (!xhr.responseJSON?.message) {
            if (xhr.status === 0) {
                message = 'Unable to connect to server. Please check your connection.';
            } else {
                message = getErrorMessage(xhr.status, errorCode);
            }
        }

        // Handle session expiry
        if (xhr.status === 401) {
            // Redirect to login after showing message
            setTimeout(() => {
                if (/^\/home\/?$/.test(window.location.pathname) && window.location.hash) {
                    sessionStorage.setItem('vmcontrol.intendedRoute', window.location.hash);
                }
                window.location.href = '/login.html';
            }, 2000);
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
        delete: del,
        getErrorMessage
    };
})();

