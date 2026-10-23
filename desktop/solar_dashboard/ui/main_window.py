from __future__ import annotations

import uuid
from dataclasses import replace

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (QHBoxLayout, QInputDialog, QLabel, QListWidget, QListWidgetItem, QMainWindow,
                               QMessageBox, QPushButton, QStackedWidget, QVBoxLayout, QWidget)

from ..model import Plant
from ..storage import ActivePolling, HistoryStore, PlantStore
from .devices import AddDeviceDialog
from .plant_page import PlantPage


class PlantsPage(QWidget):
    def __init__(self, window: MainWindow):
        super().__init__()
        self.window = window
        root = QVBoxLayout(self)
        title = QLabel("Plants")
        f = title.font()
        f.setPointSizeF(f.pointSizeF() * 1.6)
        title.setFont(f)
        root.addWidget(title)
        self.list = QListWidget()
        self.list.itemActivated.connect(lambda item: window.open_plant(item.data(Qt.ItemDataRole.UserRole)))
        root.addWidget(self.list, 1)
        self.empty = QLabel("No plants yet. Add a plant, then add its inverters, batteries and meters.")
        root.addWidget(self.empty)
        buttons = QHBoxLayout()
        for text, slot in (("Add plant…", self._add), ("Open", self._open), ("Rename…", self._rename),
                           ("Remove", self._remove)):
            b = QPushButton(text)
            b.clicked.connect(slot)
            buttons.addWidget(b)
        buttons.addStretch()
        root.addLayout(buttons)

    def refresh(self):
        self.list.clear()
        for p in self.window.plants:
            item = QListWidgetItem(p.name)
            item.setData(Qt.ItemDataRole.UserRole, p.id)
            self.list.addItem(item)
        self.empty.setVisible(not self.window.plants)

    def _selected(self) -> str | None:
        item = self.list.currentItem()
        return item.data(Qt.ItemDataRole.UserRole) if item else None

    def _add(self):
        name, ok = QInputDialog.getText(self, "New plant", "Name:", text="")
        if ok and name.strip():
            self.window.update_plants(self.window.plants + [Plant(uuid.uuid4().hex, name.strip())])

    def _open(self):
        if pid := self._selected():
            self.window.open_plant(pid)

    def _rename(self):
        if pid := self._selected():
            self.window.rename_plant(pid, self)

    def _remove(self):
        pid = self._selected()
        plant = next((p for p in self.window.plants if p.id == pid), None)
        if plant and QMessageBox.question(
                self, f"Remove {plant.name}?",
                "This removes the plant, its devices and its recorded history from the app. "
                "Nothing on the devices changes.") == QMessageBox.StandardButton.Yes:
            self.window.update_plants([p for p in self.window.plants if p.id != pid])
            self.window.history.delete_plant(pid)
            self.window.history.delete_devices([d.id for d in plant.devices])


class MainWindow(QMainWindow):
    def __init__(self, store: PlantStore, history: HistoryStore, active: ActivePolling):
        super().__init__()
        self.setWindowTitle("Solar Dashboard")
        self.resize(1200, 800)
        self.store, self.history, self.active = store, history, active
        self.plants = store.load()
        self.stack = QStackedWidget()
        self.setCentralWidget(self.stack)
        self.plants_page = PlantsPage(self)
        self.stack.addWidget(self.plants_page)
        self.plant_page: PlantPage | None = None
        self.plants_page.refresh()
        # On startup only: with a single plant, open it straight away. Back still leads to the list.
        if len(self.plants) == 1:
            self.open_plant(self.plants[0].id)

    def update_plants(self, plants: list[Plant]):
        self.plants = plants
        self.store.save(plants)
        self.plants_page.refresh()
        if self.plant_page:
            current = next((p for p in plants if p.id == self.plant_page.plant.id), None)
            if current:
                self.plant_page.set_plant(current)
            else:
                self.show_plants()

    def rename_plant(self, plant_id: str, parent: QWidget | None = None):
        plant = next((p for p in self.plants if p.id == plant_id), None)
        if plant is None:
            return
        name, ok = QInputDialog.getText(parent or self, "Rename plant", "Name:", text=plant.name)
        if ok and name.strip() and name.strip() != plant.name:
            self._replace_plant(replace(plant, name=name.strip()))

    def open_plant(self, plant_id: str):
        plant = next(p for p in self.plants if p.id == plant_id)
        self._close_plant_page()
        page = PlantPage(plant, self.history, self.active)
        page.back.connect(self.show_plants)
        page.rename.connect(lambda: self.rename_plant(page.plant.id, page))
        page.add_device.connect(self._add_device)
        page.rename_device.connect(lambda d, name: self._replace_plant(
            replace(page.plant, devices=tuple(replace(x, name=name) if x.id == d.id else x for x in page.plant.devices))))
        page.remove_device.connect(lambda d: self._remove_device(page.plant, d))
        page.chart_channels.connect(lambda channels: self._replace_plant(replace(page.plant, chart_channels=channels)))
        self.plant_page = page
        self.stack.addWidget(page)
        self.stack.setCurrentWidget(page)

    def show_plants(self):
        self._close_plant_page()
        self.stack.setCurrentWidget(self.plants_page)

    def _close_plant_page(self):
        if self.plant_page:
            self.plant_page.stop()
            self.stack.removeWidget(self.plant_page)
            self.plant_page.deleteLater()
            self.plant_page = None

    def _add_device(self):
        plant = self.plant_page.plant
        dlg = AddDeviceDialog(plant.name, self)
        if dlg.exec() and dlg.result_config:
            self._replace_plant(replace(plant, devices=plant.devices + (dlg.result_config,)))

    def _remove_device(self, plant: Plant, device):
        self._replace_plant(replace(plant, devices=tuple(x for x in plant.devices if x.id != device.id),
                                    chart_channels=tuple(c for c in plant.chart_channels if c[0] != device.id)))
        self.history.delete_devices([device.id])

    def _replace_plant(self, plant: Plant):
        self.update_plants([plant if p.id == plant.id else p for p in self.plants])

    def closeEvent(self, event):
        self._close_plant_page()
        super().closeEvent(event)
