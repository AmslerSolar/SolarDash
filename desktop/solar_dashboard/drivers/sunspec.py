"""SunSpec over Modbus TCP: finding the models on a device and reading scaled values."""
from __future__ import annotations

from ..modbus import ModbusException, ModbusTcpClient, RegisterBlock

SUNS = (0x5375, 0x6E53)  # "SunS"
BASES = (0, 40000, 50000)  # where a SunSpec map may start
END = 0xFFFF
MAX_MODELS = 32

# "Not implemented" markers per SunSpec type.
NI_U16, NI_S16, NI_U32 = 0xFFFF, -0x8000, 0xFFFFFFFF


class SunSpecDevice:
    """Walks the model chain (ID, length, ID, length, …) once and reads whole model blocks on demand."""

    def __init__(self, client: ModbusTcpClient, unit_id: int):
        self.client = client
        self.unit_id = unit_id
        self.models: dict[int, tuple[int, int]] | None = None  # model id → (address of its ID register, length)

    def discover(self) -> dict[int, tuple[int, int]]:
        if self.models is not None:
            return self.models
        for base in BASES:
            try:
                head = self.client.read_holding_registers(self.unit_id, base, 2)
            except ModbusException:
                continue
            if (head.u16(base), head.u16(base + 1)) != SUNS:
                continue
            models, addr = {}, base + 2
            for _ in range(MAX_MODELS):
                hdr = self.client.read_holding_registers(self.unit_id, addr, 2)
                model_id, length = hdr.u16(addr), hdr.u16(addr + 1)
                if model_id == END or length == 0:
                    break
                models.setdefault(model_id, (addr, length))
                addr += 2 + length
            self.models = models
            return models
        raise IOError("No SunSpec map found on this device (looked for 'SunS' at 0, 40000, 50000)")

    def model(self, model_id: int) -> Model | None:
        found = self.discover().get(model_id)
        if found is None:
            return None
        addr, length = found
        return Model(self.client.read_holding_registers(self.unit_id, addr, length + 2), addr, length)


class Model:
    """One model block; offsets are relative to the model's ID register, as in the SunSpec tables."""

    def __init__(self, block: RegisterBlock, addr: int, length: int):
        self.b, self.addr, self.length = block, addr, length

    def has(self, offset: int, size: int = 1) -> bool:
        return offset + size <= self.length + 2

    def u16(self, o: int) -> int | None:
        v = self.b.u16(self.addr + o) if self.has(o) else NI_U16
        return None if v == NI_U16 else v

    def s16(self, o: int) -> int | None:
        v = self.b.s16(self.addr + o) if self.has(o) else NI_S16
        return None if v == NI_S16 else v

    def u32(self, o: int) -> int | None:
        v = self.b.u32(self.addr + o) if self.has(o, 2) else NI_U32
        return None if v == NI_U32 else v

    def s32(self, o: int) -> int | None:
        v = self.b.s32(self.addr + o) if self.has(o, 2) else -0x80000000
        return None if v == -0x80000000 else v

    def s64(self, o: int) -> int | None:
        v = self.b.s64(self.addr + o) if self.has(o, 4) else -(1 << 63)
        return None if v == -(1 << 63) else v

    def f32(self, o: int) -> float | None:
        if not self.has(o, 2):
            return None
        v = self.b.f32(self.addr + o)
        return None if v != v else v  # NaN = not implemented

    def text(self, o: int, size: int) -> str:
        return self.b.ascii(self.addr + o, size) if self.has(o, size) else ""

    def scaled(self, value: int | None, sf_offset: int | None, default_sf: int = 0) -> float | None:
        """value × 10^sf. A scale factor that's absent or 'not implemented' falls back to `default_sf`."""
        if value is None:
            return None
        sf = self.s16(sf_offset) if sf_offset is not None else None
        return value * 10 ** (default_sf if sf is None else sf)
