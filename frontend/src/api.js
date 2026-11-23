let session = null;
let generation = 0;
let refreshing = null;
const listeners = new Set();
const notify = () => listeners.forEach(fn => fn(session?.accessToken || ''));

export function subscribeSession(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function clearSession() { generation++; session = null; refreshing = null; notify(); }
export function acceptSession(tokens) {
  generation++;
  session = { ...tokens, expiresAt: Date.now() + tokens.expiresIn * 1000 };
  refreshing = null;
  notify();
}
async function raw(path, token, options = {}) {
  const response = await fetch('/api/v1' + path, { ...options, headers: {
    'Content-Type': 'application/json', 'X-Correlation-ID': crypto.randomUUID(),
    ...(token ? { Authorization: 'Bearer ' + token } : {}), ...options.headers } });
  const body = response.status === 204 ? null : await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error(body?.message || 'Request failed'), {
    status: response.status, code: body?.code, fieldErrors: body?.fieldErrors || [],
    correlationId: body?.correlationId || response.headers.get('X-Correlation-ID') });
  return body;
}
export async function refreshSession() {
  if (refreshing) return refreshing;
  if (!session) throw Object.assign(new Error('Sign in again.'), { status: 401 });
  const revision = generation;
  const refreshToken = session.refreshToken;
  const promise = raw('/auth/refresh', '', { method: 'POST', body: JSON.stringify({ refreshToken }) })
    .then(tokens => {
      if (generation !== revision) throw Object.assign(new Error('Session changed.'), { status: 401 });
      session = { ...tokens, expiresAt: Date.now() + tokens.expiresIn * 1000 };
      notify();
      return session.accessToken;
    }).catch(error => { if (generation === revision) clearSession(); throw error; })
    .finally(() => { if (refreshing === promise) refreshing = null; });
  refreshing = promise;
  return promise;
}
export async function accessToken() {
  if (!session) throw Object.assign(new Error('Sign in again.'), { status: 401 });
  return session.expiresAt - Date.now() < 30000 ? refreshSession() : session.accessToken;
}
export async function request(path, unusedToken, options = {}) {
  if (path.startsWith('/auth/')) return raw(path, '', options);
  const revision = generation;
  const token = await accessToken();
  const check = body => {
    if (generation !== revision) throw Object.assign(new Error('Session changed.'), { status: 401 });
    return body;
  };
  check(null);
  try { return check(await raw(path, token, options)); }
  catch (error) {
    if (error.status !== 401 || revision !== generation || !session) throw error;
    const next = session.accessToken !== token ? session.accessToken : await refreshSession();
    try { return check(await raw(path, next, options)); }
    catch (retryError) { if (retryError.status === 401 && revision === generation) clearSession(); throw retryError; }
  }
}
export async function signOut() {
  const refreshToken = session?.refreshToken;
  clearSession();
  if (refreshToken) await raw('/auth/logout', '', { method: 'POST', body: JSON.stringify({ refreshToken }) });
}
