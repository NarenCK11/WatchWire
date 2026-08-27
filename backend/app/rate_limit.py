"""A tiny in-memory login rate limiter to slow down credential brute-forcing.

Not a substitute for a real WAF/rate-limiting layer in production, but a sensible
MVP-level guard. Keyed by an arbitrary string (we use "username:client_ip").
"""
import time


class LoginRateLimiter:
    def __init__(self, max_attempts: int, lockout_seconds: int) -> None:
        self.max_attempts = max_attempts
        self.lockout_seconds = lockout_seconds
        self._failures: dict[str, list[float]] = {}

    def _prune(self, key: str, now: float) -> list[float]:
        window_start = now - self.lockout_seconds
        attempts = [t for t in self._failures.get(key, []) if t >= window_start]
        self._failures[key] = attempts
        return attempts

    def is_locked_out(self, key: str) -> bool:
        now = time.time()
        return len(self._prune(key, now)) >= self.max_attempts

    def register_failure(self, key: str) -> None:
        now = time.time()
        attempts = self._prune(key, now)
        attempts.append(now)
        self._failures[key] = attempts

    def register_success(self, key: str) -> None:
        self._failures.pop(key, None)
