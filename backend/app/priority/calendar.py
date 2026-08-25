from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError


@dataclass(frozen=True)
class CalendarInvite:
    uid: str | None = None
    start: datetime | None = None
    end: datetime | None = None
    timezone_name: str | None = None
    location: str | None = None
    url: str | None = None
    status: str | None = None


def _unfold(text: str) -> list[str]:
    out: list[str] = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        if raw.startswith((" ", "\t")) and out:
            out[-1] += raw[1:]
        else:
            out.append(raw)
    return out


def _value(lines: list[str], name: str) -> tuple[str | None, str | None]:
    for line in lines:
        head, separator, value = line.partition(":")
        if not separator or head.split(";", 1)[0].upper() != name:
            continue
        timezone_name = None
        for param in head.split(";")[1:]:
            key, equals, candidate = param.partition("=")
            if equals and key.upper() == "TZID":
                timezone_name = candidate
        return value.strip() or None, timezone_name
    return None, None


def _datetime(value: str | None, timezone_name: str | None) -> datetime | None:
    if not value:
        return None
    cleaned = value.strip()
    try:
        if cleaned.endswith("Z"):
            return datetime.strptime(cleaned, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc)
        fmt = "%Y%m%dT%H%M%S" if len(cleaned) >= 15 else "%Y%m%dT%H%M"
        parsed = datetime.strptime(cleaned[:15] if fmt.endswith("%S") else cleaned[:13], fmt)
        try:
            tz = ZoneInfo(timezone_name) if timezone_name else ZoneInfo("Asia/Kolkata")
        except ZoneInfoNotFoundError:
            tz = ZoneInfo("Asia/Kolkata")
        return parsed.replace(tzinfo=tz).astimezone(timezone.utc)
    except ValueError:
        return None


def parse_ics(text: str) -> CalendarInvite | None:
    if "BEGIN:VCALENDAR" not in text.upper():
        return None
    lines = _unfold(text)
    start_raw, timezone_name = _value(lines, "DTSTART")
    end_raw, end_timezone = _value(lines, "DTEND")
    uid, _ = _value(lines, "UID")
    location, _ = _value(lines, "LOCATION")
    url, _ = _value(lines, "URL")
    status, _ = _value(lines, "STATUS")
    return CalendarInvite(
        uid=uid,
        start=_datetime(start_raw, timezone_name),
        end=_datetime(end_raw, end_timezone or timezone_name),
        timezone_name=timezone_name,
        location=location,
        url=url,
        status=status.lower() if status else None,
    )
