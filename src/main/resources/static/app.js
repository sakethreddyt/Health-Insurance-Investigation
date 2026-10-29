'use strict';
/* Health claim Investigation — Case Desk frontend. No inline scripts/handlers (strict CSP): all clicks go through data-act. */

const $ = (s, r) => (r || document).querySelector(s);
const pageLoaderImage = $('#page-loader-image');
const showPageLoaderFallback = () => { $('#page-loader-fallback').hidden = false; };
pageLoaderImage.addEventListener('error', showPageLoaderFallback);
if (pageLoaderImage.complete && pageLoaderImage.naturalWidth === 0) showPageLoaderFallback();
let pageLoaderTimer = null;
const setPageLoading = loading => {
  const loader = $('#page-loader');
  clearTimeout(pageLoaderTimer);
  if (loading) {
    loader.hidden = false;
    loader.classList.remove('is-leaving');
    return;
  }
  loader.classList.add('is-leaving');
  pageLoaderTimer = setTimeout(() => {
    loader.hidden = true;
    loader.classList.remove('is-leaving');
  }, 500);
};
const esc = s => (s == null ? '' : String(s)).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const enc = encodeURIComponent;
const fmtDate = d => { if (!d) return ''; const p = String(d).slice(0, 10).split('-'); return p.length === 3 ? p[2] + '-' + p[1] + '-' + p[0] : d; };
const today = () => new Date().toISOString().slice(0, 10);
const ROLE_LABEL = { MAIN_ADMIN: 'Main Admin', STATE_ADMIN: 'State Admin', OFFICE_ASSISTANT: 'Office Assistant', INVESTIGATOR: 'Investigator' };

let ME = null, META = null, TAB = null;
let INDIA_LOCATIONS = [];
let OPTS = { insurance: [], district: [], investigator: [], month: [] };
const F = { view: 'all', q: '', insurance: '', district: '', investigator: '', month: '', sortBy: '' };
let UFILTER = 'all';
let CUR = null;            // case being worked on by an investigator
let LETTER_CASES = [];

const isStaff = () => ME && ME.role !== 'INVESTIGATOR';
const isAdmin = () => ME && (ME.role === 'MAIN_ADMIN' || ME.role === 'STATE_ADMIN');

/* ---------------------------------------------------------------- api / ui helpers */
function cookie(n) { const m = document.cookie.match(new RegExp('(?:^|; )' + n + '=([^;]*)')); return m ? decodeURIComponent(m[1]) : null; }

async function api(path, method, body, isForm) {
  const opts = { method: method || 'GET', credentials: 'same-origin', headers: {} };
  const t = cookie('XSRF-TOKEN'); if (t) opts.headers['X-XSRF-TOKEN'] = t;
  if (isForm) opts.body = body;
  else if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
  const r = await fetch(path, opts);
  const ct = r.headers.get('content-type') || '';
  const data = ct.includes('json') ? await r.json().catch(() => null) : null;
  if (r.status === 401 && path !== '/api/auth/login') { showLogin((data && data.message) || 'Please sign in.'); throw new Error('Signed out'); }
  if (r.status === 403 && data && data.message === 'PASSWORD_CHANGE_REQUIRED') { openPw(true); throw new Error('Please set a new password first.'); }
  if (!r.ok) throw new Error((data && data.message) || ('Request failed (' + r.status + ')'));
  return data;
}

function toast(msg, isErr) {
  const d = document.createElement('div'); d.className = 'toast' + (isErr ? ' err' : ''); d.textContent = msg;
  $('#toastHost').appendChild(d); setTimeout(() => d.remove(), isErr ? 6000 : 3000);
}
const fail = e => { if (e && e.message !== 'Signed out') toast(e.message, true); };

function openModal(title, body, foot, wide) {
  const d = document.createElement('div'); d.className = 'modal'; d.style.display = 'flex';
  d.innerHTML = '<div class="modal-box' + (wide ? ' wide' : '') + '"><div class="modal-head"><h3>' + esc(title) + '</h3>'
    + '<button class="btn sm ghost" data-act="closeModal">Close</button></div>'
    + '<div class="modal-body">' + body + '</div>' + (foot ? '<div class="modal-foot">' + foot + '</div>' : '') + '</div>';
  $('#modalHost').appendChild(d); return d;
}
function dialog(title, body, okLabel, onOk, wide) {
  const m = openModal(title, body + '<div class="err" data-role="err"></div>',
    '<button class="btn ghost" data-act="closeModal">Cancel</button><button class="btn brick" data-role="ok">' + esc(okLabel) + '</button>', wide);
  const ok = $('[data-role=ok]', m);
  ok.addEventListener('click', async () => {
    ok.disabled = true;
    try { await onOk(m); m.remove(); } catch (e) { $('[data-role=err]', m).textContent = e.message; ok.disabled = false; }
  });
  return m;
}
const setView = html => { $('#view').innerHTML = html; };
const kv = (k, v) => '<span>' + esc(k) + '</span><span>' + (v ? esc(v) : '—') + '</span>';
const field = (id, label, val, type, extra) => '<div class="field"><label>' + esc(label) + '</label>'
  + (type === 'password'
    ? '<div class="password-input"><input id="' + id + '" type="password" value="' + esc(val) + '" ' + (extra || '') + '><button type="button" class="password-toggle" data-act="togglePassword" aria-label="Show password" aria-pressed="false"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg></button></div></div>'
    : '<input id="' + id + '" type="' + (type || 'text') + '" value="' + esc(val) + '" ' + (extra || '') + '></div>');
function locationStates() { return INDIA_LOCATIONS.map(x => x.state).sort((a, b) => a.localeCompare(b)); }
function locationDistricts(state) {
  const entry = INDIA_LOCATIONS.find(x => x.state === state);
  return entry ? entry.districts : [];
}
function stateOptions(selected) {
  const states = locationStates();
  return '<option value="">-- choose state --</option>'
    + (selected && !states.includes(selected) ? '<option value="' + esc(selected) + '" selected>Existing: ' + esc(selected) + '</option>' : '')
    + states.map(s => '<option value="' + esc(s) + '"' + (s === selected ? ' selected' : '') + '>' + esc(s) + '</option>').join('');
}
function districtOptions(state, selected) {
  const districts = locationDistricts(state);
  return '<option value="">-- choose district --</option>'
    + (selected && !districts.includes(selected) ? '<option value="' + esc(selected) + '" selected>Existing: ' + esc(selected) + '</option>' : '')
    + districts.map(d => '<option value="' + esc(d) + '"' + (d === selected ? ' selected' : '') + '>' + esc(d) + '</option>').join('');
}
function stateField(id, label, selected, districtId) {
  return '<div class="field"><label for="' + id + '">' + esc(label) + '</label><select id="' + id + '" required data-change="locationChanged" data-arg="' + districtId + '">'
    + stateOptions(selected) + '</select></div>';
}
function districtField(id, label, selected, state, refreshArg) {
  return '<div class="field"><label for="' + id + '">' + esc(label) + '</label><select id="' + id + '" required'
    + (refreshArg ? ' data-change="partsRefresh" data-arg="' + refreshArg + '"' : '')
    + (!state && !selected ? ' disabled' : '') + '>'
    + districtOptions(state, selected) + '</select></div>';
}
function locationFields(stateId, stateLabel, state, districtId, districtLabel, district, refreshArg) {
  return stateField(stateId, stateLabel, state, districtId)
    + districtField(districtId, districtLabel, district, state, refreshArg);
}
function locationRow(stateId, stateLabel, state, districtId, districtLabel, district, refreshArg) {
  return '<div class="row">' + locationFields(stateId, stateLabel, state, districtId, districtLabel, district, refreshArg) + '</div>';
}
function ensureLocation(stateId, districtId, label) {
  if (!$('#' + stateId).value) throw new Error('Choose a state for ' + label + '.');
  if (!$('#' + districtId).value) throw new Error('Choose a district for ' + label + '.');
}

/* ---------------------------------------------------------------- event delegation */
const A = {};
A.locationChanged = (districtId, state) => {
 const district = $('#' + districtId);
  if (!district) return;
  district.innerHTML = districtOptions(state.value, '');
  district.disabled = !state.value;
 if (district.dataset.change && A[district.dataset.change]) A[district.dataset.change](district.dataset.arg, district);
};
document.addEventListener('click', e => {
  const el = e.target.closest('[data-act]'); if (!el) return;
  const fn = A[el.dataset.act]; if (!fn) return;
  e.preventDefault();
  Promise.resolve(fn(el.dataset.arg, el)).catch(fail);
});
document.addEventListener('change', e => { const el = e.target.closest('[data-change]'); if (el && A[el.dataset.change]) Promise.resolve(A[el.dataset.change](el.dataset.arg, el)).catch(fail); });
let inputTimer = null;
document.addEventListener('input', e => {
  const el = e.target.closest('[data-input]'); if (!el || !A[el.dataset.input]) return;
  clearTimeout(inputTimer); inputTimer = setTimeout(() => Promise.resolve(A[el.dataset.input](el.dataset.arg, el)).catch(fail), 250);
});
document.addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.id === 'loginPass') A.login(); });

/* ---- profile dropdown (menu + change password + sign out) ---- */
function setMenu(open) {
  document.body.classList.toggle('menu-open', open);
  const b = document.getElementById('menuBtn'); if (b) b.setAttribute('aria-expanded', open ? 'true' : 'false');
}
A.toggleMenu = () => setMenu(!document.body.classList.contains('menu-open'));
function syncProfileHeader(profile) {
  const name = (profile.fullName || profile.username || '?').trim();
  $('#whoName').textContent = profile.fullName || profile.username;
  $('#whoRole').textContent = (ROLE_LABEL[profile.role] || profile.role) + (profile.state ? ' · ' + profile.state : '');
  $('#menuInitial').textContent = name.charAt(0).toUpperCase();
  const whoPhoto = $('#whoPhoto'), menuPhoto = $('#menuPhoto'), mark = $('#brandMark');
  if (profile.hasPhoto) {
    const src = '/api/users/' + enc(profile.username) + '/photo?v=' + Date.now();
    whoPhoto.src = src; whoPhoto.classList.remove('hide');
    menuPhoto.src = src; menuPhoto.classList.remove('hide');
    mark.innerHTML = '<img alt="" src="' + src + '">';
  } else {
    whoPhoto.classList.add('hide'); menuPhoto.classList.add('hide');
    mark.textContent = 'H';
  }
}
document.addEventListener('click', e => {
  if (!document.body.classList.contains('menu-open') || e.target.closest('.menu-btn')) return;
  if (!e.target.closest('.menu-panel') || e.target.closest('.menu-panel button')) setMenu(false);   // outside click, or a menu item was chosen
});
document.addEventListener('keydown', e => { if (e.key === 'Escape') setMenu(false); });
A.closeModal = (_, el) => el.closest('.modal').remove();
A.togglePassword = (_, el) => {
  const input = el.closest('.password-input').querySelector('input');
  const show = input.type === 'password';
  input.type = show ? 'text' : 'password';
  el.setAttribute('aria-label', (show ? 'Hide' : 'Show') + ' password');
  el.setAttribute('aria-pressed', show ? 'true' : 'false');
  el.innerHTML = show
    ? '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 3 18 18M10.6 10.6a2 2 0 0 0 2.8 2.8"/><path d="M9.9 5.2A10.8 10.8 0 0 1 12 5c6.4 0 10 7 10 7a15.7 15.7 0 0 1-3.1 3.8M6.2 6.2C3.5 8 2 12 2 12s3.6 7 10 7c1.2 0 2.3-.2 3.3-.6"/></svg>'
    : '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/></svg>';
};
A.myProfile = async () => { setMenu(false); await vMyProfile(); };

/* ---------------------------------------------------------------- login / session */
function showLogin(msg) {
  ME = null; if (POLL) { clearInterval(POLL); POLL = null; } LAST_TR = -1; $('#alertBar').innerHTML = '';
  $('#app-shell').style.display = 'none'; $('#screen-login').style.display = 'flex';
  $('#modalHost').innerHTML = ''; $('#loginErr').textContent = msg || ''; $('#loginPass').value = '';
}
A.login = async () => {
  $('#loginErr').textContent = '';
  setPageLoading(true);
  try {
    ME = await api('/api/auth/login', 'POST', { username: $('#loginUser').value.trim(), password: $('#loginPass').value });
    $('#loginPass').value = ''; await enterApp();
  } catch (e) { $('#loginErr').textContent = e.message; }
  finally { setPageLoading(false); }
};
A.logout = async () => { try { await api('/api/auth/logout', 'POST'); } catch (e) { /* ignore */ } showLogin(''); };

function tabsFor() {
  const T = {
    newcase: 'New Case', cases: 'Cases List', investigators: 'Investigators', editreq: 'Request for Edit',
    transfers: 'Part Transfers', createinv: 'Create Investigator', assistants: 'Office Assistants', stateadmins: 'State Admins', audit: 'Audit Log',
    icases: 'Case List', withdrawal: 'Withdrawal Letter', fraud: 'Fraud Letter', empform: 'Employee Info Form', patq: 'Patient Questionnaire'
  };
  const map = {
    MAIN_ADMIN: ['cases', 'newcase', 'investigators', 'editreq', 'transfers', 'createinv', 'assistants', 'stateadmins', 'audit'],
    STATE_ADMIN: ['cases', 'newcase', 'investigators', 'editreq', 'transfers', 'createinv', 'assistants'],
    OFFICE_ASSISTANT: ['cases', 'newcase', 'investigators', 'editreq', 'transfers', 'createinv'],
    INVESTIGATOR: ['icases', 'withdrawal', 'fraud', 'empform', 'patq']
  };
  return map[ME.role].map(k => [k, T[k]]);
}

