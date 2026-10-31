/* AcciSafe RDC council console. Talks to the same API as the phone app (/api/v1). */
"use strict";

const API = "/api/v1";
const POLL_MS = 20000;
const CLOSED = ["RESOLVED", "CLOSED", "CANCELLED", "COMPLETED", "REJECTED", "UNABLE_TO_RESPOND"];

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const human = (s) => s ? s.charAt(0) + s.slice(1).toLowerCase().replace(/_/g, " ") : "";
const when = (iso) => iso ? new Date(iso).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—";
const tri = (v) => v === "YES" ? "Yes" : v === "NO" ? "No" : v === "UNKNOWN" ? "Don't know" : "Not answered";
function ago(iso) {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)} min ago`;
  if (s < 86400) return `${Math.round(s / 3600)} h ago`;
  return `${Math.round(s / 86400)} d ago`;
}

// --- session -------------------------------------------------------------------------------
// Tokens live in sessionStorage: closing the browser window signs the user out.
const store = {
  get(k) { try { return JSON.parse(sessionStorage.getItem(k)); } catch { return null; } },
  set(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, JSON.stringify(v)); } catch { /* storage blocked */ } },
};
let session = store.get("accisafe.session");   // {access, refresh, user}
let refreshing = null;

async function refreshTokens() {
  if (!refreshing) {
    refreshing = (async () => {
      const r = await fetch(`${API}/auth/refresh`, { method: "POST", headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ refresh_token: session.refresh }) });
      if (!r.ok) throw new Error("Your session has ended. Please sign in again.");
      const t = await r.json();
      session = { access: t.access_token, refresh: t.refresh_token, user: t.user };
      store.set("accisafe.session", session);
    })().finally(() => { refreshing = null; });
  }
  return refreshing;
}

async function api(path, opts = {}, retry = true) {
  const headers = { ...(opts.headers || {}) };
  if (session) headers.Authorization = `Bearer ${session.access}`;
  if (opts.body && !(opts.body instanceof FormData)) { headers["Content-Type"] = "application/json"; opts.body = JSON.stringify(opts.body); }
  const r = await fetch(API + path, { ...opts, headers });
  if (r.status === 401 && session && retry) {
    try { await refreshTokens(); } catch (e) { signOut(e.message); throw e; }
    return api(path, opts, false);
  }
  if (!r.ok) {
    let msg = `Request failed (${r.status})`;
    try { msg = (await r.json()).detail || msg; } catch { /* not JSON */ }
    throw new Error(msg);
  }
  const type = r.headers.get("content-type") || "";
  return type.includes("json") ? r.json() : r;
}

const can = (p) => session?.user?.permissions?.includes(p);

// --- sign in / out ----------------------------------------------------------------------------
$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const btn = $("login-btn");
  btn.disabled = true; btn.textContent = "Signing in…";
  $("login-error").classList.add("hidden");
  try {
    const r = await fetch(`${API}/auth/login`, { method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ council_code: $("l-council").value.trim(), username: $("l-user").value.trim(),
        password: $("l-pass").value, device: "Desktop console" }) });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(body.detail || "Sign-in failed");
    session = { access: body.access_token, refresh: body.refresh_token, user: body.user };
    store.set("accisafe.session", session);
    $("l-pass").value = "";
    start();
  } catch (err) {
    $("login-error").textContent = err.message === "Failed to fetch" ? "Cannot reach the server. Check the internet connection." : err.message;
    $("login-error").classList.remove("hidden");
  } finally {
    btn.disabled = false; btn.textContent = "Sign in";
  }
});

$("logout").addEventListener("click", async () => {
  try { await fetch(`${API}/auth/logout`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refresh_token: session.refresh }) }); } catch { /* offline */ }
  signOut();
});

function signOut(message) {
  session = null; store.set("accisafe.session", null);
  clearInterval(pollTimer);
  $("app").classList.add("hidden"); $("drawer").classList.add("hidden");
  $("login").classList.remove("hidden");
  if (message) { $("login-error").textContent = message; $("login-error").classList.remove("hidden"); }
}

// --- navigation ---------------------------------------------------------------------------------
let view = "map";
document.querySelectorAll("#tabs button").forEach((b) => b.addEventListener("click", () => show(b.dataset.view)));
function show(v) {
  view = v;
  document.querySelectorAll("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.view === v));
  ["map", "list", "dash"].forEach((x) => $(`view-${x}`).classList.toggle("hidden", x !== v));
  if (v === "map") map.invalidateSize();
  if (v === "list") loadList();
  if (v === "dash") loadDash();
}

// --- live map -------------------------------------------------------------------------------------
const map = L.map("map", { zoomControl: true, preferCanvas: false }).setView([-17.80, 31.84], 10);
// Base maps: real satellite photos (default) with road and place labels, or the street map.
const ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services";
const satellite = L.layerGroup([
  L.tileLayer(`${ESRI}/World_Imagery/MapServer/tile/{z}/{y}/{x}`, {
    maxZoom: 19, maxNativeZoom: 18, attribution: "Imagery © Esri, Maxar, Earthstar Geographics",
  }),
  L.tileLayer(`${ESRI}/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}`, { maxZoom: 19, maxNativeZoom: 18 }),
  L.tileLayer(`${ESRI}/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}`, { maxZoom: 19, maxNativeZoom: 18 }),
]);
const streets = L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
  maxZoom: 19, attribution: "Map © OpenStreetMap contributors. Rural roads may be incomplete.",
});
let baseName = "Satellite";
try { baseName = localStorage.getItem("accisafe.basemap") || "Satellite"; } catch { /* storage blocked */ }
(baseName === "Map" ? streets : satellite).addTo(map);
L.control.layers({ Satellite: satellite, Map: streets }, null, { position: "bottomright", collapsed: false }).addTo(map);
map.on("baselayerchange", (e) => { try { localStorage.setItem("accisafe.basemap", e.name); } catch { /* storage blocked */ } });
const markers = L.layerGroup().addTo(map);
let boundaryLayer = null;
let live = null;            // last /map/live response
let hiddenTypes = new Set();
let seen = null;            // incident ids already shown, for "new report" alerts
let pollTimer = null;
let fitted = false;

$("period").addEventListener("change", () => { fitted = false; loadMap(); });
$("active-only").addEventListener("change", loadMap);

async function loadMap() {
  try {
    const data = await api(`/map/live?days=${$("period").value}&active_only=${$("active-only").checked}`);
    live = data;
    drawBoundary(data.council);
    renderMap();
    alertNew(data.incidents);
    $("updated").textContent = `Live · updated ${new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" })}`;
    document.querySelector(".live").classList.remove("stale");
  } catch (e) {
    $("updated").textContent = "Offline · retrying";
    document.querySelector(".live").classList.add("stale");
  }
}

