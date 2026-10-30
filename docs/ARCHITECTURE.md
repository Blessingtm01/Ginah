# AcciSafe RDC – Architecture

## System architecture

```mermaid
flowchart LR
  subgraph Phone["Android app (Kotlin, Compose, MVVM)"]
    UI[Compose screens] --> VM[ViewModels]
    VM --> Repo[Repositories]
    Repo --> Room[(Room: offline queue,\nconfig cache, contacts)]
    Repo --> API[Retrofit + OkHttp\nauth + token refresh]
    WM[WorkManager SyncWorker] --> Room
    WM --> API
    Sensors[Accelerometer, gyroscope, GPS] --> Mon[MonitoringService\n+ AccidentDetector]
    Mon --> Coord[DetectionCoordinator\ncountdown]
    Coord --> Repo
    Coord --> SMSP[Phone SMS to\npersonal contacts]
  end
  API -- HTTPS JSON --> BE
  subgraph Server["Backend (FastAPI)"]
    BE[REST API /api/v1\nRBAC on every endpoint] --> WF[Workflow rules\nstate machine, priority]
    BE --> DB[(PostgreSQL / SQLite)]
    ESC[Escalation worker] --> DB
    ESC --> NOTIF[Notification service]
    BE --> NOTIF
    NOTIF --> SMSA[SMS provider adapter\nlog | http gateway]
    BE --> FILES[(Attachment storage)]
  end
```

**Two separate SMS paths.** The phone sends SMS only to the user's own confirmed personal contacts,
and only after an accident alert is confirmed. Council notifications are sent by the server through
the configured gateway. Neither path calls emergency services.

## Incident workflow (emergency)

```mermaid
stateDiagram-v2
  [*] --> SUBMITTED : report received
  SUBMITTED --> AWAITING_REVIEW : system
  AWAITING_REVIEW --> ACKNOWLEDGED : coordinator
  AWAITING_REVIEW --> CANCELLED : coordinator + reason
  ACKNOWLEDGED --> UNDER_ASSESSMENT : coordinator / assessment
  ACKNOWLEDGED --> ASSIGNED : assignment
  UNDER_ASSESSMENT --> ASSIGNED : assignment
  ASSIGNED --> ACCEPTED : team accepts
  ASSIGNED --> UNDER_ASSESSMENT : team declines (reason)
  ACCEPTED --> DISPATCHED : team (departure time)
  DISPATCHED --> ON_SCENE : team (arrival time)
  ON_SCENE --> ASSISTANCE_REQUESTED : team
  ASSISTANCE_REQUESTED --> ON_SCENE
  ON_SCENE --> RESPONSE_COMPLETED : team
  ON_SCENE --> REFERRED_EXTERNALLY : referral recorded
  ACKNOWLEDGED --> REFERRED_EXTERNALLY : referral recorded
  REFERRED_EXTERNALLY --> RESOLVED : coordinator
  RESPONSE_COMPLETED --> RESOLVED : coordinator
  RESPONSE_COMPLETED --> ON_SCENE : reopened
  UNDER_ASSESSMENT --> UNABLE_TO_RESPOND : coordinator + reason
  RESOLVED --> CLOSED : coordinator
  UNABLE_TO_RESPOND --> CLOSED
  CANCELLED --> CLOSED
```

Routine service requests use: Submitted → Under review → (Inspection required) → Assigned →
Work scheduled → In progress → Completed → Closed, or Rejected with a reason. A coordinator can
escalate an open routine request to an emergency.

The full transition table, including who may perform each transition, is in
`backend/app/workflow.py` and is unit-tested.

## Offline reporting

```mermaid
sequenceDiagram
  participant U as User
  participant A as App (Room)
  participant W as SyncWorker
  participant S as Server
  U->>A: Submit report
  A-->>U: "Saved on this device"
  W->>S: POST /incidents (idempotency_key)
  Note over W,S: connection lost before the reply
  W->>S: retry, same idempotency_key
  S-->>W: 200 existing incident (duplicate=true)
  W->>A: status RECEIVED + reference
  A-->>U: "Received by the server · MRDC-2026-000123"
```

The original device time (`reported_at`) and the server receipt time (`received_at`) are both stored.
Acknowledgement time is measured from `received_at`.

## Database (entity relationships)

```mermaid
erDiagram
  COUNCILS ||--o{ USERS : has
  USERS ||--o{ USER_ROLES : holds
  ROLES ||--o{ USER_ROLES : grants
  USERS ||--o{ REFRESH_TOKENS : has
  COUNCILS ||--o{ WARDS : has
  COUNCILS ||--o{ INCIDENT_CATEGORIES : defines
  COUNCILS ||--o{ INCIDENTS : owns
  INCIDENT_CATEGORIES ||--o{ INCIDENTS : classifies
  WARDS ||--o{ INCIDENTS : locates
  USERS ||--o{ INCIDENTS : reports
  INCIDENTS ||--o{ INCIDENT_STATUS_HISTORY : records
  INCIDENTS ||--o{ INCIDENT_ASSESSMENTS : assessed_by
  INCIDENTS ||--o{ INCIDENT_ATTACHMENTS : has
  INCIDENTS ||--o{ INCIDENT_ASSIGNMENTS : assigned
  RESPONSE_TEAMS ||--o{ INCIDENT_ASSIGNMENTS : receives
  RESPONSE_TEAMS ||--o{ TEAM_MEMBERS : has
  RESPONSE_TEAMS ||--o{ TEAM_AVAILABILITY_LOG : logs
  INCIDENTS ||--o{ RESPONSE_UPDATES : has
  COUNCILS ||--o{ RESOURCES : owns
  RESOURCES ||--o{ RESOURCE_ALLOCATIONS : allocated
  INCIDENTS ||--o{ RESOURCE_ALLOCATIONS : uses
  RESOURCES ||--o{ MAINTENANCE_RECORDS : maintained
  COUNCILS ||--o{ EXTERNAL_ORGANISATIONS : lists
  EXTERNAL_ORGANISATIONS ||--o{ INCIDENT_REFERRALS : receives
  INCIDENTS ||--o{ INCIDENT_REFERRALS : referred
  INCIDENTS ||--o{ NOTIFICATIONS : triggers
  NOTIFICATIONS ||--o{ NOTIFICATION_ATTEMPTS : delivery
  INCIDENTS ||--o{ ESCALATION_EVENTS : escalated
  COUNCILS ||--o{ AUDIT_LOGS : audited
  COUNCILS ||--o{ DETECTION_EVENTS : records
  COUNCILS ||--o{ SYNC_RECORDS : records
```

Routine infrastructure requests are stored as incidents with `kind = ROUTINE`, and they have their own
status set. Every council-owned table has a `council_id` column, so councils stay isolated.

## Security decisions

- Permissions are checked on the server for every request. The app only hides what the server would refuse anyway.
- An incident outside the user's access returns HTTP 404, not 403, so its existence is not revealed.
- Passwords are hashed with scrypt.
- Access tokens last 15 minutes. Refresh tokens are single-use, stored only as hashes, and revoked on password change or deactivation.
- Tokens on the phone are encrypted with an Android Keystore AES-GCM key. Backups are disabled.
- No secrets are kept in the app. SMS credentials exist only as server environment variables.
- Audit logs can only be appended to. No endpoint updates or deletes them.
