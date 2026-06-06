/**
 * VM Self-Service Platform - Form Validation (TASK-020)
 * Real-time inline validation for forms
 */

const FormValidator = (function() {
    'use strict';

    /**
     * Initialize validation for a form
     * @param {string} formId - The form element ID
     */
    function init(formId) {
        const form = document.getElementById(formId);
        if (!form) return;

        // Get all form fields
        const fields = form.querySelectorAll('input, select, textarea');

        fields.forEach(field => {
            // Validate on blur
            field.addEventListener('blur', () => validateField(field));

            // Clear error on input if already invalid
            field.addEventListener('input', () => {
                if (field.classList.contains('is-invalid')) {
                    validateField(field);
                }
            });
        });
    }

    /**
     * Validate a single field
     * @param {HTMLElement} field - The form field element
     * @returns {boolean} - True if valid
     */
    function validateField(field) {
        const value = field.value.trim();
        let isValid = true;
        let message = '';

        // Skip hidden or disabled fields
        if (field.type === 'hidden' || field.disabled) {
            return true;
        }

        // Required check
        if (field.required && !value) {
            isValid = false;
            message = 'This field is required';
        }

        // Min length check
        if (isValid && field.minLength > 0 && value.length > 0 && value.length < field.minLength) {
            isValid = false;
            message = `Minimum ${field.minLength} characters required`;
        }

        // Max length check
        if (isValid && field.maxLength > 0 && value.length > field.maxLength) {
            isValid = false;
            message = `Maximum ${field.maxLength} characters allowed`;
        }

        // Pattern check
        if (isValid && field.pattern && value) {
            const regex = new RegExp(field.pattern);
            if (!regex.test(value)) {
                isValid = false;
                message = field.dataset.patternMessage || 'Invalid format';
            }
        }

        // Email validation
        if (isValid && field.type === 'email' && value) {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            if (!emailRegex.test(value)) {
                isValid = false;
                message = 'Please enter a valid email address';
            }
        }

        // Number validation
        if (isValid && field.type === 'number' && value) {
            const num = parseFloat(value);
            if (isNaN(num)) {
                isValid = false;
                message = 'Please enter a valid number';
            } else {
                if (field.min !== '' && num < parseFloat(field.min)) {
                    isValid = false;
                    message = `Minimum value is ${field.min}`;
                }
                if (field.max !== '' && num > parseFloat(field.max)) {
                    isValid = false;
                    message = `Maximum value is ${field.max}`;
                }
            }
        }

        // Custom validators
        if (isValid && field.dataset.validator) {
            const result = customValidators[field.dataset.validator]?.(value, field);
            if (result && !result.valid) {
                isValid = false;
                message = result.message;
            }
        }

        setFieldState(field, isValid, message);
        return isValid;
    }

    /**
     * Set the visual state of a field
     * @param {HTMLElement} field - The form field
     * @param {boolean} isValid - Validation result
     * @param {string} message - Error message
     */
    function setFieldState(field, isValid, message) {
        // Find feedback element
        const feedback = field.parentElement.querySelector('.invalid-feedback') ||
                        field.parentElement.querySelector('.form-text.text-danger');

        // Update classes
        field.classList.toggle('is-valid', isValid && field.value);
        field.classList.toggle('is-invalid', !isValid);

        // Update feedback text
        if (feedback) {
            feedback.textContent = message;
        }

        // Update aria-invalid for accessibility
        field.setAttribute('aria-invalid', !isValid);
    }

    /**
     * Validate entire form
     * @param {string} formId - The form element ID
     * @returns {boolean} - True if all fields are valid
     */
    function validateForm(formId) {
        const form = document.getElementById(formId);
        if (!form) return false;

        let isValid = true;
        const fields = form.querySelectorAll('input, select, textarea');

        fields.forEach(field => {
            if (!validateField(field)) {
                isValid = false;
            }
        });

        // Focus first invalid field
        if (!isValid) {
            const firstInvalid = form.querySelector('.is-invalid');
            if (firstInvalid) {
                firstInvalid.focus();
            }
        }

        return isValid;
    }

    /**
     * Clear all validation states
     * @param {string} formId - The form element ID
     */
    function clearValidation(formId) {
        const form = document.getElementById(formId);
        if (!form) return;

        const fields = form.querySelectorAll('input, select, textarea');
        fields.forEach(field => {
            field.classList.remove('is-valid', 'is-invalid');
            field.removeAttribute('aria-invalid');
        });
    }

    /**
     * Custom validators
     * Add custom validation logic here
     */
    const customValidators = {
        // Environment name: lowercase, numbers, hyphens only
        environmentName(value) {
            if (!/^[a-z0-9-]+$/.test(value)) {
                return {
                    valid: false,
                    message: 'Only lowercase letters, numbers, and hyphens allowed'
                };
            }
            return { valid: true };
        },

        // JSON format validator
        jsonFormat(value) {
            if (!value) return { valid: true };
            try {
                JSON.parse(value);
                return { valid: true };
            } catch {
                return {
                    valid: false,
                    message: 'Invalid JSON format'
                };
            }
        },

        // VM name: alphanumeric, hyphens, underscores
        vmName(value) {
            if (!/^[a-zA-Z0-9_-]+$/.test(value)) {
                return {
                    valid: false,
                    message: 'Only letters, numbers, hyphens, and underscores allowed'
                };
            }
            return { valid: true };
        },

        // AWS instance ID format
        awsInstanceId(value) {
            if (!/^i-[a-f0-9]{8,17}$/.test(value)) {
                return {
                    valid: false,
                    message: 'Invalid AWS instance ID format (e.g., i-0123456789abcdef0)'
                };
            }
            return { valid: true };
        },

        // Positive integer
        positiveInteger(value) {
            const num = parseInt(value, 10);
            if (isNaN(num) || num < 1) {
                return {
                    valid: false,
                    message: 'Please enter a positive number'
                };
            }
            return { valid: true };
        }
    };

    /**
     * Register a custom validator
     * @param {string} name - Validator name
     * @param {function} validator - Validator function(value, field) => {valid: boolean, message: string}
     */
    function registerValidator(name, validator) {
        customValidators[name] = validator;
    }

    return {
        init,
        validateField,
        validateForm,
        clearValidation,
        registerValidator
    };
})();

