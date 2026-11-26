import React, { useEffect, useState } from 'react';
import { request } from './api';

export function SLACountdown({ target }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => { const timer = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(timer); }, []);
  const seconds = Math.ceil((new Date(target.dueAt).getTime() - now) / 1000);
  if (target.completedAt) return <span>Completed {new Date(target.completedAt).toLocaleString()}</span>;
  return <span>{seconds < 0 ? 'Overdue by' : 'Remaining'} {Math.floor(Math.abs(seconds) / 3600)}h {Math.floor(Math.abs(seconds) % 3600 / 60)}m {Math.abs(seconds) % 60}s</span>;
}

function Failure({ error }) {
  return error && <p className="error" role="alert">{error.status === 403 ? 'Your team role does not allow this action.' :
    error.code === 'STALE_VERSION' ? 'This policy changed. Current policies have been reloaded; review before saving again.' : error.message}
    {error.correlationId && <small> Reference: {error.correlationId}</small>}</p>;
}

export function SLAPolicies({ team, token, canManage }) {
  const [policies, setPolicies] = useState([]), [busy, setBusy] = useState(true), [error, setError] = useState(null);
  const load = async () => setPolicies(await request(`/teams/${team}/sla/policies`, token));
  useEffect(() => { let active = true; setBusy(true); setError(null);
    request(`/teams/${team}/sla/policies`, token).then(data => { if (active) setPolicies(data); })
      .catch(e => { if (active) setError(e); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; }; }, [team, token]);
  const save = async (event, policy) => {
    event.preventDefault(); const data = new FormData(event.currentTarget); setBusy(true); setError(null);
    try { await request(`/teams/${team}/sla/policies/${policy.severity}`, token, { method: 'PUT', body: JSON.stringify({
      expectedVersion: policy.version, name: data.get('name'), acknowledgementSeconds: Number(data.get('ack')),
      resolutionSeconds: Number(data.get('resolve')), active: data.get('active') === 'on' }) }); await load(); }
    catch (e) { setError(e); if (e.code === 'STALE_VERSION') await load().catch(setError); }
    finally { setBusy(false); }
  };
  return <section className="card"><h2>Team SLA policies</h2>
    <p>SEV1 is most urgent. Durations are elapsed seconds, every day, without pauses. Changes apply to new incidents. Reopening preserves the original SLA.</p>
    {!canManage && <p>Your role permits reading policies. A team admin or incident manager can publish a new version.</p>}
    <Failure error={error}/>{busy && <p role="status">Loading policies…</p>}
    {!busy && !policies.length && !error && <p>No SLA policies are available.</p>}
    {policies.map(p => <form className="card" key={`${p.id}-${p.version}`} onSubmit={event => save(event, p)}>
      <h3>{p.severity} · version {p.version} · {p.active ? 'Active' : 'Inactive'}</h3>
      <label>Name<input name="name" defaultValue={p.name} required maxLength={100} disabled={!canManage || busy}/></label>
      <label>Acknowledge within (seconds)<input name="ack" type="number" min={1} max={31536000} defaultValue={p.acknowledgementSeconds} required disabled={!canManage || busy}/></label>
      <label>Resolve within (seconds)<input name="resolve" type="number" min={1} max={31536000} defaultValue={p.resolutionSeconds} required disabled={!canManage || busy}/></label>
      <label><input name="active" type="checkbox" defaultChecked={p.active} disabled={!canManage || busy}/> Active for new incidents</label>
      {canManage && <button disabled={busy}>Publish next version</button>}
    </form>)}
  </section>;
}

export function Notifications({ team, token, notifications }) {
  const [history, setHistory] = useState(null), [busy, setBusy] = useState(false), [error, setError] = useState(null);
  useEffect(() => { setHistory(null); setError(null); }, [team]);
  const inspect = async id => { setBusy(true); setError(null);
    try { const [deliveries, attempts] = await Promise.all([
      request(`/teams/${team}/notifications/${id}/deliveries`, token), request(`/teams/${team}/notifications/${id}/attempts`, token)]);
      setHistory({ id, deliveries, attempts }); } catch (e) { setError(e); } finally { setBusy(false); } };
  return <details><summary>Team notifications ({notifications.length}, latest 50)</summary>
    <p>Local in-app delivery. Routing identifies the intended responder or escalation roles; the team inbox is shared with permitted viewers.</p>
    {!notifications.length && <p>No notifications yet. New events arrive asynchronously.</p>}
    {notifications.map(n => <article className="card" key={n.id}><p>{n.message} — {new Date(n.createdAt).toLocaleString()}</p>
      <p>{n.target} · {n.channel || 'Legacy inbox'} · {n.status || 'Legacy record'} · {n.attempts} attempts {n.lastError && `· ${n.lastError}`}</p>
      <button disabled={busy} onClick={() => inspect(n.id)}>Delivery history</button></article>)}
    {busy && <p role="status">Loading delivery history…</p>}<Failure error={error}/>
    {history && <section><h3>Delivery history</h3>
      {history.deliveries.map(d => <p key={d.id}>{d.channel}: {d.status} · delivery {d.id}</p>)}
      {!history.attempts.length && <p>No attempts recorded yet.</p>}
      {history.attempts.map(a => <p key={`${a.delivery_id}-${a.attempt}`}>Attempt {a.attempt}: {a.status} · {new Date(a.started_at).toLocaleString()} {a.error && `· ${a.error}`}</p>)}
    </section>}
  </details>;
}
