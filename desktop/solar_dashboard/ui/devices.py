"""Devices tab, the add-device dialog and the device detail dialog."""
from __future__ import annotations

import threading
import uuid
from html import escape

from PySide6.QtCore import Qt, Signal
from PySide6.QtGui import QStandardItem, QStandardItemModel
from PySide6.QtWidgets import (QComboBox, QDialog, QDialogButtonBox, QFormLayout, QHBoxLayout, QInputDialog, QLabel,
                               QLineEdit,
                               QMessageBox, QPushButton, QScrollArea, QSpinBox, QTreeWidget, QTreeWidgetItem,
                               QVBoxLayout, QWidget)

from ..drivers import create_driver
from ..model import DeviceConfig, DeviceKind, DeviceReading, DeviceStatus, DeviceType, Plant
from ..modbus import ModbusConnections
from .common import battery_direction, format_energy, format_power, grid_direction


def summary(r: DeviceReading) -> str:
    parts = []
    if r.pv_power_w is not None:
        parts.append(f"PV {format_power(r.pv_power_w)}")
    if r.battery is not None:
        soc = f" ({r.battery.soc_percent} %)" if r.battery.soc_percent is not None else ""
        parts.append(f"Battery {format_power(r.battery.power_w)}{soc}")
    if r.grid_power_w is not None:
        parts.append(f"Grid {format_power(r.grid_power_w)}")
    return " · ".join(parts) or r.status or "OK"


class DevicesTab(QWidget):
    add_requested = Signal()
    remove_requested = Signal(object)  # DeviceConfig
    rename_requested = Signal(object, str)  # DeviceConfig, new name

    def __init__(self, parent=None):
        super().__init__(parent)
        self.plant: Plant | None = None
        self.statuses: dict[str, DeviceStatus] = {}
        self._details: list[DeviceDialog] = []
        root = QVBoxLayout(self)
        buttons = QHBoxLayout()
        add = QPushButton("Add device…")
        add.clicked.connect(self.add_requested)
        self.remove_btn = QPushButton("Remove")
        self.remove_btn.clicked.connect(self._remove)
        self.detail_btn = QPushButton("Details")
        self.detail_btn.clicked.connect(self._open_detail)
        self.rename_btn = QPushButton("Rename…")
        self.rename_btn.clicked.connect(self._rename)
        for b in (add, self.detail_btn, self.rename_btn, self.remove_btn):
            buttons.addWidget(b)
        buttons.addStretch()
        root.addLayout(buttons)
        self.tree = QTreeWidget()
        self.tree.setHeaderLabels(["Name", "Type / address", "Now"])
        self.tree.setColumnWidth(0, 200)
        self.tree.setColumnWidth(1, 360)
        self.tree.itemDoubleClicked.connect(lambda *_: self._open_detail())
        self.tree.itemSelectionChanged.connect(self._update_buttons)
        root.addWidget(self.tree, 1)
        self.empty = QLabel("This plant has no devices yet. Add its inverters, batteries and power meters.")
        root.addWidget(self.empty)

    def set_plant(self, plant: Plant):
        self.plant = plant
        self._rebuild()
        by_id = {d.id: d for d in plant.devices}
        for dlg in self._details:
            if dlg.device.id in by_id:
                dlg.set_device(by_id[dlg.device.id])

    def set_statuses(self, statuses):
        self.statuses = {s.device.id: s for s in statuses}
        for i in range(self.tree.topLevelItemCount()):
            group = self.tree.topLevelItem(i)
            for j in range(group.childCount()):
                self._fill(group.child(j))
        for d in self._details:
            d.set_status(self.statuses.get(d.device.id))

    def _rebuild(self):
        selected = self._selected()
        self.tree.clear()
        devices = self.plant.devices if self.plant else ()
        self.empty.setVisible(not devices)
        self.tree.setVisible(bool(devices))
        for kind in DeviceKind:
            members = [d for d in devices if d.type.kind is kind]
            if not members:
                continue
            group = QTreeWidgetItem([kind.value])
            group.setFlags(Qt.ItemFlag.ItemIsEnabled)
            f = group.font(0)
            f.setBold(True)
            group.setFont(0, f)
            self.tree.addTopLevelItem(group)
            for d in members:
                item = QTreeWidgetItem([d.name, d.address(), ""])
                item.setData(0, Qt.ItemDataRole.UserRole, d)
                group.addChild(item)
                self._fill(item)
                if selected and d.id == selected.id:
                    item.setSelected(True)  # keep the selection across a rename
            group.setExpanded(True)
        self._update_buttons()

    def _fill(self, item: QTreeWidgetItem):
        d: DeviceConfig = item.data(0, Qt.ItemDataRole.UserRole)
        s = self.statuses.get(d.id)
        if s is None:
            item.setText(2, "Waiting for data…")
            item.setForeground(2, self.palette().text())
        elif s.error:
            item.setText(2, s.error)
            item.setForeground(2, Qt.GlobalColor.red)
        else:
            item.setText(2, summary(s.reading))
            item.setForeground(2, self.palette().text())

    def _selected(self) -> DeviceConfig | None:
        items = self.tree.selectedItems()
        return items[0].data(0, Qt.ItemDataRole.UserRole) if items else None

    def _update_buttons(self):
        has = self._selected() is not None
        for b in (self.remove_btn, self.detail_btn, self.rename_btn):
            b.setEnabled(has)

    def _rename(self):
        d = self._selected()
        if not d:
            return
        name, ok = QInputDialog.getText(self, "Rename device", "Name:", text=d.name)
        if ok and name.strip() and name.strip() != d.name:
            self.rename_requested.emit(d, name.strip())

    def _remove(self):
        d = self._selected()
        if d and QMessageBox.question(
                self, f"Remove {d.name}?",
                "This only removes it from the plant in the app. Nothing on the device changes.") == QMessageBox.StandardButton.Yes:
            self.remove_requested.emit(d)

    def _open_detail(self):
        d = self._selected()
        if not d:
            return
        dlg = DeviceDialog(d, self)
        dlg.set_status(self.statuses.get(d.id))
        self._details.append(dlg)
        dlg.finished.connect(lambda *_: self._details.remove(dlg))
        dlg.show()


