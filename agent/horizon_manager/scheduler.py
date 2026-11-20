from datetime import datetime, timedelta, timezone
from typing import Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator


class Schedule(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["off", "interval", "calendar"] = "off"
    hours: int = Field(default=6, ge=1, le=168)
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


def next_run(schedule, after):
    if schedule.mode == "off":
        return None
    if schedule.mode == "interval":
        return after + schedule.hours * 3600
    zone = ZoneInfo(schedule.zone)
    local_date = datetime.fromtimestamp(after, zone).date()
    for offset in range(8):
        day = local_date + timedelta(days=offset)
        if day.weekday() not in schedule.days:
            continue
        candidate = datetime(day.year, day.month, day.day, schedule.hour, schedule.minute, tzinfo=zone)
        stamp = candidate.timestamp()
        # Skip nonexistent local times. Use the first occurrence for a repeated wall time.
        round_trip = datetime.fromtimestamp(stamp, timezone.utc).astimezone(zone)
        if stamp > after and round_trip.replace(tzinfo=None) == candidate.replace(tzinfo=None):
            return stamp
    # A weekly schedule can skip its only slot during a DST jump.
    return next_run(schedule, after + 7 * 86400)
