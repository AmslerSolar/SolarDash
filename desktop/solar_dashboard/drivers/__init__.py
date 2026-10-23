"""One driver per device family. Drivers must be safe to call repeatedly from a polling loop."""
from __future__ import annotations

from typing import Protocol

from ..model import DeviceConfig, DeviceInfo, DeviceReading, DeviceType
from ..modbus import ModbusConnections


class DeviceDriver(Protocol):
    def identify(self) -> DeviceInfo: ...

    def read(self) -> DeviceReading: ...


def create_driver(config: DeviceConfig, connections: ModbusConnections) -> DeviceDriver:
    """Connections come from `connections`, so devices behind the same gateway share one TCP connection."""
    from . import demo
    from .goodwe_et import GoodWeEtDriver
    from .huawei_sun2000 import HuaweiSun2000Driver
    from .lg_ess import LgEssDriver
    from .lg_ess_sunspec import LgEssSunSpecDriver

    match config.type:
        case DeviceType.GOODWE_ET:
            return GoodWeEtDriver(connections.get(config.host, config.port), config.unit_id)
        case DeviceType.HUAWEI:
            return HuaweiSun2000Driver(connections.get(config.host, config.port), config.unit_id)
        case DeviceType.LG_ESS_SUNSPEC:
            return LgEssSunSpecDriver(connections.get(config.host, config.port), config.unit_id)
        case DeviceType.LG_ESS:
            return LgEssDriver(config.host, config.port, config.password)
        case DeviceType.DEMO_INVERTER:
            return demo.DemoInverter()
        case DeviceType.DEMO_BATTERY:
            return demo.DemoBattery()
        case DeviceType.DEMO_METER:
            return demo.DemoMeter()
    raise NotImplementedError(f"{config.type.display_name} is not implemented yet")