class DeviceDialog(QDialog):
    """Everything one device reports, updated live from the plant's polling."""

    def __init__(self, device: DeviceConfig, parent=None):
        super().__init__(parent)
        self.device = device
        self.setWindowTitle(device.name)
        self.resize(560, 720)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(device.address()))
        self.body = QLabel(textFormat=Qt.TextFormat.RichText, alignment=Qt.AlignmentFlag.AlignTop)
        self.body.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse)
        self.body.setContentsMargins(4, 4, 12, 4)
        scroll = QScrollArea(widgetResizable=True)
        scroll.setWidget(self.body)
        layout.addWidget(scroll, 1)

    def set_device(self, device: DeviceConfig):
        self.device = device
        self.setWindowTitle(device.name)

    def set_status(self, s: DeviceStatus | None):
        if s is None:
            self.body.setText("Waiting for data…")
            return
        if s.error:
            self.body.setText(f"<span style='color:#c62828'>Can't read this device: {s.error}</span>")
            return
        r = s.reading
        rows: list[tuple[str, list[tuple[str, str]]]] = []
        now = [("Status", r.status)] if r.status else []
        if r.pv_power_w is not None:
            now.append(("Solar", format_power(r.pv_power_w)))
        if r.grid_power_w is not None:
            now.append(("Grid", f"{format_power(r.grid_power_w)} · {grid_direction(r.grid_power_w)}"))
        if r.load_power_w is not None:
            now.append(("Load", format_power(r.load_power_w)))
        if r.temperature_c is not None:
            now.append(("Temperature", f"{r.temperature_c:.1f} °C"))
        rows.append(("Now", now))
        if r.battery:
            b = r.battery
            rows.append(("Battery", [("Power", f"{format_power(b.power_w)} · {battery_direction(b.power_w)}")]
                         + ([("State of charge", f"{b.soc_percent} %")] if b.soc_percent is not None else [])
                         + ([("Voltage", f"{b.voltage_v:.1f} V")] if b.voltage_v is not None else [])
                         + [("Mode", b.mode)]))
        if r.pv_strings:
            rows.append(("PV strings", [(f"PV{p.index}", " · ".join(
                [f"{p.voltage_v:.1f} V", f"{p.current_a:.1f} A"] + ([format_power(p.power_w)] if p.power_w is not None else [])))
                for p in r.pv_strings]))
        if r.phases:
            rows.append(("Phases", [(ph.name, " · ".join(
                [f"{ph.voltage_v:.1f} V"] + ([f"{ph.current_a:.1f} A"] if ph.current_a is not None else [])
                + ([format_power(ph.power_w)] if ph.power_w is not None else []))) for ph in r.phases]
                + ([("Frequency", f"{r.phases[0].frequency_hz:.2f} Hz")] if r.phases[0].frequency_hz else [])))
        t = r.today
        today = [(n, format_energy(v)) for n, v in (("Solar yield", t.pv_kwh), ("Consumption", t.load_kwh),
                                                     ("Exported", t.export_kwh), ("Imported", t.import_kwh)) if v is not None]
        if r.lifetime_pv_kwh is not None:
            today.append(("Lifetime yield", f"{r.lifetime_pv_kwh:.0f} kWh"))
        if today:
            rows.append(("Today", today))
        # Everything else the driver knows, as the device reports it.
        rows += [(section.title, list(section.rows)) for section in r.details if section.rows]
        html = ""
        for title, items in rows:
            html += f"<h3>{title}</h3><table width='100%'>"
            html += "".join(f"<tr><td>{escape(k)}</td><td align='right'><b>{escape(v)}</b></td></tr>" for k, v in items)
            html += "</table>"
        self.body.setText(html)


