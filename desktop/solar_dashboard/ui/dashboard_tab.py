from __future__ import annotations

from datetime import datetime

from PySide6.QtCore import Qt, Signal
from PySide6.QtWidgets import QFormLayout, QGroupBox, QHBoxLayout, QLabel, QPushButton, QVBoxLayout, QWidget

from ..model import PlantSnapshot
from .common import format_energy
from .energy_flow import EnergyFlowWidget


class DashboardTab(QWidget):
    show_devices = Signal()

    def __init__(self, parent=None):
        super().__init__(parent)
        root = QHBoxLayout(self)
        self.flow = EnergyFlowWidget()
        root.addWidget(self.flow, 3)

        side = QVBoxLayout()
        self.status = QLabel("Waiting for data…")
        side.addWidget(self.status)
        self.warning = QLabel(styleSheet="color: #c62828;", wordWrap=True)
        self.show_btn = QPushButton("Show devices")
        self.show_btn.clicked.connect(self.show_devices)
        side.addWidget(self.warning)
        side.addWidget(self.show_btn, alignment=Qt.AlignmentFlag.AlignLeft)
        today = QGroupBox("Today")
        self.today = QFormLayout(today)
        self.values = {}
        for label in ("Solar yield", "Consumption", "Exported", "Imported"):
            v = QLabel("–", alignment=Qt.AlignmentFlag.AlignRight)
            self.values[label] = v
            self.today.addRow(label, v)
        side.addWidget(today)
        side.addStretch()
        root.addLayout(side, 2)
        self._set_warning(0, 0)

    def set_snapshot(self, s: PlantSnapshot):
        self.flow.set_snapshot(s)
        self.status.setText(f"Updated {datetime.fromtimestamp(s.read_at):%H:%M:%S}")
        self._set_warning(len(s.failed_devices), len(s.devices))
        t = s.today
        for label, v in (("Solar yield", t.pv_kwh), ("Consumption", t.load_kwh),
                         ("Exported", t.export_kwh), ("Imported", t.import_kwh)):
            self.values[label].setText(format_energy(v))

    def _set_warning(self, failed: int, total: int):
        self.warning.setVisible(failed > 0)
        self.show_btn.setVisible(failed > 0)
        self.warning.setText(f"{failed} of {total} devices not responding. Totals are incomplete.")
