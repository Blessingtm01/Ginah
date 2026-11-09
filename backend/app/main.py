"""AcciSafe RDC backend application."""
import logging
import mimetypes
import os
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from sqlalchemy import text

from .config import settings
from .db import SessionLocal, engine
from .escalation import EscalationWorker
from .routers import admin, auth, incidents, livemap, operations, reports

# Android packages must be served with their own type so phones offer to install them.
mimetypes.add_type("application/vnd.android.package-archive", ".apk")

logging.basicConfig(level=logging.INFO, format='{"time":"%(asctime)s","level":"%(levelname)s","logger":"%(name)s","message":"%(message)s"}')
log = logging.getLogger("accisafe")


def create_app(start_worker: bool = True) -> FastAPI:
    worker = EscalationWorker(SessionLocal, settings.escalation_interval_seconds)

    @asynccontextmanager
    async def lifespan(_app):
        if start_worker:
            worker.start()
        yield
        worker.stop()

    app = FastAPI(
        title="AcciSafe RDC API",
        version="1.0.0",
        description="Emergency incident reporting, management and response coordination for Rural District Councils.",
        lifespan=lifespan,
    )
    if settings.cors_origins:
        app.add_middleware(CORSMiddleware, allow_origins=settings.cors_origins, allow_methods=["*"], allow_headers=["*"])

    @app.middleware("http")
    async def correlation(request: Request, call_next):
        request.state.correlation_id = request.headers.get("X-Request-ID") or uuid.uuid4().hex
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.correlation_id
        log.info("%s %s -> %s [%s]", request.method, request.url.path, response.status_code, request.state.correlation_id)
        return response

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, exc: RequestValidationError):
        problems = [f"{'.'.join(str(p) for p in e['loc'][1:])}: {e['msg']}" for e in exc.errors()]
        return JSONResponse(status_code=422, content={"detail": "; ".join(problems) or "Invalid request"})

    @app.exception_handler(Exception)
    async def unexpected(request: Request, exc: Exception):
        log.exception("Unhandled error [%s]", getattr(request.state, "correlation_id", "-"))
        return JSONResponse(status_code=500, content={
            "detail": "An unexpected error occurred. Quote this reference to support.",
            "reference": getattr(request.state, "correlation_id", None)})

    @app.get("/health", tags=["health"])
    def health():
        with engine.connect() as conn:
            conn.execute(text("SELECT 1"))
        return {"status": "ok", "environment": settings.environment, "sms_provider": settings.sms_provider}

    for r in (auth.router, admin.router, incidents.router, livemap.router, operations.router, reports.router):
        app.include_router(r, prefix="/api/v1")

    # Council desktop console (browser app, installable on Windows/macOS from Chrome or Edge).
    console_dir = os.path.join(os.path.dirname(__file__), "console")
    app.mount("/console", StaticFiles(directory=console_dir, html=True), name="console")

    @app.get("/", include_in_schema=False)
    def root():
        return RedirectResponse("/console/")
    return app


app = create_app()
