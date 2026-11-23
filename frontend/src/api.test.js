import { test, beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { acceptSession, clearSession, request, accessToken, signOut } from './api.js';
const tokens = (access = 'access', expiry = 900) => ({ accessToken: access, refreshToken: 'refresh', expiresIn: expiry });
const reply = (body, status = 200) => new Response(status === 204 ? null : JSON.stringify(body), { status });
beforeEach(() => clearSession());

test('parallel expired requests rotate the refresh token once', async () => {
  acceptSession(tokens('old', 0));
  let refreshes = 0;
  global.fetch = async (url, options) => {
    if (url.endsWith('/auth/refresh')) { refreshes++; await new Promise(resolve => setTimeout(resolve, 10)); return reply(tokens('new')); }
    assert.equal(options.headers.Authorization, 'Bearer new'); return reply({ ok: true });
  };
  const results = await Promise.all([request('/teams'), request('/users/me'), request('/teams/x/dashboard')]);
  assert.equal(refreshes, 1); assert.equal(results.length, 3);
});

test('concurrent unauthorized responses share refresh and retry once', async () => {
  acceptSession(tokens('old'));
  let refreshes = 0;
  global.fetch = async (url, options) => {
    if (url.endsWith('/auth/refresh')) { refreshes++; return reply(tokens('new')); }
    return options.headers.Authorization === 'Bearer old' ? reply({ message: 'Expired' }, 401) : reply({ ok: true });
  };
  await Promise.all([request('/teams'), request('/users/me')]);
  assert.equal(refreshes, 1);
});

test('revoked refresh ends the local session', async () => {
  acceptSession(tokens('old', 0));
  global.fetch = async () => reply({ message: 'Revoked' }, 401);
  await assert.rejects(request('/teams'), error => error.status === 401);
  await assert.rejects(accessToken(), error => error.status === 401);
});

test('logout calls the revocation API and clears local state', async () => {
  acceptSession(tokens());
  global.fetch = async (url, options) => {
    assert.equal(url, '/api/v1/auth/logout'); assert.equal(JSON.parse(options.body).refreshToken, 'refresh');
    return reply(null, 204);
  };
  await signOut(); await assert.rejects(accessToken());
});

test('pending logout ends local access immediately and preserves a later login', async () => {
  acceptSession(tokens('old'));
  let finishLogout;
  global.fetch = () => new Promise(resolve => { finishLogout = resolve; });
  const loggingOut = signOut();
  await assert.rejects(accessToken(), error => error.status === 401);
  acceptSession(tokens('new'));
  finishLogout(reply(null, 204));
  await loggingOut;
  assert.equal(await accessToken(), 'new');
});

test('a delayed refresh cannot resurrect a logged out session', async () => {
  acceptSession(tokens('old', 0));
  let finish;
  global.fetch = () => new Promise(resolve => { finish = resolve; });
  const pending = accessToken(); clearSession(); finish(reply(tokens('new')));
  await assert.rejects(pending); await assert.rejects(accessToken());
});

test('validation errors preserve safe field details and correlation ID', async () => {
  acceptSession(tokens());
  const fieldErrors = [{ field: 'name', message: 'must not be blank' }];
  global.fetch = async () => reply({ code: 'INVALID_REQUEST', message: 'Invalid fields',
    correlationId: 'validation-check', fieldErrors }, 400);
  await assert.rejects(request('/teams/x/services'), error => {
    assert.equal(error.status, 400);
    assert.equal(error.correlationId, 'validation-check');
    assert.deepEqual(error.fieldErrors, fieldErrors);
    return true;
  });
});
