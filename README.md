# AcciSafe RDC

Emergency incident reporting, management and response coordination for Zimbabwean Rural District
Councils, with Murewa Rural District Council (MRDC) as the first case study. It extends the AcciSafe
accident-detection app into a multi-user council system.

| Folder | Contents |
|---|---|
| `android/` | Android app: Kotlin, Jetpack Compose, MVVM, Hilt, Room, DataStore, WorkManager, Retrofit |
| `backend/` | REST API: Python, FastAPI, SQLAlchemy, Alembic migrations, JWT auth with RBAC |
| `docs/` | Architecture, database and workflow diagrams; OpenAPI specification (`openapi.json`) |

The original single-user app is unchanged in `..\AcciSafe`.

> AcciSafe RDC does **not** contact police, hospitals or ambulance services automatically. A report,
> notification or referral does not guarantee that help will arrive.

---

## 1. Run the backend (development)

Requires Python 3.11+.

```powershell
cd backend
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
copy .env.example .env            # edit values; never commit real secrets
.venv\Scripts\python -m app.manage init-db       # applies database migrations
.venv\Scripts\python -m app.manage seed-demo     # optional: clearly-labelled DEMO council
$env:ACCISAFE_SECRET_KEY = "dev-only-change-me-0123456789abcdef"
.venv\Scripts\python -m uvicorn app.main:app --host 0.0.0.0 --port 8799
```

- API documentation: <http://localhost:8799/docs> (OpenAPI at `/openapi.json`)
- Health check: <http://localhost:8799/health>
- Tests: `.venv\Scripts\python -m pytest`

**Demo accounts** (council code `DEMO`, password `Demo-Pass-2026`): `admin`, `coordinator`, `manager`,
`roadslead`, `firelead`, `officer`, `reporter`, `auditor`. Every demo record and screen is marked as
demonstration data. Do not seed demo data on a production server.

**Create a real council and administrator:**

```powershell
.venv\Scripts\python -m app.manage create-council MRDC "Murewa Rural District Council"
.venv\Scripts\python -m app.manage create-admin MRDC admin "Full Name"     # prompts for a password
```

## 2. Run the Android app

1. Open the `android` folder in Android Studio and let Gradle sync.
2. Start an emulator. The debug build connects to `http://10.0.2.2:8799/`, which is the host PC as seen from the emulator.
3. On a physical phone on the same Wi-Fi, tap **Development server** on the sign-in screen and enter
   `http://<PC-IP-address>:8799/`. Allow port 8799 through Windows Firewall.
4. Sign in with council code `DEMO` and a demo account, or register as a community reporter.

Command line build: `.\gradlew.bat assembleDebug testDebugUnitTest` →
`android\app\build\outputs\apk\debug\app-debug.apk`. Install with `adb install -r <apk>`.

## 2a. Council desktop console

The server also hosts a browser console for council staff at `/console/`. On Render this is
https://accisafe-rdc-api.onrender.com/console/. Sign in with the same council code, username and password
as on the phone. In Chrome or Edge, **Install app** (or the install icon in the address bar) adds it to the
Windows Start menu and desktop as its own window.

- **Live map:** the council boundary and every report with a GPS position, coloured by the map key.
  Click a dot to see the details and every photo taken at the scene. New reports pop up with a sound.
- **Reports:** a searchable, filterable list of every report, with CSV export for roles that may export.
- **Dashboard:** open, unacknowledged and critical counts, breakdowns by type, ward and status, and response times.
- Coordinators can acknowledge reports, change their status, set priority and assign teams from the detail panel.

Community reporters open the phone app on the same live map (`GET /api/v1/map/live`). It shows every
report in the council area and its photos, but never the reporter's name or phone number.

## 3. Production configuration (summary)

- Use PostgreSQL: `ACCISAFE_DATABASE_URL=postgresql+psycopg://…` (install `psycopg[binary]`), then `init-db`.
- Set `ACCISAFE_ENV=production` and a long random `ACCISAFE_SECRET_KEY`. The server refuses to start without it.
- Serve over HTTPS behind a reverse proxy. Release builds of the app accept HTTPS addresses only.
  Set the release server address in `android/app/build.gradle.kts` (`DEFAULT_SERVER_URL`).
- SMS: `ACCISAFE_SMS_PROVIDER=http` with the gateway URL and token as environment variables, then
  enable SMS for the council (`PATCH /api/v1/config/settings {"sms_enabled": true}`). With the
  default `log` provider, SMS are recorded as **SIMULATED** and nothing is sent.