function drawBoundary(council) {
  if (boundaryLayer || !council.boundary) return;
  boundaryLayer = L.geoJSON(council.boundary, {
    style: { color: "#7CFFA8", weight: 3, fillColor: "#2E9E5B", fillOpacity: 0.04, dashArray: "8 5" },
    interactive: false,
  }).addTo(map);
  if (!fitted) map.fitBounds(boundaryLayer.getBounds(), { padding: [20, 20] });
  fitted = true;
}

function pinIcon(inc) {
  const fresh = inc.active && Date.now() - new Date(inc.received_at).getTime() < 60 * 60 * 1000;
  const cam = inc.photo_ids.length ? `<span class="cam">📷${inc.photo_ids.length}</span>` : "";
  return L.divIcon({
    className: "",
    html: `<div class="pin ${inc.active ? "" : "done"} ${fresh ? "new" : ""}" style="background:${inc.color};color:${inc.color}">${cam}</div>`,
    iconSize: [22, 22], iconAnchor: [11, 11],
  });
}

function renderMap() {
  markers.clearLayers();
  const counts = {};
  const visible = live.incidents.filter((i) => !hiddenTypes.has(i.type));
  live.incidents.forEach((i) => { counts[i.type] = (counts[i.type] || 0) + 1; });
  // Draw resolved first so open incidents sit on top.
  [...visible].sort((a, b) => a.active - b.active).forEach((inc) => {
    L.marker([inc.latitude, inc.longitude], { icon: pinIcon(inc), title: `${inc.category} · ${inc.reference}`, riseOnHover: true })
      .on("click", () => openIncident(inc.id))
      .bindTooltip(`<b>${esc(inc.category)}</b><br>${esc(human(inc.status))} · ${ago(inc.received_at)}`, { direction: "top", offset: [0, -10] })
      .addTo(markers);
  });
  $("legend-list").innerHTML = live.legend.map((l) => `
    <li data-type="${l.code}" class="${hiddenTypes.has(l.code) ? "off" : ""}">
      <span class="dot" style="background:${l.color}"></span>${esc(l.label)}<span class="n">${counts[l.code] || 0}</span>
    </li>`).join("");
  $("legend-list").querySelectorAll("li").forEach((li) => li.addEventListener("click", () => {
    const t = li.dataset.type;
    hiddenTypes.has(t) ? hiddenTypes.delete(t) : hiddenTypes.add(t);
    renderMap();
  }));
  const open = live.incidents.filter((i) => i.active).length;
  $("map-count").textContent = `${live.incidents.length} on map · ${open} open`;
  $("feed-list").innerHTML = live.incidents.slice(0, 25).map((i) => `
    <li data-id="${i.id}"><span class="dot" style="background:${i.color}"></span>
      <div><div class="t">${esc(i.category)}</div>
      <div class="s">${esc(i.ward || i.landmark || "GPS position")} · ${ago(i.received_at)}${i.photo_ids.length ? ` · 📷 ${i.photo_ids.length}` : ""}</div>
      <div class="s">${esc(human(i.status))}</div></div></li>`).join("") || `<li class="muted">No reports in this period.</li>`;
  $("feed-list").querySelectorAll("li[data-id]").forEach((li) => li.addEventListener("click", () => {
    const inc = live.incidents.find((x) => x.id === +li.dataset.id);
    map.setView([inc.latitude, inc.longitude], Math.max(map.getZoom(), 13));
    openIncident(inc.id);
  }));
}

