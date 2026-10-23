"""Building the detail view's sections: format values, skip what the device doesn't provide."""
from __future__ import annotations

from ..model import DetailSection


def fmt(value, unit: str = "", decimals: int | None = None) -> str | None:
    if value is None:
        return None
    if isinstance(value, float):
        if decimals is None:
            decimals = 0 if value.is_integer() and abs(value) >= 100 else 2 if abs(value) < 10 else 1
        text = f"{value:.{decimals}f}"
    else:
        text = str(value)
    return f"{text} {unit}".strip()


def enum(value: int | None, names: dict[int, str]) -> str | None:
    if value is None:
        return None
    return f"{names[value]} ({value})" if value in names else str(value)


def bits(value: int | None, width: int = 8) -> str | None:
    return None if value is None else f"0x{value:0{width}X}"


def section(title: str, *rows: tuple[str, str | None]) -> DetailSection:
    return DetailSection(title, tuple((label, v) for label, v in rows if v not in (None, "")))
