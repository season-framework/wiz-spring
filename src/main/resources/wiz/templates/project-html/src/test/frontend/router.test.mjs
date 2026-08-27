import test from 'node:test';
import assert from 'node:assert/strict';

import { parseRoute } from '../../../frontend/lib/router.js';

test('hash routes distinguish the post list, create form, and UUID detail', () => {
  assert.equal(parseRoute('#/posts').name, 'posts');
  assert.equal(parseRoute('#/posts/new').name, 'post-new');
  assert.deepEqual(parseRoute('#/posts/79f5e3c5-4ed4-48cb-9878-22977bfccfd2'), {
    name: 'post-detail',
    path: '/posts/79f5e3c5-4ed4-48cb-9878-22977bfccfd2',
    params: { id: '79f5e3c5-4ed4-48cb-9878-22977bfccfd2' },
  });
});

test('unknown hashes safely return the dashboard route', () => {
  assert.deepEqual(parseRoute('#/unknown'), { name: 'dashboard', path: '/dashboard', params: {} });
});
