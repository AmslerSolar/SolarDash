"""A plant: header with back button and name, and the Dashboard / History / Devices tabs, fed by live polling."""
from __future__ import annotations

import threading
import time

from PySide6.QtCore import QObject, QTimer, Signal
from PySide6.QtWidgets import QHBoxLayout, QLabel, QTabWidget, QToolButton, QVBoxLayout, QWidget

from ..model import Plant
from ..reader import PlantReader
from ..storage import ActivePolling, HistoryStore
from .dashboard_tab import DashboardTab
from .devices import DevicesTab
from .history_tab import HistoryTab

POLL_INTERVAL_MS = 5_000
RECORD_INTERVAL_S = 60


class PlantPoller(QObject):
    """Polls every device of a plant in a worker thread every 5 s; one sample per minute goes into the history."""
    snapshot = Signal(object)
    recorded = Signal()

    def __init__(self, plant: Plant, history: HistoryStore, active: ActivePolling, parent=None):
        super().__init__(parent)
        self.plant, self.history, self.active = plant, history, active
        self.reader = PlantReader(plant.devices)
        self._busy = False
        self._last_record = 0.0
        self._stopped = False
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._poll)

    def start(self):
        self._poll()
        self.timer.start(POLL_INTERVAL_MS)

    def stop(self):
        self._stopped = True
        self.timer.stop()
        self.active.stop(self.plant.id)
        threading.Thread(target=self.reader.close, daemon=True).start()

    def _poll(self):
        if self._busy or self._stopped:
            return  # a slow device is still answering the previous poll
        self._busy = True
        self.active.beat(self.plant.id)
        threading.Thread(target=self._work, daemon=True).start()

    def _work(self):
        try:
            snap = self.reader.read()
            if self._stopped:
                return
            self.snapshot.emit(snap)
            if snap.read_at - self._last_record >= RECORD_INTERVAL_S:
                self._last_record = snap.read_at
                try:
                    self.history.record(self.plant.id, snap)
                    self.recorded.emit()
                except Exception:
                    pass  # a storage problem shouldn't stop the live view
        finally:
            self._busy = False


class PlantPage(QWidget):
    back = Signal()
    rename = Signal()
    add_device = Signal()
    remove_device = Signal(object)
    rename_device = Signal(object, str)
    chart_channels = Signal(object)

    def __init__(self, plant: Plant, history: HistoryStore, active: ActivePolling, parent=None):
        super().__init__(parent)
        self.history, self.active = history, active
        self.poller: PlantPoller | None = None
        root = QVBoxLayout(self)
        header = QHBoxLayout()
        back = QToolButton(text="←")
        back.setToolTip("All plants")
        back.clicked.connect(self.back)
        self.title = QLabel()
        f = self.title.font()
        f.setPointSizeF(f.pointSizeF() * 1.6)
        self.title.setFont(f)
        rename = QToolButton(text="✎")
        rename.setToolTip("Rename plant")
        rename.clicked.connect(self.rename)
        header.addWidget(back)
        header.addWidget(self.title)
        header.addWidget(rename)
        header.addStretch(1)
        root.addLayout(header)

        self.tabs = QTabWidget()
        self.dashboard = DashboardTab()
        self.history_tab = HistoryTab(plant, history)
        self.devices = DevicesTab()
        self.tabs.addTab(self.dashboard, "Dashboard")
        self.tabs.addTab(self.history_tab, "History")
        self.tabs.addTab(self.devices, "Devices")
        self.dashboard.show_devices.connect(lambda: self.tabs.setCurrentWidget(self.devices))
        self.devices.add_requested.connect(self.add_device)
        self.devices.remove_requested.connect(self.remove_device)
        self.devices.rename_requested.connect(self.rename_device)
        self.history_tab.chart_channels_changed.connect(self.chart_channels)
        self.tabs.currentChanged.connect(lambda _: self.tabs.currentWidget() is self.history_tab and self.history_tab.reload(keep_view=True))
        root.addWidget(self.tabs, 1)
        self.plant = None
        self.set_plant(plant)

    def set_plant(self, plant: Plant):
        """Restarts polling when a device was added or removed, or its connection settings changed;
        a rename alone keeps the running connections."""
        def connections(p):
            return [(d.id, d.type, d.host, d.port, d.unit_id, d.password) for d in p.devices]
        devices_changed = self.plant is None or connections(self.plant) != connections(plant)
        self.plant = plant
        self.title.setText(plant.name)
        self.devices.set_plant(plant)
        self.history_tab.set_plant(plant)
        if devices_changed:
            self._restart()

    def _restart(self):
        if self.poller:
            self.poller.stop()
            self.poller.deleteLater()
        self.poller = None
        if not self.plant.devices:
            return
        self.poller = PlantPoller(self.plant, self.history, self.active, self)
        self.poller.snapshot.connect(self._on_snapshot)
        self.poller.recorded.connect(self.history_tab.on_recorded)
        self.poller.start()

    def _on_snapshot(self, snap):
        self.dashboard.set_snapshot(snap)
        self.devices.set_statuses(snap.devices)

    def stop(self):
        if self.poller:
            self.poller.stop()
            self.poller = None
