"""History: day and week as curves (plant power, battery charge and any added device channel, one y-axis per
unit, up to four, alternating left and right), month/year/lifetime as energy bars. Wheel or drag to zoom and pan
the time axis; hover for values; double-click to reset."""
from __future__ import annotations

import bisect
import calendar
import math
import threading
from dataclasses import dataclass
from datetime import date, datetime, time as dtime, timedelta

import pyqtgraph as pg
from html import escape

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QColor
from PySide6.QtWidgets import (QButtonGroup, QComboBox, QDialog, QDialogButtonBox, QFormLayout, QGroupBox,
                               QHBoxLayout, QHeaderView, QLabel, QLineEdit, QMessageBox, QPushButton, QStackedWidget,
                               QTableWidget, QTableWidgetItem, QToolButton, QTreeWidget, QTreeWidgetItem, QVBoxLayout,
                               QWidget)

from .. import history as hm
from ..demo_history import generate
from ..history import HistoryPeriod, HistoryRange, Sample
from ..model import EnergyTotals, Plant
from ..channels import display_unit
from ..storage import HistoryStore
from .common import dot_icon, format_energy, format_power, format_share, is_dark, series_colors
from .flow_layout import FlowLayout
from .multi_axis_plot import MAX_AXES, MultiAxisPlot

POWER_METRICS = [("Solar", "pv_w", "solar"), ("Consumption", "load_w", "consumption"),
                 ("Battery", "battery_w", "battery"), ("Grid", "grid_w", "grid")]
ENERGY_METRICS = [("Solar", "pv_kwh", "solar"), ("Consumption", "load_kwh", "consumption"),
                  ("Exported", "export_kwh", "grid"), ("Imported", "import_kwh", "import_")]

# Plant series on the day / week chart: (key, label, Sample field, colour role, factor, unit, line style).
PLANT_SERIES = [("plant:pv", "Solar", "pv_w", "solar", 1 / 1000, "kW", Qt.PenStyle.SolidLine),
                ("plant:load", "Consumption", "load_w", "consumption", 1 / 1000, "kW", Qt.PenStyle.SolidLine),
                ("plant:battery", "Battery", "battery_w", "battery", 1 / 1000, "kW", Qt.PenStyle.SolidLine),
                ("plant:grid", "Grid", "grid_w", "grid", 1 / 1000, "kW", Qt.PenStyle.SolidLine),
                ("plant:soc", "Battery charge", "soc_percent", "battery", 1.0, "%", Qt.PenStyle.DashLine)]

# Added device channels: at most three, each with a fixed colour slot and its own dash pattern. Validated with
# the four plant colours on all pairs: light CVD ΔE 9.4 / normal 15.3; dark CVD 7.5 (dash pattern as the second
# cue) / normal 15.7. Light-mode slot 1 and dark slots 2–3 sit below 3:1 contrast, so every series is named in
# the legend and the readout.
MAX_CHANNELS = 3
CHANNEL_LIGHT = ("#2F2FEE", "#F04C5C", "#7D32AE")
CHANNEL_DARK = ("#6A13EC", "#D926B5", "#8C2C96")
CHANNEL_STYLES = (Qt.PenStyle.DashDotLine, Qt.PenStyle.DotLine, Qt.PenStyle.DashDotDotLine)
MAX_GAP_FOR_READOUT = hm.MAX_GAP_SECONDS


@dataclass
class Slot:
    label: str
    short: str
    energy: EnergyTotals


@dataclass
class Series:
    key: str
    label: str  # shown on the chart: the channel name only, shortened
    full: str  # tooltip: full channel name, with its device
    unit: str  # display unit (one axis per unit)
    color: QColor
    style: Qt.PenStyle
    xs: list[int]
    ys: list[float]


def _epoch(d: date) -> int:
    return int(datetime.combine(d, dtime()).timestamp())


def _with_gaps(points: list[tuple[int, float]]) -> tuple[list[float], list[float]]:
    """Break the line (NaN) wherever recording paused for longer than the gap limit."""
    xs, ys, prev = [], [], None
    for t, v in points:
        if prev is not None and t - prev > hm.MAX_GAP_SECONDS:
            xs.append(prev + 1)
            ys.append(math.nan)
        xs.append(t)
        ys.append(v)
        prev = t
    return xs, ys