- Map tiles come from OpenStreetMap. For heavy use, configure a tile provider the council is licensed to use.
- Back up the database and the attachments folder regularly. Assess applicable Zimbabwean
  data-protection and cybersecurity requirements before real deployment.

---

## 4. Feature status

### Implemented and tested

**Backend** (33 automated tests passing)
- Authentication: login, short-lived access tokens, rotating refresh tokens, logout, registration, password change, rate-limited login.
- Seven roles with server-side permission checks on every endpoint, and per-council data isolation.
- Community reporters see only their own reports. Management and auditors do not see reporters' personal details.
- Incidents: duplicate-safe creation (idempotency keys), search and filters, pagination, full detail.
- Controlled lifecycle: only listed transitions are allowed, each by an allowed actor. Optimistic locking returns HTTP 409 on conflicting edits.
- Advisory priority suggestion. Final priority is set by an officer, with recorded reasons and history.
- Assignment, reassignment with a reason, accept or decline with a reason, departure, arrival and completion times, and field updates.
- External referrals, recorded as "not confirmed" until marked otherwise.
- Routine service requests with their own workflow, and escalation to an emergency.
- Escalation engine: per-priority acknowledgement targets, multi-level escalation, each level fires once.
- Notifications: in-app, plus SMS through a provider adapter with one controlled retry and honest statuses (Simulated / Sent / Failed / No recipient).
- Teams, members and availability. Resources, maintenance records, allocation and return.
- Reports computed from records: by category, ward, month, priority, status and source; timings; detection outcomes. CSV export without personal data, with the export audited.
- Append-only audit log with correlation IDs. Photo attachments (JPEG/PNG/WebP, size-limited, access-checked).
- Database migrations (Alembic), structured logs, health check, OpenAPI documentation, demo seed.

**Android** (14 unit tests passing, including contract tests against real server responses)
- Green gradient Material 3 theme with dark mode. Role-specific navigation and home dashboards.
- Sign in, registration, password recovery (shows the council's contact details; no fake reset email), and a development server setting.
- Incident form with GPS (Play services, falling back to LocationManager), ward and landmark, unknown versus not-answered fields, and photos through the system photo picker.
- Offline-first reporting: saved to Room, sent by WorkManager with retries and idempotency keys. Honest submission states, a sync status screen, and retry or discard.
- Incident register with search, filters and pagination. Incident detail with every server-permitted action.
- Team inbox, teams, resources, map (OpenStreetMap), notification centre, and reports with CSV export and PDF generation.
- Accident detection: the existing tested detector with configurable threshold, cooldown and countdown; foreground monitoring with disclosure; full-screen countdown.
  - A confirmed alert queues a council report and SMS to personal contacts that the user has confirmed.
  - Detection outcomes are recorded for false-alarm statistics.
- Simulation mode: no incident is ever created, and no SMS is sent unless a separate, explicit test-SMS confirmation lists the recipients.
- Administration: users and roles, wards and categories, audit log viewer. Privacy information screen.
- Tokens are encrypted with the Android Keystore. Backups are disabled. Release builds allow HTTPS only.

### Partially implemented
- **Offline viewing**: council configuration is cached for offline report forms. Incident lists and details need a connection.
- **Conflict handling**: server-side version checks prevent silent overwrites. The app shows the conflict message and reloads, but has no field-by-field merge screen.
- **Council branding**: name and contact details are configurable through the API. Logo upload is not yet built (there is a `logo_path` field and a placeholder icon).
- **Escalation and SMS settings**: configurable through the API (`PATCH /config/settings`), but not yet from the app.
- **Map**: shows and filters incidents. Adjusting a report's position by dragging a map marker is not yet built (GPS, ward and landmark are used).
- **Translations**: all text is English. Strings are not yet moved into resource files for Shona.
- **External organisations**: managed through the API only.

### Not implemented
- Push notifications (needs a Firebase project and server key). Email notifications.
- Optional live tracking of response teams. Fuel allocation records. Data-retention automation.
- Instrumented Android UI and integration tests (no emulator was available in the development environment).
- Direct integration with ZRP, hospitals, ambulance or Civil Protection systems. This is out of scope without formal approval.

See `docs/ARCHITECTURE.md` for diagrams and design notes.
