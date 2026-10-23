"""Energy flow between solar, grid, home and battery through a central point.

Dots travel in the direction energy flows, faster at higher power; the figures in the circles are magnitudes.
"""
from __future__ import annotations

import math
import time

from PySide6.QtCore import QPointF, QRectF, Qt, QTimer
from PySide6.QtGui import QColor, QFont, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import QSizePolicy, QWidget

from ..model import PlantSnapshot
from .common import battery_direction, format_power, grid_direction, series_colors

def pen(color: QColor, width: float, cap=Qt.PenCapStyle.RoundCap, join=Qt.PenJoinStyle.RoundJoin) -> QPen:
    p = QPen(color)
    p.setWidthF(width)
    p.setCapStyle(cap)
    p.setJoinStyle(join)
    return p


IDLE_W = 20  # below this a branch counts as idle: grey line, no dots
DOTS_PER_LINE = 3
MIN_NODE, MAX_NODE = 90.0, 150.0
SPACING, SPACING_EXTRA = 0.9, 8.0  # centre-to-centre distance to the middle: node * SPACING + extra
CAPTION = 24.0
EDGE = 8.0


class EnergyFlowWidget(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.snapshot: PlantSnapshot | None = None
        self.setMinimumSize(320, 360)
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self._start = time.monotonic()
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.update)
        self._timer.start(33)

    def set_snapshot(self, snapshot: PlantSnapshot | None):
        self.snapshot = snapshot
        self._update_accessible()
        self.update()

    def _update_accessible(self):
        s = self.snapshot
        if not s:
            return
        parts = [f"Solar {format_power(s.pv_power_w)}", f"Home {format_power(s.load_power_w)}"]
        if s.battery:
            soc = f"{s.battery.soc_percent} percent, " if s.battery.soc_percent is not None else ""
            parts.append(f"Battery {soc}{battery_direction(s.battery.power_w).lower()} {format_power(abs(s.battery.power_w))}")
        g = s.grid_power_w
        parts.append(f"Grid {grid_direction(g).lower()} {format_power(abs(g))}" if g is not None else "Grid unknown")
        self.setAccessibleDescription(". ".join(parts) + ".")

    # --- layout -------------------------------------------------------------------------------------------
    def _geometry(self):
        factor = 2 * SPACING + 1
        by_w = (self.width() - 2 * EDGE - 2 * SPACING_EXTRA) / factor
        by_h = (self.height() - 2 * CAPTION - 2 * SPACING_EXTRA) / factor
        node = max(MIN_NODE, min(MAX_NODE, by_w, by_h))
        spacing = node * SPACING + SPACING_EXTRA
        hub = QPointF(self.width() / 2, self.height() / 2)
        return node, {
            "solar": QPointF(hub.x(), hub.y() - spacing),
            "grid": QPointF(hub.x() - spacing, hub.y()),
            "home": QPointF(hub.x() + spacing, hub.y()),
            "battery": QPointF(hub.x(), hub.y() + spacing),
        }, hub

    # --- painting -----------------------------------------------------------------------------------------
    def paintEvent(self, _event):
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        pal = self.palette()
        surface = pal.base().color()
        text = pal.text().color()
        idle = QColor(pal.mid().color())
        colors = series_colors()
        s = self.snapshot
        node, pos, hub = self._geometry()
        seconds = time.monotonic() - self._start

        pv = s.pv_power_w if s else None
        load = s.load_power_w if s else None
        grid = s.grid_power_w if s else None
        battery = s.battery if s else None

        # Power flowing *into* the centre per branch; negative flows out to the node.
        branches = [("solar", pv or 0, colors.solar), ("grid", -(grid or 0), colors.grid),
                    ("home", -(load or 0), colors.consumption)]
        if battery is not None:
            branches.append(("battery", battery.power_w, colors.battery))

        for name, power_in, color in branches:
            end = pos[name]
            active = abs(power_in) >= IDLE_W
            line = QColor(color)
            line.setAlphaF(0.35)
            p.setPen(pen(line if active else idle, 3))
            p.drawLine(end, hub)
            if active:
                speed = 0.25 + 0.75 * min(1.0, abs(power_in) / 15_000)  # cycles per second
                a, b = (end, hub) if power_in > 0 else (hub, end)
                p.setPen(Qt.PenStyle.NoPen)
                p.setBrush(color)
                for i in range(DOTS_PER_LINE):
                    t = (seconds * speed + i / DOTS_PER_LINE) % 1.0
                    p.drawEllipse(a + (b - a) * t, 4, 4)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(idle)
        p.drawEllipse(hub, 5, 5)

        value_font = QFont(self.font())
        value_font.setPixelSize(int(node * 0.16))
        value_font.setWeight(QFont.Weight.DemiBold)
        caption_font = QFont(self.font())
        caption_font.setPixelSize(14)

        def draw_node(name, color, power, caption, icon, soc=None):
            c = pos[name]
            r = node / 2
            rect = QRectF(c.x() - r, c.y() - r, node, node)
            p.setBrush(surface)
            if soc is None:
                p.setPen(pen(color, 2))
                p.drawEllipse(rect)
            else:
                p.setPen(pen(idle, 4))
                inner = rect.adjusted(2, 2, -2, -2)
                p.drawEllipse(inner)
                p.setPen(pen(color, 4))
                p.setBrush(Qt.BrushStyle.NoBrush)
                p.drawArc(inner, 90 * 16, -int(360 * 16 * max(0, min(100, soc)) / 100))
            icon(p, QPointF(c.x(), c.y() - r * 0.55), node * 0.2, color)
            p.setFont(value_font)
            p.setPen(text)
            line_h = node * 0.22
            p.drawText(QRectF(c.x() - r, c.y() - line_h / 2, node, line_h), Qt.AlignmentFlag.AlignCenter, power)
            if soc is not None:
                p.drawText(QRectF(c.x() - r, c.y() + r * 0.38 - line_h / 2, node, line_h), Qt.AlignmentFlag.AlignCenter, f"{soc} %")
            p.setFont(caption_font)
            p.setPen(text)
            y = c.y() - r - CAPTION if name == "solar" else c.y() + r + 2
            p.drawText(QRectF(c.x() - r - 30, y, node + 60, CAPTION - 2), Qt.AlignmentFlag.AlignCenter, caption)

        draw_node("solar", colors.solar, format_power(pv), "Solar", _sun)
        draw_node("grid", colors.grid, format_power(abs(grid)) if grid is not None else "–", "Grid", _plug)
        draw_node("home", colors.consumption, format_power(load), "Home", _house)
        if battery is not None:
            draw_node("battery", colors.battery, format_power(abs(battery.power_w)), "Battery", _battery,
                      soc=battery.soc_percent)
        p.end()


