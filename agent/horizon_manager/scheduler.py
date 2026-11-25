from datetime import datetime, timedelta, timezone
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Schedule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["off", "interval", "calendar"] = "off"
    hours: int = Field(default=6, ge=1, le=168)
    interval_start: str | None = Field(default=None, pattern=r"^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")
    hour: int = Field(default=8, ge=0, le=23)
    minute: int = Field(default=0, ge=0, le=59)
    days: list[int] = Field(default_factory=lambda: list(range(7)), min_length=1, max_length=7)
    zone: str = Field(default="UTC", max_length=80)

    @field_validator("zone")
    @classmethod
    def valid_zone(cls, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError("Unknown IANA time zone") from None
        return value

    @field_validator("days")
    @classmethod
    def valid_days(cls, days):
        if any(day not in range(7) for day in days) or len(days) != len(set(days)):
            raise ValueError("Expected distinct weekdays in 0–6")
        return sorted(days)


def next_run(schedule, after, previous=None):
    if schedule.mode == "off":
        return None
    if schedule.mode == "interval":
        period = schedule.hours * 3600
        if schedule.interval_start is None:
            return after + period  # Existing schedules keep their original cadence.
        if previous is not None:
            periods = int((after - previous) // period) + 1
            return previous + max(1, periods) * period
        hour, minute = map(int, schedule.interval_start.split(":"))
        return next_wall_time(schedule.zone, hour, minute, range(7), after)
    return next_wall_time(schedule.zone, schedule.hour, schedule.minute, schedule.days, after)


def next_wall_time(zone_name, hour, minute, days, after):
    zone = ZoneInfo(zone_name)
    local_date = datetime.fromtimestamp(after, zone).date()
    for offset in range(8):
        day = local_date + timedelta(days=offset)
        if day.weekday() not in days:
            continue
        candidate = datetime(day.year, day.month, day.day, hour, minute, tzinfo=zone)
        stamp = candidate.timestamp()
        # Skip nonexistent local times. Use the first occurrence for a repeated wall time.
        round_trip = datetime.fromtimestamp(stamp, timezone.utc).astimezone(zone)
        if stamp > after and round_trip.replace(tzinfo=None) == candidate.replace(tzinfo=None):
            return stamp
    # A weekly schedule can skip its only slot during a DST jump.
    return next_wall_time(zone_name, hour, minute, days, after + 7 * 86400)
