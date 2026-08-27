from fastapi import APIRouter, Depends, HTTPException, Request, status

from ..config import settings
from ..deps import get_current_user
from ..rate_limit import LoginRateLimiter
from ..schemas import LoginRequest, LoginResponse, MeResponse
from ..security import create_access_token
from ..users import User, user_store

router = APIRouter(prefix="/api/auth", tags=["auth"])

_rate_limiter = LoginRateLimiter(
    max_attempts=settings.login_max_attempts,
    lockout_seconds=settings.login_lockout_seconds,
)


@router.post("/login", response_model=LoginResponse)
def login(payload: LoginRequest, request: Request) -> LoginResponse:
    client_ip = request.client.host if request.client else "unknown"
    rate_key = f"{payload.username.strip().lower()}:{client_ip}"

    if _rate_limiter.is_locked_out(rate_key):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many failed login attempts. Please wait a few minutes and try again.",
        )

    user = user_store.verify_credentials(payload.username, payload.password)
    if user is None:
        _rate_limiter.register_failure(rate_key)
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid username or password")

    _rate_limiter.register_success(rate_key)
    token, expires_in = create_access_token(user.username)
    return LoginResponse(access_token=token, expires_in=expires_in)


@router.get("/me", response_model=MeResponse)
def me(current_user: User = Depends(get_current_user)) -> MeResponse:
    return MeResponse(username=current_user.username)
