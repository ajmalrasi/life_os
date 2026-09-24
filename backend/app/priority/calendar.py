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
    summary: str | None = None
    organizer: str | None = None


def _unfold(text: str) -> list[str]:
    out: list[str] = []
    for raw in text.replace("\r\n", "\n").split("\n"):
        if raw.startswith((" ", "\t")) and out:
            out[-1] += raw[1:]
        else:
            out.append(raw)
    return out


def _event_lines(lines: list[str]) -> list[str]:
    """Only the first VEVENT's properties.

    VTIMEZONE blocks carry their own DTSTART (e.g. ``16010101T000000`` in
    Outlook invites); reading those produced year-1601 event times.
    """
    inside = False
    event: list[str] = []
    for line in lines:
        upper = line.strip().upper()
        if upper == "BEGIN:VEVENT":
            inside = True
            continue
        if upper == "END:VEVENT":
            break
        if inside:
            event.append(line)
    return event


# Outlook/Exchange emit Windows zone names as TZID.
_WINDOWS_ZONES = {
    "india standard time": "Asia/Kolkata",
    "utc": "UTC",
    "coordinated universal time": "UTC",
    "gmt standard time": "Europe/London",
    "greenwich standard time": "UTC",
    "pacific standard time": "America/Los_Angeles",
    "mountain standard time": "America/Denver",
    "central standard time": "America/Chicago",
    "eastern standard time": "America/New_York",
    "w. europe standard time": "Europe/Berlin",
    "central europe standard time": "Europe/Budapest",
    "romance standard time": "Europe/Paris",
    "singapore standard time": "Asia/Singapore",
    "arabian standard time": "Asia/Dubai",
    "arab standard time": "Asia/Riyadh",
    "china standard time": "Asia/Shanghai",
    "tokyo standard time": "Asia/Tokyo",
    "aus eastern standard time": "Australia/Sydney",
}


def _zone(timezone_name: str | None) -> ZoneInfo:
    if timezone_name:
        cleaned = timezone_name.strip().strip('"')
        candidate = _WINDOWS_ZONES.get(cleaned.casefold(), cleaned)
        try:
            return ZoneInfo(candidate)
        except (ZoneInfoNotFoundError, ValueError):
            pass
    return ZoneInfo("Asia/Kolkata")


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
        if len(cleaned) == 8:  # VALUE=DATE (all-day)
            parsed = datetime.strptime(cleaned, "%Y%m%d")
        else:
            fmt = "%Y%m%dT%H%M%S" if len(cleaned) >= 15 else "%Y%m%dT%H%M"
            parsed = datetime.strptime(cleaned[:15] if fmt.endswith("%S") else cleaned[:13], fmt)
        result = parsed.replace(tzinfo=_zone(timezone_name)).astimezone(timezone.utc)
    except ValueError:
        return None
    return result if result.year >= 2000 else None


def parse_ics(text: str) -> CalendarInvite | None:
    if "BEGIN:VCALENDAR" not in text.upper():
        return None
    all_lines = _unfold(text)
    method, _ = _value(all_lines, "METHOD")
    lines = _event_lines(all_lines)
    if not lines:
        return None
    start_raw, timezone_name = _value(lines, "DTSTART")
    end_raw, end_timezone = _value(lines, "DTEND")
    uid, _ = _value(lines, "UID")
    location, _ = _value(lines, "LOCATION")
    url, _ = _value(lines, "URL")
    status, _ = _value(lines, "STATUS")
    summary, _ = _value(lines, "SUMMARY")
    organizer, _ = _value(lines, "ORGANIZER")
    return CalendarInvite(
        uid=uid,
        start=_datetime(start_raw, timezone_name),
        end=_datetime(end_raw, end_timezone or timezone_name),
        timezone_name=_zone(timezone_name).key if timezone_name else None,
        location=location.replace("\\,", ",").replace("\\;", ";") if location else None,
        url=url,
        summary=summary.replace("\\,", ",").replace("\\;", ";") if summary else None,
        organizer=(organizer or "").removeprefix("mailto:").removeprefix("MAILTO:") or None,
        status=(
            "cancelled"
            if (method or "").upper() == "CANCEL"
            else status.lower() if status else None
        ),
    )
