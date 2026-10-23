"""GoodWe ET / EH / BT / BH hybrid inverters (incl. GW25K-ET, GW29.9K-ET, GW30K-ET) over Modbus TCP.

Modbus TCP has to be enabled on the inverter (register 47040, or via the SolarGo app). The unit id is the
inverter's Modbus address: 1–247, factory default 247 (0xF7).

Register map: "GoodWe Modbus Protocol Hybrid", Modbus protocol Map V1.2 (docs/specs/goodwe/). The spec does not
state power sign conventions; the ones used here (grid + = export, battery + = discharge) follow the open-source
`goodwe` library and still need confirming on a real inverter.
"""
from __future__ import annotations

from dataclasses import replace

from ..model import BatteryState, DetailSection, DeviceInfo, DeviceReading, EnergyTotals, PhaseReading, PvString
from ..modbus import ModbusException, ModbusTcpClient, RegisterBlock
from .details import bits, enum, fmt, section

DEVICE_INFO, DEVICE_INFO_COUNT = 35000, 0x21
RUNNING_DATA, RUNNING_DATA_COUNT = 35100, 0x7D
BATTERY2, BATTERY2_COUNT = 35262, 5
PV_EXTENDED, PV_EXTENDED_COUNT = 35301, 7  # PV total power, PV channel count, PV5/PV6 voltage and current
BMS_DATA, BMS_DATA_COUNT = 37000, 0x18

WORK_MODES = {0: "Waiting", 1: "On-grid", 2: "Off-grid", 3: "Fault", 4: "Firmware update", 5: "Self-check"}  # table 8-1
BATTERY_MODES = {1: "Standby", 2: "Discharging", 3: "Charging"}  # table 8-9, 0 = no battery
BATTERY_MODES_ALL = {0: "No battery", **BATTERY_MODES}
GRID_MODES = {0: "Disconnected", 1: "Connected", 2: "Fault"}  # table 8-10
DEVICE_INFO_REFRESH_POLLS = 60  # device info rarely changes: about every 5 minutes


class GoodWeEtDriver:
    def __init__(self, client: ModbusTcpClient, unit_id: int):
        self.client = client
        self.unit_id = unit_id
        self._unsupported: set[int] = set()  # optional blocks answered with a Modbus exception
        self._device_info: DetailSection | None = None
        self._polls = 0

    def _registers(self, register: int, count: int) -> RegisterBlock:
        return self.client.read_holding_registers(self.unit_id, register, count)

    def _optional(self, register: int, count: int) -> RegisterBlock | None:
        """Registers older firmware may lack: a failure there shouldn't hide the rest of the data."""
        if register in self._unsupported:
            return None
        try:
            return self._registers(register, count)
        except ModbusException:
            self._unsupported.add(register)
        except OSError:
            pass
        return None

    def identify(self) -> DeviceInfo:
        r = self._registers(DEVICE_INFO, DEVICE_INFO_COUNT)
        return DeviceInfo(model=r.ascii(35011, 5), serial=r.ascii(35003, 8))

    def read(self) -> DeviceReading:
        r = self._registers(RUNNING_DATA, RUNNING_DATA_COUNT)
        ext = self._optional(PV_EXTENDED, PV_EXTENDED_COUNT)
        bat2 = self._optional(BATTERY2, BATTERY2_COUNT)

        # PV1–PV4 have per-string power; PV5+ (35304…) only voltage and current.
        strings = [PvString(i + 1, r.u16(b) / 10, r.u16(b + 1) / 10, r.u32(b + 2))
                   for i, b in enumerate(range(35103, 35119, 4))]
        if ext:
            strings += [PvString(5 + i, ext.u16(35304 + 2 * i) / 10, ext.u16(35305 + 2 * i) / 10, None) for i in range(2)]
        active = tuple(s for s in strings if s.voltage_v > 0 or (s.power_w or 0) > 0)
        pv = ext.u32(35301) if ext else sum(s.power_w or 0 for s in strings)

        phases = tuple(PhaseReading(name, r.u16(b) / 10, r.u16(b + 1) / 10, r.u16(b + 2) / 100, r.s32(b + 3))
                       for name, b in (("L1", 35121), ("L2", 35126), ("L3", 35131)))

        mode1 = r.u16(35184)
        mode2 = bat2.u16(35266) if bat2 else 0
        battery = None
        bms = None
        if mode1 or mode2:
            soc = None
            if mode1:
                bms = self._optional(BMS_DATA, BMS_DATA_COUNT)
                soc = bms.u16(37007) if bms else None  # battery 1 only
            power = (r.s32(35182) if mode1 else 0) + (bat2.s32(35264) if mode2 else 0)
            mode = mode1 or mode2
            battery = BatteryState(
                power_w=power,
                soc_percent=soc,
                voltage_v=r.u16(35180) / 10 if mode1 else bat2.u16(35262) / 10,
                mode=BATTERY_MODES.get(mode, f"Mode {mode}"),
            )

        work = r.u16(35187)
        if self._polls % DEVICE_INFO_REFRESH_POLLS == 0:
            info = self._optional(DEVICE_INFO, DEVICE_INFO_COUNT)
            if info:
                self._device_info = device_info_details(info)
        self._polls += 1
        details = tuple(x for x in (self._device_info, *running_details(r), ext and pv_extended_details(ext),
                                    bat2 and battery2_details(bat2), bms and bms_details(bms)) if x)
        reading = DeviceReading(
            pv_power_w=pv,
            pv_strings=active,
            battery=battery,
            grid_power_w=r.s32(35139),  # meter power when a meter is connected, else the grid port
            phases=phases,
            load_power_w=r.s32(35171) + r.s32(35169),  # on-grid load excludes the back-up port
            temperature_c=r.s16(35176) / 10,
            status=WORK_MODES.get(work, f"Unknown ({work})"),
            today=EnergyTotals(pv_kwh=r.u32(35193) / 10, export_kwh=r.u16(35199) / 10,
                               import_kwh=r.u16(35202) / 10, load_kwh=r.u16(35205) / 10),
            lifetime_pv_kwh=r.u32(35191) / 10,
        )
        return replace(reading, details=details)


