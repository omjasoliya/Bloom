"""The Bloom wheel: a round, frosted-glass window of petals that opens under the
cursor. Files are dropped on it (Shift/Ctrl/Alt + drag) or it is clicked (the
right-click menu). Drawn with Qt so it looks the same on Windows and Linux."""
import math
import os
import sys
from pathlib import Path

from PySide6.QtCore import QByteArray, QFileInfo, QPointF, QRectF, Qt, QTimer, Signal
from PySide6.QtGui import (QColor, QCursor, QFont, QGuiApplication, QImageReader, QPainter,
                           QPainterPath, QPainterPathStroker, QPen, QPixmap, QRegion)
from PySide6.QtSvg import QSvgRenderer
from PySide6.QtWidgets import QFileIconProvider, QWidget

from . import icons

RING, OUTER, INNER, CENTER = 150, 141, 64, 55       # radii (logical pixels)
GAP, CORNER = 8, 16                                  # petal gap and corner rounding
SIZE = RING * 2 + 4
ACCENT = QColor(107, 76, 246)                        # iris violet, matches the website
PETAL = QColor(248, 247, 255)
INK = QColor(20, 32, 25)
MODE_TITLES = {"convert": "Convert", "tools": "Tools", "actions": "Actions"}
FONT = "Segoe UI" if sys.platform.startswith("win") else "Sans"


def human_size(paths):
    total = sum(os.path.getsize(p) for p in paths if os.path.isfile(p))
    for unit in ("B", "KB", "MB", "GB"):
        if total < 1024 or unit == "GB":
            size = f"{total:.0f} {unit}" if unit == "B" else f"{total:.1f} {unit}"
            break
        total /= 1024
    return f"{len(paths)} items · {size}" if len(paths) > 1 else size


def thumbnail(path, box=56):
    reader = QImageReader(path)
    if reader.canRead():
        size = reader.size()
        if size.isValid():
            size.scale(box, box, Qt.KeepAspectRatio)
            reader.setScaledSize(size)
        image = reader.read()
        if not image.isNull():
            return QPixmap.fromImage(image)
    # Anything else: the system's own icon for the file (Word, PDF, folder, ...).
    return QFileIconProvider().icon(QFileInfo(path)).pixmap(box, box)


