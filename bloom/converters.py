"""Bloom conversion engine: which formats fit a file, and converting it into a
new copy next to the original. Works on Windows and Linux."""
import shutil
import tempfile
from pathlib import Path

from . import platform_utils as pu

IMAGE = {"png", "jpg", "jpeg", "webp", "bmp", "gif", "tif", "tiff", "ico"}
VIDEO = {"mp4", "mkv", "mov", "webm", "avi", "m4v", "flv", "wmv"}
AUDIO = {"mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma"}
TEXT_DOC = {"doc", "docx", "odt", "rtf", "txt"}
SHEET = {"xls", "xlsx", "ods", "csv"}
SLIDES = {"ppt", "pptx", "odp"}
OFFICE = {"text", "sheet", "slides"}

FORMATS = {
    "image": ["png", "jpg", "webp", "pdf", "bmp", "tiff", "ico", "gif"],
    "video": ["mp4", "webm", "gif", "mp3", "wav", "mkv"],
    "audio": ["mp3", "wav", "flac", "ogg", "m4a"],
    "text": ["pdf", "docx", "odt", "txt", "rtf"],
    "sheet": ["pdf", "xlsx", "ods", "csv"],
    "slides": ["pdf", "pptx", "odp"],
    "pdf": ["png", "jpg"],
}


class MissingTool(RuntimeError):
    pass


def category(path):
    ext = Path(path).suffix.lower().lstrip(".")
    for name, exts in (("image", IMAGE), ("video", VIDEO), ("audio", AUDIO),
                       ("text", TEXT_DOC), ("sheet", SHEET), ("slides", SLIDES)):
        if ext in exts:
            return name
    return "pdf" if ext == "pdf" else None


def _norm(ext):
    return "jpg" if ext == "jpeg" else "tiff" if ext == "tif" else ext


def formats_for(paths):
    """Formats offered on the Convert wheel, based on the first convertible file."""
    for p in paths:
        cat = category(p)
        if cat:
            own = _norm(Path(p).suffix.lower().lstrip("."))
            return [f for f in FORMATS[cat] if f != own]
    return []


def unique_target(src, ext):
    src = Path(src)
    target = src.with_suffix("." + ext)
    n = 1
    while target.exists():
        target = src.with_name(f"{src.stem} ({n}).{ext}")
        n += 1
    return target


def _image(src, target, ext):
    from PIL import Image, ImageOps
    with Image.open(src) as opened:
        im = ImageOps.exif_transpose(opened)
        if ext in ("jpg", "bmp", "pdf") and im.mode in ("RGBA", "LA", "P"):
            im = im.convert("RGBA")
            bg = Image.new("RGB", im.size, "white")
            bg.paste(im, mask=im.getchannel("A"))
            im = bg
        elif ext == "ico":
            im = im.copy()
            im.thumbnail((256, 256))
        fmt = {"jpg": "JPEG", "tiff": "TIFF"}.get(ext, ext.upper())
        im.save(target, fmt)


def _ffmpeg(src, target, ext):
    args = [pu.ffmpeg_exe(), "-y", "-loglevel", "error", "-i", str(src)]
    if ext == "gif":
        args += ["-vf", "fps=12,scale='min(640,iw)':-1:flags=lanczos"]
    elif ext in ("mp3", "wav", "flac", "ogg", "m4a"):
        args += ["-vn"]
    pu.run(args + [str(target)], timeout=3600)


def _office(src, target, ext):
    soffice = pu.soffice_exe()
    if not soffice:
        raise MissingTool("Install LibreOffice to convert documents, spreadsheets and slides.")
    with tempfile.TemporaryDirectory(prefix="bloom-") as tmp:
        pu.run([soffice, "--headless", "--convert-to", ext, "--outdir", tmp, str(src)], timeout=180)
        produced = next(Path(tmp).glob(f"*.{ext}"))
        shutil.move(str(produced), str(target))


def _pdf_pages(src, target, ext):
    """Every page becomes its own image: name-1.png, name-2.png, ... at 150 DPI."""
    import pypdfium2 as pdfium
    pdf = pdfium.PdfDocument(str(src))
    made = []
    try:
        for i in range(len(pdf)):
            image = pdf[i].render(scale=150 / 72).to_pil()
            out = unique_target(target.with_name(f"{target.stem}-{i + 1}{target.suffix}"), ext)
            image.convert("RGB").save(out, "PNG" if ext == "png" else "JPEG", quality=90)
            made.append(out)
    finally:
        pdf.close()
    return made


def convert(src, ext):
    """Convert one file. Returns the list of files created."""
    cat = category(src)
    if not cat or ext not in FORMATS[cat]:
        raise ValueError(f"{Path(src).name} can't be converted to {ext.upper()}")
    target = unique_target(src, ext)
    if cat == "image":
        _image(src, target, ext)
    elif cat in ("video", "audio"):
        _ffmpeg(src, target, ext)
    elif cat == "pdf":
        return _pdf_pages(src, target, ext)
    else:
        _office(src, target, ext)
    return [target]
