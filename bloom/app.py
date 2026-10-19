"""Bloom app: tray icon, the key+drag watcher, the right-click wheel, and jobs.

  Bloom                 start in the tray and watch for Shift / Ctrl / Alt + drag
  Bloom --pick FILE...  open the wheel at the cursor for these files (right-click menu)
"""
import getpass
import json
import os
import sys
import threading
import time
from pathlib import Path

from PySide6.QtCore import QObject, QTimer, Signal
from PySide6.QtGui import QAction, QCursor, QGuiApplication, QIcon
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from . import converters, inputstate, tools
from . import platform_utils as pu
from .wheel import Wheel

SERVER = f"bloom-{getpass.getuser()}"
MOVE_THRESHOLD = 10          # pixels the mouse must travel before it counts as a drag
SETTINGS = Path(os.environ.get("APPDATA") or Path.home() / ".config") / "Bloom" / "settings.json"


def items_for(mode, paths):
    files = [p for p in paths if os.path.isfile(p)]
    if mode == "convert":
        return [(f, f.upper()) for f in converters.formats_for(files)]
    if mode == "tools":
        return tools.tools_for(files)
    return tools.actions_for(paths)


def first_mode_with_items(paths):
    for mode in ("convert", "tools", "actions"):
        if items_for(mode, paths):
            return mode
    return None


class Jobs(QObject):
    """Runs a petal's work in the background and reports back on the GUI thread."""
    finished = Signal(str, str)        # title, body

    def run(self, mode, key, paths):
        if mode == "actions" and key == "copypath":     # the clipboard lives on the GUI thread
            summary, _ = tools.run_action(key, paths, QGuiApplication.clipboard().setText)
            self.finished.emit(summary, "\n".join(Path(p).name for p in paths[:3]))
            return

        def work():
            try:
                if mode == "convert":
                    made, failed = [], []
                    for p in [p for p in paths if os.path.isfile(p)]:
                        try:
                            made += converters.convert(p, key)
                        except Exception as err:
                            failed.append(f"{Path(p).name}: {err}")
                    if failed and not made:
                        raise RuntimeError("\n".join(failed))
                    title = f"Converted to {key.upper()}"
                elif mode == "tools":
                    made, title = tools.run_tool(key, paths), "Done"
                else:
                    title, made = tools.run_action(key, paths)
                names = ", ".join(Path(m).name for m in made[:3]) + (" …" if len(made) > 3 else "")
                body = f"Saved {names}\nin {Path(made[0]).parent}" if made and mode != "actions" else names
                self.finished.emit(title, body)
            except Exception as err:
                self.finished.emit("Bloom couldn't finish that", str(err)[:300])
        threading.Thread(target=work, daemon=True).start()