function alertNew(incidents) {
  const ids = new Set(incidents.map((i) => i.id));
  if (seen) {
    incidents.filter((i) => !seen.has(i.id)).forEach((i) => {
      toast(`New report: ${i.category}`, `${i.ward || i.landmark || "GPS position"} · ${human(i.status)}`, () => openIncident(i.id));
      beep();
      if ("Notification" in window && Notification.permission === "granted") {
        new Notification(`AcciSafe RDC: ${i.category}`, { body: `${i.reference} · ${i.ward || i.landmark || ""}`, icon: "icon.svg" });
      }
    });
  }
  seen = new Set([...(seen || []), ...ids]);
}

function beep() {
  try {
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const o = ctx.createOscillator(); const g = ctx.createGain();
    o.frequency.value = 880; g.gain.value = 0.08; o.connect(g); g.connect(ctx.destination);
    o.start(); o.stop(ctx.currentTime + 0.25);
  } catch { /* audio blocked until the user interacts */ }
}

function toast(title, text, onClick) {
  const el = document.createElement("div");
  el.className = "toast";
  el.innerHTML = `<b>${esc(title)}</b>${esc(text)}`;
  el.addEventListener("click", () => { onClick?.(); el.remove(); });
  $("toasts").appendChild(el);
  setTimeout(() => el.remove(), 12000);
}

// --- incident drawer -------------------------------------------------------------------------------
let current = null;     // {id, version}
let photoUrls = [];

$("drawer-close").addEventListener("click", closeDrawer);
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape") { if (!$("lightbox").classList.contains("hidden")) closeLightbox(); else closeDrawer(); }
  if (!$("lightbox").classList.contains("hidden")) { if (e.key === "ArrowLeft") stepLightbox(-1); if (e.key === "ArrowRight") stepLightbox(1); }
});
function closeDrawer() { $("drawer").classList.add("hidden"); current = null; photoUrls.forEach(URL.revokeObjectURL); photoUrls = []; }

