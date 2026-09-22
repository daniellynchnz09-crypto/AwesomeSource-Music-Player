# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for a standalone Windows .exe (Phase 3 packaging).

PySide6 is NOT run through collect_all() — PyInstaller ships its own PySide6 hook
that already collects the right Qt plugins (platforms, imageformats, styles) for
whichever Qt modules this app actually imports (QtCore/QtGui/QtWidgets), which is
the fix for the common PyInstaller + PySide6 pitfall ("could not find or load the Qt
platform plugin windows"). collect_all('PySide6') also works but bundles the entire
PySide6 package — WebEngine, Qt3D, Multimedia, etc. — none of which this app uses;
the `excludes` list below drops that dead weight instead, which is what keeps the
built exe closer to Qt's actual footprint rather than ~250MB of unused modules.

mutagen, musicbrainzngs, and rapidfuzz ARE run through collect_all() as a precaution
against missing submodules/package data — they're small enough that the blunt
approach costs nothing.
"""

from PyInstaller.utils.hooks import collect_all

datas = []
binaries = []
hiddenimports = []
for pkg in ("mutagen", "musicbrainzngs", "rapidfuzz"):
    pkg_datas, pkg_binaries, pkg_hiddenimports = collect_all(pkg)
    datas += pkg_datas
    binaries += pkg_binaries
    hiddenimports += pkg_hiddenimports

# Qt modules this app never imports (no web content, 3D, media playback, IoT
# sensors, charts, PDF, or QML/Quick UI — it's plain QtWidgets) — excluding them
# keeps PyInstaller from bundling their (large) binaries at all.
excluded_qt_modules = [
    "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets", "PySide6.QtWebEngineQuick",
    "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtQuick3D", "PySide6.QtQuickWidgets",
    "PySide6.Qt3DCore", "PySide6.Qt3DRender", "PySide6.Qt3DInput", "PySide6.Qt3DLogic",
    "PySide6.Qt3DAnimation", "PySide6.Qt3DExtras",
    "PySide6.QtMultimedia", "PySide6.QtMultimediaWidgets", "PySide6.QtSpatialAudio",
    "PySide6.QtBluetooth", "PySide6.QtNfc", "PySide6.QtSensors",
    "PySide6.QtSerialPort", "PySide6.QtSerialBus",
    "PySide6.QtPositioning", "PySide6.QtLocation",
    "PySide6.QtCharts", "PySide6.QtDataVisualization", "PySide6.QtGraphs",
    "PySide6.QtPdf", "PySide6.QtPdfWidgets",
    "PySide6.QtHttpServer", "PySide6.QtRemoteObjects",
    "PySide6.QtScxml", "PySide6.QtStateMachine",
    "PySide6.QtWebSockets", "PySide6.QtWebChannel", "PySide6.QtWebView",
    "PySide6.QtDesigner", "PySide6.QtHelp", "PySide6.QtUiTools", "PySide6.QtTest",
    "PySide6.QtNetworkAuth",
]

a = Analysis(
    ["musictagger/app.py"],
    pathex=[],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excluded_qt_modules,
    noarchive=False,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="MusicDetailsGenerator",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
