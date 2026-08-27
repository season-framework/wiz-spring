'use strict';

const DEFAULT_API_PREFIX = '/api';
const DEFAULT_BACKEND_TARGET = 'http://localhost:8080';
const VALID_API_PREFIX = /^\/[A-Za-z0-9._~-]+(?:\/[A-Za-z0-9._~-]+)*$/;

function normalizeApiPrefix(value = process.env.APP_API_PREFIX) {
    const prefix = typeof value === 'string' && value.trim() ? value.trim() : DEFAULT_API_PREFIX;
    if (!VALID_API_PREFIX.test(prefix)) {
        throw new Error('APP_API_PREFIX must be an absolute path such as /api or /api/v2');
    }
    return prefix;
}

function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function createDevProxy({ apiPrefix, target = DEFAULT_BACKEND_TARGET } = {}) {
    const prefix = normalizeApiPrefix(apiPrefix);
    const proxy = () => ({ target, secure: false });

    return {
        [`^${escapeRegExp(prefix)}(?=/|\\?|$)`]: proxy(),
        '^/app-config\\.json(?=\\?|$)': proxy(),
        '^/v3/api-docs(?:\\.yaml)?(?=/|\\?|$)': proxy(),
        '^/swagger-ui(?:\\.html)?(?=/|\\?|$)': proxy(),
        '^/actuator(?=/|\\?|$)': proxy(),
    };
}

module.exports = {
    createDevProxy,
    normalizeApiPrefix,
};