class AddDeviceDialog(QDialog):
    tested = Signal(str)

    def __init__(self, plant_name: str, parent=None):
        super().__init__(parent)
        self.setWindowTitle(f"Add device to {plant_name}")
        self.resize(520, 0)
        # Hints sit between two forms rather than in form rows, where wrapped labels get clipped.
        root = QVBoxLayout(self)
        form = QFormLayout()
        root.addLayout(form)
        self.name = QLineEdit(placeholderText="e.g. Inverter 1 (east roof)")
        form.addRow("Name", self.name)

        self.type = QComboBox()
        model = QStandardItemModel(self.type)
        for kind in DeviceKind:
            header = QStandardItem(kind.value)
            header.setEnabled(False)
            f = header.font()
            f.setBold(True)
            header.setFont(f)
            model.appendRow(header)
            for t in DeviceType:
                if t.kind is kind:
                    item = QStandardItem("   " + t.display_name + ("" if t.supported else " (coming soon)"))
                    item.setData(t, Qt.ItemDataRole.UserRole)
                    item.setEnabled(t.supported)
                    model.appendRow(item)
        self.type.setModel(model)
        self.type.setCurrentIndex(1)  # GoodWe ET
        self.type.currentIndexChanged.connect(self._type_changed)
        form.addRow("Type", self.type)
        self.hint = QLabel(wordWrap=True, styleSheet="color: palette(mid);")
        root.addWidget(self.hint)

        form = QFormLayout()
        root.addLayout(form)
        self.host = QLineEdit(placeholderText="192.168.1.50")
        self.port = QSpinBox(maximum=65535)
        self.unit = QSpinBox(maximum=255)
        form.addRow("IP address", self.host)
        form.addRow("Port", self.port)
        form.addRow("Modbus unit id", self.unit)
        self.password = QLineEdit(echoMode=QLineEdit.EchoMode.Password)
        self.password_label = QLabel("Device password")
        self.mac_btn = QPushButton("Use MAC address")
        self.mac_btn.setToolTip("Look up the unit's MAC address on the network and use it as the password")
        self.mac_btn.clicked.connect(self._use_mac)
        password_row = QHBoxLayout()
        password_row.addWidget(self.password, 1)
        password_row.addWidget(self.mac_btn)
        self.password_row = QWidget()
        self.password_row.setLayout(password_row)
        password_row.setContentsMargins(0, 0, 0, 0)
        form.addRow(self.password_label, self.password_row)
        self.form = form
        self.address_hint = QLabel("Several devices behind the same gateway share its IP address and port, "
                                   "and differ by unit id.", wordWrap=True, styleSheet="color: palette(mid);")
        root.addWidget(self.address_hint)
        self.password_hint = QLabel(
            "The user password is the unit's MAC address, lowercase without colons (e.g. 001122aabbcc). "
            "Enter the IP address and press “Use MAC address” to look it up. It's saved in plants.json, "
            "readable only by your user.",
            wordWrap=True, styleSheet="color: palette(mid);")
        root.addWidget(self.password_hint)

        test_row = QHBoxLayout()
        self.test_btn = QPushButton("Test connection")
        self.test_btn.clicked.connect(self._test)
        self.result = QLabel(wordWrap=True)
        test_row.addWidget(self.test_btn)
        test_row.addWidget(self.result, 1)
        root.addLayout(test_row)
        self.tested.connect(self._tested)
        self.mac_found.connect(self._mac_done)

        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        self.save_btn = buttons.button(QDialogButtonBox.StandardButton.Save)
        root.addWidget(buttons)
        for w in (self.host, self.password):
            w.textChanged.connect(self._validate)
        self.result_config: DeviceConfig | None = None
        self._type_changed()

    def _device_type(self) -> DeviceType:
        return self.type.currentData(Qt.ItemDataRole.UserRole)

    def _type_changed(self):
        t = self._device_type()
        self.port.setValue(t.default_port)
        self.unit.setValue(t.default_unit_id)
        for w in (self.host, self.port):
            w.setEnabled(t.needs_host)
        self.unit.setEnabled(t.needs_host and t.modbus)
        self.form.setRowVisible(self.unit, t.modbus)
        self.form.setRowVisible(self.password_row, t.needs_password)
        self.address_hint.setVisible(t.needs_host and t.modbus)
        self.password_hint.setVisible(t.needs_password)
        type_hints = {
            DeviceType.HUAWEI: "Unit id 1 for an inverter behind an SDongle or SmartLogger (its address there); "
                               "0 when connecting to the inverter's own WLAN or FE port. Modbus TCP must be enabled "
                               "on the SDongle (FusionSolar app: Communication Configuration).",
        }
        self.hint.setText(type_hints.get(t) or {
            DeviceKind.BATTERY: "Only add standalone batteries here. A battery on a hybrid inverter is already read "
                                "through the inverter, and adding it again would count it twice.",
            DeviceKind.METER: "When a plant has a power meter, the grid total comes from the meter instead of the inverters.",
        }.get(t.kind, ""))
        self.hint.setVisible(bool(self.hint.text()))
        self.result.clear()
        self._validate()

    def _validate(self):
        t = self._device_type()
        ok = (not t.needs_host or bool(self.host.text().strip())) and (not t.needs_password or bool(self.password.text()))
        self.save_btn.setEnabled(ok)
        self.test_btn.setEnabled(ok)

    def _build(self) -> DeviceConfig:
        t = self._device_type()
        return DeviceConfig(uuid.uuid4().hex, self.name.text().strip() or t.display_name, t,
                            self.host.text().strip(), self.port.value(), self.unit.value(),
                            self.password.text() if t.needs_password else "")

    def _test(self):
        config = self._build()
        self.test_btn.setEnabled(False)
        self.result.setText("Testing…")

        def work():
            try:
                with ModbusConnections() as conns:
                    info = create_driver(config, conns).identify()
                self.tested.emit(f"Connected: {info.model}, S/N {info.serial}")
            except Exception as e:
                self.tested.emit(f"Failed: {e or type(e).__name__}")
        threading.Thread(target=work, daemon=True).start()

    mac_found = Signal(object)

    def _use_mac(self):
        from ..drivers.lg_ess import mac_address

        host, port = self.host.text().strip(), self.port.value()
        if not host:
            self.result.setText("Enter the IP address first.")
            return
        self.mac_btn.setEnabled(False)
        self.result.setText("Looking up the MAC address…")
        threading.Thread(target=lambda: self.mac_found.emit(mac_address(host, port)), daemon=True).start()

    def _mac_done(self, mac):
        self.mac_btn.setEnabled(True)
        if mac:
            self.password.setText(mac)
            self.result.setText("Password set from the MAC address. Press Test connection to check it.")
        else:
            self.result.setText("Couldn't find the MAC address. Is the IP right and the unit on this network?")

    def _tested(self, text: str):
        self.result.setText(text)
        self._validate()

    def _save(self):
        self.result_config = self._build()
        self.accept()
