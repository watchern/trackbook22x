# Bundled offline map

The Jiangsu vector map (`jiangsu.map`, ~94 MB, mapsforge v5 format) ships inside the APK:

- **CI builds**: the GitHub Actions workflows download the map from
  `https://download.mapsforge.org/maps/v5/asia/china/jiangsu.map` before building
  (see "Download bundled offline map" step) and bundle it under this folder.
- **Local builds**: place your own `jiangsu.map` here (same URL), or let the CI step download it.

On first use the app copies the bundled map to the active offline map directory
(`/storage/emulated/0/trackbook/osmdroid/` once storage access is granted, the
app-private dir otherwise) and verifies the mapsforge magic bytes. The copy is
idempotent - it is skipped when a valid map is already installed.

The in-app download button remains as an update path (see docs/OFFLINE_MAP.md).