# --- detail view: the named registers of each block (spec V1.2, scaling as in its SF column) ---------------------
def _v(x: int, sf: int) -> float:
    return x / sf


def device_info_details(i: RegisterBlock) -> DetailSection:
    return section("Device info (35000)",
                   ("Rated power", fmt(i.u16(35001), "W")), ("Serial number", i.ascii(35003, 8)),
                   ("Model", i.ascii(35011, 5)), ("Firmware DSP master", fmt(i.u16(35016))),
                   ("Firmware DSP slave", fmt(i.u16(35017))), ("Firmware ARM", fmt(i.u16(35019))))


def running_details(r: RegisterBlock) -> list[DetailSection]:
    clock = f"20{r.u16(35100) >> 8:02d}-{r.u16(35100) & 0xFF:02d}-{r.u16(35101) >> 8:02d} " \
            f"{r.u16(35101) & 0xFF:02d}:{r.u16(35102) >> 8:02d}:{r.u16(35102) & 0xFF:02d}"
    pv = section("PV inputs (35103–35119)",
                 *[row for i, b in enumerate(range(35103, 35119, 4), start=1) for row in (
                     (f"PV{i} voltage", fmt(_v(r.u16(b), 10), "V", 1)), (f"PV{i} current", fmt(_v(r.u16(b + 1), 10), "A", 1)),
                     (f"PV{i} power", fmt(r.u32(b + 2), "W")))],
                 ("PV mode", bits(r.u32(35119))))
    grid = section("Grid side (35121–35143)",
                   *[row for name, b in (("L1", 35121), ("L2", 35126), ("L3", 35131)) for row in (
                       (f"{name} voltage", fmt(_v(r.u16(b), 10), "V", 1)),
                       (f"{name} current", fmt(_v(r.u16(b + 1), 10), "A", 1)),
                       (f"{name} frequency", fmt(_v(r.u16(b + 2), 100), "Hz", 2)),
                       (f"{name} power", fmt(r.s32(b + 3), "W")))],
                   ("Grid mode", enum(r.u16(35136), GRID_MODES)), ("Total inverter power", fmt(r.s32(35137), "W")),
                   ("AC active power (meter)", fmt(r.s32(35139), "W")), ("AC reactive power", fmt(r.s32(35141), "var")),
                   ("AC apparent power", fmt(r.s32(35143), "VA")))
    backup = section("Back-up output (35145–35173)",
                     *[row for name, b in (("L1", 35145), ("L2", 35151), ("L3", 35157)) for row in (
                         (f"{name} voltage", fmt(_v(r.u16(b), 10), "V", 1)),
                         (f"{name} current", fmt(_v(r.u16(b + 1), 10), "A", 1)),
                         (f"{name} frequency", fmt(_v(r.u16(b + 2), 100), "Hz", 2)),
                         (f"{name} load mode", fmt(r.u16(b + 3))),
                         (f"{name} power", fmt(r.s32(b + 4), "W")))],
                     ("On-grid load L1", fmt(r.s32(35163), "W")), ("On-grid load L2", fmt(r.s32(35165), "W")),
                     ("On-grid load L3", fmt(r.s32(35167), "W")), ("Total back-up load", fmt(r.s32(35169), "W")),
                     ("Total on-grid load", fmt(r.s32(35171), "W")), ("UPS load", fmt(_v(r.u16(35173), 100), "%", 2)))
    inverter = section("Inverter (35174–35189)",
                       ("Clock", clock), ("Air temperature", fmt(_v(r.s16(35174), 10), "°C", 1)),
                       ("Module temperature", fmt(_v(r.s16(35175), 10), "°C", 1)),
                       ("Heat sink temperature", fmt(_v(r.s16(35176), 10), "°C", 1)),
                       ("Function bits", bits(r.u16(35177), 4)), ("Bus voltage", fmt(_v(r.u16(35178), 10), "V", 1)),
                       ("N-bus voltage", fmt(_v(r.u16(35179), 10), "V", 1)), ("Warning code", bits(r.u16(35185), 4)),
                       ("Safety country", fmt(r.u16(35186))), ("Work mode", enum(r.u16(35187), WORK_MODES)),
                       ("Error message", bits(r.u32(35189))), ("Operating hours", fmt(r.u32(35197), "h")))
    battery = section("Battery 1 (35180–35184)",
                      ("Voltage", fmt(_v(r.u16(35180), 10), "V", 1)), ("Current", fmt(_v(r.s16(35181), 10), "A", 1)),
                      ("Power", fmt(r.s32(35182), "W")), ("Mode", enum(r.u16(35184), BATTERY_MODES_ALL)),
                      ("Battery strings", fmt(r.u16(35212))))
    energy = section("Energy (35191–35211)",
                     ("PV total", fmt(_v(r.u32(35191), 10), "kWh", 1)), ("PV today", fmt(_v(r.u32(35193), 10), "kWh", 1)),
                     ("Exported total", fmt(_v(r.u32(35195), 10), "kWh", 1)),
                     ("Exported today", fmt(_v(r.u16(35199), 10), "kWh", 1)),
                     ("Imported total", fmt(_v(r.u32(35200), 10), "kWh", 1)),
                     ("Imported today", fmt(_v(r.u16(35202), 10), "kWh", 1)),
                     ("Load total (without back-up)", fmt(_v(r.u32(35203), 10), "kWh", 1)),
                     ("Load today (without back-up)", fmt(_v(r.u16(35205), 10), "kWh", 1)),
                     ("Battery charged total", fmt(_v(r.u32(35206), 10), "kWh", 1)),
                     ("Battery charged today", fmt(_v(r.u16(35208), 10), "kWh", 1)),
                     ("Battery discharged total", fmt(_v(r.u32(35209), 10), "kWh", 1)),
                     ("Battery discharged today", fmt(_v(r.u16(35211), 10), "kWh", 1)),
                     ("CPLD warning code", bits(r.u16(35213), 4)),
                     ("Diagnosis status high", bits(r.u32(35218))), ("Diagnosis status low", bits(r.u32(35220))))
    return [inverter, pv, grid, backup, battery, energy]


