#!/usr/bin/env bash
# Start-up for hosted deployments (e.g. Render): migrate, optionally seed the DEMO council, serve.
set -euo pipefail
python -m app.manage init-db
if [ "${ACCISAFE_SEED_DEMO:-false}" = "true" ]; then
  python -m app.manage seed-demo
fi
exec uvicorn app.main:app --host 0.0.0.0 --port "${PORT:-8799}" --proxy-headers --forwarded-allow-ips="*"
