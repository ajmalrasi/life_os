"""Small process boundary around the gog Google Workspace CLI."""

from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class GogCommandError(RuntimeError):
    message: str
    stderr: str = ""

    def __str__(self) -> str:
        return self.message


class GogRunner:
    def __init__(
        self,
        *,
        keyring_password: str = "",
        gog_bin: str = "gog",
        home: str | None = None,
    ) -> None:
        self.keyring_password = keyring_password
        self.gog_bin = gog_bin
        self.home = home

    @property
    def available(self) -> bool:
        return shutil.which(self.gog_bin) is not None

    def _env(self) -> dict[str, str]:
        env = {**os.environ}
        if self.keyring_password:
            env["GOG_KEYRING_PASSWORD"] = self.keyring_password
        if self.home:
            env["GOG_HOME"] = self.home
        return env

    def run(
        self,
        args: list[str],
        *,
        account: str | None = None,
        timeout: float = 30.0,
        input_text: str | None = None,
    ) -> str:
        command = [self.gog_bin]
        if account:
            command.extend(("-a", account))
        command.extend(args)
        try:
            proc = subprocess.run(
                command,
                capture_output=True,
                text=True,
                input=input_text,
                timeout=timeout,
                env=self._env(),
            )
        except subprocess.TimeoutExpired as exc:
            logger.warning("gog command timed out: %s", args[:3])
            raise GogCommandError("Google authorization took too long to respond.") from exc
        except (FileNotFoundError, OSError) as exc:
            logger.warning("gog command could not start: %s", exc)
            raise GogCommandError("The Gmail reader is unavailable on the LifeOS server.") from exc
        if proc.returncode != 0:
            stderr = proc.stderr.strip()
            logger.warning("gog command failed (%s): %s", proc.returncode, stderr[:240])
            raise GogCommandError("Google rejected the Gmail operation.", stderr=stderr)
        return proc.stdout

    def json(
        self,
        args: list[str],
        *,
        account: str | None = None,
        timeout: float = 30.0,
        input_text: str | None = None,
    ) -> dict:
        output = self.run(
            args,
            account=account,
            timeout=timeout,
            input_text=input_text,
        )
        try:
            value = json.loads(output)
        except json.JSONDecodeError as exc:
            logger.warning("gog returned invalid JSON for %s", args[:3])
            raise GogCommandError("Google returned an invalid response.") from exc
        if not isinstance(value, dict):
            raise GogCommandError("Google returned an invalid response.")
        return value