async function openIncident(id) {
  const mapItem = live?.incidents.find((i) => i.id === id);
  $("drawer").classList.remove("hidden");
  $("drawer-body").innerHTML = `<div class="d-head" style="background:var(--gradient)"><h2>Loading…</h2></div>`;
  let d = null;
  try { d = await api(`/incidents/${id}`); } catch { /* not visible in full to this role: use the map summary */ }
  const inc = { ...(mapItem || {}), ...(d || {}) };
  if (!inc.id) { $("drawer-body").innerHTML = `<div class="d-sec">This incident could not be loaded.</div>`; return; }
  current = { id, version: inc.version };
  const color = mapItem?.color || "#14532D";
  const label = live?.legend.find((l) => l.code === mapItem?.type)?.label;
  const photoIds = d ? d.attachments.filter((a) => a.content_type.startsWith("image/")).map((a) => a.id) : (mapItem?.photo_ids || []);
  const prio = inc.priority || inc.suggested_priority;

  let html = `
    <div class="d-head" style="background:linear-gradient(135deg, ${color}, #0B3D20)">
      <span class="chip">${esc(label || inc.kind)}</span>
      <h2>${esc(inc.category)}</h2>
      <div>${esc(inc.reference)} · ${esc(human(inc.status))}${prio ? ` · ${esc(human(prio))} priority` : ""}</div>
    </div>
    <div class="d-sec"><h3>Photos (${photoIds.length})</h3>
      ${photoIds.length ? `<div class="gallery" id="gallery">${photoIds.map((p, n) => `<img data-n="${n}" alt="Photo ${n + 1}">`).join("")}</div>`
                        : `<p class="muted">No photos were added to this report.</p>`}
    </div>
    <div class="d-sec"><h3>What happened</h3><p>${esc(inc.description)}</p>
      <dl class="kv">
        <dt>Deaths reported</dt><dd>${tri(inc.fatalities)}</dd>
        <dt>Injuries</dt><dd>${tri(inc.injuries)}</dd>
        ${inc.immediate_danger !== undefined ? `<dt>Immediate danger</dt><dd>${tri(inc.immediate_danger)}</dd>` : ""}
        ${inc.threat_to_life !== undefined ? `<dt>Threat to life</dt><dd>${tri(inc.threat_to_life)}</dd>` : ""}
        <dt>People affected</dt><dd>${inc.affected_people ?? (inc.affected_people_unknown ? "Don't know" : "Not answered")}</dd>
        <dt>Reported</dt><dd>${when(inc.reported_at)} (${ago(inc.received_at)})</dd>
      </dl>
    </div>
    <div class="d-sec"><h3>Where</h3>
      <dl class="kv">
        <dt>Ward</dt><dd>${esc(inc.ward || "Not recorded")}</dd>
        <dt>Landmark</dt><dd>${esc(inc.landmark || "—")}</dd>
        <dt>GPS</dt><dd>${inc.latitude != null ? `<a href="https://www.openstreetmap.org/?mlat=${inc.latitude}&mlon=${inc.longitude}#map=16/${inc.latitude}/${inc.longitude}" target="_blank" rel="noopener">${inc.latitude.toFixed(5)}, ${inc.longitude.toFixed(5)}</a>` : "No GPS position"}</dd>
      </dl>
    </div>`;
  if (d?.reporter_name || d?.reporter_phone) {
    html += `<div class="d-sec"><h3>Reporter (confidential)</h3><dl class="kv">
      <dt>Name</dt><dd>${esc(d.reporter_name || "—")}</dd><dt>Phone</dt><dd>${d.reporter_phone ? `<a href="tel:${esc(d.reporter_phone)}">${esc(d.reporter_phone)}</a>` : "—"}</dd></dl></div>`;
  }
  if (d) html += actionsHtml(d);
  if (d?.assignments?.length) {
    html += `<div class="d-sec"><h3>Teams</h3>${d.assignments.map((a) => `<p><b>${esc(a.team)}</b> · ${esc(human(a.status))}${a.instructions ? `<br><span class="muted">${esc(a.instructions)}</span>` : ""}</p>`).join("")}</div>`;
  }
  if (d?.updates?.length) {
    html += `<div class="d-sec"><h3>Field updates</h3>${d.updates.map((u) => `<p><b>${esc(u.by || "")}</b> · ${when(u.at)}<br>${esc(u.text)}</p>`).join("")}</div>`;
  }
  if (d?.status_history?.length) {
    html += `<div class="d-sec"><h3>History</h3><ul class="timeline">${d.status_history.map((h) =>
      `<li><b>${esc(human(h.to))}</b> · ${when(h.at)}${h.by ? ` · ${esc(h.by)}` : ""}${h.note ? `<br><span class="muted">${esc(h.note)}</span>` : ""}</li>`).join("")}</ul></div>`;
  }
  $("drawer-body").innerHTML = html;
  bindActions(d);
  loadPhotos(photoIds);
}