# --- icons: simple strokes, centred on c, about `size` tall ---------------------------------------------------
def _pen(p: QPainter, color: QColor, size: float):
    p.setPen(pen(color, max(1.5, size * 0.09)))
    p.setBrush(Qt.BrushStyle.NoBrush)


def _sun(p: QPainter, c: QPointF, size: float, color: QColor):
    _pen(p, color, size)
    r = size * 0.22
    p.drawEllipse(c, r, r)
    for i in range(8):
        a = i * math.pi / 4
        p.drawLine(QPointF(c.x() + math.cos(a) * r * 1.6, c.y() + math.sin(a) * r * 1.6),
                   QPointF(c.x() + math.cos(a) * r * 2.2, c.y() + math.sin(a) * r * 2.2))


def _house(p: QPainter, c: QPointF, size: float, color: QColor):
    _pen(p, color, size)
    w, h = size * 0.8, size * 0.8
    path = QPainterPath()
    path.moveTo(c.x() - w / 2, c.y())
    path.lineTo(c.x(), c.y() - h / 2)
    path.lineTo(c.x() + w / 2, c.y())
    p.drawPath(path)
    p.drawRect(QRectF(c.x() - w * 0.35, c.y(), w * 0.7, h * 0.45))
    p.drawRect(QRectF(c.x() - w * 0.1, c.y() + h * 0.18, w * 0.2, h * 0.27))


def _battery(p: QPainter, c: QPointF, size: float, color: QColor):
    _pen(p, color, size)
    w, h = size * 0.5, size * 0.85
    p.drawRoundedRect(QRectF(c.x() - w / 2, c.y() - h / 2 + size * 0.08, w, h - size * 0.08), 2, 2)
    p.drawLine(QPointF(c.x() - w * 0.2, c.y() - h / 2), QPointF(c.x() + w * 0.2, c.y() - h / 2))
    bolt = QPainterPath()
    bolt.moveTo(c.x() + w * 0.08, c.y() - h * 0.25)
    bolt.lineTo(c.x() - w * 0.15, c.y() + h * 0.05)
    bolt.lineTo(c.x() + w * 0.1, c.y() + h * 0.05)
    bolt.lineTo(c.x() - w * 0.08, c.y() + h * 0.3)
    p.drawPath(bolt)


def _plug(p: QPainter, c: QPointF, size: float, color: QColor):
    _pen(p, color, size)
    w = size * 0.5
    body = QRectF(c.x() - w / 2, c.y() - size * 0.12, w, size * 0.32)
    p.drawRoundedRect(body, 3, 3)
    for dx in (-w * 0.22, w * 0.22):
        p.drawLine(QPointF(c.x() + dx, body.top()), QPointF(c.x() + dx, body.top() - size * 0.25))
    p.drawLine(QPointF(c.x(), body.bottom()), QPointF(c.x(), body.bottom() + size * 0.22))