class DragWatcher(QObject):
    """Polls the mouse button and Shift / Ctrl / Alt. When a drag is under way
    and one of those keys is pressed, the wheel is armed under the cursor; the
    drag's own DragEnter then tells it which files are being dragged."""

    def __init__(self, wheel, jobs, is_paused):
        super().__init__()
        self.wheel, self.jobs, self.is_paused = wheel, jobs, is_paused
        self.input = inputstate.backend()
        self.visible_ring = not (pu.IS_WINDOWS or self.input.composited())
        self.tracking = False
        self.prev = (False, False, False)
        self.timer = QTimer(self, interval=30, timeout=self.poll)
        self.timer.start()
        wheel.files_seen.connect(self.on_files)
        wheel.chosen.connect(self.on_chosen)
        wheel.cancelled.connect(self.on_cancel)

    def poll(self):
        if self.is_paused():
            return
        down, shift, ctrl, alt = self.input.poll()
        mods = (shift, ctrl, alt)
        pressed_now = [m and not p for m, p in zip(mods, self.prev)]
        self.prev = mods
        pos = QCursor.pos()
        if down and not self.tracking:
            self.tracking, self.start, self.armed, self.mode, self.done = True, pos, False, None, False
            self.held_at_start = mods
            return
        if not down:
            if self.tracking:
                self.tracking = False
                QTimer.singleShot(350, self.release)        # let the drop land first
            return
        if self.done:
            return
        want = None
        for flag, mode in zip(pressed_now, ("convert", "tools", "actions")):
            if flag:
                want = mode
        if want is None and self.mode is None:
            for flag, mode in zip(mods, ("convert", "tools", "actions")):
                if flag:
                    want = mode
                    break
        moved = (pos - self.start).manhattanLength() > MOVE_THRESHOLD
        if want and moved and not self.armed:
            self.armed, self.mode = True, want
            if alt and pu.IS_WINDOWS:
                _cancel_alt_menu()
            self.wheel.arm(pos, want, self.visible_ring)
        elif want and self.armed and want != self.mode:
            self.mode = want
            if alt and pu.IS_WINDOWS:
                _cancel_alt_menu()
            self.wheel.switch(want)

    def on_files(self, paths):
        if not self.wheel.items and not self.wheel.populate(self.mode or "convert", paths):
            # This key's wheel has nothing for these files: fall back to one that does.
            mode = first_mode_with_items(paths)
            if not mode or not self.wheel.populate(mode, paths):
                self.on_cancel()

    def on_chosen(self, mode, key, paths):
        self.done = True
        self.wheel.close_wheel()
        self.jobs.run(mode, key, paths)

    def on_cancel(self):
        self.done = True
        self.wheel.close_wheel()

    def release(self):
        if not self.tracking:
            self.wheel.close_wheel()


def _cancel_alt_menu():
    """On Windows, letting go of Alt after a drag would open the window's menu
    (or the ribbon's key tips). A harmless unassigned key press cancels that."""
    import ctypes
    user32 = ctypes.windll.user32
    user32.keybd_event(0xE8, 0, 0, 0)
    user32.keybd_event(0xE8, 0, 2, 0)


class PickCollector(QObject):
    """Explorer starts one process per selected file; gather them for 350 ms
    and open a single wheel for the whole selection."""

    def __init__(self, jobs, notify, input_backend):
        super().__init__()
        self.jobs, self.notify, self.paths, self.input = jobs, notify, [], input_backend
        self.timer = QTimer(self, singleShot=True, interval=350, timeout=self.open)
        self.wheel = Wheel(items_for, click_mode=True)
        self.wheel.chosen.connect(self.chosen)
        self.wheel.cancelled.connect(self.close)
        # A background app can't capture clicks outside its window on Windows, so
        # watch for a click outside the wheel (or Esc) ourselves.
        self.outside = QTimer(self, interval=30, timeout=self.watch_outside)
        self.was_down = True

    def add(self, paths):
        self.paths += [p for p in paths if os.path.exists(p) and p not in self.paths]
        self.timer.start()

    def open(self):
        paths, self.paths = self.paths, []
        mode = first_mode_with_items(paths)
        if not mode:
            self.notify("Bloom", "Bloom has nothing for these files.")
            return
        if self.wheel.open_for_click(QCursor.pos(), mode, paths):
            self.was_down = True
            self.outside.start()

    def watch_outside(self):
        down = self.input.poll()[0]
        pressed = down and not self.was_down
        self.was_down = down
        if self.input.escape():
            self.close()
        elif pressed and not self.wheel.geometry().contains(QCursor.pos()):
            self.close()

    def close(self):
        self.outside.stop()
        self.wheel.close_wheel()

    def chosen(self, mode, key, paths):
        self.close()
        self.jobs.run(mode, key, paths)


def load_settings():
    try:
        return json.loads(SETTINGS.read_text())
    except (OSError, ValueError):
        return {}


def save_settings(data):
    try:
        SETTINGS.parent.mkdir(parents=True, exist_ok=True)
        SETTINGS.write_text(json.dumps(data, indent=2))
    except OSError:
        pass