async function loadPhotos(ids) {
  photoUrls = [];
  const imgs = [...document.querySelectorAll("#gallery img")];
  await Promise.all(ids.map(async (pid, n) => {
    try {
      const r = await api(`/map/photos/${pid}`);
      const url = URL.createObjectURL(await r.blob());
      photoUrls[n] = url;
      imgs[n].src = url;
      imgs[n].addEventListener("click", () => openLightbox(n));
    } catch { imgs[n].alt = "Photo could not be loaded"; }
  }));
}

let lbIndex = 0;
function openLightbox(n) { lbIndex = n; $("lightbox").classList.remove("hidden"); stepLightbox(0); }
function closeLightbox() { $("lightbox").classList.add("hidden"); }
function stepLightbox(d) {
  const urls = photoUrls.filter(Boolean);
  if (!urls.length) return;
  lbIndex = (lbIndex + d + urls.length) % urls.length;
  $("lb-img").src = urls[lbIndex];
  $("lb-caption").textContent = `Photo ${lbIndex + 1} of ${urls.length}`;
}
$("lb-close").addEventListener("click", closeLightbox);
$("lb-prev").addEventListener("click", () => stepLightbox(-1));
$("lb-next").addEventListener("click", () => stepLightbox(1));
$("lightbox").addEventListener("click", (e) => { if (e.target.id === "lightbox") closeLightbox(); });

function actionsHtml(d) {
  const parts = [];
  if (d.allowed_status_changes?.length) {
    parts.push(`<div class="actions">${d.allowed_status_changes.map((s) =>
      `<button class="btn ${["CANCELLED", "UNABLE_TO_RESPOND", "REJECTED"].includes(s) ? "danger" : s === "ACKNOWLEDGED" ? "primary" : "soft"}" data-status="${s}">${esc(human(s))}</button>`).join("")}</div>
      <div class="form-row"><textarea id="a-note" placeholder="Note or reason (required for cancel, reject or unable to respond)"></textarea></div>`);
  }
  if (d.can_assess) {
    parts.push(`<div class="form-row"><label class="muted small">Set priority</label>
      <div class="actions"><select id="a-prio">${["CRITICAL", "HIGH", "MEDIUM", "LOW"].map((p) => `<option ${p === (d.priority || d.suggested_priority) ? "selected" : ""}>${p}</option>`).join("")}</select>
      <input id="a-reason" placeholder="Reason (needed when changing)"><button class="btn soft" id="a-assess">Save priority</button></div></div>`);
  }
  if (d.can_assign) {
    parts.push(`<div class="form-row"><label class="muted small">Assign a response team</label>
      <div class="actions"><select id="a-team"><option value="">Loading teams…</option></select>
      <input id="a-instr" placeholder="Instructions"><button class="btn primary" id="a-assign">Assign</button></div></div>`);
  }
  if (!parts.length) return "";
  return `<div class="d-sec"><h3>Council action</h3>${parts.join("")}<p id="a-error" class="error hidden"></p></div>`;
}

