"""In-memory user store.

WatchWire's web login is backed by a small, fixed set of accounts configured via
environment variables (see config.py). There's no self-service signup -- this is an MVP
for a single household/team, not a multi-tenant SaaS. Swapping this out for a real
database later just means implementing the same get()/verify() interface.
"""
from dataclasses import dataclass

from .security import hash_password, verify_password


@dataclass
class User:
    username: str
    password_hash: str


class UserStore:
    def __init__(self) -> None:
        self._users: dict[str, User] = {}

    def seed(self, username: str, password: str) -> None:
        key = username.strip().lower()
        self._users[key] = User(username=username.strip(), password_hash=hash_password(password))

    def get(self, username: str) -> User | None:
        return self._users.get(username.strip().lower())

    def verify_credentials(self, username: str, password: str) -> User | None:
        user = self.get(username)
        if user is None:
            return None
        if not verify_password(password, user.password_hash):
            return None
        return user


user_store = UserStore()
