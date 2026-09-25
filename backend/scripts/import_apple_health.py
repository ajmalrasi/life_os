#!/usr/bin/env python3
"""One-time importer for an Apple Health export (export.zip / export.xml).

Why this exists
---------------
LifeOS normally ingests health data from Health Connect on Android. But an
Apple Watch writes sleep (and everything else) into Apple Health on iOS, which
never reaches Health Connect — so on Android those metrics are simply absent.
This script backfills them straight from the Apple export, mapping each record
onto the same `HealthRecordType` schema the Android client uploads and POSTing
to the existing, idempotent `/health/batch` endpoint (keyed by `source_id`, so
re-running is safe and never double-counts).

stdlib only — no pip installs. It streams the (large) XML so memory stays flat.

Usage
-----
    # Validate parsing without touching any server (safe anywhere):
    python import_apple_health.py --zip /path/to/export.zip --dry-run

    # Backfill the real backend (run wherever the API is reachable):
    python import_apple_health.py --zip /path/to/export.zip --api-url http://localhost:8000

    # Only sleep, only since a date:
    python import_apple_health.py --zip export.zip --types sleep --since 2024-01-01
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from collections import defaultdict
from datetime import datetime
from typing import Callable, Iterable, Iterator
from urllib import error, request
from xml.etree import ElementTree as ET

# --------------------------------------------------------------------------
# Apple HKQuantityType -> (HealthRecordType value, canonical unit, converter).
# The converter takes (raw_value, source_unit) and returns the value in the
# canonical unit. `_id` passes the value through unchanged.
# --------------------------------------------------------------------------

def workout_label(raw: str | None) -> str:
    """"HKWorkoutActivityTypeTraditionalStrengthTraining" -> "Traditional Strength Training"."""
    name = (raw or "").strip().removeprefix("HKWorkoutActivityType")
    if name and " " not in name:
        name = re.sub(r"(?<=[a-z])(?=[A-Z])", " ", name)
    return (name or "Workout")[:64]


def _id(v: float, unit: str) -> float:
    return v


def _spo2(v: float, unit: str) -> float:
    # Apple stores oxygen saturation as a fraction (0.95); HC wants 0-100.
    return v * 100.0 if v <= 1.0 else v


def _to_meters(v: float, unit: str) -> float:
    u = unit.lower()
    if u == "km":
        return v * 1000.0
    if u == "mi":
        return v * 1609.344
    if u in ("m", ""):
        return v
    if u == "cm":
        return v / 100.0
    return v


def _to_kg(v: float, unit: str) -> float:
    u = unit.lower()
    if u == "kg":
        return v
    if u == "lb":
        return v * 0.45359237
    if u == "g":
        return v / 1000.0
    if u == "st":
        return v * 6.35029318
    return v


def _height_to_m(v: float, unit: str) -> float:
    u = unit.lower()
    if u == "cm":
        return v / 100.0
    if u == "m":
        return v
    if u == "in":
        return v * 0.0254
    if u == "ft":
        return v * 0.3048
    return v


def _energy_kcal(v: float, unit: str) -> float:
    u = unit.lower()
    if u in ("cal", "kcal"):  # Apple "Cal" == dietary Calorie == kcal
        return v
    if u == "kj":
        return v / 4.184
    return v


def _temp_c(v: float, unit: str) -> float:
    if unit.lower() in ("degf", "f"):
        return (v - 32.0) * 5.0 / 9.0
    return v


# Apple quantity identifier suffix -> (record type, unit, converter)
QUANTITY_MAP: dict[str, tuple[str, str, Callable[[float, str], float]]] = {
    "HeartRate": ("heart_rate", "bpm", _id),
    "RestingHeartRate": ("resting_heart_rate", "bpm", _id),
    "WalkingHeartRateAverage": ("heart_rate", "bpm", _id),
    "HeartRateVariabilitySDNN": ("hrv", "ms", _id),  # NB: Apple=SDNN, HC=RMSSD
    "OxygenSaturation": ("blood_oxygen", "%", _spo2),
    "RespiratoryRate": ("respiratory_rate", "rpm", _id),
    # Mapped to CALORIES (not active_calories) because the analytics engine reads
    # the active-energy input from the CALORIES type, and older backends only
    # know CALORIES. See analytics.py.
    "ActiveEnergyBurned": ("calories", "kcal", _energy_kcal),
    "StepCount": ("steps", "count", _id),
    "DistanceWalkingRunning": ("walking_distance", "m", _to_meters),
    "FlightsClimbed": ("floors_climbed", "count", _id),
    "BodyMass": ("weight", "kg", _to_kg),
    "Height": ("height", "m", _height_to_m),
    "AppleSleepingWristTemperature": ("basal_body_temperature", "C", _temp_c),
    "VO2Max": ("vo2_max", "ml/kg/min", _id),
}

# Apple sleep category value suffix -> stage record type (None => asleep, no stage)
SLEEP_STAGE: dict[str, str | None] = {
    "AsleepDeep": "sleep_deep",
    "AsleepREM": "sleep_rem",
    "AsleepCore": "sleep_light",
    "AsleepUnspecified": None,
    "Asleep": None,  # very old exports
    "Awake": "sleep_awake",
    # "InBed" handled separately: it's the bed-time span, not an asleep stage.
}

SLEEP_GAP_MINUTES = 60  # segments farther apart than this start a new session
MIN_SLEEP_HOURS = 0.5   # ignore naps/fragments under 30 min as a "night"
MAX_SLEEP_HOURS = 16.0  # drop implausible single sessions (in-bed artifacts)


def _camel_to_snake(name: str) -> str:
    """AppleStandTime -> apple_stand_time, HeartRateVariabilitySDNN -> ..._sdnn."""
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def parse_apple_date(s: str) -> datetime:
    # e.g. "2026-06-14 11:38:28 +0530"
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S %z")


def hours_between(a: datetime, b: datetime) -> float:
    return (b - a).total_seconds() / 3600.0


def make_source_id(*parts: object) -> str:
    raw = "|".join(str(p) for p in parts)
    return "apple-" + hashlib.md5(raw.encode()).hexdigest()  # 38 chars, stable


def iter_records(xml_fp: Iterable[bytes]) -> Iterator[ET.Element]:
    """Stream <Record> and <Workout> elements, clearing as we go (flat memory)."""
    for event, elem in ET.iterparse(xml_fp, events=("end",)):
        if elem.tag in ("Record", "Workout"):
            yield elem
            elem.clear()


class Importer:
    def __init__(self, want_types: set[str], since: datetime | None,
                 allowed: set[str] | None = None) -> None:
        self.want = want_types  # category names: heart, sleep, energy, etc. or "all"
        self.since = since
        self.allowed = allowed  # record-type values the backend accepts (None => all)
        self.out: list[dict] = []          # non-sleep records ready to send
        self.sleep_segments: list[dict] = []  # buffered, grouped at the end
        self.skipped: dict[str, int] = defaultdict(int)
        self.mapped: dict[str, int] = defaultdict(int)
        self.seen: set[str] = set()        # source_ids already queued (de-dup)

    def _wanted(self, category: str) -> bool:
        return "all" in self.want or category in self.want

    def _emit(self, rec: dict) -> None:
        """Queue a record, de-duping source_ids and honoring any backend allow-list."""
        if self.allowed is not None and rec["type"] not in self.allowed:
            self.skipped["__type_unsupported_by_backend__"] += 1
            return
        sid = rec["source_id"]
        if sid in self.seen:  # same reading from multiple Apple source devices
            self.skipped["__duplicate_source_id__"] += 1
            return
        self.seen.add(sid)
        self.out.append(rec)
        self.mapped[rec["type"]] += 1

    def feed(self, elem: ET.Element) -> None:
        if elem.tag == "Workout":
            self._feed_workout(elem)
            return
        rtype = elem.get("type", "")
        if rtype.startswith("HKCategoryTypeIdentifierSleepAnalysis"):
            self._feed_sleep(elem)
        elif rtype.startswith("HKQuantityTypeIdentifier"):
            self._feed_quantity(elem)
        else:
            self.skipped[rtype] += 1

    def _in_range(self, start: datetime) -> bool:
        return self.since is None or start >= self.since

    def _feed_quantity(self, elem: ET.Element) -> None:
        suffix = elem.get("type", "").removeprefix("HKQuantityTypeIdentifier")
        mapping = QUANTITY_MAP.get(suffix)
        if mapping is not None:
            rec_type, unit, conv = mapping
        else:
            # Capture-everything: any other numeric quantity is stored under a
            # snake_case type with its native unit and raw value, so signals
            # LifeOS doesn't score yet are still kept for later use.
            rec_type, unit, conv = _camel_to_snake(suffix), elem.get("unit", ""), _id
        category = _category_of(rec_type)
        if not self._wanted(category):
            return
        try:
            start = parse_apple_date(elem.get("startDate", ""))
            end = parse_apple_date(elem.get("endDate", ""))
            value = conv(float(elem.get("value", "")), elem.get("unit", ""))
        except (ValueError, TypeError):
            # Non-numeric category-style records have no float value — skip them.
            self.skipped["__non_numeric__"] += 1
            return
        if not self._in_range(start):
            return
        self._emit({
            "source_id": make_source_id(rec_type, start.isoformat(), end.isoformat(), value),
            "type": rec_type,
            "value": value,
            "unit": unit,
            "start_time": start.isoformat(),
            "end_time": end.isoformat(),
        })

    def _feed_workout(self, elem: ET.Element) -> None:
        if not self._wanted("workout"):
            return
        try:
            start = parse_apple_date(elem.get("startDate", ""))
            end = parse_apple_date(elem.get("endDate", ""))
        except ValueError:
            self.skipped["__unparseable__"] += 1
            return
        if not self._in_range(start):
            return
        dur = elem.get("duration")
        unit = (elem.get("durationUnit") or "min").lower()
        try:
            minutes = float(dur) if dur else hours_between(start, end) * 60.0
        except ValueError:
            minutes = hours_between(start, end) * 60.0
        if unit in ("s", "sec"):
            minutes /= 60.0
        elif unit in ("h", "hr"):
            minutes *= 60.0
        self._emit({
            "source_id": make_source_id("workout", start.isoformat(), end.isoformat(),
                                        elem.get("workoutActivityType", "")),
            "type": "workout",
            "value": minutes,
            "unit": "min",
            "start_time": start.isoformat(),
            "end_time": end.isoformat(),
            "label": workout_label(elem.get("workoutActivityType")),
        })
        # Effort, so a session can be judged by more than its existence.
        label = workout_label(elem.get("workoutActivityType"))
        wtype = elem.get("workoutActivityType", "")
        for stat in elem.findall("WorkoutStatistics"):
            kind = stat.get("type", "")
            if kind == "HKQuantityTypeIdentifierActiveEnergyBurned" and stat.get("sum"):
                rec_type, value, unit = "workout_energy", _energy_kcal(float(stat.get("sum")), stat.get("unit", "kcal")), "kcal"
            elif kind == "HKQuantityTypeIdentifierHeartRate" and stat.get("average"):
                rec_type, value, unit = "workout_avg_hr", float(stat.get("average")), "bpm"
            else:
                continue
            self._emit({
                "source_id": make_source_id(rec_type, start.isoformat(), end.isoformat(), wtype),
                "type": rec_type,
                "value": round(value, 1),
                "unit": unit,
                "start_time": start.isoformat(),
                "end_time": end.isoformat(),
                "label": label,
            })

    def _feed_sleep(self, elem: ET.Element) -> None:
        if not self._wanted("sleep"):
            return
        val = elem.get("value", "").removeprefix("HKCategoryValueSleepAnalysis")
        try:
            start = parse_apple_date(elem.get("startDate", ""))
            end = parse_apple_date(elem.get("endDate", ""))
        except ValueError:
            self.skipped["__unparseable__"] += 1
            return
        if end <= start or not self._in_range(start):
            return
        self.sleep_segments.append({"value": val, "start": start, "end": end})

    # -- sleep gets grouped into per-night sessions after the full pass --------
    def finalize_sleep(self) -> None:
        if not self.sleep_segments:
            return
        segs = sorted(self.sleep_segments, key=lambda s: s["start"])
        # de-dup identical segments coming from multiple sources
        seen: set[tuple] = set()
        deduped = []
        for s in segs:
            key = (s["value"], s["start"], s["end"])
            if key not in seen:
                seen.add(key)
                deduped.append(s)

        # group by proximity into sessions
        sessions: list[list[dict]] = []
        cur: list[dict] = []
        cur_end: datetime | None = None
        for s in deduped:
            if cur_end is not None and (s["start"] - cur_end).total_seconds() / 60.0 > SLEEP_GAP_MINUTES:
                sessions.append(cur)
                cur = []
            cur.append(s)
            cur_end = max(cur_end, s["end"]) if cur_end else s["end"]
        if cur:
            sessions.append(cur)

        for sess in sessions:
            self._emit_session(sess)

    def _emit_session(self, sess: list[dict]) -> None:
        start = min(s["start"] for s in sess)
        end = max(s["end"] for s in sess)
        stage_hours: dict[str, float] = defaultdict(float)
        asleep_hours = 0.0
        inbed_hours = 0.0
        for s in sess:
            dur = hours_between(s["start"], s["end"])
            if s["value"] == "InBed":
                inbed_hours += dur
                continue
            stage = SLEEP_STAGE.get(s["value"], None)
            if s["value"] != "Awake":
                asleep_hours += dur  # Core/Deep/REM/Unspecified all count as asleep
            if stage:
                stage_hours[stage] += dur

        # Prefer measured asleep time; fall back to in-bed span for old iPhone data.
        total = asleep_hours if asleep_hours > 0 else inbed_hours
        # Drop fragments (sub-30min naps recorded as separate nights) and
        # implausible spans (old iPhone "in-bed" artifacts up to ~21h).
        if total < MIN_SLEEP_HOURS or total > MAX_SLEEP_HOURS:
            return

        sid = start.isoformat()
        self._emit({
            "source_id": make_source_id("sleep_duration", sid),
            "type": "sleep_duration", "value": total, "unit": "h",
            "start_time": start.isoformat(), "end_time": end.isoformat(),
        })
        for stage, hrs in stage_hours.items():
            if hrs <= 0:
                continue
            self._emit({
                "source_id": make_source_id(stage, sid),
                "type": stage, "value": hrs, "unit": "h",
                "start_time": start.isoformat(), "end_time": end.isoformat(),
            })


def _category_of(rec_type: str) -> str:
    if rec_type.startswith("sleep"):
        return "sleep"
    if rec_type in ("heart_rate", "resting_heart_rate", "hrv", "blood_oxygen",
                    "respiratory_rate", "basal_body_temperature", "vo2_max"):
        return "vitals"
    if rec_type in ("active_calories", "calories"):
        return "energy"
    if rec_type in ("steps", "walking_distance", "floors_climbed"):
        return "activity"
    if rec_type in ("weight", "height"):
        return "body"
    return "other"


def post_batch(api_url: str, records: list[dict], api_token: str | None) -> dict:
    body = json.dumps({"records": records}).encode()
    headers = {"Content-Type": "application/json"}
    if api_token:
        headers["Authorization"] = f"Bearer {api_token}"
    req = request.Request(
        api_url.rstrip("/") + "/health/batch",
        data=body, headers=headers, method="POST",
    )
    with request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read())


def flush(imp: Importer, api_url: str | None, api_token: str | None, batch_size: int, totals: dict) -> None:
    while len(imp.out) >= batch_size:
        chunk, imp.out = imp.out[:batch_size], imp.out[batch_size:]
        _send(chunk, api_url, api_token, totals)


def _send(chunk: list[dict], api_url: str | None, api_token: str | None, totals: dict) -> None:
    if api_url is None:  # dry run
        totals["would_send"] += len(chunk)
        return
    try:
        res = post_batch(api_url, chunk, api_token)
        totals["received"] += res.get("received", 0)
        totals["inserted"] += res.get("inserted", 0)
        totals["skipped"] += res.get("skipped", 0)
        print(f"  sent {len(chunk):>5}  inserted={res.get('inserted')}  "
              f"skipped(existing)={res.get('skipped')}")
    except error.URLError as e:
        sys.exit(f"\nFailed to POST to {api_url}: {e}\n"
                 f"Is the backend running and reachable? (try --dry-run first)")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--zip", help="Path to export.zip (reads export.xml inside)")
    src.add_argument("--xml", help="Path to an already-extracted export.xml")
    p.add_argument("--api-url", help="Backend base URL, e.g. http://localhost:8000. "
                                     "Omit to dry-run (parse only, no network).")
    p.add_argument("--api-token", default=os.environ.get("LIFEOS_API_TOKEN"),
                   help="Bearer token for Authorization, if the backend has "
                        "Settings.api_token set (see AGENTS.md §2). Defaults to "
                        "$LIFEOS_API_TOKEN. Unnecessary when the backend has no "
                        "token configured.")
    p.add_argument("--dry-run", action="store_true",
                   help="Parse and summarize without sending (same as omitting --api-url).")
    p.add_argument("--since", help="Only import records starting on/after YYYY-MM-DD.")
    p.add_argument("--batch-size", type=int, default=1000)
    p.add_argument("--types", default="all",
                   help="Comma list of categories to import: "
                        "sleep,vitals,energy,activity,body,workout — or 'all' (default).")
    p.add_argument("--allowed", default=None,
                   help="Comma list of HealthRecordType values the backend accepts. "
                        "Records of any other type are skipped (avoids 422s against an "
                        "older backend). Omit to send every mapped type.")
    args = p.parse_args()

    since = None
    if args.since:
        since = datetime.strptime(args.since, "%Y-%m-%d").astimezone()
    want = {t.strip() for t in args.types.split(",") if t.strip()}
    allowed = ({t.strip() for t in args.allowed.split(",") if t.strip()}
               if args.allowed else None)
    api_url = None if args.dry_run else args.api_url

    imp = Importer(want_types=want, since=since, allowed=allowed)
    totals = defaultdict(int)

    print(f"Reading {'(dry run) ' if api_url is None else ''}"
          f"{args.zip or args.xml} ...")

    def run(fp: Iterable[bytes]) -> None:
        for i, elem in enumerate(iter_records(fp), 1):
            imp.feed(elem)
            if i % 200_000 == 0:
                print(f"  parsed {i:,} records ...")
            flush(imp, api_url, args.api_token, args.batch_size, totals)

    if args.zip:
        with zipfile.ZipFile(args.zip) as zf:
            name = next((n for n in zf.namelist() if n.endswith("export.xml")), None)
            if name is None:
                sys.exit("No export.xml found inside the zip.")
            with zf.open(name) as fp:
                run(fp)
    else:
        with open(args.xml, "rb") as fp:
            run(fp)

    imp.finalize_sleep()
    # send remainder
    while imp.out:
        chunk, imp.out = imp.out[:args.batch_size], imp.out[args.batch_size:]
        _send(chunk, api_url, args.api_token, totals)

    print("\n=== Mapped (by record type) ===")
    for k, v in sorted(imp.mapped.items(), key=lambda kv: -kv[1]):
        print(f"  {k:<24} {v:,}")
    total_mapped = sum(imp.mapped.values())
    skipped_total = sum(imp.skipped.values())
    print(f"\nTotal mapped records: {total_mapped:,}")
    print(f"Unmapped/skipped source records: {skipped_total:,} "
          f"(no matching HealthRecordType)")
    if api_url is None:
        print(f"\nDRY RUN — would have sent {totals['would_send']:,} records. "
              f"Re-run with --api-url to backfill.")
    else:
        print(f"\nDone. inserted={totals['inserted']:,}  "
              f"already-present={totals['skipped']:,}  received={totals['received']:,}")


if __name__ == "__main__":
    main()