def send_to_running(message):
    if pu.IS_WINDOWS and message.get("pick"):
        # Explorer gave this short-lived process the right to take focus; pass it on.
        import ctypes
        ctypes.windll.user32.AllowSetForegroundWindow(-1)      # ASFW_ANY
    sock = QLocalSocket()
    sock.connectToServer(SERVER)
    if not sock.waitForConnected(400):
        return False
    sock.write(json.dumps(message).encode())
    sock.flush()
    sock.waitForBytesWritten(1000)
    sock.disconnectFromServer()
    return True


def main():
    args = sys.argv[1:]
    pick = [os.path.abspath(a) for a in args[1:]] if args[:1] == ["--pick"] else None
    if "--register-menu" in args or "--unregister-menu" in args:
        pu.set_context_menu("--register-menu" in args)
        return 0

    app = QApplication(sys.argv)
    app.setQuitOnLastWindowClosed(False)
    app.setApplicationName("Bloom")

    # One Bloom at a time: later launches hand their files to the running one.
    if send_to_running({"pick": pick} if pick else {"hello": True}):
        return 0
    QLocalServer.removeServer(SERVER)
    server = QLocalServer()
    server.listen(SERVER)

    icon = QIcon(pu.asset("icon.png"))
    app.setWindowIcon(icon)
    tray = QSystemTrayIcon(icon)
    tray.setToolTip("Bloom: hold Shift, Ctrl or Alt while dragging a file")

    def notify(title, body=""):
        if QSystemTrayIcon.supportsMessages():
            tray.showMessage(title, body, icon, 4000)
        else:
            print(f"{title}: {body}")

    settings = load_settings()
    paused = {"on": False}
    jobs = Jobs()
    jobs.finished.connect(notify)
    watcher = DragWatcher(Wheel(items_for), jobs, lambda: paused["on"])
    picker = PickCollector(jobs, notify, watcher.input)

    def on_connection():
        sock = server.nextPendingConnection()

        def read():
            try:
                msg = json.loads(bytes(sock.readAll()).decode() or "{}")
            except ValueError:
                return
            if msg.get("pick"):
                picker.add(msg["pick"])
            elif msg.get("hello"):
                notify("Bloom is already running", "Find it in the tray (system tray / notification area).")
        sock.readyRead.connect(read)
    server.newConnection.connect(on_connection)

    menu = QMenu()
    status = QAction("Bloom is on")
    status.setCheckable(True)
    status.setChecked(True)
    status.toggled.connect(lambda on: (paused.update(on=not on),
                                       status.setText("Bloom is on" if on else "Bloom is paused")))
    menu.addAction(status)
    menu.addSeparator()
    login = QAction("Start when I log in")
    login.setCheckable(True)
    login.setChecked(pu.autostart_enabled())
    login.toggled.connect(pu.set_autostart)
    menu.addAction(login)
    if pu.IS_WINDOWS:
        ctx = QAction('Add "Open in Bloom" to right-click menu')
        ctx.setCheckable(True)
        ctx.setChecked(pu.context_menu_enabled())
        ctx.toggled.connect(pu.set_context_menu)
        menu.addAction(ctx)
    howto = QAction("How to use Bloom")
    howto.triggered.connect(lambda: notify(
        "How to use Bloom", "Start dragging a file, then press Shift to convert, Ctrl for tools or "
        "Alt for actions. Drop it on a petal. Drop in the middle to cancel."))
    menu.addAction(howto)
    menu.addSeparator()
    quit_action = QAction("Quit Bloom")
    quit_action.triggered.connect(app.quit)
    menu.addAction(quit_action)
    tray.setContextMenu(menu)
    tray.show()

    if not settings.get("welcomed"):
        settings["welcomed"] = True
        save_settings(settings)
        if getattr(sys, "frozen", False) and not pu.IS_WINDOWS:
            pu.set_autostart(True)          # the Windows installer asks about this instead
        QTimer.singleShot(800, lambda: notify(
            "Bloom is running", "Start dragging a file, then press Shift, Ctrl or Alt."))
    if pick:
        picker.add(pick)

    app._keep = (tray, menu, watcher, picker, server, jobs, status, login, howto, quit_action)
    return app.exec()
