"""Solar Dashboard desktop app.

    python -m solar_dashboard           open the app
    python -m solar_dashboard record    record one history sample per plant and exit (for a timer / cron)
"""
from __future__ import annotations

import argparse
import sys


def record() -> int:
    from .reader import PlantReader
    from .storage import ActivePolling, HistoryStore, PlantStore

    history, active = HistoryStore(), ActivePolling()
    failures = 0
    for plant in PlantStore().load():
        if not plant.devices or active.is_active(plant.id):
            continue  # the app is polling it right now
        try:
            with PlantReader(plant.devices) as reader:
                snap = reader.read()
            history.record(plant.id, snap)
            print(f"{plant.name}: solar {snap.pv_power_w} W, home {snap.load_power_w} W, "
                  f"{len(snap.failed_devices)} device(s) not responding")
        except Exception as e:  # one unreachable plant shouldn't stop the others
            failures += 1
            print(f"{plant.name}: {e}", file=sys.stderr)
    return 1 if failures else 0


def gui() -> int:
    import pyqtgraph as pg
    from PySide6.QtWidgets import QApplication

    from .storage import ActivePolling, HistoryStore, PlantStore
    from .ui.main_window import MainWindow

    app = QApplication(sys.argv)
    app.setApplicationName("Solar Dashboard")
    pg.setConfigOptions(antialias=True, background=app.palette().base().color(), foreground=app.palette().text().color())
    window = MainWindow(PlantStore(), HistoryStore(), ActivePolling())
    window.show()
    return app.exec()


def main() -> int:
    parser = argparse.ArgumentParser(prog="solar_dashboard", description="Solar plant dashboard")
    parser.add_argument("command", nargs="?", choices=["record"], help="record one history sample per plant and exit")
    args = parser.parse_args()
    return record() if args.command == "record" else gui()


if __name__ == "__main__":
    sys.exit(main())
