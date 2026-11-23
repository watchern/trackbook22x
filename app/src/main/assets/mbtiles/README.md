# Bundled offline map (debug only)

Debug builds offer a "Use Bundled Map (Debug)" button on the map management screen.
It copies the file below into the app's private storage:

    app/src/main/assets/mbtiles/jiangsu.map

The file is NOT stored in this repository (it is ~100 MB+). To test the debug flow:

1. Download the Jiangsu vector map (mapsforge format v5):
   https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/asia/china/jiangsu.map
2. Copy it here as `jiangsu.map`.

The download button in the app fetches the same file from the URL above, so bundling
is only needed when you want to test without network access. See docs/OFFLINE_MAP.md.
