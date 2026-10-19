from datetime import datetime, timezone

import pytest

from app.services.chat_agent import parse_date_from_text

NOW = datetime(2026, 10, 14, 12, 0, 0)  # Wednesday; Monday of that week is 2026-10-12


def _d(day):
    # Local midnight in Buenos Aires (UTC-3) is 03:00Z the same day.
    return f"{day}T03:00:00Z"


@pytest.mark.parametrize("message,from_day,to_day", [
    ("mails del 12/10", "2026-10-12", "2026-10-13"),
    ("mails del 12-10-2025", "2025-10-12", "2025-10-13"),
    ("emails del 2026-10-12", "2026-10-12", "2026-10-13"),
    ("emails del 12 de octubre", "2026-10-12", "2026-10-13"),
    ("emails del 12 de octubre de 2024", "2024-10-12", "2024-10-13"),
    ("emails del 12 de oct", "2026-10-12", "2026-10-13"),
    ("emails de octubre", "2026-10-01", "2026-11-01"),
    ("emails de enero", "2026-01-01", "2026-02-01"),
    ("emails de diciembre", "2025-12-01", "2026-01-01"),
    ("emails de febrero de 2024", "2024-02-01", "2024-03-01"),
    ("emails de diciembre de 2025", "2025-12-01", "2026-01-01"),
    ("el lunes", "2026-10-12", "2026-10-13"),
    ("los mails del lunes", "2026-10-12", "2026-10-13"),
    ("el lunes pasado", "2026-10-05", "2026-10-06"),
    ("hoy", "2026-10-14", "2026-10-15"),
    ("lo de ayer", "2026-10-13", "2026-10-14"),
    ("anteayer", "2026-10-12", "2026-10-13"),
    ("esta semana", "2026-10-12", "2026-10-15"),
    ("la semana pasada", "2026-10-05", "2026-10-12"),
    ("este mes", "2026-10-01", "2026-10-15"),
    ("el mes pasado", "2026-09-01", "2026-10-01"),
    ("este año", "2026-01-01", "2026-10-15"),
    ("el año pasado", "2025-01-01", "2026-01-01"),
    ("últimos 3 días", "2026-10-12", "2026-10-15"),
    ("ultimos 3 dias", "2026-10-12", "2026-10-15"),
])
def test_parse_date_from_text(message, from_day, to_day):
    assert parse_date_from_text(message, NOW) == {"dateFrom": _d(from_day), "dateTo": _d(to_day)}


@pytest.mark.parametrize("message", [
    "",
    "mails de Juan García",
    "correos de la empresa Acme",
    "mails sobre facturas",
    "mostrame todo",
])
def test_parse_date_from_text_no_date_returns_empty(message):
    assert parse_date_from_text(message, NOW) == {}


def test_parse_weekday_today_when_now_is_that_weekday():
    # 2026-10-12 is a Monday
    assert parse_date_from_text("el lunes", datetime(2026, 10, 12, 8, 0, 0)) == {
        "dateFrom": _d("2026-10-12"), "dateTo": _d("2026-10-13")}


def test_parse_uses_buenos_aires_timezone_not_utc():
    # 02:00Z on the 14th is 23:00 local on the 13th
    assert parse_date_from_text("mails de hoy", datetime(2026, 10, 14, 2, 0, 0, tzinfo=timezone.utc)) == {
        "dateFrom": _d("2026-10-13"), "dateTo": _d("2026-10-14")}


def test_parse_last_days_crosses_month_boundary():
    assert parse_date_from_text("últimos 5 días", datetime(2026, 11, 2, 9, 0, 0)) == {
        "dateFrom": _d("2026-10-29"), "dateTo": _d("2026-11-03")}


def test_parse_rejects_absurd_last_days_count():
    assert parse_date_from_text("últimos 0 días", NOW) == {}


@pytest.mark.parametrize("token,days", [("last_7_days", 7), ("last_30_days", 30), ("last_90_days", 90)])
def test_last_n_days_covers_exactly_n_days(token, days):
    from datetime import date, timedelta
    from app.services.chat_agent import resolve_date_token
    out = resolve_date_token(token, NOW)
    start = date(*map(int, out["dateFrom"][:10].split("-")))
    end = date(*map(int, out["dateTo"][:10].split("-")))
    assert (end - start).days == days          # exclusive upper bound -> exactly n days
    assert end == date(2026, 10, 15)           # ends tomorrow local