def pv_extended_details(e: RegisterBlock) -> DetailSection:
    return section("PV extended (35301–35307)",
                   ("PV total power", fmt(e.u32(35301), "W")), ("PV channels", fmt(e.u16(35303))),
                   ("PV5 voltage", fmt(_v(e.u16(35304), 10), "V", 1)), ("PV5 current", fmt(_v(e.u16(35305), 10), "A", 1)),
                   ("PV6 voltage", fmt(_v(e.u16(35306), 10), "V", 1)), ("PV6 current", fmt(_v(e.u16(35307), 10), "A", 1)))


def battery2_details(b: RegisterBlock) -> DetailSection:
    return section("Battery 2 (35262–35266)",
                   ("Voltage", fmt(_v(b.u16(35262), 10), "V", 1)), ("Current", fmt(_v(b.s16(35263), 10), "A", 1)),
                   ("Power", fmt(b.s32(35264), "W")), ("Mode", enum(b.u16(35266), BATTERY_MODES_ALL)))


def bms_details(m: RegisterBlock) -> DetailSection:
    return section("Battery management (37000–37023)",
                   ("DRM status", fmt(m.u16(37000))), ("Battery type index", fmt(m.u16(37001))),
                   ("BMS status", fmt(m.u16(37002))), ("Pack temperature", fmt(_v(m.u16(37003), 10), "°C", 1)),
                   ("Max. charge current", fmt(m.u16(37004), "A")), ("Max. discharge current", fmt(m.u16(37005), "A")),
                   ("Error code low", bits(m.u16(37006), 4)), ("State of charge", fmt(m.u16(37007), "%")),
                   ("State of health", fmt(m.u16(37008), "%")), ("Battery strings", fmt(m.u16(37009))),
                   ("Warning code low", bits(m.u16(37010), 4)), ("Battery protocol", fmt(m.u16(37011))),
                   ("Error code high", bits(m.u16(37012), 4)), ("Warning code high", bits(m.u16(37013), 4)),
                   ("BMS software version", fmt(m.u16(37014))), ("Battery hardware version", fmt(m.u16(37015))),
                   ("Max. cell temperature", fmt(_v(m.u16(37020), 10), "°C", 1)),
                   ("Min. cell temperature", fmt(_v(m.u16(37021), 10), "°C", 1)),
                   ("Max. cell voltage", fmt(m.u16(37022), "mV")), ("Min. cell voltage", fmt(m.u16(37023), "mV")))
