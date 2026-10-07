/**
 * VM Self-Service Platform - Utility Functions
 *
 * Escaping contract: put user or server data into HTML only through Utils.escapeHtml or the
 * Utils.html tagged template; use jQuery .text()/.attr() for single values; never place data
 * inside inline event handlers (onclick="..."). Utils.raw marks markup you built yourself
 * (for example another Utils.html result) so Utils.html embeds it unescaped.
 */

const Utils = (function() {
    'use strict';

    /**
     * Format a date for display
     * @param {string|Date} date - Date to format
     * @returns {string} - Formatted date string
     */
    function formatDate(date) {
        if (!date) return '-';
        const d = new Date(date);
        return d.toLocaleDateString() + ' ' + d.toLocaleTimeString();
    }

    /**
     * Format duration from milliseconds
     * @param {number} ms - Duration in milliseconds
     * @returns {string} - Formatted duration (e.g., "2h 15m")
     */
    function formatDuration(ms) {
        if (!ms || ms < 0) return '-';

        const seconds = Math.floor(ms / 1000);
        const minutes = Math.floor(seconds / 60);
        const hours = Math.floor(minutes / 60);
        const days = Math.floor(hours / 24);

        if (days > 0) {
            return `${days}d ${hours % 24}h`;
        } else if (hours > 0) {
            return `${hours}h ${minutes % 60}m`;
        } else if (minutes > 0) {
            return `${minutes}m`;
        } else {
            return `${seconds}s`;
        }
    }

    /**
     * Format uptime from a start timestamp
     * @param {string|Date} startTime - Start time
     * @returns {string} - Formatted uptime
     */
    function formatUptime(startTime) {
        if (!startTime) return '-';
        const start = new Date(startTime);
        const now = new Date();
        return formatDuration(now - start);
    }

    /**
     * Format currency
     * @param {number} amount - Amount to format
     * @param {string} currency - Currency code (default: USD)
     * @returns {string} - Formatted currency
     */
    function formatCurrency(amount, currency = 'USD') {
        return new Intl.NumberFormat('en-US', {
            style: 'currency',
            currency: currency
        }).format(amount);
    }

    /**
     * Debounce function calls
     * @param {Function} func - Function to debounce
     * @param {number} wait - Wait time in ms
     * @returns {Function} - Debounced function
     */
    function debounce(func, wait) {
        let timeout;
        return function executedFunction(...args) {
            const later = () => {
                clearTimeout(timeout);
                func(...args);
            };
            clearTimeout(timeout);
            timeout = setTimeout(later, wait);
        };
    }

    /**
     * Generate a UUID
     * @returns {string} - UUID string
     */
    function generateUUID() {
        return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
            const r = Math.random() * 16 | 0;
            const v = c === 'x' ? r : (r & 0x3 | 0x8);
            return v.toString(16);
        });
    }

    /**
     * Check if an object is empty
     * @param {object} obj - Object to check
     * @returns {boolean}
     */
    function isEmpty(obj) {
        return !obj || Object.keys(obj).length === 0;
    }

    /**
     * Capitalize first letter
     * @param {string} str - String to capitalize
     * @returns {string}
     */
    function capitalize(str) {
        if (!str) return '';
        return str.charAt(0).toUpperCase() + str.slice(1).toLowerCase();
    }

    /**
     * Truncate string with ellipsis
     * @param {string} str - String to truncate
     * @param {number} maxLength - Maximum length
     * @returns {string}
     */
    function truncate(str, maxLength) {
        if (!str || str.length <= maxLength) return str;
        return str.substring(0, maxLength - 3) + '...';
    }

    const HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

    /**
     * Escape a value for HTML text and quoted attribute values (escapes & < > " ').
     * @param {*} value - Value to escape; null/undefined become '', 0 becomes '0'
     * @returns {string} - Escaped string
     */
    function escapeHtml(value) {
        if (value === null || value === undefined) return '';
        return String(value).replace(/[&<>"']/g, ch => HTML_ESCAPES[ch]);
    }

    /** Markup that Utils.html embeds as-is. Create it with Utils.raw. */
    class RawHtml {
        constructor(markup) {
            this.__html = markup;
            Object.freeze(this);
        }

        toString() {
            return this.__html;
        }
    }

    /**
     * Mark trusted markup so Utils.html does not escape it.
     * @param {*} markup - Markup built by the app (never raw user or server data)
     * @returns {RawHtml}
     */
    function raw(markup) {
        return new RawHtml(markup === null || markup === undefined ? '' : String(markup));
    }

    function htmlValue(value) {
        if (value instanceof RawHtml) return value.__html;
        if (Array.isArray(value)) return value.map(htmlValue).join('');
        if (value === null || value === undefined || value === false) return '';
        return escapeHtml(value);
    }

    /**
     * Tagged template that escapes every interpolated value:
     *   Utils.html`<td title="${name}">${name}</td>`
     * Arrays are escaped per element and joined; null, undefined and false render as ''.
     * Wrap trusted markup (e.g. another Utils.html result) in Utils.raw(...) to embed it.
     * @returns {string} - HTML string for jQuery .html()/.append()
     */
    function html(strings, ...values) {
        let out = strings[0];
        for (let i = 0; i < values.length; i++) {
            out += htmlValue(values[i]) + strings[i + 1];
        }
        return out;
    }

    /**
     * Parse query string parameters
     * @param {string} queryString - Query string to parse
     * @returns {object} - Parsed parameters
     */
    function parseQueryString(queryString) {
        const params = {};
        const searchParams = new URLSearchParams(queryString);
        for (const [key, value] of searchParams) {
            params[key] = value;
        }
        return params;
    }

    /**
     * Format relative time (e.g., "2 hours ago")
     * @param {string|Date} date - Date to format
     * @returns {string} - Relative time string
     */
    function formatRelativeTime(date) {
        if (!date) return '-';

        const now = new Date();
        const then = new Date(date);
        const diffMs = now - then;
        const diffSec = Math.floor(diffMs / 1000);
        const diffMin = Math.floor(diffSec / 60);
        const diffHour = Math.floor(diffMin / 60);
        const diffDay = Math.floor(diffHour / 24);

        if (diffSec < 60) return 'just now';
        if (diffMin < 60) return `${diffMin}m ago`;
        if (diffHour < 24) return `${diffHour}h ago`;
        if (diffDay < 7) return `${diffDay}d ago`;

        return then.toLocaleDateString();
    }

    /**
     * Trigger a browser download of a client-generated Blob.
     * @param {Blob} blob - The blob to download
     * @param {string} filename - Suggested filename
     */
    function downloadBlob(blob, filename) {
        const link = document.createElement('a');
        link.setAttribute('href', URL.createObjectURL(blob));
        link.setAttribute('download', filename);
        link.style.visibility = 'hidden';
        document.body.appendChild(link);
        link.click();
        document.body.removeChild(link);
    }

    /**
     * Render a VM status badge with screen reader text (TASK-033)
     * @param {string} status - VM status (RUNNING, STOPPED, etc.)
     * @returns {string} - HTML for the status badge
     */
    function renderStatusBadge(status) {
        const statusConfig = {
            RUNNING: {
                cssClass: 'running',
                icon: 'fa-play-circle',
                text: 'Running',
                srText: 'VM is currently running',
                spinning: false
            },
            STOPPED: {
                cssClass: 'stopped',
                icon: 'fa-stop-circle',
                text: 'Stopped',
                srText: 'VM is currently stopped',
                spinning: false
            },
            STARTING: {
                cssClass: 'starting',
                icon: 'fa-spinner',
                text: 'Starting...',
                srText: 'VM is starting up',
                spinning: true
            },
            STOPPING: {
                cssClass: 'stopping',
                icon: 'fa-spinner',
                text: 'Stopping...',
                srText: 'VM is shutting down',
                spinning: true
            },
            ERROR: {
                cssClass: 'error',
                icon: 'fa-exclamation-triangle',
                text: 'Error',
                srText: 'VM has encountered an error',
                spinning: false
            },
            UNKNOWN: {
                cssClass: 'unknown',
                icon: 'fa-question-circle',
                text: 'Unknown',
                srText: 'VM status is unknown',
                spinning: false
            }
        };

        const config = statusConfig[status] || statusConfig.UNKNOWN;
        const spinClass = config.spinning ? 'fa-spin' : '';

        return `
            <span class="status-badge ${config.cssClass}" role="status">
                <i class="fas ${config.icon} ${spinClass}" aria-hidden="true"></i>
                <span class="status-text">${config.text}</span>
                <span class="visually-hidden">${config.srText}</span>
            </span>
        `;
    }

    return {
        formatDate,
        formatDuration,
        formatUptime,
        formatCurrency,
        debounce,
        generateUUID,
        isEmpty,
        capitalize,
        truncate,
        escapeHtml,
        html,
        raw,
        parseQueryString,
        formatRelativeTime,
        renderStatusBadge,
        downloadBlob
    };
})();