async function enterApp() {
  setPageLoading(true);
  try {
    $('#screen-login').style.display = 'none'; $('#app-shell').style.display = 'block';
    $('#menuName').textContent = ME.fullName || ME.username;
    setMenu(false);
    syncProfileHeader(ME);
    if (ME.mustChangePassword) { openPw(true); return; }   // nothing else loads until the password is changed
    META = await api('/api/meta');
    await showTab(tabsFor()[0][0]);
    refreshBadges();
    if (!POLL) POLL = setInterval(refreshBadges, 60000);   // keeps notification badges fresh
  } finally { setPageLoading(false); }
}

let POLL = null, LAST_TR = -1;
const BADGES = { cases: 0, icases: 0, editreq: 0, investigators: 0, transfers: 0 };
async function refreshBadges() {
  try {
    const unread = await api('/api/cases/new-count');
    BADGES.cases = BADGES.icases = unread.count;
    if (isStaff()) {
      const [er, inv, tr] = await Promise.all([api('/api/cases?view=editreq'), api('/api/users?role=INVESTIGATOR'), api('/api/cases/part-transfers')]);
      BADGES.editreq = er.length;
      BADGES.investigators = isAdmin() ? inv.filter(u => u.approvalStatus === 'PENDING_APPROVAL').length : inv.filter(u => u.approvalStatus === 'CHANGES_REQUESTED').length;
      setTransferAlert(tr.filter(t => t.canAct).length);
    }
    renderTabbar();
  } catch (e) { /* non-critical */ }
}
function setTransferAlert(n) {
  BADGES.transfers = n;
  if (LAST_TR >= 0 && n > LAST_TR) toast('New part-transfer request received.');
  LAST_TR = n;
  $('#alertBar').innerHTML = n ? '<div class="alert-bar"><span>\u26A0 <b>' + n + ' part-transfer request' + (n > 1 ? 's' : '') + '</b> waiting for your decision.</span>'
    + '<button class="btn sm brick" data-act="tab" data-arg="transfers">Review now</button></div>' : '';
}

function renderTabbar() {
  $('#tabbar').innerHTML = tabsFor().map(([k, l]) => '<button class="tabbtn' + (k === TAB ? ' active' : '') + '" data-act="tab" data-arg="' + k + '">' + esc(l)
    + (BADGES[k] ? '<span class="pill">' + BADGES[k] + '</span>' : '') + '</button>').join('');
}
A.tab = k => showTab(k);
async function showTab(k) {
  TAB = k; renderTabbar(); setPageLoading(true); setView('<div class="empty">Loading…</div>');
  const V = { newcase: vNewCase, cases: vCases, icases: vCases, investigators: vInvestigators, editreq: vEditReq, transfers: vTransfers, createinv: vCreateInv,
    assistants: () => vSimpleUsers('OFFICE_ASSISTANT'), stateadmins: () => vSimpleUsers('STATE_ADMIN'), audit: vAudit,
    withdrawal: vWithdrawal, fraud: vFraud, empform: () => vDoc('Employee Case Information Form', '/docs/Employee_Case_Information_Form.pdf'),
    patq: () => vDoc("Insured's Verification / Patient Questionnaire", '/docs/Patient_Questionnaire.pdf') };
  try { await V[k](); } catch (e) { fail(e); if (e.message !== 'Signed out') setView('<div class="empty">Could not load this page.</div>'); }
  finally { setPageLoading(false); }
}

async function vMyProfile() {
  const x = await api('/api/auth/profile');
  const photo = x.hasPhoto
    ? '<img class="profile-photo" alt="Profile photo" src="/api/users/' + enc(x.username) + '/photo?v=' + Date.now() + '">'
    : '<div class="profile-photo initials" aria-label="No profile photo">' + esc((x.fullName || x.username || '?').charAt(0).toUpperCase()) + '</div>';
  const createdAt = x.createdAt ? new Date(x.createdAt).toLocaleString() : '—';
  setView('<div class="toolbar"><h2>My profile</h2><button class="btn brick" data-act="editMyProfile">Edit profile</button></div>'
    + '<div class="card"><div class="profile-summary">' + photo + '<div><h3>' + esc(x.fullName || x.username) + '</h3><div class="muted">' + esc(ROLE_LABEL[x.role] || x.role) + '</div></div></div>'
    + '<div class="kv">' + kv('Username', x.username) + kv('Role', ROLE_LABEL[x.role] || x.role) + kv('Mobile', x.mobile)
    + kv('State', x.state) + kv('District', x.district) + kv('Created by', x.createdBy) + kv('Created on', createdAt)
    + (x.role === 'INVESTIGATOR' ? kv('Aadhaar no.', x.aadhar) + kv('PAN', x.pan) + kv('Driving licence', x.dl)
      + kv('Bank & branch', x.bankName) + kv('Account no.', x.bankAcc) + kv('IFSC', x.ifsc) : '')
    + '</div></div>');
}
function myProfileFormHtml(x) {
  return '<div class="profile-edit-photo">' + (x.hasPhoto ? '<img class="avatar lg" alt="Current profile photo" src="/api/users/' + enc(x.username) + '/photo?v=' + Date.now() + '">' : '')
    + '<div class="field"><label for="profilePhoto">Profile photo (JPG or PNG, max 2 MB)</label><input id="profilePhoto" type="file" accept="image/jpeg,image/png"><div class="hint">Leave empty to keep the current photo.</div></div></div>'
    + '<div class="row">' + field('profileName', 'Full name', x.fullName, 'text', 'maxlength="120"')
    + field('profileMobile', 'Mobile number', x.mobile, 'text', 'maxlength="15"') + '</div>'
    + locationRow('profileState', 'State', x.state, 'profileDistrict', 'District', x.district)
    + (x.role === 'INVESTIGATOR' ? '<div class="section-title">Identity & bank details</div><div class="row">'
      + field('profileAadhar', 'Aadhaar number', x.aadhar, 'text', 'maxlength="20"')
      + field('profilePan', 'PAN number', x.pan, 'text', 'maxlength="12"')
      + field('profileDl', 'Driving licence number', x.dl, 'text', 'maxlength="30"') + '</div><div class="row">'
      + field('profileBank', 'Bank name & branch', x.bankName, 'text', 'maxlength="120"')
      + field('profileAccount', 'Bank account number', x.bankAcc, 'text', 'maxlength="30"')
      + field('profileIfsc', 'IFSC code', x.ifsc, 'text', 'maxlength="15"') + '</div>' : '');
}
A.editMyProfile = async () => {
  const x = await api('/api/auth/profile');
  dialog('Edit my profile', myProfileFormHtml(x), 'Save profile', async () => {
    const photo = $('#profilePhoto').files[0];
    if (photo && photo.size > 2 * 1024 * 1024) throw new Error('Photo is larger than 2 MB.');
    const value = id => { const el = $('#' + id); return el ? el.value : ''; };
    const data = {
      fullName: value('profileName'), mobile: value('profileMobile'), state: value('profileState'), district: value('profileDistrict'),
      aadhar: value('profileAadhar'), pan: value('profilePan'), dl: value('profileDl'),
      bankName: value('profileBank'), bankAcc: value('profileAccount'), ifsc: value('profileIfsc')
    };
    ensureLocation('profileState', 'profileDistrict', 'your profile');
    const form = new FormData();
    form.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }));
    if (photo) form.append('photo', photo);
    const updated = await api('/api/auth/profile', 'PUT', form, true);
    ME = Object.assign(ME, updated);
    syncProfileHeader(ME);
    $('#menuName').textContent = ME.fullName || ME.username;
    toast('Profile updated.');
    await vMyProfile();
  });
};

/* password change (also the forced first-login change) */
function openPw(forced) {
  const m = dialog(forced ? 'Set a new password to continue' : 'Change password',
    (forced ? '<div class="alert amber">For security you must choose your own password before using the system.</div>' : '')
    + field('pwCur', 'Current password', '', 'password', 'autocomplete="current-password"')
    + field('pwNew', 'New password', '', 'password', 'autocomplete="new-password"')
    + '<div class="hint">At least 8 characters, with a letter and a digit.</div>',
    'Save password', async () => {
      await api('/api/auth/change-password', 'POST', { currentPassword: $('#pwCur').value, newPassword: $('#pwNew').value });
      ME.mustChangePassword = false; toast('Password changed.');
      if (forced) await enterApp();
    });
  if (forced) $('[data-act=closeModal]', m).classList.add('hide');
}
A.openPw = () => openPw(false);

