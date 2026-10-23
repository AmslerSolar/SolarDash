"""Formatting, series colors and small shared widgets."""
from __future__ import annotations

from dataclasses import dataclass

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QGuiApplication, QPainter, QPixmap, QIcon


def format_power(watts: int | None) -> str:
    if watts is None:
        return "–"
    return f"{watts / 1000:.2f} kW" if abs(watts) >= 1000 else f"{watts} W"


def format_energy(kwh: float | None) -> str:
    return "–" if kwh is None else f"{kwh:.1f} kWh"


def format_share(v: float | None) -> str:
    return "–" if v is None else f"{round(v * 100)} %"


def grid_direction(w: int | None) -> str | None:
    if w is None:
        return None
    return "Exporting" if w > 0 else "Importing" if w < 0 else "Idle"


def battery_direction(w: int) -> str:
    return "Discharging" if w > 0 else "Charging" if w < 0 else "Idle"


@dataclass(frozen=True)
class SeriesColors:
    """Validated as sets against the chart surface: the day/week lines and the energy bars each clear colorblind
    (≥ 8 ΔE) and normal-vision (≥ 15 ΔE) separation on all pairs. Light solar and dark consumption sit below 3:1
    contrast, so series always carry a visible text label too."""
    solar: QColor
    consumption: QColor
    battery: QColor
    grid: QColor  # grid power and exported energy
    import_: QColor


LIGHT = SeriesColors(QColor("#D99500"), QColor("#AB2B2B"), QColor("#17A195"), QColor("#2F7FD1"), QColor("#DC188B"))
DARK = SeriesColors(QColor("#C98500"), QColor("#BB231B"), QColor("#18AA99"), QColor("#3E8BE0"), QColor("#D7428C"))


def is_dark() -> bool:
    try:
        return QGuiApplication.styleHints().colorScheme() == Qt.ColorScheme.Dark
    except AttributeError:
        return QGuiApplication.palette().window().color().lightness() < 128


def series_colors() -> SeriesColors:
    return DARK if is_dark() else LIGHT


def dot_icon(color: QColor, size: int = 12) -> QIcon:
    pm = QPixmap(size, size)
    pm.fill(Qt.GlobalColor.transparent)
    p = QPainter(pm)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(color)
    p.drawEllipse(1, 1, size - 2, size - 2)
    p.end()
    return QIcon(pm)
