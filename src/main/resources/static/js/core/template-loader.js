/**
 * VM Self-Service Platform - Template Loader
 * Handles loading and caching of HTML templates
 */

const TemplateLoader = (function() {
    'use strict';

    // Template cache
    const cache = {};

    // Template base path
    const TEMPLATE_PATH = '/templates';

    /**
     * Load a template via AJAX with caching
     * @param {string} templateName - Template filename (e.g., 'dashboard.html')
     * @returns {Promise<string>} - Template HTML string
     */
    function load(templateName) {
        return new Promise((resolve, reject) => {
            // Check cache first
            if (cache[templateName]) {
                resolve(cache[templateName]);
                return;
            }

            const url = `${TEMPLATE_PATH}/${templateName}`;

            $.ajax({
                url: url,
                method: 'GET',
                dataType: 'html',
                success: function(html) {
                    cache[templateName] = html;
                    resolve(html);
                },
                error: function(xhr, status, error) {
                    console.error(`Failed to load template: ${templateName}`, error);
                    reject(new Error(`Template not found: ${templateName}`));
                }
            });
        });
    }

    /**
     * Render a template with data using simple placeholder replacement
     * Supports {{variable}} and {{variable.nested}} syntax
     * @param {string} template - Template HTML string
     * @param {object} data - Data object for placeholder replacement
     * @returns {string} - Rendered HTML
     */
    function render(template, data) {
        return template.replace(/\{\{(\w+(?:\.\w+)*)\}\}/g, (match, key) => {
            // Support nested properties like {{user.name}}
            const keys = key.split('.');
            let value = data;

            for (const k of keys) {
                if (value && typeof value === 'object' && k in value) {
                    value = value[k];
                } else {
                    return ''; // Key not found
                }
            }

            // Escape HTML to prevent XSS
            return escapeHtml(String(value));
        });
    }

    /**
     * Load template and render with data in one call
     * @param {string} templateName - Template filename
     * @param {object} data - Data for rendering
     * @returns {Promise<string>} - Rendered HTML
     */
    async function loadAndRender(templateName, data) {
        const template = await load(templateName);
        return render(template, data);
    }

    /**
     * Preload multiple templates for better performance
     * @param {string[]} templateNames - Array of template filenames
     * @returns {Promise<void>}
     */
    function preload(templateNames) {
        return Promise.all(templateNames.map(name => load(name)));
    }

    /**
     * Clear the template cache (useful for development)
     */
    function clearCache() {
        Object.keys(cache).forEach(key => delete cache[key]);
    }

    /**
     * Escape HTML special characters
     * @param {string} str - String to escape
     * @returns {string} - Escaped string
     */
    function escapeHtml(str) {
        const div = document.createElement('div');
        div.textContent = str;
        return div.innerHTML;
    }

    /**
     * Render HTML without escaping (use with caution!)
     * @param {string} template - Template HTML string
     * @param {object} data - Data object
     * @returns {string} - Rendered HTML
     */
    function renderRaw(template, data) {
        return template.replace(/\{\{\{(\w+(?:\.\w+)*)\}\}\}/g, (match, key) => {
            const keys = key.split('.');
            let value = data;

            for (const k of keys) {
                if (value && typeof value === 'object' && k in value) {
                    value = value[k];
                } else {
                    return '';
                }
            }

            return String(value);
        });
    }

    return {
        load,
        render,
        loadAndRender,
        preload,
        clearCache,
        renderRaw
    };
})();

