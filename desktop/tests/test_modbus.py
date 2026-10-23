import pytest

from solar_dashboard.modbus import (MBAP_SIZE, ModbusException, ProtocolError, RegisterBlock, parse_read_response,
                                    read_request, remaining_length)


def response(tid: int, data: bytes) -> bytes:
    length = 3 + len(data)
    return bytes([tid >> 8, tid & 0xFF, 0, 0, length >> 8, length & 0xFF, 0xF7, 0x03, len(data)]) + data


def test_builds_et_running_data_request():
    assert read_request(1, 0xF7, 35100, 0x7D) == bytes([0, 1, 0, 0, 0, 6, 0xF7, 0x03, 0x89, 0x1C, 0x00, 0x7D])


def test_parses_valid_response():
    frame = response(0x1234, bytes([0x12, 0x34, 0xFF, 0xFE]))
    assert remaining_length(frame[:MBAP_SIZE]) == len(frame) - MBAP_SIZE
    assert parse_read_response(frame, 0x1234, 2) == bytes([0x12, 0x34, 0xFF, 0xFE])


def test_rejects_wrong_transaction_and_count():
    with pytest.raises(ProtocolError):
        parse_read_response(response(7, b"\x00\x01"), 8, 1)
    with pytest.raises(ProtocolError):
        parse_read_response(response(1, b"\x00\x01"), 1, 2)


def test_reports_modbus_exception_code():
    with pytest.raises(ModbusException) as e:
        parse_read_response(bytes([0, 1, 0, 0, 0, 3, 0xF7, 0x83, 0x02]), 1, 1)
    assert e.value.code == 2


def test_decodes_signed_and_wide_registers():
    b = RegisterBlock(35180, bytes([0xFF, 0x38, 0xFF, 0xFF, 0xFC, 0x18, 0x00, 0x01, 0x86, 0xA0]))
    assert b.s16(35180) == -200
    assert b.s32(35181) == -1000
    assert b.u32(35183) == 100_000
