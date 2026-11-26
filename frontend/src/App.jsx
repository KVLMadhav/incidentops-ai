import React, { useEffect, useRef, useState } from 'react';
import { SLACountdown, SLAPolicies, Notifications } from './SLAViews';
import { request, acceptSession, subscribeSession, signOut } from './api';

const NEXT = { OPEN: 'ACKNOWLEDGED', ACKNOWLEDGED: 'INVESTIGATING', INVESTIGATING: 'MITIGATED',
  MITIGATED: 'RESOLVED', RESOLVED: 'CLOSED', REOPENED: 'INVESTIGATING' };
function ErrorMessage({ error }) {
  if (!error) return null;
  return <p role="alert" className="error">{error.code === 'INVALID_CREDENTIALS' ? error.message : error.status === 401 ? 'Your session expired. Log in again.' :
    error.status === 403 ? 'Your team role does not allow this action.' :
    error.code === 'STALE_VERSION' ? 'Another person changed this incident. Current data has been reloaded; review it before trying again.' :
    error.code === 'INVALID_TRANSITION' ? error.message : error.message}{error.fieldErrors?.map((field,index) => <small key={index}> {field.field}: {field.message}</small>)}{error.correlationId && <small> Reference: {error.correlationId}</small>}</p>;
}
export default function App() {
  const [token, setToken] = useState('');
  const [register, setRegister] = useState(false);
  const [displayName, setDisplayName] = useState('');
  useEffect(() => { sessionStorage.removeItem('accessToken'); return subscribeSession(setToken); }, []);
  const [login, setLogin] = useState({ email: '', password: '' });
  const [teams, setTeams] = useState([]);
  const [team, setTeam] = useState('');
  const [services, setServices] = useState([]);
  const [members, setMembers] = useState([]);
  const [incidents, setIncidents] = useState([]);
  const [alerts, setAlerts] = useState([]);
  const [selected, setSelected] = useState(null);
  const [timeline, setTimeline] = useState([]);
  const [audit, setAudit] = useState([]);
  const [sla, setSla] = useState([]);
  const [summary, setSummary] = useState(null);
  const [notifications, setNotifications] = useState([]);
  const pendingCommands = useRef(new Map());
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [alertTotalPages, setAlertTotalPages] = useState(0);
  const [filter, setFilter] = useState({ status: '', severity: '', serviceId: '', assigneeUserId: '', from: '', to: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [userId, setUserId] = useState('');
  const currentTeam = useRef(team);
  currentTeam.current = team;
  const listRevision = useRef(0);
  const detailRevision = useRef(0);
  const [form, setForm] = useState({ serviceId: '', title: '', description: '', severity: 'SEV2', source: '', externalAlertId: '', alertId: '', assigneeUserId: '', reason: '', note: '', detailTitle: '', detailDescription: '', detailSeverity: 'SEV2' });
  const [view, setView] = useState('incidents');
  const role = teams.find(t => t.id === team)?.role;
  const canRespond = role && role !== 'VIEWER';
  const canManage = role === 'ADMIN' || role === 'INCIDENT_MANAGER';
  const canWorkSelected = canManage || (role === 'RESPONDER' && selected?.assigneeUserId === userId);
  const setField = (key, value) => setForm(f => ({ ...f, [key]: value }));
  const run = async fn => { setBusy(true); setError(null); try { await fn(); }
    catch (e) { setError(e); if (e.code === 'STALE_VERSION' && selected) await refreshDetail(selected.id).catch(() => {}); }
    finally { setBusy(false); } };
  const refreshDetail = async (id, resetForm = true) => {
    const revision = ++detailRevision.current;
    const requestedTeam = team;
    const [incident, events, entries, targets] = await Promise.all([
      request('/teams/' + team + '/incidents/' + id, token),
      request('/teams/' + team + '/incidents/' + id + '/timeline', token),
      request('/teams/' + team + '/incidents/' + id + '/audit', token),
      request('/teams/' + team + '/incidents/' + id + '/sla', token)]);
    if (revision !== detailRevision.current || requestedTeam !== currentTeam.current) return;
    setSelected(incident); setTimeline(events); setAudit(entries); setSla(targets);
    if (resetForm) setForm(f => ({ ...f, detailTitle: incident.title, detailDescription: incident.description || '', detailSeverity: incident.severity, detailVersion: incident.version }));
  };
  const refreshList = async () => {
    const revision = ++listRevision.current;
    const requestedTeam = team;
    const q = new URLSearchParams({ page: String(page), size: '20' });
    Object.entries(filter).forEach(([key, value]) => { if (value) q.set(key, (key === 'from' || key === 'to') ? new Date(value).toISOString() : value); });
    const alertQuery = new URLSearchParams({ page: String(page), size: '20' });
    ['severity', 'serviceId', 'from', 'to'].forEach(key => { if (filter[key]) alertQuery.set(key,
      (key === 'from' || key === 'to') ? new Date(filter[key]).toISOString() : filter[key]); });
    const [incidentPage, alertPage, teamSummary, inbox] = await Promise.all([
      request('/teams/' + team + '/incidents?' + q, token),
      request('/teams/' + team + '/alerts?' + alertQuery, token),
      request('/teams/' + team + '/summary', token),
      request('/teams/' + team + '/notifications', token)]);
    if (revision !== listRevision.current || requestedTeam !== currentTeam.current) return;
    setSummary(teamSummary); setNotifications(inbox);
    setIncidents(incidentPage.items); setTotalPages(incidentPage.totalPages);
    setAlerts(alertPage.items); setAlertTotalPages(alertPage.totalPages);
  };
  useEffect(() => { if (!token) { setTeam(''); setTeams([]); setSelected(null); return; } run(async () => {
    const [result, me] = await Promise.all([request('/teams', token), request('/users/me', token)]);
    setTeams(result.items); setUserId(me.id);
    setTeam(current => result.items.some(t => t.id === current) ? current : (result.items[0]?.id || ''));
  }); }, [token]);
  useEffect(() => { if (!team || !token) return; setSelected(null); setSla([]); setSummary(null); setNotifications([]); pendingCommands.current.clear(); setIncidents([]); setAlerts([]); setMembers([]); run(async () => {
    const [catalog, teamMembers] = await Promise.all([
      request('/teams/' + team + '/services?page=0&size=100', token),
      request('/teams/' + team + '/members?page=0&size=100', token)]);
    if (team !== currentTeam.current) return;
    setServices(catalog.items); setMembers(teamMembers.items); await refreshList();
  }); }, [team]);
  useEffect(() => { if (team && token) run(refreshList); }, [page, filter.status, filter.severity, filter.serviceId, filter.assigneeUserId, filter.from, filter.to]);
  useEffect(() => {
    if (!team || !token) return;
    const timer = setInterval(() => {
      refreshList().catch(setError);
      if (selected) refreshDetail(selected.id, false).catch(setError);
    }, 15000);
    return () => clearInterval(timer);
  }, [team, token, page, filter, selected?.id]);
  const loginSubmit = e => { e.preventDefault(); run(async () => {
    if (register) await request('/auth/register', '', { method: 'POST', body: JSON.stringify({ ...login, displayName }) });
    const result = await request('/auth/login', '', { method: 'POST', body: JSON.stringify(login) });
    acceptSession(result); setLogin({ email: '', password: '' });
  }); };
  const mutate = (path, payload, method = 'POST') => run(async () => {
    const isCommand = method === 'POST' && /\/(assignment|transitions|notes)$/.test(path);
    const key = JSON.stringify([team, path, payload]);
    if (isCommand) {
      if (!pendingCommands.current.has(key)) pendingCommands.current.set(key, crypto.randomUUID());
      payload = { ...payload, commandId: pendingCommands.current.get(key) };
    }
    await request('/teams/' + team + path, token, { method, body: JSON.stringify(payload) });
    pendingCommands.current.delete(key);
    if (selected) await refreshDetail(selected.id);
    if (path.endsWith('/notes')) setField('note', '');
    await refreshList();
  });
  const createIncident = () => run(async () => {
    const payload = { serviceId: form.serviceId, title: form.title, description: form.description,
      severity: form.severity, alertIds: form.alertId ? [form.alertId] : [] };
    const created = await request('/teams/' + team + '/incidents', token, { method: 'POST', body: JSON.stringify(payload) });
    await refreshList(); await refreshDetail(created.id); setView('incidents');
  });
  const submitAlert = () => run(async () => {
    await request('/teams/' + team + '/alerts', token, { method: 'POST', body: JSON.stringify({
      serviceId: form.serviceId, source: form.source, externalAlertId: form.externalAlertId,
      title: form.title, description: form.description, severity: form.severity,
      occurredAt: new Date().toISOString(), metadata: {} }) });
    await refreshList(); setView('alerts');
  });
  if (!token) return <main className="login"><h1>IncidentOps AI</h1><p>Sign in to your team workspace.</p>
    <form onSubmit={loginSubmit}>{register && <label>Display name<input value={displayName} maxLength={100} onChange={e => setDisplayName(e.target.value)} required/></label>}
      <label>Email<input type="email" value={login.email} onChange={e => setLogin({ ...login, email: e.target.value })} required/></label>
      <label>Password<input type="password" minLength={12} maxLength={72} value={login.password} onChange={e => setLogin({ ...login, password: e.target.value })} required/></label>
      <p>Use 12–72 printable ASCII characters. New accounts need an administrator to add them to a team.</p>
      <button disabled={busy}>{register ? 'Create account' : 'Sign in'}</button></form>
      <button disabled={busy} onClick={() => setRegister(!register)}>{register ? 'Use existing account' : 'Register as viewer'}</button><ErrorMessage error={error}/></main>;
  return <main><header><div><h1>IncidentOps AI</h1><p>Alerts and incident response</p></div>
    <button disabled={busy} onClick={() => run(async () => {
      await signOut(); setTeam(''); setTeams([]); setSelected(null);
    })}>Log out</button></header>
    <ErrorMessage error={error}/>{busy && <p role="status">Loading…</p>}
    {!teams.length && !busy && <p>No teams are available for this account.</p>}
    {!!teams.length && <><nav><label>Team <select value={team} onChange={e => { setTeam(e.target.value); setPage(0); }}>
      {teams.map(t => <option key={t.id} value={t.id}>{t.name} ({t.role})</option>)}</select></label>
      <button onClick={() => { setView('incidents'); setSelected(null); setPage(0); }}>Incidents</button>
      <button onClick={() => { setView('alerts'); setSelected(null); setPage(0); }}>Alerts</button>
      <button onClick={() => { setView('services'); setSelected(null); }}>Services</button>
      <button onClick={() => { setView('policies'); setSelected(null); }}>SLA policies</button>
      <button disabled={busy} onClick={() => run(async () => { await refreshList(); if (selected) await refreshDetail(selected.id); })}>Reload</button></nav>
      {summary && <p role="status">{summary.active} active / {summary.total} total incidents · {summary.breachedTargets} overdue or late targets · Summary as of {new Date(summary.generatedAt).toLocaleTimeString()} (cache up to 10 seconds)</p>}
      <Notifications key={team} team={team} token={token} notifications={notifications}/>
      {view === 'policies' ? <SLAPolicies key={team} team={team} token={token} canManage={canManage}/> : view === 'services' ? <section className="card"><h2>Service catalog</h2>
        {!services.length && !busy && <p>No services are available in this team.</p>}
        <p>Showing up to 100 services, sorted by name.</p>
        {services.map(service => <article key={service.id}><h3>{service.name}</h3>
          <p>{service.description || 'No description provided.'}</p><p>{service.active ? 'Active' : 'Inactive'}</p>
        </article>)}
      </section> : <>
      <section className="filters">{view === 'incidents' && <><label>Status<select value={filter.status} onChange={e => { setFilter({ ...filter, status: e.target.value }); setPage(0); }}>
        <option value="">All</option>{['OPEN','ACKNOWLEDGED','INVESTIGATING','MITIGATED','RESOLVED','CLOSED','REOPENED'].map(x => <option key={x}>{x}</option>)}</select></label></>}
        <label>Severity<select value={filter.severity} onChange={e => { setFilter({ ...filter, severity: e.target.value }); setPage(0); }}>
          <option value="">All</option>{['SEV1','SEV2','SEV3','SEV4'].map(x => <option key={x}>{x}</option>)}</select></label>
        <label>Service<select value={filter.serviceId} onChange={e => { setFilter({ ...filter, serviceId: e.target.value }); setPage(0); }}>
          <option value="">All</option>{services.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
        {view === 'incidents' && <label>Assignee ID<input value={filter.assigneeUserId} onChange={e => { setFilter({ ...filter, assigneeUserId: e.target.value }); setPage(0); }}/></label>}
        <label>From<input type="datetime-local" value={filter.from} onChange={e => { setFilter({ ...filter, from: e.target.value }); setPage(0); }}/></label>
        <label>To<input type="datetime-local" value={filter.to} onChange={e => { setFilter({ ...filter, to: e.target.value }); setPage(0); }}/></label></section>
      <div className="columns"><section><h2>{view === 'alerts' ? 'Alerts' : 'Incidents'}</h2>
        {view === 'incidents' ? <>{!incidents.length && !busy && <p>No incidents match these filters.</p>}
          {incidents.map(i => <button className="row" key={i.id} onClick={() => run(() => refreshDetail(i.id))}>
            <strong>{i.incidentNumber} · {i.title}</strong><span>{i.severity} · {i.status} · version {i.version}</span></button>)}
          <div className="pagination"><button disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {Math.max(totalPages, 1)}</span>
            <button disabled={page + 1 >= totalPages || busy} onClick={() => setPage(page + 1)}>Next</button></div></> :
          <>{!alerts.length && !busy && <p>No alerts match these filters.</p>}{alerts.map(a => <div className="row" key={a.id}>
            <strong>{a.title}</strong><span>{a.severity} · {a.source} · {a.externalAlertId}</span><small>ID: {a.id}</small></div>)}
            <div className="pagination"><button disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>Previous</button>
              <span>Page {page + 1} of {Math.max(alertTotalPages, 1)}</span>
              <button disabled={page + 1 >= alertTotalPages || busy} onClick={() => setPage(page + 1)}>Next</button></div></>}
        {canRespond && <div className="card"><h3>{view === 'alerts' ? 'Submit alert' : 'Create incident'}</h3>
          <label>Service<select value={form.serviceId} onChange={e => setField('serviceId', e.target.value)}>
            <option value="">Choose service</option>{services.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
          <label>Title<input value={form.title} onChange={e => setField('title', e.target.value)}/></label>
          <label>Description<textarea value={form.description} onChange={e => setField('description', e.target.value)}/></label>
          <label>Severity<select value={form.severity} onChange={e => setField('severity', e.target.value)}>
            {['SEV1','SEV2','SEV3','SEV4'].map(x => <option key={x}>{x}</option>)}</select></label>
          {view === 'alerts' ? <><label>Source<input value={form.source} onChange={e => setField('source', e.target.value)}/></label>
            <label>External alert ID<input value={form.externalAlertId} onChange={e => setField('externalAlertId', e.target.value)}/></label>
            <button disabled={busy} onClick={submitAlert}>Submit alert</button></> :
            <><label>Related alert<select value={form.alertId} onChange={e => setField('alertId', e.target.value)}>
              <option value="">None</option>{alerts.filter(a => a.serviceId === form.serviceId).map(a => <option key={a.id} value={a.id}>{a.title}</option>)}</select></label>
              <button disabled={busy} onClick={createIncident}>Create incident</button></>}</div>}
      </section><section>{selected ? <><h2>{selected.incidentNumber}</h2><p>{selected.title}</p><p>{selected.severity} · {selected.status} · version {selected.version}</p>
        <p>{selected.description}</p><p>Assignee: {selected.assigneeUserId || 'Unassigned'}</p>
        {!canWorkSelected && role === 'RESPONDER' && <p>A manager must assign this incident to you before you can respond.</p>}
        {canWorkSelected && <><div className="card"><h3>Response</h3>
          <label>Reason<input value={form.reason} onChange={e => setField('reason', e.target.value)}/></label>
          {NEXT[selected.status] && <button disabled={busy} onClick={() => mutate('/incidents/' + selected.id + '/transitions',
            { version: selected.version, status: NEXT[selected.status], reason: form.reason })}>Move to {NEXT[selected.status]}</button>}
          {selected.status === 'RESOLVED' && <button disabled={busy} onClick={() => mutate('/incidents/' + selected.id + '/transitions',
            { version: selected.version, status: 'REOPENED', reason: form.reason })}>Reopen</button>}
          {canManage && <><label>Assignee<select value={form.assigneeUserId} onChange={e => setField('assigneeUserId', e.target.value)}>
            <option value="">Choose team member</option>{members.filter(m => m.role !== 'VIEWER').map(m =>
              <option key={m.userId} value={m.userId}>{m.displayName} ({m.role})</option>)}</select></label>
            <button disabled={busy} onClick={() => mutate('/incidents/' + selected.id + '/assignment',
              { version: selected.version, assigneeUserId: form.assigneeUserId, reason: form.reason })}>Assign</button>
            <label>Incident title<input value={form.detailTitle} onChange={e => setField('detailTitle', e.target.value)}/></label>
            <label>Incident description<textarea value={form.detailDescription} onChange={e => setField('detailDescription', e.target.value)}/></label>
            <label>Incident severity<select value={form.detailSeverity} onChange={e => setField('detailSeverity', e.target.value)}>
              {['SEV1','SEV2','SEV3','SEV4'].map(x => <option key={x}>{x}</option>)}</select></label>
            <button disabled={busy} onClick={() => mutate('/incidents/' + selected.id + '/details',
              { version: form.detailVersion, title: form.detailTitle, description: form.detailDescription, severity: form.detailSeverity }, 'PUT')}>Save details</button></>}</div>
          <div className="card"><h3>Add note</h3><textarea value={form.note} onChange={e => setField('note', e.target.value)}/>
            <button disabled={busy} onClick={() => mutate('/incidents/' + selected.id + '/notes', { body: form.note })}>Add note</button></div>
          <div className="card"><h3>Link alert</h3><select value={form.alertId} onChange={e => setField('alertId', e.target.value)}>
            <option value="">Choose alert</option>{alerts.filter(a => a.serviceId === selected.serviceId).map(a => <option key={a.id} value={a.id}>{a.title}</option>)}</select>
            <button disabled={busy} onClick={() => mutate('/incidents/' + selected.id + '/alerts', { alertId: form.alertId })}>Link</button></div></>}
        <h3>SLA targets</h3><p>Deadlines use elapsed time. Reopening keeps the original SLA. Updates refresh every 15 seconds.</p>
        {sla.map(target => <div className="event" key={target.target}><strong>{target.target}</strong> · due {new Date(target.dueAt).toLocaleString()} · <SLACountdown target={target}/>
          {target.breachedAt && <p className="error">Breach detected {new Date(target.breachedAt).toLocaleString()}</p>}</div>)}
        <h3>Timeline</h3>{!timeline.length && <p>No timeline entries.</p>}
        {timeline.map(e => <div className="event" key={e.id}><strong>{e.type}</strong> · {new Date(e.at).toLocaleString()}<p>{e.detail}</p></div>)}
        <h3>Audit entries</h3>{audit.map(e => <div className="event" key={e.id}>{e.action} · {new Date(e.createdAt).toLocaleString()}</div>)}
      </> : <div className="empty"><h2>Incident detail</h2><p>Select an incident to read its timeline and take action.</p></div>}</section></div></>}</>}
  </main>;
}
