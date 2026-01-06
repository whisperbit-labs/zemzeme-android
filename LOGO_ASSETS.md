# Logo & Launcher Assets Reference

---

## `drawable/` — Vector & general assets

| File | Purpose |
|---|---|
| `ic_launcher_foreground.xml` | Vector foreground layer of the adaptive icon |
| `ic_launcher_monochrome.xml` | Monochrome version (Android 13+ themed icons) |
| `ic_notification.xml` | Icon shown in the status bar for notifications |
| `zemzeme_logo.png` | Logo used inside the app (e.g. About screen) |

---

## `mipmap-anydpi-v26/` — Adaptive icon definitions (Android 8+)

| File | Purpose |
|---|---|
| `ic_launcher.xml` | Adaptive icon XML — square shape |
| `ic_launcher_round.xml` | Adaptive icon XML — round shape |

These XML files reference the background color + foreground vector.
They apply to all screen densities on Android 8+.

---

## `mipmap-[density]/` — Raster launcher icons (5 folders)

Each of the five density folders contains the same three files:

| File | Purpose |
|---|---|
| `ic_launcher.png` | Standard (square) launcher icon |
| `ic_launcher_round.png` | Round launcher icon |
| `ic_launcher_foreground.png` | Foreground layer only (used by adaptive icon on older tools) |

| Folder | DPI | Launcher icon size |
|---|---|---|
| `mipmap-mdpi` | 160 dpi | 48 × 48 px |
| `mipmap-hdpi` | 240 dpi | 72 × 72 px |
| `mipmap-xhdpi` | 320 dpi | 96 × 96 px |
| `mipmap-xxhdpi` | 480 dpi | 144 × 144 px |
| `mipmap-xxxhdpi` | 640 dpi | 192 × 192 px |

---

## Full file list

```
res/
├── drawable/
│   ├── ic_launcher_foreground.xml
│   ├── ic_launcher_monochrome.xml
│   ├── ic_notification.xml
│   └── zemzeme_logo.png
│
├── mipmap-anydpi-v26/
│   ├── ic_launcher.xml
│   └── ic_launcher_round.xml
│
├── mipmap-mdpi/
│   ├── ic_launcher.png          (48 × 48 px)
│   ├── ic_launcher_round.png    (48 × 48 px)
│   └── ic_launcher_foreground.png
│
├── mipmap-hdpi/
│   ├── ic_launcher.png          (72 × 72 px)
│   ├── ic_launcher_round.png    (72 × 72 px)
│   └── ic_launcher_foreground.png
│
├── mipmap-xhdpi/
│   ├── ic_launcher.png          (96 × 96 px)
│   ├── ic_launcher_round.png    (96 × 96 px)
│   └── ic_launcher_foreground.png
│
├── mipmap-xxhdpi/
│   ├── ic_launcher.png          (144 × 144 px)
│   ├── ic_launcher_round.png    (144 × 144 px)
│   └── ic_launcher_foreground.png
│
└── mipmap-xxxhdpi/
    ├── ic_launcher.png          (192 × 192 px)
    ├── ic_launcher_round.png    (192 × 192 px)
    └── ic_launcher_foreground.png
```

---

## What to replace for a full rebrand

| What | Files to replace |
|---|---|
| App launcher icon | All 5 `ic_launcher.png` + 5 `ic_launcher_round.png` in mipmap folders |
| Adaptive icon foreground | `drawable/ic_launcher_foreground.xml` + 5 `ic_launcher_foreground.png` |
| Monochrome (Android 13 themed) | `drawable/ic_launcher_monochrome.xml` |
| In-app logo | `drawable/zemzeme_logo.png` |
| Notification bar icon | `drawable/ic_notification.xml` |

---

## Notes

- The adaptive icon system (Android 8+) is defined in `mipmap-anydpi-v26/`. It composes a **background** (color or drawable) with a **foreground** layer (`ic_launcher_foreground`). The system then masks it into whatever shape the launcher uses (circle, squircle, etc.).
- The monochrome icon (`ic_launcher_monochrome.xml`) is used by Android 13+ for themed/tinted icons that match the user's wallpaper color palette.
- The notification icon (`ic_notification.xml`) must be a **single-color vector** (white/transparent only) — Android ignores color in notification icons.
- Use [Android Asset Studio](https://romannurik.github.io/AndroidAssetStudio/icons-launcher.html) or Figma with an Android Icon Export plugin to generate all density sizes from one 1024 × 1024 px source file.