/* ---------------------------------------------------------------- new case (staff) */
const FS = {};   // per form: { invs, sel: {PARTTYPE: username}, cur: {PARTTYPE: name} }
function partKeysFor(t, bill) { if (!t) return []; return t.needsBillSource ? [bill === 'MEDICAL_SHOP' ? 'MEDICAL_SHOP' : 'HOSPITAL'] : t.partKeys; }
function formKeys(p) {
  const t = META.claimTypes.find(x => x.key === $('#' + p + 'Type').value);
  const bs = document.querySelector('input[name="' + p + 'bs"]:checked');
  return partKeysFor(t, bs ? bs.value : null);
}
function captureSel(p) {
  const st = FS[p]; if (!st) return;
  document.querySelectorAll('#' + p + 'Parts select[data-part]').forEach(s => { st.sel[s.dataset.part] = s.value; });
}
function assignmentOnlyPartsHtml(p, c) {
  const st = FS[p];
  const parts = (c.parts || []).filter(part => part.visible !== false);
  st.assignmentKeys = parts.map(part => part.type);
  if (!parts.length) return '<div class="empty">No case parts are assigned to your State Admin.</div>';
  const norm = value => (value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
  const stateFor = type => type === 'PATIENT' ? c.patientState
    : type === 'POLICE_STATION' ? c.policeStationState : c.hospitalState;
  return parts.map(part => {
    const state = stateFor(part.type) || '';
    const investigators = st.invs.filter(inv => norm(state) && norm(inv.state) === norm(state));
    const current = st.sel[part.type];
    const currentInvestigator = st.invs.find(inv => inv.username === current);
    if (current && currentInvestigator && !investigators.some(inv => inv.username === current))
      investigators.unshift(currentInvestigator);
    const options = investigators.map(inv => '<option value="' + esc(inv.username) + '"'
      + (current === inv.username ? ' selected' : '') + '>' + esc(inv.fullName || inv.username) + ' (' + esc(inv.username) + ')'
      + (inv.district || inv.state ? ' \u2014 ' + esc([inv.district, inv.state].filter(Boolean).join(', ')) : '')
      + (current === inv.username && norm(inv.state) !== norm(state) ? ' (currently assigned)' : '') + '</option>').join('');
    return '<div class="field"><label>' + esc(part.label || META.partDefs[part.type].label)
      + ' <span class="muted">\u2014 ' + esc(state || 'state not specified') + '</span></label>'
      + '<select data-part="' + esc(part.type) + '"><option value="">-- choose investigator from ' + esc(state || 'this state') + ' --</option>'
      + (options || '<option value="" disabled>No approved investigator found for ' + esc(state || 'this state') + '</option>')
      + '</select></div>';
  }).join('');
}
function caseFormHtml(p, c, invs, noParts, stateAdmins, assignmentOnly) {
  c = c || {};
  FS[p] = { invs, stateAdmins: stateAdmins || [], sel: {}, cur: {}, adminSel: {},
    locked: (c.parts || []).filter(x => x.visible === false).map(x => x.type),
    caseLocations: {
      patientState: c.patientState || '',
      hospitalState: c.hospitalState || '',
      policeStationState: c.policeStationState || ''
    } };
  (c.parts || []).forEach(x => {
    FS[p].sel[x.type] = x.investigatorUsername;
    FS[p].cur[x.type] = x.investigatorName;
    FS[p].adminSel[x.type] = x.stateAdminUsername || c.ownerAdmin || '';
  });
  const types = META.claimTypes.map(t => '<option value="' + t.key + '"' + (c.claimType === t.key ? ' selected' : '') + '>' + esc(t.label) + '</option>').join('');
  if (assignmentOnly) {
    return caseSummary(c) + '<div class="section-title">Assign investigators for your State Admin team</div>'
      + '<div id="' + p + 'Parts">' + assignmentOnlyPartsHtml(p, c) + '</div>';
  }
  return '<datalist id="insurers">' + META.insurers.map(i => '<option value="' + esc(i) + '">').join('') + '</datalist>'
    + (ME.role !== 'MAIN_ADMIN' && FS[p].locked.length
      ? '<div class="alert amber">This case is routed across State Admins. You can assign investigators only for parts handled by your team; Main Admin manages the case details and routing.</div>' : '')
    + '<div class="row">' + field(p + 'Tmc', 'TMCID', c.tmcNumber, 'text', 'maxlength="60"') + field(p + 'Patient', 'Name of the patient', c.patientName, 'text', 'maxlength="150"') + '</div>'
    + '<div class="row">' + locationFields(p + 'PatientState', 'Patient place \u2014 State', c.patientState, p + 'District', 'Patient place \u2014 District', c.patientDistrict || c.district, p)
    + field(p + 'Village', 'Patient place \u2014 Village', c.village, 'text', 'maxlength="120"') + '</div>'
    + '<div class="row">' + field(p + 'Hospital', 'Hospital name', c.hospitalName, 'text', 'maxlength="200"')
    + locationFields(p + 'HospitalState', 'Hospital place \u2014 State', c.hospitalState, p + 'HDist', 'Hospital place \u2014 District', c.hospitalDistrict, p) + '</div>'
    + '<div id="' + p + 'Admins"></div>'
    + '<div id="' + p + 'PolicePlace" class="hide"><div class="section-title">Police station location</div>'
    + locationRow(p + 'PoliceState', 'Police station \u2014 State', c.policeStationState, p + 'PoliceDistrict', 'Police station \u2014 District', c.policeStationDistrict, p) + '</div>'
    + '<div class="row">' + field(p + 'Doa', 'Date of admission (DOA)', c.doa, 'date') + field(p + 'Dod', 'Date of discharge (DOD)', c.dod, 'date') + '</div>'
    + '<div class="row"><div class="field"><label>Which insurance</label><input id="' + p + 'Ins" list="insurers" value="' + esc(c.insuranceCompany) + '" maxlength="120"></div>'
    + '<div class="field"><label>Type of insurance claim</label><select id="' + p + 'Type" data-change="claimChange" data-arg="' + p + '"><option value="">-- choose --</option>' + types + '</select></div></div>'
    + '<div id="' + p + 'Bill" class="hide"><div class="radio-row"><label><input type="radio" name="' + p + 'bs" value="HOSPITAL" data-change="partsRefresh" data-arg="' + p + '"' + (c.billSource !== 'MEDICAL_SHOP' ? ' checked' : '') + '> Hospital</label>'
    + '<label><input type="radio" name="' + p + 'bs" value="MEDICAL_SHOP" data-change="partsRefresh" data-arg="' + p + '"' + (c.billSource === 'MEDICAL_SHOP' ? ' checked' : '') + '> Local medical shop</label></div></div>'
    + '<div id="' + p + 'Info" class="info hide"></div>'
    + (noParts ? '' : '<div class="section-title">Who handles which part?</div><div id="' + p + 'Parts"></div>');
}
A.claimChange = p => {
  const t = META.claimTypes.find(x => x.key === $('#' + p + 'Type').value);
  const info = $('#' + p + 'Info'), bill = $('#' + p + 'Bill'), police = $('#' + p + 'PolicePlace');
  if (police) police.classList.toggle('hide', !t || !t.partKeys.includes('POLICE_STATION'));
  if (!t) {
    if (info) info.classList.add('hide');
    if (bill) bill.classList.add('hide');
    renderParts(p);
    return;
  }
  if (bill) bill.classList.toggle('hide', !t.needsBillSource);
  if (info) {
    info.classList.remove('hide');
    info.textContent = t.needsBillSource ? 'The investigator will verify the bills at the hospital OR the local medical shop (choose above).'
      : 'The investigator will have to handle: ' + t.parts.join(', ') + '.';
  }
  captureSel(p); renderParts(p);
};
A.partsRefresh = p => { captureSel(p); renderParts(p); };
A.filterAdmins = p => { captureSel(p); renderParts(p); };
A.copyHospitalAdmin = p => {
  if ($('#' + p + 'PatientState').value !== $('#' + p + 'HospitalState').value) throw new Error('Patient and hospital must be in the same state to copy the State Admin.');
  const hospital = $('#' + p + 'HospitalSA').value;
  if (!hospital) throw new Error('Choose the hospital State Admin first.');
  $('#' + p + 'PatientSA').value = hospital;
  FS[p].adminSel.PATIENT = hospital;
};
A.assignAll = (p, el) => {
  if (!el.value) return;
  captureSel(p); formKeys(p).forEach(k => { FS[p].sel[k] = el.value; }); renderParts(p);
};
/* One investigator selector per part. Hospital and patient in different districts -> the admin is asked to pick for each;
   same district -> one person may take both. There is NO restriction either way. */
function renderParts(p) {
  const host = $('#' + p + 'Parts');
  const st = FS[p], allKeys = formKeys(p);
  const adminHost = $('#' + p + 'Admins');
  const normalize = value => (value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
  const ownerAdmin = ME.ownerAdmin || (ME.role === 'STATE_ADMIN' ? ME.username : '');
  const ownerState = ME.ownerState || ME.state || '';
  const hospitalState = $('#' + p + 'HospitalState').value;
  const patientState = $('#' + p + 'PatientState').value;
  const hasPatient = allKeys.includes('PATIENT');
  const mainAdmin = ME.role === 'MAIN_ADMIN';
  const hospitalNeedsRouting = mainAdmin || (!!hospitalState && normalize(hospitalState) !== normalize(ownerState));
  const patientNeedsRouting = hasPatient && (mainAdmin || (!!patientState && normalize(patientState) !== normalize(ownerState)));

  if (adminHost) {
    const selectedAdmin = (type, state, needsRouting) => {
      const select = $('#' + p + type + 'SA');
      let selected = select ? select.value : st.adminSel[type];
      if (!mainAdmin && !needsRouting) selected = ownerAdmin;
      const valid = st.stateAdmins.some(u => u.username === selected && normalize(u.state) === normalize(state));
      if (needsRouting && selected && !valid) selected = '';
      st.adminSel[type] = selected || '';
      return st.adminSel[type];
    };
    const currentHospital = selectedAdmin('Hospital', hospitalState, hospitalNeedsRouting);
    const currentPatient = selectedAdmin('Patient', patientState, patientNeedsRouting);
    const options = (state, selected) => {
      const matching = st.stateAdmins.filter(u => normalize(u.state) && normalize(state) && normalize(u.state) === normalize(state));
      return '<option value="">-- choose State Admin --</option>'
        + (matching.length
          ? matching.map(u => '<option value="' + esc(u.username) + '"' + (selected === u.username ? ' selected' : '') + '>'
            + esc(u.fullName || u.username) + ' (' + esc(u.username) + ')</option>').join('')
          : '<option value="" disabled>No active State Admin found for ' + esc(state || 'this state') + '</option>');
    };
    const sameState = $('#' + p + 'PatientState').value && $('#' + p + 'PatientState').value === $('#' + p + 'HospitalState').value;
    const routeFields = (hospitalNeedsRouting ? '<div class="field"><label>Hospital State Admin *</label>'
      + '<select id="' + p + 'HospitalSA" data-change="filterAdmins" data-arg="' + p + '">' + options(hospitalState, currentHospital) + '</select></div>' : '')
      + (patientNeedsRouting ? '<div class="field"><label>Patient State Admin *</label><select id="' + p + 'PatientSA" data-change="filterAdmins" data-arg="' + p + '">'
        + options(patientState, currentPatient) + '</select></div>' : '');
    adminHost.innerHTML = routeFields
      ? '<div class="section-title">State Admin routing</div><div class="row">' + routeFields + '</div>'
        + (hasPatient && mainAdmin ? '<button type="button" class="btn sm ghost" data-act="copyHospitalAdmin" data-arg="' + p + '"' + (sameState ? '' : ' disabled') + '>Use same State Admin for both locations</button>' : '')
        + '<div class="hint">Parts for an out-of-state location go to its State Admin; no investigator is selected for those parts.</div>'
      : '';
  }
  if (!host) return;
  const routeAdmin = type => type === 'PATIENT'
    ? (patientNeedsRouting ? st.adminSel.PATIENT : ownerAdmin)
    : (hospitalNeedsRouting ? st.adminSel.HOSPITAL : ownerAdmin);
  const localKeys = allKeys.filter(k => !st.locked.includes(k) && (mainAdmin || routeAdmin(k) === ownerAdmin));
  const otherStateParts = allKeys.filter(k => !st.locked.includes(k) && !localKeys.includes(k));
  const keys = localKeys;
  const lockedOtherParts = st.locked.filter(k => allKeys.includes(k));
  if (!keys.length) {
    host.innerHTML = otherStateParts.length || lockedOtherParts.length
      ? '<div class="hint">No parts are assigned to your State Admin team.</div>'
      : '<div class="hint">Choose the type of claim to assign its parts.</div>';
    return;
  }
  if (mainAdmin) { host.innerHTML = '<div class="hint">Investigators are assigned by each State Admin.</div>'; return; }
  const pd = $('#' + p + 'District').value.trim(), hd = $('#' + p + 'HDist').value.trim();
  const ps = $('#' + p + 'PatientState').value.trim(), hs = $('#' + p + 'HospitalState').value.trim();
  const patientPlace = [pd, ps].filter(Boolean).join(', '), hospitalPlace = [hd, hs].filter(Boolean).join(', ');
  const both = keys.includes('HOSPITAL') && keys.includes('PATIENT');
  const differ = both && pd && hd && (pd.toLowerCase() !== hd.toLowerCase() || ps.toLowerCase() !== hs.toLowerCase());
  let note = '';
  if (differ) note = '<div class="alert amber"><b>Hospital (' + esc(hospitalPlace) + ') and patient (' + esc(patientPlace) + ') are in different districts.</b> Choose which investigator takes the hospital part and which takes the patient part.</div>';
  else if (both && pd && hd) note = '<div class="info">Hospital and patient are both in ' + esc(hospitalPlace) + '. One investigator can take both parts \u2014 or you may still split them.</div>';
  else if (both) note = '<div class="hint" style="margin-bottom:8px;">Enter the patient district and the hospital district above to see the assignment hint.</div>';
  const distFor = k => k === 'HOSPITAL' ? hd : k === 'PATIENT' ? pd : '';
  const stateFor = k => {
    if (k === 'PATIENT') return ($('#' + p + 'PatientState') || {}).value || st.caseLocations.patientState || '';
    if (k === 'POLICE_STATION') return ($('#' + p + 'PoliceState') || {}).value || st.caseLocations.policeStationState || '';
    return ($('#' + p + 'HospitalState') || {}).value || st.caseLocations.hospitalState || '';
  };
  const investigatorsFor = k => {
    const state = stateFor(k), matching = st.invs.filter(u => normalize(u.state) === normalize(state) && normalize(state));
    const current = st.invs.find(u => u.username === st.sel[k]);
    if (current && !matching.some(u => u.username === current.username)) matching.unshift(current);
    return matching;
  };
  const label = u => esc(u.fullName) + ' (' + esc(u.username) + ')';
  const optsFor = k => {
    const d = distFor(k).toLowerCase(), cur = st.sel[k], state = stateFor(k), list = investigatorsFor(k);
    if (cur && !list.some(u => u.username === cur)) list.unshift({ username: cur, fullName: st.cur[k] || cur, state: '', district: '' });
    const options = list.map(u => {
      const place = [u.district, u.state].filter(Boolean).join(', ');
      const same = d && u.district && u.district.toLowerCase() === d;
      const currentOtherState = cur === u.username && normalize(u.state) !== normalize(state);
      return '<option value="' + esc(u.username) + '"' + (cur === u.username ? ' selected' : '') + '>' + label(u)
        + (place ? ' \u2014 ' + esc(place) : '') + (currentOtherState ? ' (currently assigned)' : '')
        + (same ? '  \u2713 same district' : '') + '</option>';
    }).join('');
    return '<option value="">-- choose investigator in ' + esc(state || 'the required state') + ' --</option>'
      + (options || '<option value="" disabled>No approved investigator found for ' + esc(state || 'this state') + '</option>');
  };
  const quickFillInvestigators = st.invs.filter(u => keys.length
    && keys.every(k => normalize(u.state) === normalize(stateFor(k)) && normalize(stateFor(k))));
  const quickFill = quickFillInvestigators.length
    ? '<div class="field"><label>Quick fill \u2014 give ALL parts to one investigator</label><select data-change="assignAll" data-arg="' + p + '"><option value="">-- choose --</option>'
      + quickFillInvestigators.map(u => '<option value="' + esc(u.username) + '">' + label(u) + '</option>').join('') + '</select></div>'
    : '';
  host.innerHTML = note
    + (otherStateParts.length ? '<div class="hint">Parts routed to another State Admin: ' + otherStateParts.map(k => esc(META.partDefs[k].label)).join(', ') + ' (no investigator selected here).</div>' : '')
    + (lockedOtherParts.length ? '<div class="hint">Parts owned by another State Admin: ' + lockedOtherParts.map(k => esc(META.partDefs[k].label)).join(', ') + ' (completion status only).</div>' : '')
    + quickFill
    + keys.map(k => {
      const d = distFor(k), who = k === 'HOSPITAL' ? 'hospital' : 'patient';
      return '<div class="field"><label>' + esc(META.partDefs[k].label) + (d ? ' <span class="muted">\u2014 ' + who + ' district: ' + esc(d) + '</span>' : '') + '</label>'
        + '<select data-part="' + k + '">' + optsFor(k) + '</select></div>';
    }).join('');
}
function readCaseForm(p) {
  captureSel(p);
  const bs = document.querySelector('input[name="' + p + 'bs"]:checked');
  const claimType = $('#' + p + 'Type').value;
  ensureLocation(p + 'PatientState', p + 'District', 'the patient');
  ensureLocation(p + 'HospitalState', p + 'HDist', 'the hospital');
  const normalize = value => (value || '').trim().replace(/\s+/g, ' ').toLocaleLowerCase();
  const mainAdmin = ME.role === 'MAIN_ADMIN';
  const ownerAdmin = ME.ownerAdmin || (ME.role === 'STATE_ADMIN' ? ME.username : '');
  const ownerState = ME.ownerState || ME.state || '';
  const hasPatientPart = formKeys(p).includes('PATIENT');
  const hospitalState = $('#' + p + 'HospitalState').value;
  const patientState = $('#' + p + 'PatientState').value;
  const pickStateAdmin = (type, state, needsRouting, location) => {
    const selected = needsRouting ? (($('#' + p + type + 'SA') || {}).value || '') : ownerAdmin;
    if (!selected) throw new Error('Choose the ' + location + ' State Admin for the selected ' + location + ' state.');
    if (!FS[p].stateAdmins.some(u => u.username === selected && normalize(u.state) === normalize(state)))
      throw new Error('The selected ' + location + ' State Admin does not match the ' + location + ' state. Choose it again.');
    return selected;
  };
  const hospitalAdmin = pickStateAdmin('Hospital', hospitalState,
    mainAdmin || normalize(hospitalState) !== normalize(ownerState), 'Hospital');
  const patientAdmin = hasPatientPart ? pickStateAdmin('Patient', patientState,
    mainAdmin || normalize(patientState) !== normalize(ownerState), 'Patient') : null;
  if (META.claimTypes.some(t => t.key === claimType && t.partKeys.includes('POLICE_STATION'))) {
    ensureLocation(p + 'PoliceState', p + 'PoliceDistrict', 'the police station');
  }
  return { tmcNumber: $('#' + p + 'Tmc').value, patientName: $('#' + p + 'Patient').value, district: $('#' + p + 'District').value,
    patientState: $('#' + p + 'PatientState').value, village: $('#' + p + 'Village').value,
    hospitalState: $('#' + p + 'HospitalState').value, hospitalDistrict: $('#' + p + 'HDist').value,
    policeStationState: $('#' + p + 'PoliceState').value, policeStationDistrict: $('#' + p + 'PoliceDistrict').value,
    doa: $('#' + p + 'Doa').value, dod: $('#' + p + 'Dod').value,
    hospitalName: $('#' + p + 'Hospital').value, insuranceCompany: $('#' + p + 'Ins').value, claimType, billSource: bs ? bs.value : null,
    hospitalStateAdmin: hospitalAdmin,
    patientStateAdmin: patientAdmin,
    stateAdmin: hospitalAdmin,
    partAssignments: formKeys(p).filter(k => !FS[p].locked.includes(k)
      && (mainAdmin || (k === 'PATIENT' ? patientAdmin : hospitalAdmin) === ownerAdmin))
      .map(k => ({ type: k, investigatorUsername: FS[p].sel[k] || '' })) };
}
async function approvedInvestigators() {
  const all = await api('/api/users?role=INVESTIGATOR');
  return all.filter(u => u.approvalStatus === 'APPROVED' && u.active);
}
async function vNewCase() {
  /* Main Admin routes the hospital and patient parts to the State Admin for each location. */
  if (ME.role === 'MAIN_ADMIN') {
    const sas = (await api('/api/users?role=STATE_ADMIN')).filter(u => u.active);
    setView('<div class="toolbar"><h2>Assign a new case</h2></div><div class="card">'
      + (sas.length ? '' : '<div class="alert amber">There are no active State Admins yet. Create one first (State Admins menu).</div>')
      + caseFormHtml('nc', null, [], true, sas)
      + '<div class="btn-row"><button class="btn brick" data-act="saveNewCase">Send to State Admin</button></div></div>');
    A.claimChange('nc');
    return;
  }
  const [invs, sas] = await Promise.all([approvedInvestigators(), api('/api/users/state-admins')]);
  setView('<div class="toolbar"><h2>Assign a new case</h2></div><div class="card">'
    + (invs.length ? '' : '<div class="alert amber">There are no approved investigators yet. Investigators are needed only for parts handled by your State Admin team; out-of-state parts go directly to that State Admin.</div>')
    + caseFormHtml('nc', null, invs, false, sas)
    + '<div class="btn-row"><button class="btn brick" data-act="saveNewCase">Assign case</button></div></div>');
  renderParts('nc');
}
A.saveNewCase = async () => { await api('/api/cases', 'POST', readCaseForm('nc')); toast(ME.role === 'MAIN_ADMIN' ? 'Case sent to the State Admin.' : 'Case assigned.'); await refreshBadges(); await vNewCase(); };

/* ---------------------------------------------------------------- case list */
const STATUS_BADGE = { ASSIGNED: 'open', SUBMITTED: 'sub', SENT_BACK: 'back', COMPLETED: 'done' };
const statusBadge = c => '<span class="badge ' + STATUS_BADGE[c.status] + '">' + esc(c.statusLabel) + '</span>'
  + (c.status !== 'COMPLETED' && (c.reviewFlagged || c.flagged) ? ' <span class="badge warn">Remarks</span>' : '')
  + (c.editRequested ? ' <span class="badge warn">Edit requested</span>' : '');
function qs(extra) {
  const p = new URLSearchParams();
  Object.entries(F).forEach(([k, v]) => { if (v) p.set(k, v); });
  Object.entries(extra || {}).forEach(([k, v]) => p.set(k, v));
  return p.toString();
}
async function vCases() {
  if (isStaff()) {
    const all = await api('/api/cases');
    const uniq = k => [...new Set(all.map(c => c[k]).filter(Boolean))].sort();
    OPTS.insurance = uniq('insuranceCompany'); OPTS.district = uniq('district'); OPTS.month = uniq('month').reverse();
    OPTS.investigator = [...new Map(all.map(c => [c.investigatorUsername, c.investigatorName])).entries()];
    const views = [['all', 'All cases'], ['open', 'Open'], ['review', 'Submitted'], ['remarks', 'Submitted with remarks'], ['unassigned', 'Awaiting assignment'], ['sentback', 'Sent back'], ['completed', 'Completed'], ['transfer', 'Transfer requested']];
    const sel = (key, label, opts) => '<select aria-label="' + label + '" data-change="setFilter" data-arg="' + key + '"><option value="">' + label + '</option>' + opts.map(o => '<option value="' + esc(o[0]) + '"' + (F[key] === o[0] ? ' selected' : '') + '>' + esc(o[1]) + '</option>').join('') + '</select>';
    setView('<div class="toolbar"><h2>Cases</h2><div style="display:flex;gap:8px;flex-wrap:wrap;">'
      + '<button class="btn sm ghost" data-act="exportCases" data-arg="xlsx">\u2B07 Excel</button><button class="btn sm ghost" data-act="exportCases" data-arg="pdf">\u2B07 PDF</button></div></div>'
      + '<div class="filterbar" id="filterbar"><input class="search" placeholder="Search TMCID / patient / hospital / district / investigator" value="' + esc(F.q) + '" data-input="setQ">'
      + '<select aria-label="Status" data-change="setViewSel">' + views.map(c => '<option value="' + c[0] + '"' + (F.view === c[0] ? ' selected' : '') + '>' + c[1] + '</option>').join('') + '</select>'
      + '<button type="button" class="btn ghost filter-toggle" id="filterToggle" data-act="toggleFilters">' + filterLabel() + '</button>'
      + '<div class="filter-panel">'
      + sel('insurance', 'All insurance', OPTS.insurance.map(x => [x, x])) + sel('district', 'All districts', OPTS.district.map(x => [x, x]))
      + sel('investigator', 'All investigators', OPTS.investigator.map(x => [x[0], x[1] || x[0]])) + sel('month', 'All months', OPTS.month.map(x => [x, x]))
      + '<select aria-label="Sort" data-change="setFilter" data-arg="sortBy"><option value="">Newest first</option>'
      + [['insurance', 'Sort by insurance'], ['district', 'Sort by district'], ['investigator', 'Sort by investigator'], ['month', 'Sort by month']].map(o => '<option value="' + o[0] + '"' + (F.sortBy === o[0] ? ' selected' : '') + '>' + o[1] + '</option>').join('')
      + '</select></div></div><div class="card"><div class="tablewrap" id="caseTable"></div></div>');
  } else {
    setView('<div class="toolbar"><h2>My cases</h2><input class="search" placeholder="Search TMCID / patient / hospital" data-input="setQ"></div><div id="sentBackBanner"></div><div class="card"><div class="tablewrap" id="caseTable"></div></div>');
  }
  await loadCaseTable();
}
A.setQ = (_, el) => { F.q = el.value; return loadCaseTable(); };
const filterCount = () => ['insurance', 'district', 'investigator', 'month', 'sortBy'].filter(k => F[k]).length;
const filterLabel = () => 'Filters' + (filterCount() ? ' <span class="count">' + filterCount() + '</span>' : '');
A.setFilter = (k, el) => { F[k] = el.value; const t = document.getElementById('filterToggle'); if (t) t.innerHTML = filterLabel(); return loadCaseTable(); };
A.setViewSel = (_, el) => { F.view = el.value; return loadCaseTable(); };
A.toggleFilters = (_, el) => { el.closest('.filterbar').classList.toggle('open'); };
A.exportCases = fmt => { window.location.href = '/api/cases/export?' + qs({ format: fmt }); };

const respBadge = p => p.answer === 'Y' ? '<span class="badge done">Yes</span>' + (p.viaOther ? ' <span class="badge warn">via another person</span>' : '')
  : p.answer === 'N' ? '<span class="badge back">No</span>' : '<span class="badge open">Pending</span>';
const transferBadge = p => p.transferStatus ? ' <span class="badge warn">Transfer ' + (p.transferStatus === 'FORWARDED' ? 'forwarded' : 'requested') + '</span>' : '';
/* Hospital part / Patient part columns: assigned or not, who has it, the response and the reason for "No". */
function partCell(c, type) {
  const p = c.parts.find(x => x.type === type);
  if (!p) return '<span class="muted">Not assigned</span>';
  if (p.visible === false) return '<span class="badge ' + (p.submitted ? 'done' : 'open') + '">' + (p.submitted ? 'Completed' : 'Not completed') + '</span>';
  if (!p.assigned) return '<span class="badge warn">Awaiting assignment</span>';
  return '<div class="pc"><span class="muted">Assigned to</span> <b>' + esc(p.investigatorName || p.investigatorUsername) + '</b><br>' + respBadge(p) + transferBadge(p)
    + (p.answer === 'N' ? '<div class="why"><b>Reason:</b> ' + esc(p.reason) + '</div>' : p.viaOther ? '<div class="why">' + esc(p.note) + '</div>' : '') + '</div>';
}
function otherCell(c) {
  const rest = c.parts.filter(x => x.type !== 'HOSPITAL' && x.type !== 'PATIENT');
  if (!rest.length) return '<span class="muted">\u2014</span>';
  return rest.map(p => p.visible === false ? '<div class="pc"><b>' + esc(p.label) + '</b> <span class="badge ' + (p.submitted ? 'done' : 'open') + '">' + (p.submitted ? 'Completed' : 'Not completed') + '</span></div>'
    : '<div class="pc"><b>' + esc(p.label) + '</b> <span class="muted">(' + (p.assigned ? esc(p.investigatorName || p.investigatorUsername) : 'not assigned yet') + ')</span> ' + (p.assigned ? respBadge(p) : '') + transferBadge(p)
      + (p.answer === 'N' ? '<div class="why">' + esc(p.reason) + '</div>' : '') + '</div>').join('');
}
async function loadCaseTable() {
  const list = await api('/api/cases?' + qs());
  const host = $('#caseTable'); if (!host) return;
  if (!list.length) { host.innerHTML = '<div class="empty">No cases found.</div>'; if (!isStaff()) $('#sentBackBanner').innerHTML = ''; return; }
  if (isStaff()) {
    host.innerHTML = '<table><thead><tr><th>TMCID</th><th>Patient</th><th>Insurance</th><th>Claim type</th><th>Patient district</th><th>Hospital district</th>'
      + '<th>Hospital part</th><th>Patient part</th><th>Other parts</th><th>Status</th><th>Assigned</th><th></th></tr></thead><tbody>'
      + list.map(c => '<tr><td class="mono">' + esc(c.tmcNumber) + (c.newForCurrentUser ? ' <span class="badge warn" id="newCaseBadge-' + c.id + '">New</span>' : '') + '</td><td>' + esc(c.patientName) + '</td><td>' + esc(c.insuranceCompany) + '</td><td><span class="tag-type">' + esc(c.claimTypeLabel) + '</span></td>'
        + '<td>' + esc(c.patientDistrict) + '</td><td>' + esc(c.hospitalDistrict) + '</td>'
        + '<td class="pcell">' + partCell(c, 'HOSPITAL') + '</td><td class="pcell">' + partCell(c, 'PATIENT') + '</td><td class="pcell">' + otherCell(c) + '</td>'
        + '<td>' + statusBadge(c) + '</td><td>' + fmtDate(c.createdAt) + '</td>'
        + '<td style="display:flex;gap:6px;"><button class="btn sm" data-act="openCase" data-arg="' + c.id + '">View</button>'
        + (c.canManageCase && c.awaitingAssignment ? '<button class="btn sm brick" data-act="editCase" data-arg="' + c.id + '">Assign</button>' : '')
        + (c.canManageCase ? '<button class="btn sm ghost" data-act="sendBackCase" data-arg="' + c.id + '">Send back</button>' : '')
        + (c.canComplete ? '<button class="btn sm brick" data-act="completeCase" data-arg="' + c.id + '">' + (ME.role === 'MAIN_ADMIN' ? 'Complete' : 'Review &amp; close parts') + '</button>' : '') + '</td></tr>').join('') + '</tbody></table>';
  } else {
    const needMe = c => c.status === 'SENT_BACK' && c.parts.some(p => p.mine && !p.submitted);
    const back = list.filter(needMe).length;
    $('#sentBackBanner').innerHTML = back ? '<div class="alert red"><b>' + back + ' case(s) sent back to you.</b> Open them to see why, fix, and submit again.</div>' : '';
    host.innerHTML = '<table><thead><tr><th>TMCID</th><th>Patient</th><th>Hospital</th><th>Claim type</th><th>My part(s)</th><th>Status</th><th>Note</th><th></th></tr></thead><tbody>'
      + list.map(c => {
        const mine = c.parts.filter(p => p.mine);
        const mineHtml = mine.map(p => '<div class="pc"><b>' + esc(p.label) + '</b> ' + (p.submitted ? '<span class="badge done">Submitted</span>' : respBadge(p)) + transferBadge(p) + '</div>').join('');
        const note = needMe(c) ? '<span style="color:var(--brick)">' + esc(c.sentBackReason) + '</span>' : '';
        const denied = mine.filter(p => p.transferDecisionNote).map(p => '<div style="color:var(--brick)">Transfer denied (' + esc(p.label) + '): ' + esc(p.transferDecisionNote) + '</div>').join('');
        return '<tr><td class="mono">' + esc(c.tmcNumber) + (c.newForCurrentUser ? ' <span class="badge warn" id="newCaseBadge-' + c.id + '">New</span>' : '') + '</td><td>' + esc(c.patientName) + '</td><td>' + esc(c.hospitalName) + '</td><td><span class="tag-type">' + esc(c.claimTypeLabel) + '</span></td>'
          + '<td class="pcell">' + mineHtml + '</td><td>' + statusBadge(c) + '</td><td style="white-space:normal;max-width:260px;">' + note + denied + '</td>'
          + '<td><button class="btn sm brick" data-act="openCase" data-arg="' + c.id + '">Open</button></td></tr>';
      }).join('') + '</tbody></table>';
  }
}

/* ---------------------------------------------------------------- case detail */
function partsReadOnly(c, only) {
  const parts = only ? c.parts.filter(only) : c.parts;
  if (!parts.length) return '<div class="empty">No parts.</div>';
  return '<table><thead><tr><th>Part</th><th>Investigator</th><th>Result</th><th>Reason / remarks</th></tr></thead><tbody>' + parts.map(p => p.visible === false
    ? '<tr><td>' + esc(p.label) + '</td><td colspan="3"><span class="badge ' + (p.submitted ? 'done' : 'open') + '">' + (p.submitted ? 'Completed' : 'Not completed') + '</span></td></tr>'
    : '<tr><td>' + esc(p.label) + '</td><td>' + (p.assigned === false ? '<span class="muted">Not assigned yet</span>' : esc(p.investigatorName || p.investigatorUsername)) + (p.mine ? ' <span class="muted">(you)</span>' : '') + '</td><td>' + respBadge(p)
    + (p.submitted ? ' <span class="badge done">Submitted</span>' : '') + (p.reviewed ? ' <span class="badge done">Reviewed</span>' : '') + transferBadge(p) + '</td><td style="white-space:normal;">'
    + (p.answer === 'N' ? esc(p.reason) : esc(p.note)) + (p.remarks ? '<div class="why"><b>Remarks:</b> ' + esc(p.remarks) + '</div>' : '')
    + (p.reviewNote ? '<div class="why"><b>Review note:</b> ' + esc(p.reviewNote) + '</div>' : '')
    + (p.transferStatus ? '<div class="why"><b>Transfer requested:</b> ' + esc(p.transferReason) + (p.transferTarget ? ' (suggested: ' + esc(p.transferTarget) + ')' : '') + '</div>' : '')
    + (p.transferHistory ? '<div class="why">' + esc(p.transferHistory).replace(/\n/g, '<br>') + '</div>' : '') + '</td></tr>').join('') + '</tbody></table>';
}
function caseSummary(c) {
  return '<div class="kv">' + kv('TMCID', c.tmcNumber) + kv('Patient', c.patientName) + kv('Patient place', [c.village, c.patientDistrict || c.district, c.patientState].filter(Boolean).join(', '))
    + kv('Hospital', c.hospitalName) + kv('Hospital place', [c.hospitalDistrict, c.hospitalState].filter(Boolean).join(', '))
    + (c.policeStationDistrict || c.policeStationState ? kv('Police station place', [c.policeStationDistrict, c.policeStationState].filter(Boolean).join(', ')) : '')
    + kv('DOA', fmtDate(c.doa)) + kv('DOD', fmtDate(c.dod)) + kv('Insurance', c.insuranceCompany)
    + kv('Claim type', c.claimTypeLabel + (c.billSource ? ' (' + (c.billSource === 'MEDICAL_SHOP' ? 'medical shop' : 'hospital') + ')' : ''))
    + kv('Investigator(s)', c.investigatorName) + (ME.role === 'MAIN_ADMIN' ? kv('Hospital State Admin', c.ownerAdmin) : '') + kv('Investigation status', c.statusLabel) + '</div>';
}
A.openCase = async id => {
  const c = await api('/api/cases/' + id);
  await api('/api/cases/' + id + '/seen', 'POST');
  const newBadge = document.getElementById('newCaseBadge-' + id);
  if (newBadge) newBadge.remove();
  await refreshBadges();
  if (isStaff()) return staffCaseModal(c);
  setCur(c); workModal();
};
function staffCaseModal(c) {
  const transfers = c.parts.filter(p => p.transferStatus).map(p => '<div class="alert amber"><b>Part transfer requested</b> \u2014 ' + esc(p.label) + ' (' + esc(p.investigatorName) + '): ' + esc(p.transferReason)
    + (p.transferTarget ? ' <i>(suggested: ' + esc(p.transferTarget) + ')</i>' : '') + (p.transferStatus === 'FORWARDED' ? ' <b>[forwarded to Main Admin]</b>' : '') + '</div>').join('');
  let body = (c.awaitingAssignment ? '<div class="alert amber"><b>Waiting for investigator assignment.</b> '
      + (ME.role === 'MAIN_ADMIN' ? 'This case is with State Admin <b>' + esc(c.ownerAdmin) + '</b>, who will choose the investigators.' : 'Choose the investigators for each part using the button below.') + '</div>' : '')
    + (c.status === 'SENT_BACK' ? '<div class="alert red"><b>Sent back</b> by ' + esc(c.sentBackBy) + ': ' + esc(c.sentBackReason) + '</div>' : '')
    + (c.editRequested ? '<div class="alert amber"><b>Edit requested</b> by the investigator: ' + esc(c.editRequestReason || '(no reason given)') + '</div>' : '')
    + transfers
    + (c.reviewFlagged || c.flagged ? '<div class="alert amber"><b>Your reviewable parts have remarks / exceptions.</b> Review them below and confirm the documents are received before completing.</div>' : '')
    + caseSummary(c) + '<div class="section-title">Verification checklist</div>' + partsReadOnly(c)
    + '<div class="section-title">Investigator remarks</div><p style="white-space:pre-wrap;">' + (esc(c.remarks) || '\u2014') + '</p>'
    + (c.status === 'COMPLETED' ? '<div class="alert green">Completed and sent to Main Admin'
      + (ME.role === 'MAIN_ADMIN' && c.reviewedBy ? ' by ' + esc(c.reviewedBy) : '')
      + (c.reviewNote ? ' \u2014 ' + esc(c.reviewNote) : '') + '</div>' : '');
  const foot = (c.status !== 'COMPLETED' && c.canManageCase ? '<button class="btn ' + (c.awaitingAssignment ? 'brick' : 'ghost') + '" data-act="editCase" data-arg="' + c.id + '">' + (c.awaitingAssignment ? 'Assign investigators' : 'Edit details / re-assign parts') + '</button>' : '')
    + (c.transferPending ? '<button class="btn ghost" data-act="goTransfers">Review part transfers</button>' : '')
    + (c.canManageCase ? '<button class="btn ghost" data-act="sendBackCase" data-arg="' + c.id + '">Send back</button>' : '')
    + (c.editRequested ? '<button class="btn ghost" data-act="decideEdit" data-arg="' + c.id + '|deny">Deny edit</button><button class="btn brick" data-act="decideEdit" data-arg="' + c.id + '|approve">Approve edit</button>' : '')
    + (c.canComplete ? '<button class="btn brick" data-act="completeCase" data-arg="' + c.id + '">'
      + (ME.role === 'MAIN_ADMIN' ? 'Mark complete' : 'Review &amp; close your parts') + '</button>' : '');
  openModal('Case ' + c.tmcNumber, body, foot, true);
}
A.goTransfers = () => { document.querySelectorAll('#modalHost .modal').forEach(m => m.remove()); return showTab('transfers'); };
async function afterCaseChange() {
  document.querySelectorAll('#modalHost .modal').forEach(m => m.remove());
  if (TAB === 'cases' || TAB === 'icases') await loadCaseTable(); else if (TAB === 'editreq') await vEditReq();
  refreshBadges();
}
A.sendBackCase = async id => {
  const c = await api('/api/cases/' + id);
  const parts = c.parts.map(p => '<label style="display:flex;gap:8px;font-size:13px;margin-bottom:4px;"><input type="checkbox" class="sbPart" value="' + esc(p.type) + '" checked> '
    + esc(p.label) + ' <span class="muted">\u2014 ' + esc(p.investigatorName) + '</span></label>').join('');
  dialog('Send case back to investigator',
    (c.parts.length > 1 ? '<div class="field"><label>Which part(s) go back? (untick the ones that are fine)</label>' + parts + '</div>' : '')
    + '<div class="field"><label>Reason (the investigator will see this as an alert)</label><textarea id="sbReason" maxlength="1000"></textarea></div>', 'Send back',
    async () => {
      const picked = [...document.querySelectorAll('.sbPart:checked')].map(x => x.value);
      if (c.parts.length > 1 && !picked.length) throw new Error('Tick at least one part to send back.');
      await api('/api/cases/' + id + '/send-back', 'POST', { note: $('#sbReason').value, parts: picked.length === c.parts.length ? [] : picked });
      toast('Sent back to the investigator.'); await afterCaseChange();
    });
};
A.completeCase = async id => {
  const c = await api('/api/cases/' + id);
  const flagged = !!(c.reviewFlagged || c.flagged);
  const adminReview = ME.role !== 'MAIN_ADMIN';
  dialog(adminReview ? 'Review and close your team’s submitted parts' : 'Mark case complete',
    (flagged ? '<div class="alert amber">Your reviewable parts have remarks / exceptions. Confirm that you reviewed them and the documents are received.</div>' : '')
    + '<label style="display:flex;gap:8px;margin-bottom:12px;"><input type="checkbox" id="cpAck"> I have reviewed the remarks and the documents are received.</label>'
    + '<div class="field"><label>Review note (optional)</label><textarea id="cpNote" maxlength="1000"></textarea></div>',
    adminReview ? 'Review & close your parts' : 'Mark complete',
    async () => {
      await api('/api/cases/' + id + '/complete', 'POST', { note: $('#cpNote').value, ack: $('#cpAck').checked });
      toast(adminReview ? 'Your team’s parts were reviewed.' : 'Case completed.');
      await afterCaseChange();
    });
};
A.decideEdit = async arg => {
  const [id, d] = arg.split('|'); await api('/api/cases/' + id + '/edit-decision/' + d, 'POST');
  toast(d === 'approve' ? 'Edit approved — case sent back to the investigator.' : 'Edit request denied.'); await afterCaseChange();
};
A.editCase = async id => {
  const [c, allInvs] = await Promise.all([api('/api/cases/' + id), approvedInvestigators()]);
  const invs = ME.role === 'MAIN_ADMIN' ? [] : allInvs;
  document.querySelectorAll('#modalHost .modal').forEach(m => m.remove());
  let sas = [];
  if (ME.role === 'MAIN_ADMIN') {
    sas = (await api('/api/users?role=STATE_ADMIN')).filter(u => u.active
      || (c.parts || []).some(p => p.stateAdminUsername === u.username));
  }
  const assignmentOnly = ME.role !== 'MAIN_ADMIN' && c.parts.some(p => p.visible === false);
  const m = dialog(assignmentOnly ? 'Assign investigators' : 'Edit case details',
    caseFormHtml('ec', c, invs, ME.role === 'MAIN_ADMIN', sas, assignmentOnly), 'Save changes',
    async () => {
      const payload = assignmentOnly
        ? (captureSel('ec'), { partAssignments: FS.ec.assignmentKeys
          .map(k => ({ type: k, investigatorUsername: FS.ec.sel[k] || '' })) })
        : readCaseForm('ec');
      await api('/api/cases/' + id, 'PUT', payload);
      toast(assignmentOnly ? 'Your team’s investigator assignments were saved.' : 'Case updated.');
      await afterCaseChange();
    }, true);
  if (!assignmentOnly) A.claimChange('ec');
};

/* ---------------------------------------------------------------- investigator: work on a case */
function setCur(c) {
  const mineWithRemarks = c.parts.find(p => p.mine && p.remarks);
  c.myRemarks = mineWithRemarks ? mineWithRemarks.remarks : '';
  CUR = c;
}
const closeModals = () => document.querySelectorAll('#modalHost .modal').forEach(m => m.remove());
function workModal() {
  closeModals();
  const c = CUR, mine = c.parts.filter(p => p.mine), others = c.parts.filter(p => !p.mine);
  const open = c.status === 'ASSIGNED' || c.status === 'SENT_BACK';
  const editable = open ? mine.filter(p => !p.submitted) : [];
  let body = (c.status === 'SENT_BACK' && editable.length ? '<div class="alert red"><b>Sent back by ' + esc(c.sentBackBy) + ':</b> ' + esc(c.sentBackReason) + '</div>' : '') + caseSummary(c);
  if (others.length) body += '<div class="section-title">Parts handled by other investigators</div>' + partsReadOnly(c, p => !p.mine);

  if (!editable.length) {
    const waiting = c.status === 'SUBMITTED' ? 'Submitted \u2014 waiting for review. You cannot edit it now.'
      : c.status === 'COMPLETED' ? 'This case is completed and locked.'
      : 'You have submitted your part(s). Waiting for the other investigator(s) to finish theirs.';
    body += '<div class="alert amber">' + waiting + '</div><div class="section-title">Your part(s)</div>' + partsReadOnly(c, p => p.mine)
      + '<p style="white-space:pre-wrap;"><b>Remarks:</b> ' + (esc(c.myRemarks) || '\u2014') + '</p>';
    const foot = c.canRequestEdit ? (c.editRequested ? '<span class="msg-ok">Edit request sent \u2014 waiting for admin.</span>' : '<button class="btn brick" data-act="reqEdit" data-arg="' + c.id + '">Request admin approval to edit</button>') : '';
    openModal('Case ' + c.tmcNumber, body, foot, true); return;
  }

  const submittedMine = mine.filter(p => p.submitted);
  if (submittedMine.length) body += '<div class="section-title">Already submitted by you</div>' + partsReadOnly(c, p => p.mine && p.submitted);
  body += '<div class="section-title">Your verification checklist</div>'
    + (editable.some(p => p.verification) ? '<div class="hint" style="margin-bottom:8px;">Police / Nominee / Witness / Location items are verifications; the others are document collection.</div>' : '')
    + editable.map(p => {
      const q = p.verification ? 'Verification done?' : 'Documents collected?';
      return '<div class="part"><div class="ph"><b>' + esc(p.label) + '</b><span>' + esc(q) + ' &nbsp;<span class="yn"><button data-act="ans" data-arg="' + p.type + '|Y" class="' + (p.answer === 'Y' ? 'on-y' : '') + '">Yes</button>'
        + '<button data-act="ans" data-arg="' + p.type + '|N" class="' + (p.answer === 'N' ? 'on-n' : '') + '">No</button></span></span></div>'
        + (p.type === 'HOSPITAL' && c.claimType !== 'CASHLESS' ? '<div class="field"><label for="w_mrd_bill_' + p.type + '">MRD charges bill available?</label><select id="w_mrd_bill_' + p.type + '" data-change="mrdBillChanged" data-arg="' + p.type + '">'
          + '<option value="">-- choose --</option><option value="yes"' + (p.mrdBillAvailable === true ? ' selected' : '') + '>Yes</option><option value="no"' + (p.mrdBillAvailable === false ? ' selected' : '') + '>No</option></select></div>'
          + (p.mrdBillAvailable === true ? '<div class="field"><label for="w_mrd_amount_' + p.type + '">MRD charges amount</label><input id="w_mrd_amount_' + p.type + '" type="number" min="0.01" step="0.01" value="' + esc(p.mrdBillAmount) + '" placeholder="Enter amount"></div>' : '') : '')
        + (p.answer === 'N' ? '<div class="field"><label>Why not? (reason is required)</label><textarea id="w_reason_' + p.type + '" maxlength="1000">' + esc(p.reason) + '</textarea></div>' : '')
        + (p.answer === 'Y' ? '<label style="display:flex;gap:8px;font-size:13px;"><input type="checkbox" id="w_via_' + p.type + '" data-change="viaChange"' + (p.viaOther ? ' checked' : '') + '> Collected through another person / documents still to be received</label>'
          + (p.viaOther ? '<div class="field" style="margin-top:8px;"><label>Remarks \u2014 who will send the documents, and when (required)</label><textarea id="w_note_' + p.type + '" maxlength="1000">' + esc(p.note) + '</textarea></div>' : '') : '')
        + (p.transferDecisionNote ? '<div class="alert red" style="margin-top:8px;"><b>Transfer request denied:</b> ' + esc(p.transferDecisionNote) + '</div>' : '')
        + (p.transferStatus
          ? '<div class="alert amber" style="margin-top:8px;"><b>Transfer requested</b> (' + (p.transferStatus === 'FORWARDED' ? 'forwarded to the Main Admin' : 'waiting for admin') + '): ' + esc(p.transferReason)
            + (p.transferTarget ? ' <i>(suggested: ' + esc(p.transferTarget) + ')</i>' : '') + ' <button class="btn sm ghost" data-act="cancelTransfer" data-arg="' + p.type + '">Cancel request</button></div>'
          : '<div style="margin-top:8px;"><button class="link" data-act="reqTransfer" data-arg="' + p.type + '">Cannot do this part? Request transfer to another investigator / state</button></div>')
        + '</div>';
    }).join('')
    + '<div class="field"><label>Your remarks (optional \u2014 a case with remarks goes to the review-with-remarks section)</label><textarea id="w_remarks" maxlength="2000">' + esc(c.myRemarks) + '</textarea></div>'
    + '<details><summary style="cursor:pointer;font-size:13px;color:var(--muted);margin-bottom:8px;">Details used in the letters (optional)</summary><div class="row">'
    + field('w_claim', 'Claim number', c.claimNo, 'text', 'maxlength="60"') + field('w_policy', 'Policy number', c.policyNo, 'text', 'maxlength="60"') + '</div><div class="row">'
    + field('w_age', 'Patient age', c.patientAge, 'text', 'maxlength="10"') + field('w_ip', 'IP number (hospital)', c.ipNo, 'text', 'maxlength="40"') + '</div></details>'
    + '<div class="err" id="workErr"></div>';
  const m = openModal('Case ' + c.tmcNumber, body,
    '<button class="btn ghost" data-act="saveWork" data-arg="draft">Save progress</button><button class="btn brick" data-act="saveWork" data-arg="submit">Submit my part(s)</button>', true);
  m.id = 'workModal';
}
function editableMine() { return CUR.parts.filter(p => p.mine && !p.submitted); }
function syncWork() {
  const c = CUR; if (!c || !$('#workModal')) return;
  editableMine().forEach(p => {
    const r = $('#w_reason_' + p.type); if (r) p.reason = r.value;
    const v = $('#w_via_' + p.type); if (v) p.viaOther = v.checked;
    const n = $('#w_note_' + p.type); if (n) p.note = n.value;
    const mrd = $('#w_mrd_bill_' + p.type);
    if (mrd) {
      p.mrdBillAvailable = mrd.value === 'yes' ? true : mrd.value === 'no' ? false : null;
      const amount = $('#w_mrd_amount_' + p.type);
      p.mrdBillAmount = p.mrdBillAvailable && amount && amount.value ? amount.value : null;
    }
  });
  const rm = $('#w_remarks'); if (rm) c.myRemarks = rm.value;
  [['w_claim', 'claimNo'], ['w_policy', 'policyNo'], ['w_age', 'patientAge'], ['w_ip', 'ipNo']].forEach(([id, k]) => { const e = $('#' + id); if (e) c[k] = e.value; });
}
A.ans = arg => { syncWork(); const [t, a] = arg.split('|'); const p = CUR.parts.find(x => x.type === t); p.answer = a; if (a === 'N') p.viaOther = false; workModal(); };
A.viaChange = () => { syncWork(); workModal(); };
A.mrdBillChanged = type => {
  syncWork();
  const part = CUR.parts.find(p => p.type === type);
  if (!part) return;
  const selection = $('#w_mrd_bill_' + type).value;
  part.mrdBillAvailable = selection === 'yes' ? true : selection === 'no' ? false : null;
  if (!part.mrdBillAvailable) part.mrdBillAmount = null;
  workModal();
};
A.saveWork = async mode => {
  syncWork(); const c = CUR, submit = mode === 'submit';
  if (submit && !confirm('Submit your part(s) of this case? You will not be able to edit them afterwards unless the admin sends them back.')) return;
  const body = { parts: editableMine().map(p => ({ type: p.type, answer: p.answer || '', reason: p.answer === 'N' ? p.reason : null, viaOther: !!p.viaOther, note: p.answer === 'Y' && p.viaOther ? p.note : null,
      mrdBillAvailable: p.type === 'HOSPITAL' && c.claimType !== 'CASHLESS' ? p.mrdBillAvailable : null,
      mrdBillAmount: p.type === 'HOSPITAL' && c.claimType !== 'CASHLESS' ? p.mrdBillAmount : null })),
    remarks: c.myRemarks, claimNo: c.claimNo, policyNo: c.policyNo, patientAge: c.patientAge, ipNo: c.ipNo, submit };
  let fresh;
  try { fresh = await api('/api/cases/' + c.id + '/work', 'PUT', body); }
  catch (e) { const el = $('#workErr'); if (el) el.textContent = e.message; else fail(e); return; }
  setCur(fresh);
  if (submit) {
    toast(fresh.status === 'SUBMITTED' ? 'Case submitted for review.' : 'Your part(s) submitted. Waiting for the other investigator(s).');
    closeModals(); await loadCaseTable();
  } else { toast('Progress saved.'); workModal(); }
};
A.reqEdit = id => dialog('Request admin approval to edit', '<div class="field"><label>Why do you need to edit this case?</label><textarea id="reReason" maxlength="1000" required></textarea></div>', 'Send request',
  async m => {
    const reason = $('#reReason', m).value.trim();
    if (!reason) throw new Error('Please explain why you need to edit this case.');
    await api('/api/cases/' + id + '/request-edit', 'POST', { note: reason });
    toast('Edit request sent.'); closeModals(); await loadCaseTable();
  });

/* part transfer: e.g. the patient's permanent address is Guntur but the patient now lives in Hyderabad */
function mergeTransfer(fresh) {
  fresh.parts.forEach(fp => {
    const p = CUR.parts.find(x => x.type === fp.type); if (!p) return;
    ['transferStatus', 'transferReason', 'transferTarget', 'transferRequestedAt', 'transferDecisionNote', 'transferHistory'].forEach(k => { p[k] = fp[k]; });
  });
}
A.reqTransfer = type => {
  syncWork();
  const p = CUR.parts.find(x => x.type === type);
  dialog('Request transfer \u2014 ' + p.label,
    '<div class="info" style="margin-bottom:10px;">Use this when the person or place is not in your area (for example the patient\'s permanent address is in one district or state but the patient now lives in another). Your admin gets an alert and can give this part to another investigator \u2014 in another state too.</div>'
    + '<div class="field"><label>Why should this part be transferred? (required)</label><textarea id="trReason" maxlength="1000"></textarea></div>'
    + '<div class="field"><label>Where is the person / place now? (optional \u2014 district / state / investigator name)</label><input id="trTarget" maxlength="200"></div>',
    'Send transfer request',
    async () => {
      const fresh = await api('/api/cases/' + CUR.id + '/parts/' + type + '/transfer-request', 'POST', { reason: $('#trReason').value, target: $('#trTarget').value });
      mergeTransfer(fresh); toast('Transfer request sent to your admin.'); workModal();
    });
};
A.cancelTransfer = async type => {
  syncWork();
  const fresh = await api('/api/cases/' + CUR.id + '/parts/' + type + '/transfer-cancel', 'POST');
  mergeTransfer(fresh); toast('Transfer request cancelled.'); workModal();
};

/* ---------------------------------------------------------------- request for edit (staff) */
async function vEditReq() {
  const list = await api('/api/cases?view=editreq');
  BADGES.editreq = list.length; renderTabbar();
  setView('<div class="toolbar"><h2>Requests for edit</h2></div><div class="card">' + (list.length ? '<div class="tablewrap"><table><thead><tr><th>TMCID</th><th>Patient</th><th>Investigator</th><th>Reason</th><th></th></tr></thead><tbody>'
    + list.map(c => '<tr><td class="mono">' + esc(c.tmcNumber) + '</td><td>' + esc(c.patientName) + '</td><td>' + esc(c.investigatorName) + '</td><td style="white-space:normal;max-width:320px;">' + esc(c.editRequestReason) + '</td>'
      + '<td style="display:flex;gap:6px;"><button class="btn sm" data-act="openCase" data-arg="' + c.id + '">View</button><button class="btn sm brick" data-act="decideEdit" data-arg="' + c.id + '|approve">Approve</button><button class="btn sm ghost" data-act="decideEdit" data-arg="' + c.id + '|deny">Deny</button></td></tr>').join('')
    + '</tbody></table></div>' : '<div class="empty">No pending edit requests.</div>') + '</div>');
}

/* ---------------------------------------------------------------- part transfers (staff) */
let TRANSFERS = [];
async function vTransfers() {
  TRANSFERS = await api('/api/cases/part-transfers');
  setTransferAlert(TRANSFERS.filter(t => t.canAct).length); renderTabbar();
  const rows = TRANSFERS.map((t, i) => '<tr><td class="mono">' + esc(t.tmcNumber) + '<br><span class="muted">' + esc(t.patientName) + '</span></td>'
    + '<td><b>' + esc(t.partLabel) + '</b></td><td>' + esc(t.patientDistrict) + (t.village ? '<br><span class="muted">' + esc(t.village) + '</span>' : '') + '</td><td>' + esc(t.hospitalDistrict) + '</td>'
    + '<td>' + esc(t.fromName) + '</td><td style="white-space:normal;min-width:200px;max-width:300px;">' + esc(t.reason) + '</td><td>' + (esc(t.target) || '\u2014') + '</td>'
    + '<td>' + esc(new Date(t.requestedAt).toLocaleString()) + '</td>'
    + '<td>' + (t.status === 'FORWARDED' ? '<span class="badge pend">Forwarded to Main Admin</span>' : '<span class="badge warn">Waiting</span>') + '</td>'
    + '<td style="display:flex;gap:6px;flex-wrap:wrap;">'
    + '<button class="btn sm" data-act="openCase" data-arg="' + t.caseId + '">View case</button>'
    + (t.canAct ? '<button class="btn sm brick" data-act="trAssign" data-arg="' + i + '">Give to another investigator</button><button class="btn sm ghost" data-act="trDeny" data-arg="' + i + '">Deny</button>'
      + (ME.role !== 'MAIN_ADMIN' && t.status === 'REQUESTED' ? '<button class="btn sm ghost" data-act="trForward" data-arg="' + i + '">Other state \u2014 forward to Main Admin</button>' : '') : '')
    + '</td></tr>').join('');
  setView('<div class="toolbar"><h2>Part transfer requests</h2></div><div class="card">'
    + '<div class="hint" style="margin-bottom:10px;">An investigator asks to hand one part (for example the patient part) to someone else. Give it to another investigator, deny it with a reason, or forward it to the Main Admin if it must go to another state.</div>'
    + (TRANSFERS.length ? '<div class="tablewrap"><table><thead><tr><th>Case</th><th>Part</th><th>Patient district</th><th>Hospital district</th><th>Requested by</th><th>Reason</th><th>Suggested place</th><th>When</th><th>Status</th><th></th></tr></thead><tbody>' + rows + '</tbody></table></div>'
      : '<div class="empty">No part-transfer requests.</div>') + '</div>');
}
A.trAssign = async i => {
  const t = TRANSFERS[i], invs = (await approvedInvestigators()).filter(u => u.username !== t.fromUsername);
  const opts = '<option value="">-- choose investigator --</option>' + invs.map(u => '<option value="' + esc(u.username) + '">' + esc(u.fullName) + ' (' + esc(u.username) + ')'
    + (u.district || u.state ? ' \u2014 ' + esc([u.district, u.state].filter(Boolean).join(', ')) : '') + '</option>').join('');
  dialog('Transfer ' + t.partLabel + ' \u2014 case ' + t.tmcNumber,
    '<div class="kv">' + kv('Patient district', t.patientDistrict) + kv('Hospital district', t.hospitalDistrict) + kv('Now with', t.fromName) + kv('Suggested place', t.target) + '</div>'
    + '<div class="field" style="margin-top:12px;"><label>Give this part to</label><select id="trInv">' + opts + '</select></div>'
    + (ME.role === 'MAIN_ADMIN' ? '' : '<div class="hint">You only see investigators of your own team. If the person must go to another state, close this and use \u201cOther state \u2014 forward to Main Admin\u201d.</div>')
    + '<div class="hint">The new investigator starts this part fresh; earlier answers for it are cleared.</div>',
    'Transfer part',
    async () => { await api('/api/cases/' + t.caseId + '/parts/' + t.partType + '/transfer-decision', 'POST', { decision: 'approve', investigatorUsername: $('#trInv').value }); toast('Part transferred.'); await vTransfers(); refreshBadges(); });
};
A.trDeny = i => {
  const t = TRANSFERS[i];
  dialog('Deny transfer \u2014 ' + t.partLabel, '<div class="field"><label>Reason (the investigator will see it)</label><textarea id="trNote" maxlength="1000"></textarea></div>', 'Deny request',
    async () => { await api('/api/cases/' + t.caseId + '/parts/' + t.partType + '/transfer-decision', 'POST', { decision: 'deny', note: $('#trNote').value }); toast('Request denied.'); await vTransfers(); refreshBadges(); });
};
A.trForward = async i => {
  const t = TRANSFERS[i];
  if (!confirm('Forward this request to the Main Admin so the part can go to an investigator in another state?')) return;
  await api('/api/cases/' + t.caseId + '/parts/' + t.partType + '/transfer-decision', 'POST', { decision: 'forward' });
  toast('Forwarded to the Main Admin.'); await vTransfers(); refreshBadges();
};

/* ---------------------------------------------------------------- investigators (staff) */
let INVS = [];
const APPROVAL_BADGE = u => !u.active ? '<span class="badge back">Deactivated</span>'
  : u.approvalStatus === 'APPROVED' ? '<span class="badge done">Approved</span>'
  : u.approvalStatus === 'PENDING_APPROVAL' ? '<span class="badge pend">Waiting for admin approval</span>'
  : '<span class="badge back">Sent back for changes</span>';

async function vInvestigators() {
  INVS = await api('/api/users?role=INVESTIGATOR');
  const views = [['all', 'All investigators'], ['PENDING_APPROVAL', 'Waiting approval'], ['CHANGES_REQUESTED', 'Sent back for changes'], ['APPROVED', 'Approved'], ['inactive', 'Deactivated']];
  setView('<div class="toolbar"><h2>Investigators</h2></div><div class="filterbar simple"><select aria-label="Status" data-change="uFilterSel">'
    + views.map(c => '<option value="' + c[0] + '"' + (UFILTER === c[0] ? ' selected' : '') + '>' + c[1] + '</option>').join('')
    + '</select></div><div class="card"><div class="tablewrap" id="invTable"></div></div>');
  renderInvTable();
}
A.uFilterSel = (_, el) => { UFILTER = el.value; renderInvTable(); };
function renderInvTable() {
  const list = INVS.filter(u => UFILTER === 'all' || (UFILTER === 'inactive' ? !u.active : u.approvalStatus === UFILTER));
  const host = $('#invTable'); if (!host) return;
  if (!list.length) { host.innerHTML = '<div class="empty">No investigators here.</div>'; return; }
  const adm = isAdmin();
  host.innerHTML = '<table><thead><tr><th></th><th>Name</th><th>Username</th>' + (adm ? '<th>Password</th>' : '') + '<th>District</th><th>Mobile</th><th>Status</th><th></th></tr></thead><tbody>'
    + list.map(u => {
      const a = [];
      a.push('<button class="btn sm" data-act="viewUser" data-arg="' + esc(u.username) + '">View</button>');
      if (adm || u.approvalStatus === 'CHANGES_REQUESTED') a.push('<button class="btn sm ghost" data-act="editUser" data-arg="' + esc(u.username) + '">Edit</button>');
      if (adm && u.approvalStatus === 'PENDING_APPROVAL') { a.push('<button class="btn sm brick" data-act="approveUser" data-arg="' + esc(u.username) + '">Approve</button>'); a.push('<button class="btn sm ghost" data-act="sendBackUser" data-arg="' + esc(u.username) + '">Send back</button>'); }
      if (adm) { a.push('<button class="btn sm ghost" data-act="resetPw" data-arg="' + esc(u.username) + '">Reset password</button>'); a.push('<button class="btn sm ghost" data-act="toggleActive" data-arg="' + esc(u.username) + '|' + (u.active ? 'false' : 'true') + '">' + (u.active ? 'Deactivate' : 'Activate') + '</button>'); }
      return '<tr><td>' + (u.hasPhoto ? '<img class="thumb" alt="" src="/api/users/' + enc(u.username) + '/photo">' : '') + '</td><td>' + esc(u.fullName) + '</td><td class="mono">' + esc(u.username) + '</td>'
        + (adm ? '<td><span class="pwcell" id="pw_' + esc(u.username) + '">••••••••</span> <button class="link" data-act="togglePw" data-arg="' + esc(u.username) + '">Show</button></td>' : '')
        + '<td>' + esc([u.district, u.state].filter(Boolean).join(', ')) + '</td><td>' + esc(u.mobile) + '</td><td>' + APPROVAL_BADGE(u) + '</td><td style="display:flex;gap:6px;flex-wrap:wrap;">' + a.join('') + '</td></tr>';
    }).join('') + '</tbody></table>';
}
const pwTimers = {};
A.togglePw = async (u, el) => {
  const span = document.getElementById('pw_' + u);
  if (el.textContent === 'Hide') { span.textContent = '••••••••'; el.textContent = 'Show'; clearTimeout(pwTimers[u]); return; }
  const r = await api('/api/users/' + enc(u) + '/password');
  span.textContent = r.password; el.textContent = 'Hide';
  pwTimers[u] = setTimeout(() => { span.textContent = '••••••••'; el.textContent = 'Show'; }, 15000);   // auto-hide
};
A.viewUser = async u => {
  const x = await api('/api/users/' + enc(u));
  openModal('Investigator — ' + x.fullName,
    (x.reviewNote && x.approvalStatus === 'CHANGES_REQUESTED' ? '<div class="alert red"><b>Admin asked for changes:</b> ' + esc(x.reviewNote) + '</div>' : '')
    + '<div style="display:flex;gap:16px;align-items:center;margin-bottom:12px;">' + (x.hasPhoto ? '<img class="avatar lg" alt="" src="/api/users/' + enc(u) + '/photo">' : '') + '<div>' + APPROVAL_BADGE(x) + '</div></div>'
    + '<div class="kv">' + kv('Username', x.username) + kv('State', x.state) + kv('District', x.district) + kv('Mobile', x.mobile) + kv('Aadhaar no.', x.aadhar) + kv('PAN', x.pan) + kv('Driving licence', x.dl)
    + kv('Bank & branch', x.bankName) + kv('Account no.', x.bankAcc) + kv('IFSC', x.ifsc) + kv('Created by', x.createdBy) + '</div>'
    + (x.hasAadharFile ? '<a class="btn sm ghost" target="_blank" rel="noopener" href="/api/users/' + enc(u) + '/aadhar-file">Open Aadhaar card upload</a>' : ''), '', true);
};
A.approveUser = async u => { await api('/api/users/' + enc(u) + '/approve', 'POST'); toast('Investigator approved — login is now active.'); await vInvestigators(); refreshBadges(); };
A.sendBackUser = u => dialog('Send back to office assistant', '<div class="field"><label>What needs to change?</label><textarea id="sbuNote" maxlength="1000"></textarea></div>', 'Send back',
  async () => { await api('/api/users/' + enc(u) + '/send-back', 'POST', { note: $('#sbuNote').value }); toast('Sent back for changes.'); await vInvestigators(); refreshBadges(); });
A.toggleActive = async arg => { const [u, v] = arg.split('|'); await api('/api/users/' + enc(u) + '/active/' + v, 'POST'); toast(v === 'true' ? 'Activated.' : 'Deactivated — they are signed out immediately.'); showTab(TAB); };
A.resetPw = u => dialog('Reset password — ' + u, field('rpNew', 'New password', '', 'text', 'autocomplete="off"') + '<div class="hint">At least 8 characters with a letter and a digit. Tell the person their new password.</div>', 'Reset',
  async () => { await api('/api/users/' + enc(u) + '/password', 'PUT', { newPassword: $('#rpNew').value }); toast('Password reset.'); });

/* investigator create / edit form */
function invFormHtml(p, u, owners) {
  const create = !u; u = u || {};
  return (owners ? '<div class="field"><label>Belongs to admin</label><select id="' + p + 'Owner">' + owners.map(o => '<option value="' + esc(o.username) + '">' + esc(o.fullName) + (o.state ? ' (' + esc(o.state) + ')' : '') + '</option>').join('') + '</select></div>' : '')
    + (u.reviewNote && u.approvalStatus === 'CHANGES_REQUESTED' ? '<div class="alert red"><b>Admin asked for changes:</b> ' + esc(u.reviewNote) + '</div>' : '')
    + '<div class="section-title">Login</div><div class="row">' + field(p + 'Name', 'Full name', u.fullName, 'text', 'maxlength="120"')
    + (create ? field(p + 'User', 'Username', '', 'text', 'maxlength="40" autocomplete="off"') + field(p + 'Pass', 'Initial password', '', 'text', 'autocomplete="off"') : '') + '</div>'
    + '<div class="row">' + stateField(p + 'State', 'State', u.state, p + 'District') + districtField(p + 'District', 'District', u.district, u.state) + field(p + 'Mobile', 'Mobile number', u.mobile, 'text', 'maxlength="15"') + '</div>'
    + '<div class="section-title">Identity & bank details</div><div class="row">' + field(p + 'Aadhar', 'Aadhaar number', u.aadhar, 'text', 'maxlength="20"') + field(p + 'Pan', 'PAN number', u.pan, 'text', 'maxlength="12"') + field(p + 'Dl', 'Driving licence number', u.dl, 'text', 'maxlength="30"') + '</div>'
    + '<div class="row">' + field(p + 'Bank', 'Bank name & branch', u.bankName, 'text', 'maxlength="120"') + field(p + 'Acc', 'Bank account number', u.bankAcc, 'text', 'maxlength="30"') + field(p + 'Ifsc', 'IFSC code', u.ifsc, 'text', 'maxlength="15"') + '</div>'
    + '<div class="section-title">Uploads</div><div class="row"><div class="field"><label>Passport-size photo (JPG/PNG, max 2 MB)' + (create ? ' — required' : ' — leave empty to keep the current one') + ' — becomes the profile picture</label><input id="' + p + 'Photo" type="file" accept="image/jpeg,image/png"></div>'
    + '<div class="field"><label>Aadhaar card (JPG/PNG/PDF, max 5 MB)' + (create ? ' — required' : ' — leave empty to keep the current one') + '</label><input id="' + p + 'AFile" type="file" accept="image/jpeg,image/png,application/pdf"></div></div>';
}
function invFormData(p, create) {
  const v = id => { const e = $('#' + p + id); return e ? e.value : ''; };
  ensureLocation(p + 'State', p + 'District', 'the investigator');
  const d = { username: v('User'), password: v('Pass'), role: 'INVESTIGATOR', fullName: v('Name'), mobile: v('Mobile'), state: v('State'), district: v('District'),
    ownerAdmin: v('Owner'), aadhar: v('Aadhar'), pan: v('Pan'), dl: v('Dl'), bankName: v('Bank'), bankAcc: v('Acc'), ifsc: v('Ifsc') };
  const ph = $('#' + p + 'Photo').files[0], af = $('#' + p + 'AFile').files[0];
  if (create && !ph) throw new Error('Please upload the passport-size photo.');
  if (create && !af) throw new Error('Please upload the Aadhaar card.');
  if (ph && ph.size > 2 * 1024 * 1024) throw new Error('Photo is larger than 2 MB.');
  if (af && af.size > 5 * 1024 * 1024) throw new Error('Aadhaar upload is larger than 5 MB.');
  const fd = new FormData();
  fd.append('data', new Blob([JSON.stringify(d)], { type: 'application/json' }));
  if (ph) fd.append('photo', ph);
  if (af) fd.append('aadharFile', af);
  return fd;
}
async function ownerChoices() {
  if (ME.role !== 'MAIN_ADMIN') return null;
  const sa = await api('/api/users?role=STATE_ADMIN');
  return [{ username: ME.username, fullName: 'Main admin (myself)', state: '' }].concat(sa);
}
async function vCreateInv() {
  const owners = await ownerChoices();
  setView('<div class="toolbar"><h2>Create investigator</h2></div><div class="card">'
    + (isAdmin() ? '' : '<div class="alert amber">After you submit, the admin reviews these details and either approves the login or sends it back to you for changes.</div>')
    + invFormHtml('ci', null, owners) + '<div class="err" id="ciErr"></div>'
    + '<div class="btn-row"><button class="btn brick" data-act="createInv">' + (isAdmin() ? 'Create investigator' : 'Submit for admin approval') + '</button></div></div>');
}
A.createInv = async () => {
  $('#ciErr').textContent = '';
  try { await api('/api/users', 'POST', invFormData('ci', true), true); }
  catch (e) { $('#ciErr').textContent = e.message; return; }
  toast(isAdmin() ? 'Investigator created.' : 'Submitted — waiting for admin approval.'); await vCreateInv(); refreshBadges();
};
A.editUser = async u => {
  const x = await api('/api/users/' + enc(u));
  dialog('Edit investigator — ' + x.username, invFormHtml('ei', x, null), isAdmin() ? 'Save changes' : 'Save & re-submit for approval',
    async () => { await api('/api/users/' + enc(u), 'PUT', invFormData('ei', false), true); toast(isAdmin() ? 'Saved.' : 'Re-submitted for approval.'); await vInvestigators(); refreshBadges(); }, true);
};

/* ---------------------------------------------------------------- office assistants / state admins */
async function vSimpleUsers(role) {
  const label = role === 'STATE_ADMIN' ? 'State admins' : 'Office assistants';
  const list = await api('/api/users?role=' + role);
  const owners = role === 'OFFICE_ASSISTANT' ? await ownerChoices() : null;
  setView('<div class="toolbar"><h2>' + label + '</h2></div><div class="card">' + (list.length ? '<div class="tablewrap"><table><thead><tr><th>Name</th><th>Username</th>' + (role === 'STATE_ADMIN' ? '<th>State / district</th>' : '<th>Admin</th><th>State / district</th>') + '<th>Mobile</th><th>Status</th><th></th></tr></thead><tbody>'
    + list.map(u => '<tr><td>' + esc(u.fullName) + '</td><td class="mono">' + esc(u.username) + '</td>'
      + (role === 'STATE_ADMIN' ? '<td>' + esc([u.state, u.district].filter(Boolean).join(', ')) + '</td>' : '<td>' + esc(u.ownerAdmin) + '</td><td>' + esc([u.state, u.district].filter(Boolean).join(', ')) + '</td>')
      + '<td>' + esc(u.mobile) + '</td>'
      + '<td>' + (u.active ? '<span class="badge done">Active</span>' : '<span class="badge back">Deactivated</span>') + '</td><td style="display:flex;gap:6px;">'
      + (ME.role === 'MAIN_ADMIN' || (role === 'OFFICE_ASSISTANT' && ME.role === 'STATE_ADMIN') ? '<button class="btn sm ghost" data-act="editSimpleUser" data-arg="' + esc(u.username) + '">Edit</button>' : '')
      + '<button class="btn sm ghost" data-act="resetPw" data-arg="' + esc(u.username) + '">Reset password</button>'
      + '<button class="btn sm ghost" data-act="toggleActive" data-arg="' + esc(u.username) + '|' + (u.active ? 'false' : 'true') + '">' + (u.active ? 'Deactivate' : 'Activate') + '</button></td></tr>').join('') + '</tbody></table></div>' : '<div class="empty">None yet.</div>') + '</div>'
    + '<div class="card"><h3>Add ' + (role === 'STATE_ADMIN' ? 'a state admin' : 'an office assistant') + '</h3>'
    + (owners ? '<div class="field"><label>Belongs to admin</label><select id="suOwner">' + owners.map(o => '<option value="' + esc(o.username) + '">' + esc(o.fullName) + (o.state ? ' (' + esc(o.state) + ')' : '') + '</option>').join('') + '</select></div>' : '')
    + '<div class="row">' + field('suName', 'Full name', '', 'text', 'maxlength="120"') + field('suUser', 'Username', '', 'text', 'maxlength="40" autocomplete="off"') + field('suPass', 'Temporary password', '', 'text', 'autocomplete="off"') + '</div>'
    + '<div class="row">' + field('suMobile', 'Mobile number', '', 'text', 'maxlength="15"') + stateField('suState', 'State', '', 'suDistrict') + districtField('suDistrict', 'District', '', '') + '</div>'
    + '<div class="field"><label for="suPhoto">Profile photo (JPG or PNG, max 2 MB — optional)</label><input id="suPhoto" type="file" accept="image/jpeg,image/png"></div>'
    + '<div class="hint">They are forced to choose their own password at first login.</div><div class="err" id="suErr"></div>'
    + '<div class="btn-row"><button class="btn brick" data-act="createSimple" data-arg="' + role + '">Create</button></div></div>');
}
A.createSimple = async role => {
  const v = id => { const e = $('#' + id); return e ? e.value : ''; };
  $('#suErr').textContent = '';
  try { ensureLocation('suState', 'suDistrict', 'the user'); } catch (e) { $('#suErr').textContent = e.message; return; }
  const d = { username: v('suUser'), password: v('suPass'), role, fullName: v('suName'), mobile: v('suMobile'), state: v('suState'), district: v('suDistrict'), ownerAdmin: v('suOwner') };
  const photo = $('#suPhoto').files[0];
  if (photo && photo.size > 2 * 1024 * 1024) { $('#suErr').textContent = 'Photo is larger than 2 MB.'; return; }
  const fd = new FormData(); fd.append('data', new Blob([JSON.stringify(d)], { type: 'application/json' }));
  if (photo) fd.append('photo', photo);
  try { await api('/api/users', 'POST', fd, true); } catch (e) { $('#suErr').textContent = e.message; return; }
  toast('Created.'); await vSimpleUsers(role);
};
A.editSimpleUser = async username => {
  const user = await api('/api/users/' + enc(username));
  const body = '<div class="row">' + field('euName', 'Full name', user.fullName, 'text', 'maxlength="120"')
    + field('euMobile', 'Mobile number', user.mobile, 'text', 'maxlength="15"') + '</div>'
    + locationRow('euState', 'State', user.state, 'euDistrict', 'District', user.district);
  dialog('Edit ' + (user.role === 'STATE_ADMIN' ? 'State Admin' : 'Office Assistant') + ' — ' + user.username, body, 'Save changes', async () => {
    ensureLocation('euState', 'euDistrict', 'the user');
    const data = {
      username: user.username, role: user.role, fullName: $('#euName').value, mobile: $('#euMobile').value,
      state: $('#euState').value, district: $('#euDistrict').value, ownerAdmin: user.ownerAdmin,
      aadhar: '', pan: '', dl: '', bankName: '', bankAcc: '', ifsc: ''
    };
    const form = new FormData();
    form.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }));
    await api('/api/users/' + enc(username), 'PUT', form, true);
    toast('User details updated.');
    await vSimpleUsers(user.role);
  });
};

