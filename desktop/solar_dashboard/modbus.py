"""Modbus TCP: framing, a reconnecting client, and one shared client per host:port."""
from __future__ import annotations

import socket
import struct
import threading
import time

MBAP_SIZE = 7
READ_HOLDING_REGISTERS = 0x03


class ProtocolError(IOError):
    pass


class ModbusException(ProtocolError):
    """The device answered with a Modbus exception, e.g. code 2 for a register it doesn't have."""

    def __init__(self, code: int):
        super().__init__(f"Modbus exception {code}")
        self.code = code


def read_request(transaction_id: int, unit_id: int, register: int, count: int) -> bytes:
    return struct.pack(">HHHBBHH", transaction_id, 0, 6, unit_id, READ_HOLDING_REGISTERS, register, count)


def remaining_length(header: bytes) -> int:
    """Bytes still to read after the MBAP header (its length field counts the unit id)."""
    length = struct.unpack(">H", header[4:6])[0] - 1
    if not 2 <= length <= 253:
        raise ProtocolError(f"Invalid MBAP length {length}")
    return length


def parse_read_response(frame: bytes, transaction_id: int, expected_count: int) -> bytes:
    if len(frame) < MBAP_SIZE + 2:
        raise ProtocolError(f"Response too short ({len(frame)} bytes)")
    tid, pid, length = struct.unpack(">HHH", frame[:6])
    if tid != transaction_id:
        raise ProtocolError("Transaction id mismatch")
    if pid != 0:
        raise ProtocolError("Not a Modbus TCP frame")
    if length != len(frame) - 6:
        raise ProtocolError("Length field does not match frame")
    function = frame[7]
    if function & 0x80:
        raise ModbusException(frame[8])
    if function != READ_HOLDING_REGISTERS:
        raise ProtocolError(f"Unexpected function code {function}")
    byte_count = frame[8]
    if byte_count != len(frame) - MBAP_SIZE - 2:
        raise ProtocolError(f"Byte count {byte_count} does not match frame")
    if byte_count != expected_count * 2:
        raise ProtocolError(f"Expected {expected_count * 2} data bytes, got {byte_count}")
    return frame[MBAP_SIZE + 2:]


class RegisterBlock:
    """Big-endian register view over a contiguous block starting at `start`."""

    def __init__(self, start: int, data: bytes):
        self.start = start
        self.data = data

    def _index(self, register: int) -> int:
        i = (register - self.start) * 2
        if i < 0 or i + 2 > len(self.data):
            raise IndexError(f"Register {register} outside block {self.start}..{self.start + len(self.data) // 2 - 1}")
        return i

    def u16(self, r: int) -> int:
        i = self._index(r)
        return (self.data[i] << 8) | self.data[i + 1]

    def s16(self, r: int) -> int:
        v = self.u16(r)
        return v - 0x10000 if v & 0x8000 else v

    def u32(self, r: int) -> int:
        return (self.u16(r) << 16) | self.u16(r + 1)

    def s32(self, r: int) -> int:
        v = self.u32(r)
        return v - 0x1_0000_0000 if v & 0x8000_0000 else v

    def s64(self, r: int) -> int:
        v = (self.u32(r) << 32) | self.u32(r + 2)
        return v - (1 << 64) if v & (1 << 63) else v

    def f32(self, r: int) -> float:
        return struct.unpack(">f", struct.pack(">I", self.u32(r)))[0]

    def ascii(self, r: int, count: int) -> str:
        i = self._index(r)
        self._index(r + count - 1)
        return self.data[i:i + count * 2].decode("ascii", errors="replace").strip("\x00 \t\r\n")


class ModbusTcpClient:
    """Keeps one connection open between polls (dongles often accept a single client) and reconnects
    once on failure. Requests are serialized, so several unit ids can share a client."""

    def __init__(self, host: str, port: int, timeout: float = 3.0):
        self.host, self.port, self.timeout = host, port, timeout
        # Some devices (Huawei) ignore requests sent right after the TCP connection opens.
        self.connect_delay = 0.0
        self._sock: socket.socket | None = None
        self._lock = threading.Lock()
        self._tid = 1

    def read_holding_registers(self, unit_id: int, register: int, count: int) -> RegisterBlock:
        with self._lock:
            try:
                return self._exchange(unit_id, register, count)
            except ModbusException:
                raise
            except (OSError, ProtocolError):
                self._disconnect()
                try:
                    return self._exchange(unit_id, register, count)
                except ModbusException:
                    raise
                except (OSError, ProtocolError):
                    self._disconnect()
                    raise

    def _exchange(self, unit_id: int, register: int, count: int) -> RegisterBlock:
        if self._sock is None:
            self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
            self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            if self.connect_delay:
                time.sleep(self.connect_delay)
        tid = self._tid
        self._tid = (self._tid + 1) & 0xFFFF
        self._sock.sendall(read_request(tid, unit_id, register, count))
        while True:
            header = self._recv_exact(MBAP_SIZE)
            frame = header + self._recv_exact(remaining_length(header))
            if struct.unpack(">H", frame[:2])[0] != tid:
                continue  # late answer to an earlier, timed-out request
            return RegisterBlock(register, parse_read_response(frame, tid, count))

    def _recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("Connection closed by device")
            buf += chunk
        return buf

    def _disconnect(self):
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def close(self):
        with self._lock:
            self._disconnect()


class ModbusConnections:
    """One client per host:port, shared by all devices (unit ids) behind it."""

    def __init__(self):
        self._clients: dict[tuple[str, int], ModbusTcpClient] = {}
        self._lock = threading.Lock()

    def get(self, host: str, port: int) -> ModbusTcpClient:
        with self._lock:
            return self._clients.setdefault((host, port), ModbusTcpClient(host, port))

    def close(self):
        with self._lock:
            for c in self._clients.values():
                c.close()
            self._clients.clear()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
