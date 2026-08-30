"""Serves the Android APK so a phone can install WatchWire straight from the web app.

This is deliberately unauthenticated: it's the app installer, it contains no secrets, and
requiring a login to download the client you need in order to log in would be circular.
Anyone who can reach the backend can fetch it -- on a home LAN that's the intent.
"""
import logging
from pathlib import Path

from fastapi import APIRouter, HTTPException, status
from fastapi.responses import FileResponse

from ..config import settings

logger = logging.getLogger("watchwire.download")

router = APIRouter(prefix="/download", tags=["download"])

# Android only offers the install prompt when the file is served with this content type.
APK_MEDIA_TYPE = "application/vnd.android.package-archive"

# Preference order: arm64 covers essentially every modern phone, then 32-bit ARM, then the
# universal build as a catch-all. x86_64 is emulator-only and deliberately not offered here.
_PREFERRED_APK_NAMES = (
    "watchwire-arm64-v8a-release.apk",
    "watchwire-armeabi-v7a-release.apk",
    "watchwire-universal-release.apk",
)

_REPO_ROOT = Path(__file__).resolve().parents[3]


def _apk_directory() -> Path:
    configured = Path(settings.apk_dir)
    return configured if configured.is_absolute() else (_REPO_ROOT / configured)


def find_apk() -> Path | None:
    """Returns the APK to hand out, or None if the directory holds no usable build."""
    directory = _apk_directory()
    if not directory.is_dir():
        return None

    for name in _PREFERRED_APK_NAMES:
        candidate = directory / name
        if candidate.is_file():
            return candidate

    # Fall back to any non-emulator APK present, newest first.
    others = sorted(
        (p for p in directory.glob("*.apk") if "x86" not in p.name),
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    )
    return others[0] if others else None


@router.get("/apk")
def download_apk() -> FileResponse:
    apk = find_apk()
    if apk is None:
        logger.warning("APK download requested but none found in %s", _apk_directory())
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=(
                f"No APK available. Build one with './gradlew :app:assembleRelease' and place it in "
                f"'{_apk_directory()}', or point WATCHWIRE_APK_DIR at the directory holding it."
            ),
        )

    return FileResponse(
        path=apk,
        media_type=APK_MEDIA_TYPE,
        filename="watchwire.apk",
        headers={"Cache-Control": "no-cache"},
    )


@router.get("/apk/info")
def apk_info() -> dict:
    """Lets the web app show whether a download is actually available before offering it."""
    apk = find_apk()
    if apk is None:
        return {"available": False}
    return {"available": True, "filename": apk.name, "size_bytes": apk.stat().st_size}