function bindActions(d) {
  if (!d) return;
  const fail = (e) => { $("a-error").textContent = e.message; $("a-error").classList.remove("hidden"); };
  const done = async (msg) => { toast(msg, d.reference); await loadMap(); openIncident(d.id); };
  document.querySelectorAll("[data-status]").forEach((b) => b.addEventListener("click", async () => {
    try {
      await api(`/incidents/${d.id}/status`, { method: "PATCH", body: { to: b.dataset.status, note: $("a-note").value.trim() || null, expected_version: current.version } });
      done(`Status changed to ${human(b.dataset.status)}`);
    } catch (e) { fail(e); }
  }));
  $("a-assess")?.addEventListener("click", async () => {
    try {
      await api(`/incidents/${d.id}/assess`, { method: "POST", body: { priority: $("a-prio").value, reason: $("a-reason").value.trim() || null, expected_version: current.version } });
      done("Priority saved");
    } catch (e) { fail(e); }
  });
  if ($("a-team")) {
    api("/teams").then((teams) => {
      $("a-team").innerHTML = teams.filter((t) => t.active).map((t) => `<option value="${t.id}">${esc(t.name)} (${esc(human(t.availability))})</option>`).join("");
    }).catch(fail);
    $("a-assign").addEventListener("click", async () => {
      try {
        await api(`/incidents/${d.id}/assignments`, { method: "POST", body: { team_id: +$("a-team").value, instructions: $("a-instr").value.trim() || null, expected_version: current.version } });
        done("Team assigned");
      } catch (e) { fail(e); }
    });
  }
}

// --- reports table ------------------------------------------------------------------------------------
let page = 1;
let searchTimer = null;
["f-kind", "f-priority", "f-open"].forEach((id) => $(id).addEventListener("change", () => { page = 1; loadList(); }));
$("q").addEventListener("input", () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { page = 1; loadList(); }, 350); });
$("prev").addEventListener("click", () => { if (page > 1) { page--; loadList(); } });
$("next").addEventListener("click", () => { page++; loadList(); });
$("csv").addEventListener("click", async () => {
  try {
    const r = await api("/reports/incidents.csv");
    const a = Object.assign(document.createElement("a"), { href: URL.createObjectURL(await r.blob()), download: "incidents.csv" });
    a.click();
  } catch (e) { toast("Export failed", e.message); }
});

async function loadList() {
  const p = new URLSearchParams({ page, page_size: 25, open_only: $("f-open").checked });
  if ($("q").value.trim()) p.set("q", $("q").value.trim());
  if ($("f-kind").value) p.set("kind", $("f-kind").value);
  if ($("f-priority").value) p.set("priority", $("f-priority").value);
  try {
    const data = await api(`/incidents?${p}`);
    const colors = Object.fromEntries((live?.incidents || []).map((i) => [i.id, i]));
    $("rows").innerHTML = data.items.map((i) => {
      const prio = i.priority || i.suggested_priority;
      const m = colors[i.id];
      return `<tr data-id="${i.id}">
        <td><b>${esc(i.reference)}</b></td>
        <td><span class="chip"><span class="dot" style="background:${m?.color || "#9AA"}"></span>${esc(i.category)}</span></td>
        <td><span class="chip st ${CLOSED.includes(i.status) ? "done" : ""}">${esc(human(i.status))}</span></td>
        <td>${prio ? `<span class="chip ${prio}">${esc(human(prio))}${i.priority ? "" : " (suggested)"}</span>` : `<span class="muted">Not assessed</span>`}</td>
        <td>${esc(i.ward || i.landmark || (i.latitude != null ? "GPS position" : "—"))}</td>
        <td>${when(i.received_at)}</td>
        <td>${m?.photo_ids.length ? `📷 ${m.photo_ids.length}` : ""}</td></tr>`;
    }).join("") || `<tr><td colspan="7" class="muted">No reports match.</td></tr>`;
    $("rows").querySelectorAll("tr[data-id]").forEach((tr) => tr.addEventListener("click", () => openIncident(+tr.dataset.id)));
    const pages = Math.max(1, Math.ceil(data.total / data.page_size));
    $("page-info").textContent = `Page ${data.page} of ${pages} · ${data.total} reports`;
    $("prev").disabled = page <= 1; $("next").disabled = page >= pages;
  } catch (e) { $("rows").innerHTML = `<tr><td colspan="7" class="error">${esc(e.message)}</td></tr>`; }
}

