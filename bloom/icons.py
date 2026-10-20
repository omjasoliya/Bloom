"""24px stroke icons for Tools and Actions petals (same set as the website)."""
PATHS = {
    "fit": "M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M9 12h6M12 9v6",
    "compress": "M4 14h6v6M20 10h-6V4M14 10l7-7M3 21l7-7",
    "resize": "M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7",
    "rotate": "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5",
    "strip": "M20.6 13.4l-7.2 7.2a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8zM7.5 7.5h.01",
    "gray": "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18zM12 3v18M12 8h5M12 12h8M12 16h5",
    "topdf": "M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6M8 13h8M8 17h5",
    "merge": "M6 3v6l6 6v6M18 3v6l-6 6",
    "split": "M12 3v6l-6 6v6M12 9l6 6v6",
    "720p": "M3 5h18v12H3zM8 21h8M12 17v4",
    "mute": "M11 5L6 9H3v6h3l5 4zM22 9l-6 6M16 9l6 6",
    "normalize": "M2 12h3l3-7 4 14 4-10 3 3h3",
    "copypath": "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1",
    "terminal": "M4 17l6-5-6-5M12 19h8",
    "copydesk": "M9 9h11v11H9zM5 15H4V4h11v1",
    "movedesk": "M3 12h12M11 8l4 4-4 4M15 4h5v16h-5",
    "zip": "M3 7h18v13H3zM3 7l2-4h14l2 4M10 11h4",
    "duplicate": "M8 8h12v12H8zM4 16V4h12M14 11v6M11 14h6",
}


def svg(name, color):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="{color}" '
            f'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="{PATHS[name]}"/></svg>')
