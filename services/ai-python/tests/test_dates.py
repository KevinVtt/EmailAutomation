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


@pytest.mark.parametrize("message", [
    "mails de soporte 24/7",
    "actualizacion de la version 2-3",
    "pagina 3-4 del informe",
    "revisar el archivo adjunto 1/2",
    "los mails de Domingo",
    "correos de Mar",
    "mails del mar",
    "emails de 1000",
])
def test_parse_does_not_invent_a_date(message):
    assert parse_date_from_text(message, NOW) == {}


@pytest.mark.parametrize("message,from_day,to_day", [
    ("mails del 12 de octubre del 2024", "2024-10-12", "2024-10-13"),
    ("mails de octubre del 2024", "2024-10-01", "2024-11-01"),
    ("mails de marzo del año pasado", "2025-03-01", "2025-04-01"),
    ("mails del 12/10 del 2024", "2024-10-12", "2024-10-13"),
    ("mails 12/10/2026", "2026-10-12", "2026-10-13"),
    ("mails de 2026", "2026-01-01", "2027-01-01"),
    ("mails de 2024", "2024-01-01", "2025-01-01"),
    ("el martes que viene", "2026-10-20", "2026-10-21"),
    ("el jueves proximo", "2026-10-15", "2026-10-16"),
])
def test_parse_date_from_text_extended(message, from_day, to_day):
    assert parse_date_from_text(message, NOW) == {"dateFrom": _d(from_day), "dateTo": _d(to_day)}


def test_parse_invalid_day_falls_through_to_the_month():
    # "45 de octubre" is not a day; the month the user named must survive.
    assert parse_date_from_text("emails de 45 de octubre", NOW) == {
        "dateFrom": _d("2026-10-01"), "dateTo": _d("2026-11-01")}


def test_parse_invalid_numeric_date_falls_through_to_the_month():
    assert parse_date_from_text("mails del 32/13 y de octubre", NOW) == {
        "dateFrom": _d("2026-10-01"), "dateTo": _d("2026-11-01")}


@pytest.mark.parametrize("message,from_day,to_day", [
    ("Filtrame los mails de steam del dia 10/09/2026 al 19/10/2026", "2026-09-10", "2026-10-20"),
    ("mails del 10/09/2026 al 19/10/2026", "2026-09-10", "2026-10-20"),
    ("mails del 12/10 al 15/10", "2026-10-12", "2026-10-16"),
    ("mails del 3/4 al 5/4", "2026-04-03", "2026-04-06"),
    ("mails entre el 12/10 y el 15/10", "2026-10-12", "2026-10-16"),
    ("mails del 12/10 hasta el 15/10", "2026-10-12", "2026-10-16"),
    ("mails de octubre a diciembre", "2026-10-01", "2027-01-01"),
    ("mails de diciembre a enero", "2025-12-01", "2026-02-01"),
])
def test_parse_date_range(message, from_day, to_day):
    assert parse_date_from_text(message, NOW) == {"dateFrom": _d(from_day), "dateTo": _d(to_day)}


def test_parse_inverted_range_with_years_keeps_the_first_date():
    # 20 October to 10 October is a typo, not a range that wraps a year.
    assert parse_date_from_text("mails del 20/10/2026 al 10/10/2026", NOW) == {
        "dateFrom": _d("2026-10-20"), "dateTo": _d("2026-10-21")}


def test_parse_single_date_prefixed_with_dia():
    assert parse_date_from_text("mails del dia 12/10", NOW) == {
        "dateFrom": _d("2026-10-12"), "dateTo": _d("2026-10-13")}


@pytest.mark.parametrize("token,days", [("last_7_days", 7), ("last_30_days", 30), ("last_90_days", 90)])
def test_last_n_days_covers_exactly_n_days(token, days):
    from datetime import date, timedelta
    from app.services.chat_agent import resolve_date_token
    out = resolve_date_token(token, NOW)
    start = date(*map(int, out["dateFrom"][:10].split("-")))
    end = date(*map(int, out["dateTo"][:10].split("-")))
    assert (end - start).days == days          # exclusive upper bound -> exactly n days
    assert end == date(2026, 10, 15)           # ends tomorrow local