// --- dashboard --------------------------------------------------------------------------------------------
async function loadDash() {
  try {
    const s = await api("/reports/summary");
    const t = s.totals;
    const card = (label, n, alert) => `<div class="card ${alert ? "alert" : ""}"><div class="muted">${label}</div><div class="big">${n}</div></div>`;
    const bars = (title, obj, label = (k) => k) => {
      const entries = Object.entries(obj).slice(0, 10);
      const max = Math.max(1, ...entries.map(([, v]) => v));
      return `<div class="card"><h3>${title}</h3><ul class="bars">${entries.map(([k, v]) =>
        `<li><span>${esc(label(k))}</span><span class="track"><span class="fill" style="width:${(v / max) * 100}%"></span></span><b>${v}</b></li>`).join("") || "<li class='muted'>No data</li>"}</ul></div>`;
    };
    const mins = (x) => x.count ? `${x.median_minutes} min median (${x.count})` : "No data yet";
    $("dash").innerHTML = `
      <div class="cards">
        ${card("Open incidents", t.open)}
        ${card("Awaiting acknowledgement", t.awaiting_acknowledgement, t.awaiting_acknowledgement > 0)}
        ${card("Critical or high, open", t.critical_or_high_open, t.critical_or_high_open > 0)}
        ${card("In progress", t.in_progress)}
        ${card("Resolved", t.resolved)}
        ${card("All reports", t.incidents)}
      </div>
      <div class="panels">
        ${bars("By type", s.by_category)}
        ${bars("By ward", s.by_ward)}
        ${bars("By status", s.by_status, human)}
        <div class="card"><h3>Response times</h3><dl class="kv" style="margin-top:8px">
          <dt>Acknowledgement</dt><dd>${mins(s.timings.acknowledgement)}</dd>
          <dt>Assignment → arrival</dt><dd>${mins(s.timings.assignment_to_arrival)}</dd>
          <dt>Report → resolved</dt><dd>${mins(s.timings.resolution)}</dd></dl></div>
      </div>`;
  } catch (e) { $("dash").innerHTML = `<p class="error">${esc(e.message)}</p>`; }
}

// --- desktop install ---------------------------------------------------------------------------------------
let installPrompt = null;
window.addEventListener("beforeinstallprompt", (e) => { e.preventDefault(); installPrompt = e; $("install-btn").classList.remove("hidden"); });
$("install-btn").addEventListener("click", async () => { if (!installPrompt) return; installPrompt.prompt(); await installPrompt.userChoice; installPrompt = null; $("install-btn").classList.add("hidden"); });
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});

// --- start --------------------------------------------------------------------------------------------------
function start() {
  const u = session.user;
  $("login").classList.add("hidden");
  $("app").classList.remove("hidden");
  $("council-name").textContent = u.council_name;
  $("who").innerHTML = `${esc(u.full_name)}<br><span>${esc(u.roles.map(human).join(", "))}</span>`;
  $("demo-banner").classList.toggle("hidden", !u.council_is_demo);
  $("tab-dash").classList.toggle("hidden", !can("reports.view"));
  $("csv").classList.toggle("hidden", !can("reports.export"));
  seen = null; boundaryLayer?.remove(); boundaryLayer = null; fitted = false;
  show("map");
  loadMap();
  clearInterval(pollTimer);
  pollTimer = setInterval(() => { loadMap(); if (view === "list") loadList(); }, POLL_MS);
  if ("Notification" in window && Notification.permission === "default") Notification.requestPermission().catch(() => {});
}

if (session) start(); else $("login").classList.remove("hidden");
