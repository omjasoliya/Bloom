"""Global mouse-button and modifier state, read without hooks.

poll() returns (left_button_down, shift, ctrl, alt) for the whole desktop, no
matter which app has focus. Windows uses GetAsyncKeyState; Linux (X11) uses
XQueryPointer.
"""
import ctypes
import sys

IS_WINDOWS = sys.platform.startswith("win")


class _Windows:
    VK_LBUTTON, VK_RBUTTON, VK_SHIFT, VK_CONTROL, VK_MENU = 0x01, 0x02, 0x10, 0x11, 0x12
    SM_SWAPBUTTON = 23

    def __init__(self):
        self.user32 = ctypes.windll.user32
        self.user32.GetAsyncKeyState.restype = ctypes.c_short

    def _down(self, vk):
        return bool(self.user32.GetAsyncKeyState(vk) & 0x8000)

    def escape(self):
        return self._down(0x1B)

    def poll(self):
        # GetAsyncKeyState reports the physical button; respect left-handed mice.
        primary = self.VK_RBUTTON if self.user32.GetSystemMetrics(self.SM_SWAPBUTTON) else self.VK_LBUTTON
        return (self._down(primary), self._down(self.VK_SHIFT),
                self._down(self.VK_CONTROL), self._down(self.VK_MENU))


class _X11:
    SHIFT, CONTROL, MOD1, BUTTON1 = 1 << 0, 1 << 2, 1 << 3, 1 << 8

    def __init__(self):
        self.x11 = ctypes.cdll.LoadLibrary("libX11.so.6")
        self.x11.XOpenDisplay.restype = ctypes.c_void_p
        self.x11.XDefaultRootWindow.restype = ctypes.c_ulong
        self.x11.XDefaultRootWindow.argtypes = [ctypes.c_void_p]
        self.x11.XQueryPointer.argtypes = [ctypes.c_void_p, ctypes.c_ulong] + \
            [ctypes.POINTER(ctypes.c_ulong)] * 2 + [ctypes.POINTER(ctypes.c_int)] * 4 + \
            [ctypes.POINTER(ctypes.c_uint)]
        self.display = self.x11.XOpenDisplay(None)
        if not self.display:
            raise RuntimeError("Bloom needs an X11 desktop session on Linux (Wayland is not supported).")
        self.root = self.x11.XDefaultRootWindow(self.display)

    def poll(self):
        r, c = ctypes.c_ulong(), ctypes.c_ulong()
        rx, ry, wx, wy = ctypes.c_int(), ctypes.c_int(), ctypes.c_int(), ctypes.c_int()
        mask = ctypes.c_uint()
        self.x11.XQueryPointer(self.display, self.root, ctypes.byref(r), ctypes.byref(c),
                               ctypes.byref(rx), ctypes.byref(ry), ctypes.byref(wx), ctypes.byref(wy),
                               ctypes.byref(mask))
        m = mask.value
        return (bool(m & self.BUTTON1), bool(m & self.SHIFT), bool(m & self.CONTROL), bool(m & self.MOD1))

    def escape(self):
        return False            # on X11 the wheel grabs the keyboard and sees Esc itself

    def composited(self):
        """True when a compositor runs, so a nearly transparent window stays invisible."""
        self.x11.XInternAtom.restype = ctypes.c_ulong
        self.x11.XInternAtom.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
        self.x11.XGetSelectionOwner.restype = ctypes.c_ulong
        self.x11.XGetSelectionOwner.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        atom = self.x11.XInternAtom(self.display, b"_NET_WM_CM_S0", 0)
        return self.x11.XGetSelectionOwner(self.display, atom) != 0


def backend():
    return _Windows() if IS_WINDOWS else _X11()
