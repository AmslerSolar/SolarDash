"""Reads all devices of a plant in parallel and aggregates them."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from .drivers import create_driver
from .model import DeviceConfig, DeviceStatus, PlantSnapshot, aggregate
from .modbus import ModbusConnections


class PlantReader:
    """Holds the devices' connections open until `close()`."""

    def __init__(self, devices: tuple[DeviceConfig, ...]):
        self.connections = ModbusConnections()
        self.drivers = []
        for d in devices:
            try:
                self.drivers.append((d, create_driver(d, self.connections), None))
            except Exception as e:  # unsupported type
                self.drivers.append((d, None, str(e)))
        self._pool = ThreadPoolExecutor(max_workers=max(1, len(self.drivers)))

    def read(self) -> PlantSnapshot:
        futures = [(d, self._pool.submit(drv.read) if drv else None, err) for d, drv, err in self.drivers]
        statuses = []
        for device, future, err in futures:
            if future is None:
                statuses.append(DeviceStatus(device, None, err or "Unsupported device"))
                continue
            try:
                statuses.append(DeviceStatus(device, future.result(), None))
            except Exception as e:
                statuses.append(DeviceStatus(device, None, str(e) or type(e).__name__))
        return aggregate(statuses)

    def close(self):
        self._pool.shutdown(wait=False, cancel_futures=True)
        for _, driver, _ in self.drivers:
            if hasattr(driver, "close"):  # e.g. log out of an HTTPS session
                try:
                    driver.close()
                except OSError:
                    pass
        self.connections.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
