"""A pyqtgraph plot with up to four y-axes (left, right, second left, second right) sharing one time axis.

Each axis has its own ViewBox laid over the main one; all are x-linked, so zooming and panning move every series
together while each scale fits its own data. The bottom axis sits in its own row so the side axes line up exactly
with the plot area.
"""
from __future__ import annotations

import pyqtgraph as pg
from PySide6.QtCore import Signal

MAX_AXES = 4
# Layout columns: second left, left, plot, right, second right.
_AXIS_COLUMNS = (1, 3, 0, 4)
_AXIS_SIDES = ("left", "right", "left", "right")


class MultiAxisPlot(pg.GraphicsLayoutWidget):
    hovered = Signal(float)  # x in data coordinates
    reset_requested = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        self.ci.setSpacing(0)
        self.scene().sigMouseMoved.connect(self._moved)
        self.scene().sigMouseClicked.connect(lambda e: e.double() and self.reset_requested.emit())
        self.main: pg.ViewBox | None = None
        self.views: list[pg.ViewBox] = []
        self.axes: list[pg.AxisItem] = []
        self.crosshair: pg.InfiniteLine | None = None

    def configure(self, units: list[str], bottom: pg.AxisItem, fixed: dict[str, tuple[float, float]] | None = None):
        """Rebuild the layout for these y units (one axis each, in order) and the given bottom axis. Units in
        `fixed` get that y range instead of fitting the data (e.g. % always 0–100)."""
        fixed = fixed or {}
        if len(units) > MAX_AXES:
            raise ValueError(f"At most {MAX_AXES} y-axes")
        # The extra ViewBoxes live in the scene, not in the layout, so clear() alone would leave them (and their
        # curves) behind on every rebuild.
        for vb in self.views[1:]:
            vb.clear()
            if vb.scene() is not None:
                vb.scene().removeItem(vb)
        self.clear()
        self.views, self.axes = [], []
        plot = pg.PlotItem()
        plot.hideButtons()
        plot.setMenuEnabled(False)
        plot.hideAxis("left")
        plot.hideAxis("bottom")
        self.addItem(plot, row=0, col=2)
        self.main = plot.vb
        self.views.append(self.main)
        for _ in units[1:]:
            vb = pg.ViewBox()
            plot.scene().addItem(vb)
            vb.setXLink(self.main)
            self.views.append(vb)
        for i, unit in enumerate(units):
            axis = pg.AxisItem(_AXIS_SIDES[i])
            axis.enableAutoSIPrefix(False)  # values are already in display units
            axis.setStyle(maxTickLevel=0)
            axis.setLabel(unit)
            axis.linkToView(self.views[i])
            if i == 0:
                axis.setGrid(60)  # gridlines from the primary scale only
            self.addItem(axis, row=0, col=_AXIS_COLUMNS[i])
            self.axes.append(axis)
        bottom.linkToView(self.main)
        self.addItem(bottom, row=1, col=2)
        for unit, vb in zip(units, self.views):
            vb.setMouseEnabled(x=True, y=False)
            vb.setMenuEnabled(False)
            if unit in fixed:
                vb.disableAutoRange(axis="y")
                vb.setYRange(*fixed[unit], padding=0.02)
            else:
                vb.setAutoVisible(y=True)
                vb.enableAutoRange(axis="y")

        def sync():
            for vb in self.views[1:]:
                vb.setGeometry(self.main.sceneBoundingRect())
                vb.linkedViewChanged(self.main, vb.XAxis)
        self.main.sigResized.connect(sync)
        sync()
        # clear() deletes the old items, so the crosshair is created anew with each layout.
        self.crosshair = pg.InfiniteLine(angle=90, movable=False, pen=pg.mkPen((128, 128, 128), width=1))
        self.main.addItem(self.crosshair, ignoreBounds=True)
        self.crosshair.setVisible(False)

    def view(self, axis_index: int) -> pg.ViewBox:
        return self.views[axis_index]

    def set_x_range(self, lo: float, hi: float, min_range: float):
        for vb in self.views:
            vb.setLimits(xMin=lo, xMax=hi, minXRange=min_range)
        self.main.setXRange(lo, hi, padding=0)

    def x_range(self) -> tuple[float, float] | None:
        return tuple(self.main.viewRange()[0]) if self.main else None

    def show_crosshair(self, x: float | None):
        if self.crosshair is None:
            return
        self.crosshair.setVisible(x is not None)
        if x is not None:
            self.crosshair.setPos(x)

    def _moved(self, pos):
        if self.main is not None and self.main.sceneBoundingRect().contains(pos):
            self.hovered.emit(self.main.mapSceneToView(pos).x())