NAME_LIMIT = 15
PERCENT_RANGE = (0.0, 100.0)  # a % axis always shows 0–100 %


def shorten(name: str, limit: int = NAME_LIMIT) -> str:
    return name if len(name) <= limit else name[:limit] + "…"


def channel_name(key: str, label: str) -> str:
    """The channel's own name: for detail-view channels, the row label without its section."""
    if key.startswith("d:"):
        return key.split(":", 2)[2]
    return label


def channel_section(key: str) -> str:
    return key.split(":", 2)[1] if key.startswith("d:") else "Live values"


def format_value(v: float, unit: str) -> str:
    decimals = 0 if abs(v) >= 100 else 1 if abs(v) >= 10 else 2
    return f"{v:.{decimals}f} {unit}".strip()


def channel_color(slot: int) -> QColor:
    return QColor((CHANNEL_DARK if is_dark() else CHANNEL_LIGHT)[slot % MAX_CHANNELS])


class ChannelPicker(QDialog):
    """Pick device channels for the chart: device, search, then channels grouped like the device detail view."""

    def __init__(self, plant: Plant, store: HistoryStore, taken: set[tuple[str, str]], free: int, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Add channel to the chart")
        self.resize(560, 640)
        self.plant, self.store, self.taken, self.free = plant, store, taken, free
        root = QVBoxLayout(self)
        self.device = QComboBox()
        for d in plant.devices:
            self.device.addItem(d.name, d.id)
        self.device.currentIndexChanged.connect(self._fill)
        self.search = QLineEdit(placeholderText="Search channels…")
        self.search.textChanged.connect(self._filter)
        self.tree = QTreeWidget()
        self.tree.setHeaderLabels(["Channel", "Unit"])
        self.tree.setColumnWidth(0, 380)
        self.tree.itemChanged.connect(self._update)
        self.info = QLabel(wordWrap=True, styleSheet="color: palette(mid);")
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        self.ok = buttons.button(QDialogButtonBox.StandardButton.Ok)
        self.ok.setText("Add")
        for w in (QLabel("Device"), self.device, self.search, self.tree, self.info, buttons):
            root.addWidget(w)
        self.selected: list[tuple[str, str]] = []
        self._fill()

    def _channel_items(self):
        for i in range(self.tree.topLevelItemCount()):
            group = self.tree.topLevelItem(i)
            for j in range(group.childCount()):
                yield group, group.child(j)

    def _fill(self):
        self.tree.blockSignals(True)
        self.tree.clear()
        device_id = self.device.currentData()
        groups: dict[str, QTreeWidgetItem] = {}
        for key, label, unit in self.store.channel_catalog(device_id) if device_id else []:
            section = channel_section(key)
            if section not in groups:
                group = QTreeWidgetItem([section])
                group.setFlags(Qt.ItemFlag.ItemIsEnabled)
                f = group.font(0)
                f.setBold(True)
                group.setFont(0, f)
                self.tree.addTopLevelItem(group)
                groups[section] = group
            item = QTreeWidgetItem([channel_name(key, label), unit])
            item.setData(0, Qt.ItemDataRole.UserRole, (device_id, key))
            done = (device_id, key) in self.taken
            item.setFlags((Qt.ItemFlag.ItemIsUserCheckable | Qt.ItemFlag.ItemIsSelectable) |
                          (Qt.ItemFlag(0) if done else Qt.ItemFlag.ItemIsEnabled))
            item.setCheckState(0, Qt.CheckState.Checked if done else Qt.CheckState.Unchecked)
            if done:
                item.setToolTip(0, "Already on the chart")
            groups[section].addChild(item)
        self.tree.expandAll()
        self.tree.blockSignals(False)
        self._filter(self.search.text())
        if not groups:
            self.info.setText("No recorded channels for this device yet. They appear about a minute after the "
                              "device has been read.")
        self._update()

    def _filter(self, text: str):
        text = text.lower()
        for i in range(self.tree.topLevelItemCount()):
            group = self.tree.topLevelItem(i)
            group_match = text in group.text(0).lower()
            visible = 0
            for j in range(group.childCount()):
                child = group.child(j)
                show = group_match or text in child.text(0).lower() or text in child.text(1).lower()
                child.setHidden(not show)
                visible += show
            group.setHidden(visible == 0)

    def _update(self, *_):
        self.selected = [item.data(0, Qt.ItemDataRole.UserRole) for _, item in self._channel_items()
                         if item.checkState(0) == Qt.CheckState.Checked and item.flags() & Qt.ItemFlag.ItemIsEnabled]
        too_many = len(self.selected) > self.free
        self.ok.setEnabled(bool(self.selected) and not too_many)
        if self.tree.topLevelItemCount():
            self.info.setText(f"Up to {MAX_CHANNELS} added channels at a time, so the chart stays readable "
                              f"({self.free} free)." + (" Too many selected." if too_many else ""))


class HistoryTab(QWidget):
    demo_filled = Signal()
    chart_channels_changed = Signal(object)  # tuple of (device id, channel key, colour slot)

    def __init__(self, plant: Plant, store: HistoryStore, parent=None):
        super().__init__(parent)
        self.plant = plant
        self.store = store
        self.range = HistoryRange(HistoryPeriod.DAY, date.today())
        self.hidden_power: set[str] = set()  # plant series and channel keys switched off in the legend
        self.hidden_energy: set[str] = {"export_kwh", "import_kwh"}
        self._samples: list[Sample] = []
        self._series: list[Series] = []
        self._slots: list[Slot] = []

        root = QVBoxLayout(self)

        # Period buttons, then ‹ label › and the chart/table toggle.
        periods = QHBoxLayout()
        self.period_group = QButtonGroup(self)
        for period in HistoryPeriod:
            b = QPushButton(period.value)
            b.setCheckable(True)
            b.setChecked(period is self.range.period)
            self.period_group.addButton(b)
            b.clicked.connect(lambda _=False, p=period: self.set_period(p))
            periods.addWidget(b)
        periods.addStretch()
        root.addLayout(periods)

        nav = QHBoxLayout()
        self.prev_btn = QToolButton(text="‹")
        self.next_btn = QToolButton(text="›")
        self.prev_btn.clicked.connect(lambda: self._shift(-1))
        self.next_btn.clicked.connect(lambda: self._shift(1))
        self.range_label = QLabel(alignment=Qt.AlignmentFlag.AlignCenter)
        f = self.range_label.font()
        f.setPointSizeF(f.pointSizeF() * 1.25)
        self.range_label.setFont(f)
        self.table_btn = QPushButton("Table")
        self.table_btn.setCheckable(True)
        self.table_btn.toggled.connect(self._show_table)
        nav.addWidget(self.prev_btn)
        nav.addWidget(self.range_label, 1)
        nav.addWidget(self.next_btn)
        nav.addWidget(self.table_btn)
        root.addLayout(nav)

        # Legend toggles (also the legend), channel chips and "Add channel…", readout, chart / table.
        legend = QWidget()
        self.legend = FlowLayout(legend)
        root.addWidget(legend)
        self.readout = QLabel(textFormat=Qt.TextFormat.RichText, wordWrap=True)
        self.readout.setMinimumHeight(40)
        root.addWidget(self.readout)

        self.stack = QStackedWidget()
        self.plot = MultiAxisPlot()
        self.plot.hovered.connect(self._on_hover)
        self.plot.reset_requested.connect(self.reload)
        self.table = QTableWidget()
        self.table.verticalHeader().setVisible(False)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Stretch)
        self.empty = QLabel(alignment=Qt.AlignmentFlag.AlignCenter, wordWrap=True)
        self.stack.addWidget(self.plot)
        self.stack.addWidget(self.table)
        self.stack.addWidget(self.empty)
        root.addWidget(self.stack, 1)
        root.addWidget(QLabel("Scroll or drag to zoom and pan the time axis · hover for values · double-click to reset",
                              styleSheet="color: palette(mid);"))

        bottom = QHBoxLayout()
        totals = QGroupBox("Totals")
        self.totals = QFormLayout(totals)
        bottom.addWidget(totals, 1)
        self.demo_btn = QPushButton("Fill with two years of demo history")
        self.demo_btn.clicked.connect(self._fill_demo)
        self.demo_filled.connect(self._demo_done)
        bottom.addWidget(self.demo_btn, alignment=Qt.AlignmentFlag.AlignBottom)
        root.addLayout(bottom)

        self.reload()

    # --- navigation ---------------------------------------------------------------------------------------
    def set_period(self, period: HistoryPeriod):
        self.range = HistoryRange(period, self.range.anchor)
        self.reload()

    def _shift(self, by: int):
        if by > 0 and not self.range.has_next(date.today()):
            return
        self.range = self.range.shift(by)
        self.reload()

    def set_plant(self, plant: Plant):
        self.plant = plant
        self.reload(keep_view=True)

    def on_recorded(self):
        """New live sample: refresh only if the shown range includes today."""
        end = self.range.end
        if self.isVisible() and (end is None or end >= date.today()):
            self.reload(keep_view=True)

    def _show_table(self, on: bool):
        self.reload(keep_view=True)

    # --- chart channels -----------------------------------------------------------------------------------
    def _channels(self) -> list[tuple[str, str, int]]:
        """Added channels whose device still exists."""
        ids = {d.id for d in self.plant.devices}
        return [tuple(c) for c in self.plant.chart_channels if c[0] in ids]

    def _add_channels(self):
        current = self._channels()
        taken = {(d, k) for d, k, _ in current}
        free = MAX_CHANNELS - len(current)
        if free <= 0:
            QMessageBox.information(self, "Chart is full", f"At most {MAX_CHANNELS} added channels at a time, so "
                                    "the chart stays readable. Remove one first.")
            return
        dlg = ChannelPicker(self.plant, self.store, taken, free, self)
        if not dlg.exec() or not dlg.selected:
            return
        used = {slot for *_, slot in current}
        added = list(current)
        for device_id, key in dlg.selected:
            slot = next(s for s in range(MAX_CHANNELS) if s not in used)
            used.add(slot)
            added.append((device_id, key, slot))
        units = self._units(added, self.hidden_power)
        if len(units) > MAX_AXES:
            QMessageBox.information(self, "Too many units", f"The chart shows at most {MAX_AXES} different units "
                                    f"at once ({', '.join(units)}). Hide or remove a series with another unit first.")
            return
        self.chart_channels_changed.emit(tuple(added))

    def _remove_channel(self, device_id: str, key: str):
        self.chart_channels_changed.emit(tuple(c for c in self._channels() if (c[0], c[1]) != (device_id, key)))

    def _catalog(self, device_id: str) -> dict[str, tuple[str, str]]:
        return {k: (label, unit) for k, label, unit in self.store.channel_catalog(device_id)}

    def _units(self, channels, hidden: set[str]) -> list[str]:
        """Axis units in order: plant power, battery charge, then added channels by slot."""
        units: list[str] = []
        for key, *_, unit, _style in PLANT_SERIES:
            if key not in hidden and unit not in units:
                units.append(unit)
        for device_id, key, _slot in sorted(channels, key=lambda c: c[2]):
            if f"ch:{device_id}:{key}" in hidden:
                continue
            unit = display_unit(self._catalog(device_id).get(key, ("", ""))[1])[0]
            if unit not in units:
                units.append(unit)
        return units

    # --- loading ------------------------------------------------------------------------------------------
    def reload(self, keep_view: bool = False):
        r = self.range
        lifetime = r.period is HistoryPeriod.LIFETIME
        self.range_label.setText(r.label())
        for b in self.period_group.buttons():
            b.setChecked(b.text() == r.period.value)
        self.prev_btn.setEnabled(not lifetime)
        self.next_btn.setEnabled(not lifetime and r.has_next(date.today()))
        self.demo_btn.setVisible(self.plant.has_demo_devices)
        has_data = self.store.first_day(self.plant.id) is not None

        view = self.plot.x_range() if keep_view else None
        self.readout.setText("Hover over the chart for values")
        self._samples, self._series, self._slots = [], [], []

        if r.period in (HistoryPeriod.DAY, HistoryPeriod.WEEK):
            start, end = _epoch(r.start), _epoch(r.end + timedelta(days=1))
            self._samples = self.store.samples(self.plant.id, start, end)
            days = self.store.days(self.plant.id, r.start, r.end)
            totals = hm.sum_totals(list(days.values()))
            self._series = self._build_series(start, end)
            self._build_power_legend()
            if self.table_btn.isChecked():
                if r.period is HistoryPeriod.WEEK:
                    self._energy_table(self._day_slots(r.start, r.end, days))
                else:
                    self._hourly_table()
            elif not any(s.xs for s in self._series):
                self._show_empty(has_data, "No samples recorded in this period.")
            else:
                self._draw_power(start, end)
                if view and view[0] >= start and view[1] <= end:
                    self.plot.main.setXRange(*view, padding=0)
        else:
            if r.period is HistoryPeriod.MONTH:
                days = self.store.days(self.plant.id, r.start, r.end)
                slots = self._day_slots(r.start, r.end, days, short="%d")
                totals = hm.sum_totals(list(days.values()))
            elif r.period is HistoryPeriod.YEAR:
                months = self.store.months(self.plant.id, r.anchor.year)
                slots = [Slot(f"{calendar.month_name[m]} {r.anchor.year}", calendar.month_abbr[m],
                              months.get(m, EnergyTotals())) for m in range(1, 13)]
                totals = hm.sum_totals(list(months.values()))
            else:
                years = self.store.years(self.plant.id)
                first = min(years) if years else date.today().year
                slots = [Slot(str(y), str(y), years.get(y, EnergyTotals())) for y in range(first, date.today().year + 1)]
                totals = hm.sum_totals(list(years.values()))
            self._slots = slots
            self._build_energy_legend()
            if self.table_btn.isChecked():
                self._energy_table(slots)
            elif not has_data:
                self._show_empty(False, "")
            else:
                self._draw_energy(slots)
                if view and view[1] <= len(slots):
                    self.plot.main.setXRange(*view, padding=0)
        self._fill_totals(totals)

    def _build_series(self, start: int, end: int) -> list[Series]:
        colors = series_colors()
        out = []
        for key, label, field, role, factor, unit, style in PLANT_SERIES:
            points = [(s.epoch, getattr(s, field) * factor) for s in self._samples if getattr(s, field) is not None]
            if unit == "%":
                # Charge outside 0–100 % is invalid (e.g. samples stored before a driver scaling fix).
                points = [(t, v) for t, v in points if PERCENT_RANGE[0] <= v <= PERCENT_RANGE[1]]
            xs, ys = _with_gaps(points)
            out.append(Series(key, label, label, unit, getattr(colors, role), style, xs, ys))
        by_device: dict[str, list[tuple[str, int]]] = {}
        for device_id, key, slot in self._channels():
            by_device.setdefault(device_id, []).append((key, slot))
        names = {d.id: d.name for d in self.plant.devices}
        for device_id, entries in by_device.items():
            catalog = self._catalog(device_id)
            data = self.store.channel_series(device_id, [k for k, _ in entries], start, end)
            for key, slot in entries:
                label, unit = catalog.get(key, (key, ""))
                shown, factor = display_unit(unit)
                xs, ys = _with_gaps([(t, v * factor) for t, v in data.get(key, [])])
                name = channel_name(key, label)
                full = f"{name} — {names.get(device_id, '?')}" + (f" ({channel_section(key)})" if key.startswith("d:") else "")
                out.append(Series(f"ch:{device_id}:{key}", shorten(name), full, shown,
                                  channel_color(slot), CHANNEL_STYLES[slot % MAX_CHANNELS], xs, ys))
        return out

    def _day_slots(self, start: date, end: date, days: dict, short: str = "%a") -> list[Slot]:
        out, d = [], start
        while d <= end:
            out.append(Slot(d.strftime("%a, %d %b"), d.strftime(short).lstrip("0"), days.get(d, EnergyTotals())))
            d += timedelta(days=1)
        return out

    def _show_empty(self, has_data: bool, text: str):
        self.empty.setText(text if has_data else
                           "No history yet. The app records once a minute while a plant is open. "
                           "For recording while it's closed, see the README (background recorder).")
        self.stack.setCurrentWidget(self.empty)

    # --- legend -------------------------------------------------------------------------------------------
    def _clear_legend(self):
        while self.legend.count():
            item = self.legend.takeAt(0)
            if item.widget():
                item.widget().deleteLater()

    def _toggle(self, key: str, on: bool, hidden: set[str]):
        if on and hidden is self.hidden_power:
            trial = hidden - {key}
            if len(self._units(self._channels(), trial)) > MAX_AXES:
                QMessageBox.information(self, "Too many units", f"The chart shows at most {MAX_AXES} different "
                                        "units at once. Hide a series with another unit first.")
                self.reload(keep_view=True)
                return
        (hidden.discard if on else hidden.add)(key)
        self.reload(keep_view=True)

    def _legend_button(self, text: str, color: QColor, key: str, hidden: set[str], tooltip: str = "") -> QToolButton:
        b = QToolButton(text=text, checkable=True, checked=key not in hidden)
        b.setIcon(dot_icon(color))
        b.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextBesideIcon)
        b.setToolTip(tooltip)
        b.toggled.connect(lambda on, k=key: self._toggle(k, on, hidden))
        return b

    def _build_power_legend(self):
        self._clear_legend()
        for s in self._series:
            if s.key.startswith("plant:"):
                self.legend.addWidget(self._legend_button(f"{s.label} ({s.unit})", s.color, s.key, self.hidden_power,
                                                          f"{s.full} ({s.unit})"))
        for s in self._series:
            if s.key.startswith("ch:"):
                _, device_id, key = s.key.split(":", 2)
                b = self._legend_button(f"{s.label} ({s.unit})", s.color, s.key, self.hidden_power,
                                        f"{s.full} ({s.unit}) · dashed: an added device channel")
                remove = QToolButton(text="✕")
                remove.setToolTip("Remove from the chart")
                remove.setAutoRaise(True)
                remove.clicked.connect(lambda _=False, d=device_id, k=key: self._remove_channel(d, k))
                chip = QWidget()  # name and ✕ wrap together
                row = QHBoxLayout(chip)
                row.setContentsMargins(0, 0, 0, 0)
                row.setSpacing(0)
                row.addWidget(b)
                row.addWidget(remove)
                self.legend.addWidget(chip)
        add = QPushButton("Add channel…")
        add.setToolTip("Add any recorded value of any device of this plant")
        add.clicked.connect(self._add_channels)
        add.setEnabled(bool(self.plant.devices))
        self.legend.addWidget(add)

    def _build_energy_legend(self):
        self._clear_legend()
        colors = series_colors()
        for label, key, color in ENERGY_METRICS:
            self.legend.addWidget(self._legend_button(label, getattr(colors, color), key, self.hidden_energy))
        self.legend.addWidget(QLabel("Added channels show on the Day and Week charts.", styleSheet="color: palette(mid);"))

    # --- power curve --------------------------------------------------------------------------------------
    def _draw_power(self, start: int, end: int):
        self.stack.setCurrentWidget(self.plot)
        # Series without data in this period don't get a scale.
        visible = [s for s in self._series if s.key not in self.hidden_power and s.xs]
        units = []
        for s in visible:
            if s.unit not in units:
                units.append(s.unit)
        units = units[:MAX_AXES]
        self.plot.configure(units or ["kW"], pg.DateAxisItem(orientation="bottom"),
                            fixed={u: PERCENT_RANGE for u in units if u == "%"})
        for s in visible:
            if s.unit not in units or not s.xs:
                continue
            pen = pg.mkPen(s.color, width=2, style=s.style)
            self.plot.view(units.index(s.unit)).addItem(pg.PlotDataItem(s.xs, s.ys, pen=pen, connect="finite"))
        self.plot.set_x_range(start, end, 1800)

    # --- energy bars --------------------------------------------------------------------------------------
    def _draw_energy(self, slots: list[Slot]):
        self.stack.setCurrentWidget(self.plot)
        colors = series_colors()
        axis = pg.AxisItem(orientation="bottom")
        axis.setTicks([[(i, s.short) for i, s in enumerate(slots)]])
        self.plot.configure(["kWh"], axis)
        shown = [m for m in ENERGY_METRICS if m[1] not in self.hidden_energy]
        group = 0.72
        width = group / max(1, len(shown))
        for k, (label, key, color) in enumerate(shown):
            xs, hs = [], []
            for i, s in enumerate(slots):
                v = getattr(s.energy, key)
                if v:
                    xs.append(i - group / 2 + width * (k + 0.5))
                    hs.append(v)
            if xs:
                c = getattr(colors, color)
                self.plot.view(0).addItem(pg.BarGraphItem(x=xs, height=hs, width=width * 0.9, brush=c, pen=pg.mkPen(c)))
        n = len(slots)
        self.plot.set_x_range(-0.6, n - 0.4, min(3, n))

    # --- hover readout ------------------------------------------------------------------------------------
    def _on_hover(self, x: float):
        if self.stack.currentWidget() is not self.plot:
            return
        colors = series_colors()
        if self._series:
            items, nearest_t = [], None
            for s in self._series:
                if s.key in self.hidden_power or not s.xs:
                    continue
                i = bisect.bisect_left(s.xs, x)
                best = None
                for j in (i - 1, i):
                    if 0 <= j < len(s.xs) and not math.isnan(s.ys[j]) and abs(s.xs[j] - x) <= MAX_GAP_FOR_READOUT:
                        if best is None or abs(s.xs[j] - x) < abs(s.xs[best] - x):
                            best = j
                if best is not None:
                    items.append((s.color, f"{s.label} {format_value(s.ys[best], s.unit)}"))
                    if nearest_t is None or abs(s.xs[best] - x) < abs(nearest_t - x):
                        nearest_t = s.xs[best]
            if nearest_t is None:
                return
            self.plot.show_crosshair(nearest_t)
            fmt = "%a %d %b, %H:%M" if self.range.period is HistoryPeriod.WEEK else "%H:%M"
            self._readout(datetime.fromtimestamp(nearest_t).strftime(fmt), items)
        elif self._slots:
            i = min(len(self._slots) - 1, max(0, round(x)))
            s = self._slots[i]
            self.plot.show_crosshair(i)
            items = [(getattr(colors, c), f"{label} {format_energy(getattr(s.energy, key))}")
                     for label, key, c in ENERGY_METRICS
                     if key not in self.hidden_energy and getattr(s.energy, key) is not None]
            self._readout(s.label, items)

    def _readout(self, title: str, items):
        parts = " &nbsp; ".join(f"<span style='color:{c.name()}'>●</span> {escape(t)}" for c, t in items)
        self.readout.setText(f"<b>{title}</b><br>{parts}")

    # --- tables -------------------------------------------------------------------------------------------
    def _energy_table(self, slots: list[Slot]):
        rows = [s for s in slots if any(getattr(s.energy, k) is not None for _, k, _ in ENERGY_METRICS)]
        self.table.clear()
        self.table.setColumnCount(5)
        self.table.setHorizontalHeaderLabels(["", "Solar", "Consumption", "Exported", "Imported"])
        self.table.setRowCount(len(rows))
        for i, s in enumerate(rows):
            for j, v in enumerate([s.label] + [format_energy(getattr(s.energy, k)) for _, k, _ in ENERGY_METRICS]):
                self.table.setItem(i, j, QTableWidgetItem(v))
        self.stack.setCurrentWidget(self.table)

    def _hourly_table(self):
        by_hour: dict[int, list[Sample]] = {}
        for s in self._samples:
            by_hour.setdefault(datetime.fromtimestamp(s.epoch).hour, []).append(s)
        self.table.clear()
        self.table.setColumnCount(5)
        self.table.setHorizontalHeaderLabels(["Hour (average)", "Solar", "Consumption", "Battery", "Grid"])
        self.table.setRowCount(len(by_hour))
        for i, (hour, rows) in enumerate(sorted(by_hour.items())):
            def avg(key):
                vals = [getattr(r, key) for r in rows if getattr(r, key) is not None]
                return format_power(round(sum(vals) / len(vals))) if vals else "–"
            for j, v in enumerate([f"{hour:02d}:00"] + [avg(k) for _, k, _ in POWER_METRICS]):
                self.table.setItem(i, j, QTableWidgetItem(v))
        self.stack.setCurrentWidget(self.table)

    def _fill_totals(self, e: EnergyTotals):
        while self.totals.rowCount():
            self.totals.removeRow(0)
        for label, value in [("Solar yield", format_energy(e.pv_kwh)), ("Consumption", format_energy(e.load_kwh)),
                             ("Exported", format_energy(e.export_kwh)), ("Imported", format_energy(e.import_kwh)),
                             ("Self-sufficiency", format_share(hm.self_sufficiency(e))),
                             ("Self-consumption", format_share(hm.self_consumption(e)))]:
            v = QLabel(value)
            v.setAlignment(Qt.AlignmentFlag.AlignRight)
            self.totals.addRow(label, v)

    # --- demo ---------------------------------------------------------------------------------------------
    def _fill_demo(self):
        self.demo_btn.setEnabled(False)
        self.demo_btn.setText("Generating…")

        def work():
            samples, days = generate(hash(self.plant.id) & 0xFFFFFFFF)
            self.store.insert(self.plant.id, samples, days)
            self.demo_filled.emit()
        threading.Thread(target=work, daemon=True).start()

    def _demo_done(self):
        self.demo_btn.setEnabled(True)
        self.demo_btn.setText("Fill with two years of demo history")
        self.reload()
