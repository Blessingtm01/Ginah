"""Sign-in, token refresh, registration and the signed-in user's profile."""
from datetime import timedelta

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import audit
from .. import models as m
from ..config import settings
from ..db import get_db, utcnow
from ..deps import CurrentUser, current_user
from ..permissions import ROLES
from ..schemas import Out
from ..security import (
    create_access_token, hash_password, hash_refresh_token, login_limiter, new_refresh_token,
    validate_password_strength, verify_password,
)

router = APIRouter(prefix="/auth", tags=["auth"])


class LoginIn(BaseModel):
    council_code: str = Field(min_length=2, max_length=16)
    username: str = Field(min_length=2, max_length=80)
    password: str = Field(min_length=1, max_length=200)
    device: str | None = Field(default=None, max_length=200)


class TokenOut(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"
    expires_in: int
    user: "MeOut"


class MeOut(Out):
    id: int
    council_id: int
    council_name: str
    council_code: str
    council_is_demo: bool
    username: str
    full_name: str
    phone: str | None
    roles: list[str]
    permissions: list[str]
    must_change_password: bool


class RefreshIn(BaseModel):
    refresh_token: str = Field(min_length=10, max_length=200)


class RegisterIn(BaseModel):
    council_code: str
    username: str = Field(min_length=3, max_length=80, pattern=r"^[A-Za-z0-9_.-]+$")
    full_name: str = Field(min_length=2, max_length=200)
    phone: str | None = Field(default=None, pattern=r"^\+?[0-9 ]{7,20}$")
    password: str = Field(min_length=10, max_length=200)


class ChangePasswordIn(BaseModel):
    current_password: str
    new_password: str = Field(min_length=10, max_length=200)


def me_out(user: m.User, council: m.Council) -> MeOut:
    from ..permissions import permissions_for
    roles = user.role_codes
    return MeOut(
        id=user.id, council_id=council.id, council_name=council.name, council_code=council.code,
        council_is_demo=council.is_demo, username=user.username, full_name=user.full_name, phone=user.phone,
        roles=roles, permissions=sorted(permissions_for(roles)), must_change_password=user.must_change_password,
    )


def _issue(db: Session, user: m.User, council: m.Council, device: str | None) -> TokenOut:
    token, token_hash = new_refresh_token()
    db.add(m.RefreshToken(user_id=user.id, token_hash=token_hash, device=device,
                          expires_at=utcnow() + timedelta(days=settings.refresh_token_days)))
    return TokenOut(access_token=create_access_token(user.id, council.id, user.role_codes), refresh_token=token,
                    expires_in=settings.access_token_minutes * 60, user=me_out(user, council))


@router.post("/login", response_model=TokenOut)
def login(body: LoginIn, request: Request, db: Session = Depends(get_db)):
    key = f"{body.council_code.upper()}:{body.username.lower()}:{request.client.host if request.client else ''}"
    if not login_limiter.allow(key):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many sign-in attempts. Wait a minute and try again.")
    council = db.scalar(select(m.Council).where(m.Council.code == body.council_code.upper()))
    user = council and db.scalar(select(m.User).where(m.User.council_id == council.id, m.User.username == body.username.lower()))
    if not user or not verify_password(body.password, user.password_hash):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Incorrect council code, username or password")
    if not user.active:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "This account has been deactivated")
    out = _issue(db, user, council, body.device)
    audit.record(db, council_id=council.id, user_id=user.id, action="LOGIN", entity="user", entity_id=user.id, request=request)
    db.commit()
    return out


@router.post("/refresh", response_model=TokenOut)
def refresh(body: RefreshIn, db: Session = Depends(get_db)):
    row = db.scalar(select(m.RefreshToken).where(m.RefreshToken.token_hash == hash_refresh_token(body.refresh_token)))
    if row is None or row.revoked_at is not None or row.expires_at < utcnow():
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Session expired. Please sign in again.")
    user = db.get(m.User, row.user_id)
    if user is None or not user.active:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Account is not active")
    row.revoked_at = utcnow()  # rotation: each refresh token works once
    out = _issue(db, user, db.get(m.Council, user.council_id), row.device)
    db.commit()
    return out


@router.post("/logout", status_code=204)
def logout(body: RefreshIn, db: Session = Depends(get_db)):
    row = db.scalar(select(m.RefreshToken).where(m.RefreshToken.token_hash == hash_refresh_token(body.refresh_token)))
    if row and row.revoked_at is None:
        row.revoked_at = utcnow()
        db.commit()


@router.post("/register", response_model=TokenOut, status_code=201)
def register(body: RegisterIn, request: Request, db: Session = Depends(get_db)):
    council = db.scalar(select(m.Council).where(m.Council.code == body.council_code.upper()))
    if council is None or not council.public_registration:
        raise HTTPException(status.HTTP_403_FORBIDDEN, "Public registration is not available for this council")
    if not login_limiter.allow(f"register:{request.client.host if request.client else ''}"):
        raise HTTPException(status.HTTP_429_TOO_MANY_REQUESTS, "Too many attempts. Try again later.")
    if problem := validate_password_strength(body.password):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, problem)
    username = body.username.lower()
    if db.scalar(select(m.User.id).where(m.User.council_id == council.id, m.User.username == username)):
        raise HTTPException(status.HTTP_409_CONFLICT, "That username is already taken")
    user = m.User(council_id=council.id, username=username, full_name=body.full_name, phone=body.phone,
                  password_hash=hash_password(body.password))
    user.roles.append(m.UserRole(role_code="COMMUNITY_REPORTER"))
    db.add(user)
    db.flush()
    audit.record(db, council_id=council.id, user_id=user.id, action="REGISTER", entity="user", entity_id=user.id,
                 new={"roles": ["COMMUNITY_REPORTER"]}, request=request)
    out = _issue(db, user, council, None)
    db.commit()
    return out


@router.get("/me", response_model=MeOut)
def me(cu: CurrentUser = Depends(current_user), db: Session = Depends(get_db)):
    return me_out(cu.user, db.get(m.Council, cu.council_id))


@router.post("/change-password", status_code=204)
def change_password(body: ChangePasswordIn, request: Request, cu: CurrentUser = Depends(current_user),
                    db: Session = Depends(get_db)):
    if not verify_password(body.current_password, cu.user.password_hash):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, "Current password is incorrect")
    if problem := validate_password_strength(body.new_password):
        raise HTTPException(status.HTTP_422_UNPROCESSABLE_ENTITY, problem)
    cu.user.password_hash = hash_password(body.new_password)
    cu.user.must_change_password = False
    # Sign out other devices.
    for t in db.scalars(select(m.RefreshToken).where(m.RefreshToken.user_id == cu.id, m.RefreshToken.revoked_at.is_(None))):
        t.revoked_at = utcnow()
    audit.record(db, council_id=cu.council_id, user_id=cu.id, action="CHANGE_PASSWORD", entity="user", entity_id=cu.id, request=request)
    db.commit()


@router.get("/roles")
def roles():
    return [{"code": k, "name": v} for k, v in ROLES.items()]


TokenOut.model_rebuild()