def frosted(screen_pos):
    """Blurred snapshot of the screen under the wheel, used as the glass."""
    screen = QGuiApplication.screenAt(screen_pos) or QGuiApplication.primaryScreen()
    geo = screen.geometry()
    x, y = screen_pos.x() - SIZE // 2 - geo.x(), screen_pos.y() - SIZE // 2 - geo.y()
    shot = screen.grabWindow(0, x, y, SIZE, SIZE)
    if shot.isNull():
        return None
    small = shot.scaled(max(1, SIZE // 12), max(1, SIZE // 12), Qt.IgnoreAspectRatio, Qt.SmoothTransformation)
    return small.scaled(SIZE, SIZE, Qt.IgnoreAspectRatio, Qt.SmoothTransformation)


def _petal_shape(a0, a1):
    """Rounded annular sector: a shrunken sector, grown back by a round stroke."""
    ri, ro = INNER + CORNER, OUTER - CORNER
    pi, po = (GAP / 2 + CORNER) / ri, (GAP / 2 + CORNER) / ro
    c = SIZE / 2
    path = QPainterPath()
    steps = 24
    for k in range(steps + 1):
        a = a0 + po + (a1 - po - a0 - po) * k / steps
        pt = QPointF(c + ro * math.cos(a), c + ro * math.sin(a))
        path.moveTo(pt) if k == 0 else path.lineTo(pt)
    for k in range(steps + 1):
        a = a1 - pi - (a1 - pi - a0 - pi) * k / steps
        path.lineTo(QPointF(c + ri * math.cos(a), c + ri * math.sin(a)))
    path.closeSubpath()
    stroker = QPainterPathStroker()
    stroker.setWidth(CORNER * 2)
    stroker.setJoinStyle(Qt.RoundJoin)
    stroker.setCapStyle(Qt.RoundCap)
    return path.united(stroker.createStroke(path)).simplified()


class Wheel(QWidget):
    chosen = Signal(str, str, list)        # mode, key, paths
    cancelled = Signal()
    files_seen = Signal(list)              # a drag carrying files entered the wheel

    def __init__(self, items_for, click_mode=False):
        flags = Qt.Tool | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
        if not click_mode:
            flags |= Qt.WindowDoesNotAcceptFocus
        if sys.platform.startswith("linux"):
            flags |= Qt.X11BypassWindowManagerHint
        super().__init__(None, flags)
        self.items_for = items_for          # callable(mode, paths) -> [(key, label)]
        self.click_mode = click_mode
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAttribute(Qt.WA_ShowWithoutActivating, not click_mode)
        self.setFixedSize(SIZE, SIZE)
        self.setAcceptDrops(not click_mode)
        self.setMouseTracking(True)
        self.setMask(QRegion(0, 0, SIZE, SIZE, QRegion.Ellipse))
        self.mode, self.items, self.paths = "convert", [], []
        self.hover, self.center_hover = None, False
        self.glass = self.thumb = None
        self.size_label = ""
        self.armed_only = False             # invisible, waiting to learn what is dragged
        self.grow = 1.0                     # bloom-in animation progress
        self._shapes = {}
        self._anim = QTimer(self, interval=16, timeout=self._tick)

    # ------------------------------------------------------------ state ---
    def arm(self, pos, mode, visible_ring):
        """Show at the cursor before we know what's being dragged. On Windows (and
        composited X11) this is invisible; the drag's DragEnter fills it in."""
        self.mode, self.items, self.paths = mode, [], []
        self.hover, self.center_hover = None, False
        self.glass = frosted(pos)
        self.thumb, self.size_label = None, ""
        self.armed_only = not visible_ring
        self.move(pos.x() - SIZE // 2, pos.y() - SIZE // 2)
        self.show()
        self.raise_()
        self.update()

    def populate(self, mode, paths):
        items = self.items_for(mode, paths)
        if not items:
            return False
        self.mode, self.items, self.paths = mode, items, paths
        self.thumb = thumbnail(paths[0]) if paths else None
        self.size_label = human_size(paths)
        self.armed_only = False
        self._shapes = {}
        self.grow = 0.0
        self._anim.start()
        self.update()
        return True

    def open_for_click(self, pos, mode, paths):
        self.arm(pos, mode, visible_ring=True)
        ok = self.populate(mode, paths)
        if ok:
            self.activateWindow()
            self.grabMouse()
            self.grabKeyboard()
        return ok

    def switch(self, mode):
        if mode != self.mode and self.paths:
            self.populate(mode, self.paths)
        elif not self.paths:
            self.mode = mode

    def close_wheel(self):
        if self.click_mode:
            self.releaseMouse()
            self.releaseKeyboard()
        self._anim.stop()
        self.hide()
        self.items, self.paths, self.glass = [], [], None

    def _tick(self):
        self.grow = min(1.0, self.grow + 0.09)
        if self.grow >= 1.0:
            self._anim.stop()
        self.update()

    # --------------------------------------------------------- geometry ---
    def slice_at(self, x, y):
        dx, dy = x - SIZE / 2, y - SIZE / 2
        r = math.hypot(dx, dy)
        if not self.items or r < INNER or r > RING:
            return None
        step = 2 * math.pi / len(self.items)
        angle = (math.atan2(dy, dx) + math.pi / 2 + step / 2) % (2 * math.pi)
        return int(angle / step) % len(self.items)

    def set_hover(self, x, y):
        new = self.slice_at(x, y)
        center = math.hypot(x - SIZE / 2, y - SIZE / 2) < INNER
        if new != self.hover or center != self.center_hover:
            self.hover, self.center_hover = new, center
            self.update()

    # ---------------------------------------------------------- drawing ---
    def paintEvent(self, _event):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        c = SIZE / 2
        if self.armed_only:
            # Nearly invisible, but still "there" so the system delivers the drag to us.
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(255, 255, 255, 2))
            p.drawEllipse(QPointF(c, c), RING, RING)
            return

        ease = 1 - (1 - self.grow) ** 3
        scale = 0.6 + 0.4 * ease
        p.translate(c, c)
        p.scale(scale, scale)
        p.translate(-c, -c)
        p.setOpacity(min(1.0, 0.25 + ease))

        # 1. frosted-glass ring
        circle = QPainterPath()
        circle.addEllipse(QPointF(c, c), RING, RING)
        p.save()
        p.setClipPath(circle)
        if self.glass is not None:
            p.drawPixmap(QRectF(0, 0, SIZE, SIZE), self.glass, QRectF(self.glass.rect()))
        p.fillPath(circle, QColor(248, 247, 255, 92))
        p.restore()
        p.setPen(QPen(QColor(255, 255, 255, 150), 1.5))
        p.setBrush(Qt.NoBrush)
        p.drawEllipse(QPointF(c, c), RING - 0.75, RING - 0.75)

        # 2. petals
        n = max(len(self.items), 1)
        step = 2 * math.pi / n
        for i, (key, label) in enumerate(self.items):
            a0 = -math.pi / 2 - step / 2 + i * step
            a1 = a0 + step
            shape = self._shapes.get((i, n))
            if shape is None:
                shape = self._shapes[(i, n)] = _petal_shape(a0, a1)
            hovered = i == self.hover
            p.save()
            p.setOpacity(p.opacity() * (0.95 if hovered else 0.82))
            p.fillPath(shape, ACCENT if hovered else PETAL)
            p.restore()
            mid, rad = (a0 + a1) / 2, (OUTER + INNER) / 2 + 2
            cx, cy = c + rad * math.cos(mid), c + rad * math.sin(mid)
            ink = QColor("white") if hovered else INK
            if key in icons.PATHS:
                renderer = QSvgRenderer(QByteArray(icons.svg(key, ink.name()).encode()))
                renderer.render(p, QRectF(cx - 10, cy - 20, 20, 20))
                self._label(p, label, cx, cy + 9, ink, small=True)
            else:
                self._label(p, label, cx, cy, ink)

        # 3. glass centre with thumbnail and pill
        p.setPen(QPen(QColor(255, 255, 255, 165), 1.2))
        p.setBrush(QColor(248, 247, 255, 90))
        p.drawEllipse(QPointF(c, c), CENTER, CENTER)
        if self.thumb is not None and not self.thumb.isNull():
            tw, th = self.thumb.width() / self.thumb.devicePixelRatio(), self.thumb.height() / self.thumb.devicePixelRatio()
            rect = QRectF(c - tw / 2, c - th / 2 - 6, tw, th)
            clip = QPainterPath()
            clip.addRoundedRect(rect, 8, 8)
            p.save()
            p.setClipPath(clip)
            p.drawPixmap(rect, self.thumb, QRectF(self.thumb.rect()))
            p.restore()
        pill = "cancel" if self.center_hover else f"{MODE_TITLES[self.mode]} · {self.size_label}"
        font = QFont(FONT, 8)
        font.setBold(True)
        p.setFont(font)
        width = p.fontMetrics().horizontalAdvance(pill) + 20
        pill_rect = QRectF(c - width / 2, c + 18, width, 22)
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(250, 250, 255, 235))
        p.drawRoundedRect(pill_rect, 11, 11)
        p.setPen(INK)
        p.drawText(pill_rect, Qt.AlignCenter, pill)

    def _label(self, p, text, cx, cy, color, small=False):
        text = text.upper()
        lines = [text]
        if len(text) > 9 and " " in text:
            cut = text.rfind(" ", 0, len(text) // 2 + 3)
            lines = [text[:cut], text[cut + 1:]] if cut > 0 else lines
        longest = max(map(len, lines))
        size = 7 if small else (12 if longest <= 5 else 10 if longest <= 8 else 8.5)
        font = QFont(FONT)
        font.setPointSizeF(size)
        font.setBold(True)
        p.setFont(font)
        p.setPen(color)
        lh = p.fontMetrics().height()
        top = cy - (lh * len(lines)) / 2 + (lh / 2 if small else 0)
        for k, line in enumerate(lines):
            p.drawText(QRectF(cx - 60, top + k * lh, 120, lh), Qt.AlignCenter, line)

    # -------------------------------------------- click mode (right-click) ---
    def mouseMoveEvent(self, e):
        self.set_hover(e.position().x(), e.position().y())

    def mousePressEvent(self, e):
        i = self.slice_at(e.position().x(), e.position().y())
        if i is None:
            self.cancelled.emit()
        else:
            self.chosen.emit(self.mode, self.items[i][0], list(self.paths))

    def keyPressEvent(self, e):
        k = e.key()
        if k == Qt.Key_Escape:
            self.cancelled.emit()
        elif k == Qt.Key_Shift:
            self.switch("convert")
        elif k == Qt.Key_Control:
            self.switch("tools")
        elif k == Qt.Key_Alt:
            self.switch("actions")

    # ---------------------------------------------- drop mode (key + drag) ---
    def dragEnterEvent(self, e):
        paths = [u.toLocalFile() for u in e.mimeData().urls() if u.isLocalFile()]
        paths = [p for p in paths if os.path.exists(p)]
        if not paths:
            e.ignore()
            self.cancelled.emit()            # not files: get out of the way at once
            return
        self.files_seen.emit(paths)
        e.setDropAction(Qt.CopyAction)       # always copy: the source never deletes
        e.accept()

    def dragMoveEvent(self, e):
        self.set_hover(e.position().x(), e.position().y())
        e.setDropAction(Qt.CopyAction)
        e.accept()

    def dragLeaveEvent(self, _e):
        self.hover = None
        self.update()

    def dropEvent(self, e):
        i = self.slice_at(e.position().x(), e.position().y())
        if i is None or not self.paths:
            e.ignore()
            self.cancelled.emit()
            return
        e.setDropAction(Qt.CopyAction)
        e.accept()
        self.chosen.emit(self.mode, self.items[i][0], list(self.paths))
