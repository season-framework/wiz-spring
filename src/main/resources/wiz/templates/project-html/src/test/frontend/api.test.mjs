import test from 'node:test';
import assert from 'node:assert/strict';

import { ApiClient, ApiError, joinApiPath, normalizeApiPrefix } from '../../../frontend/lib/api.js';

test('runtime API paths preserve configurable nested prefixes', () => {
  assert.equal(normalizeApiPrefix('/api/v2/'), '/api/v2');
  assert.equal(joinApiPath('/api/v2', '/posts?page=1'), '/api/v2/posts?page=1');
  assert.equal(joinApiPath('api', 'dashboard'), '/api/dashboard');
});

test('API client sends JSON with same-origin session credentials', async () => {
  let request;
  const client = new ApiClient('/api/v2', async (url, options) => {
    request = { url, options };
    return new Response(JSON.stringify({ id: 'post-1' }), {
      status: 200,
      headers: { 'content-type': 'application/json' },
    });
  });
  const result = await client.post('/posts', { title: 'Sample' });
  assert.equal(request.url, '/api/v2/posts');
  assert.equal(request.options.credentials, 'same-origin');
  assert.equal(request.options.body, '{"title":"Sample"}');
  assert.equal(result.id, 'post-1');
});

test('backend validation envelopes become useful API errors', async () => {
  const client = new ApiClient('/api', async () => new Response(JSON.stringify({
    message: 'Validation failed',
    fieldErrors: { title: 'must not be blank' },
  }), { status: 400, headers: { 'content-type': 'application/json' } }));
  await assert.rejects(
    () => client.post('/posts', { title: '' }),
    error => error instanceof ApiError
      && error.status === 400
      && error.fieldErrors.title === 'must not be blank');
});
