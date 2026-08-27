import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiClient, ApiError, joinApiPath, normalizeApiPrefix } from '../../main/webapp/assets/js/api.js';

test('JSP API URLs combine servlet context, runtime prefix, and resource', () => {
    assert.equal(normalizeApiPrefix('/api/v2/'), '/api/v2');
    assert.equal(joinApiPath('/sample', '/api/v2', '/posts?page=1'), '/sample/api/v2/posts?page=1');
    assert.equal(joinApiPath('', 'api', 'dashboard'), '/api/dashboard');
});

test('JSP API client sends JSON and same-origin session credentials', async () => {
    let request;
    const client = new ApiClient('/sample', '/api', async (url, options) => {
        request = { url, options };
        return new Response(JSON.stringify({ authenticated: true, id: 'user-1' }), {
            status: 200,
            headers: { 'content-type': 'application/json' },
        });
    });
    const session = await client.post('/auth/login', { email: 'admin@example.com', password: 'admin1234' });
    assert.equal(request.url, '/sample/api/auth/login');
    assert.equal(request.options.credentials, 'same-origin');
    assert.match(request.options.body, /admin@example\.com/);
    assert.equal(session.authenticated, true);
});

test('JSP API errors expose field validation messages', async () => {
    const client = new ApiClient('', '/api', async () => new Response(JSON.stringify({
        message: 'Validation failed', fieldErrors: { email: 'must be a well-formed email address' },
    }), { status: 400, headers: { 'content-type': 'application/json' } }));
    await assert.rejects(
        () => client.post('/members', { email: 'invalid' }),
        error => error instanceof ApiError && error.status === 400 && Boolean(error.fieldErrors.email));
});