/* ---------------------------------------------------------------- audit log (main admin) */
async function vAudit() {
  const rows = await api('/api/audit?limit=300');
  setView('<div class="toolbar"><h2>Audit log</h2></div><div class="card"><div class="hint" style="margin-bottom:8px;">Logins, password views/resets, approvals, case actions and exports. Latest 300 entries.</div><div class="tablewrap"><table><thead><tr><th>When</th><th>Who</th><th>Action</th><th>Target</th><th>Detail</th><th>IP</th></tr></thead><tbody>'
    + rows.map(r => '<tr><td>' + esc(new Date(r.at).toLocaleString()) + '</td><td>' + esc(r.actor) + '</td><td>' + esc(r.action) + '</td><td>' + esc(r.target) + '</td><td style="white-space:normal;">' + esc(r.detail) + '</td><td class="mono">' + esc(r.ip) + '</td></tr>').join('') + '</tbody></table></div></div>');
}

/* ---------------------------------------------------------------- letters + documents (investigator) */
const blank = w => '<span class="blank" style="display:inline-block;min-width:' + (w || 90) + 'px;">&nbsp;</span>';
const val = id => { const e = $('#' + id); return e ? e.value : ''; };
function caseSelect(id, fn) {
  return '<div class="field" style="max-width:420px;"><label>Select case</label><select id="' + id + '" data-change="' + fn + '"><option value="">-- choose case --</option>'
    + LETTER_CASES.map(c => '<option value="' + c.id + '">' + esc(c.tmcNumber) + ' — ' + esc(c.patientName) + '</option>').join('') + '</select></div>';
}
const letterButtons = (textId, docId) => '<div class="btn-row"><button class="btn ghost" data-act="copyLetter" data-arg="' + textId + '">Copy text</button><button class="btn brick" data-act="printLetter" data-arg="' + docId + '">Print / Save as PDF</button></div>';
async function vWithdrawal() {
  LETTER_CASES = await api('/api/cases');
  setView('<div class="toolbar"><h2>Withdrawal Letter</h2></div><div class="card">' + caseSelect('wdCase', 'wdPick')
    + '<div class="row">' + field('wdPlace', 'Place', '', 'text', 'data-input="wdRender"') + field('wdDate', 'Letter date', today(), 'date', 'data-change="wdRender"') + field('wdPolicy', 'Policy number', '', 'text', 'data-input="wdRender"') + field('wdClaim', 'Claim number', '', 'text', 'data-input="wdRender"') + '</div>'
    + letterButtons('wdText', 'wdDoc') + '</div><div class="card"><div class="letter-doc" id="wdDoc"><div class="empty">Select a case to generate the letter.</div></div></div>');
}
A.wdPick = () => {
  const c = LETTER_CASES.find(x => String(x.id) === val('wdCase')); if (!c) { $('#wdDoc').innerHTML = '<div class="empty">Select a case to generate the letter.</div>'; return; }
  $('#wdPlace').value = [c.village, c.district].filter(Boolean).join(', '); $('#wdPolicy').value = c.policyNo || ''; $('#wdClaim').value = c.claimNo || ''; A.wdRender();
};
A.wdRender = () => {
  const c = LETTER_CASES.find(x => String(x.id) === val('wdCase')); if (!c) return;
  $('#wdDoc').innerHTML = '<div id="wdText"><div class="letter-hdr"><div class="co">Health claim Investigation<small>Claims Investigation &amp; Allied Services</small></div></div>'
    + '<p>Place: ' + (esc(val('wdPlace')) || blank(140)) + '</p><p>Date: ' + (fmtDate(val('wdDate')) || blank(140)) + '</p><br>'
    + (c.insuranceCompany ? '<p>To,<br>The Claims Manager,<br>' + esc(c.insuranceCompany) + '</p><br>' : '') + '<p>Dear Sir/Madam,</p>'
    + '<p>I, ' + (esc(c.patientName) || blank(220)) + ', holder of Policy Number ' + (esc(val('wdPolicy')) || blank(150)) + ', hereby voluntarily withdraw my health insurance claim Number ' + (esc(val('wdClaim')) || blank(150))
    + ' submitted for treatment at ' + (esc(c.hospitalName) || blank(160)) + ' Hospital on ' + (fmtDate(c.doa) || blank(120)) + '.</p>'
    + '<p>I acknowledge that certain details submitted in support of my claim were not accurate and may have constituted a misrepresentation of the facts. I regret this oversight and understand that it could be considered an intentional breach of the policy terms. In light of this, I formally request to withdraw my claim and agree that no further action be pursued.</p>'
    + '<p>Kindly acknowledge receipt of this withdrawal and confirm the closure of the claim.</p><p>Thank you for your understanding.</p><p>Sincerely,</p><br><br><p>________________________<br>' + (esc(c.patientName) || 'Patient signature') + '</p></div>';
};
async function vFraud() {
  LETTER_CASES = await api('/api/cases');
  setView('<div class="toolbar"><h2>Fraud Letter</h2></div><div class="card">' + caseSelect('frCase', 'frPick')
    + '<div class="row">' + field('frAge', 'Patient age', '', 'text', 'data-input="frRender"') + field('frIp', 'IP number', '', 'text', 'data-input="frRender"') + '</div>'
    + '<div class="row">' + field('frSigner', 'Signing officer name (hospital)', '', 'text', 'data-input="frRender"') + field('frDes', 'Designation', '', 'text', 'data-input="frRender"') + field('frMobile', 'Mobile number', '', 'text', 'data-input="frRender"') + '</div>'
    + letterButtons('frText', 'frDoc') + '</div><div class="card"><div class="letter-doc" id="frDoc"><div class="empty">Select a case to generate the letter.</div></div></div>');
}
A.frPick = () => {
  const c = LETTER_CASES.find(x => String(x.id) === val('frCase')); if (!c) { $('#frDoc').innerHTML = '<div class="empty">Select a case to generate the letter.</div>'; return; }
  $('#frAge').value = c.patientAge || ''; $('#frIp').value = c.ipNo || ''; A.frRender();
};
A.frRender = () => {
  const c = LETTER_CASES.find(x => String(x.id) === val('frCase')); if (!c) return;
  $('#frDoc').innerHTML = '<div id="frText"><div class="letter-hdr"><div class="co">Health claim Investigation<small>Claims Investigation &amp; Allied Services</small></div></div>'
    + '<p style="text-align:center;font-weight:700;">TO WHOM SO EVER IT MAY CONCERN</p><br>'
    + '<p>This is to certify after verifying our complete records that Mr ' + (esc(c.patientName) || blank(180)) + ' aged ' + (esc(val('frAge')) || blank(50)) + ' was not admitted in our hospital with IP No: ' + (esc(val('frIp')) || blank(100))
    + ' DOA: ' + (fmtDate(c.doa) || blank(100)) + ' &amp; DOD: ' + (fmtDate(c.dod) || blank(100)) + (c.insuranceCompany ? ' (for the claim under ' + esc(c.insuranceCompany) + ')' : '') + '.</p>'
    + '<p>The documents shown by your Field Officer Mr ' + (esc(ME.fullName || c.investigatorName) || blank(160)) + ' were not issued by us.</p><p>It is a fraud claim.</p>'
    + '<p>Request you to consider this void and null and kindly cancel this claim.</p><p>Yours Faithfully</p><br><br>'
    + '<p>Mr ' + (esc(val('frSigner')) || blank(160)) + '<br>Designation: ' + (esc(val('frDes')) || blank(160)) + '<br>Mobile Number: ' + (esc(val('frMobile')) || blank(140)) + '</p><br><br><p>Hospital Stamp and Seal</p></div>';
};
A.copyLetter = id => { const el = document.getElementById(id); if (!el) { toast('Select a case first.', true); return; }
  navigator.clipboard.writeText(el.innerText).then(() => toast('Letter text copied.')).catch(() => toast('Could not copy automatically — select and copy manually.', true)); };
A.printLetter = docId => { const d = document.getElementById(docId); if (!d || !d.querySelector('p')) { toast('Select a case first.', true); return; } d.id = 'printableLetter'; window.print(); d.id = docId; };
function vDoc(title, url) {
  setView('<div class="toolbar"><h2>' + esc(title) + '</h2></div><div class="card"><div class="doc-download"><div><div class="name">' + esc(title) + '</div><div class="sub">Original PDF template — print and fill manually</div></div>'
    + '<a class="btn brick" href="' + url + '" download>Download PDF</a></div></div>');
}

/* ---------------------------------------------------------------- boot */
(async function boot() {
  try {
    const locationsResponse = await fetch('/india-states-districts.json', { credentials: 'same-origin' });
    if (!locationsResponse.ok) throw new Error('Could not load the state and district list.');
    INDIA_LOCATIONS = await locationsResponse.json();
    if (!Array.isArray(INDIA_LOCATIONS) || !INDIA_LOCATIONS.length) throw new Error('The state and district list is invalid.');
    const me = await api('/api/auth/me');
    if (me && me.authenticated) { ME = me; await enterApp(); } else showLogin('');
  }
  catch (e) { showLogin(e.message); }
  finally { setPageLoading(false); }
})();
