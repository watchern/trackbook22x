# 南京 / 江苏离线地图方案

Trackbook（v2.x）默认使用 osmdroid 在线瓦片（MAPNIK）。本分支将其替换为**纯离线矢量地图**，
无需 API Key、无网络也能显示底图；轨迹记录、GPX 导入导出等原有功能不受影响。

## 一、数据源

| 项目 | 说明 |
| --- | --- |
| 数据文件 | `jiangsu.map`（mapsforge 矢量地图，v5 格式，覆盖江苏省，含南京） |
| 下载地址 | <https://ftp-stud.hs-esslingen.de/pub/Mirrors/download.mapsforge.org/maps/v5/asia/china/jiangsu.map>（mapsforge 官方地图的德国 FTP 镜像） |
| 坐标系 | WGS84，无 GCJ-02 偏移 |
| 缩放级别 | 0–20 全级别矢量渲染（比预生成栅格 MBTiles 更灵活） |
| 版权 | © OpenStreetMap contributors（地图上与地图管理页均有署名，请勿移除） |
| 运行时位置 | `/storage/emulated/0/trackbook/osmdroid/jiangsu.map`（公共目录，需"所有文件访问"权限；未授权时回退应用私有目录 `getExternalFilesDir("osmdroid")`，卸载 App 后公共目录中的地图保留） |
| 可选备选 | `getExternalFilesDir("osmdroid")/nanjing.mbtiles`（MBTiles 栅格，z12–15，存在时作为补充图层） |

## 二、技术方案

```
osmdroid 6.1.11（未改动）
  └─ OfflineTileProvider（新增，MapTileProviderArray）
       ├─ MapTileSqlCacheProvider        ← 已渲染瓦片的 SQLite 缓存（最快）
       ├─ MapsforgeTileModuleProvider    ← 现场渲染 jiangsu.map 矢量瓦片（mapsforge 0.20.0）
       └─ MapTileFileArchiveProvider     ← 可选：nanjing.mbtiles 栅格归档
```

* `MapView.setUseDataConnection(false)` 在两处地图（主地图、轨迹回放）强制关闭在线请求；
  没有离线地图时显示空白网格 + 引导提示，**绝不发起在线瓦片请求**。
* mapsforge 0.20.0 才能读取 v5 格式（0.18.0+ 支持），osmdroid-mapsforge 6.1.18 之前捆绑的
  mapsforge 0.11 读不了 v5，因此仓库内自带薄桥接层
  （`MapsforgeTileSource` / `MapsforgeTileModuleProvider` / `OfflineTileProvider`，约 400 行），
  避免引入预编译 AAR 的字节码兼容风险；API 不匹配会在编译期报错，CI 可兜底。

### 关键类

| 类 | 职责 |
| --- | --- |
| `helpers/MapsforgeTileSource` | mapsforge `MapFile` + `DirectRenderer` 封装，`renderTile()` 输出 osmdroid `Drawable` |
| `helpers/MapsforgeTileModuleProvider` | 接入 osmdroid 模块链，渲染并把 PNG 写入 `SqlTileWriter` 缓存 |
| `helpers/OfflineTileProvider` | 缓存 → 矢量渲染 → MBTiles 的模块编排 |
| `helpers/OfflineMapHelper` | 文件路径/魔数校验（`mapsforge binary OSM`）/删除/内置导入/Provider 构建 |
| `MapDownloadWorker` | WorkManager `CoroutineWorker`：Range 断点续传、指数退避重试（≤5 次）、进度回调、前台通知、下载完成魔数校验 |
| `MapsFragment` | 地图管理页：状态/进度/下载/续传/删除/内置导入（仅 debug） |

## 三、两种获取离线地图的方式

**A. 内置 assets（调试用）**

1. 下载 `jiangsu.map`（见上文地址）；
2. 放到 `app/src/main/assets/mbtiles/jiangsu.map`；
3. debug 包地图管理页出现"使用内置地图（调试）"按钮，点击即复制到私有目录。

**B. App 内下载（正式流程）**

地图管理页"下载离线地图"按钮 → WorkManager 后台任务（支持断点续传/失败重试/进度通知）。
默认 URL 为上表中的镜像地址；如需改为自托管（例如放在自己 fork 的 GitHub Release）：

```kotlin
// app/src/main/java/org/y20k/trackbook/Keys.kt
const val OFFLINE_MAP_DOWNLOAD_URL: String = "https://github.com/<你的用户名>/<仓库名>/releases/download/offline-map-v1/jiangsu.map"
```

## 四、CI / 发布（不在本地构建）

* `.github/workflows/android-build.yml`：push / PR 时构建 debug APK 并上传 artifact；
* `.github/workflows/android-release.yml`：推送 `v*` 标签或手动触发时构建 release APK，
  可选通过 Secrets（`RELEASE_KEYSTORE_BASE64` / `RELEASE_STORE_PASSWORD` /
  `RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`）签名，并自动创建 GitHub Release 上传 APK。

给 GitHub Release 附加离线地图：在 Release 页面上传 `jiangsu.map` 资产，
然后把 `Keys.OFFLINE_MAP_DOWNLOAD_URL` 指向该资产即可。

## 五、Gradle 依赖变更

```groovy
// 新增
implementation 'androidx.work:work-runtime-ktx:2.7.1'   // 后台下载
implementation 'org.mapsforge:mapsforge-map-android:0.20.0'  // 矢量地图渲染（含 map/map-reader/core）
implementation 'org.mapsforge:mapsforge-themes:0.20.0'       // InternalRenderTheme.DEFAULT
implementation 'androidx.lifecycle:lifecycle-runtime-ktx:2.4.1'
// 保留
implementation 'org.osmdroid:osmdroid-android:6.1.11'
```

另启用 `buildFeatures.viewBinding`，并设置 `aaptOptions.noCompress 'map', 'mbtiles'`。

## 六、兼容性与约束说明

* Android 8–14（minSdk 25 / targetSdk 32）：地图默认存公共目录 `/storage/emulated/0/trackbook/osmdroid/`。
  Android 11+ 需要"所有文件访问"权限（地图管理页内引导跳系统设置授权）；Android 10 走
  `requestLegacyExternalStorage` + 存储运行时权限；Android 8–9 走运行时权限。未授权时自动回退
  应用私有目录（`getExternalFilesDir`，零权限可用），授权后已下载的地图自动迁移到公共目录。
* 深色模式沿用原有 `INVERT_COLORS` 滤镜，对矢量渲染同样生效。
* 下载任务有 NetworkType.CONNECTED 约束与指数退避；用户可在管理页暂停（取消任务），
  之后"继续下载"从 `.part` 文件断点续传。
* 未下载地图时：打开主地图显示引导 Snackbar（"去下载"直达管理页），轨迹记录与统计不受影响。
