/**
 * VM Self-Service Platform - Utility Functions
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

    /**
     * Escape HTML to prevent XSS
     * @param {string} text - Text to escape
     * @returns {string} - Escaped HTML string
     */
    function escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
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
        parseQueryString,
        formatRelativeTime
    };
})();

