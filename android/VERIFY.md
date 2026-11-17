# 验收记录

构建产物：`app/build/outputs/apk/release/app-release.apk`（823,476 字节）
签名：v2 方案通过，证书 `CN=Peng Xintian, OU=Personal, O=quad-diary, L=Deyang, ST=Sichuan, C=CN`
证书 SHA-256：`ff8ea6cf519dd3e70589148dd4643ee8152fda9fbe852f0bbb4f7adf41bca94d`

机器上用 `aapt2` / `apksigner` / 包内检查能确认的，都做了；需要真机点一下的，在最后一节单独列出。

---

## 逐条结果

### 1. 打开直接进首页，没有地址栏和浏览器 UI

| 项 | 结果 |
| --- | --- |
| Activity 数量 | 只有 `MainActivity` 一个（`launchable-activity` 唯一） |
| 布局内容 | `setContentView(webView)`，界面里只有 WebView，没有 Toolbar / 地址栏 / 前进后退按钮 |
| 全屏标志 | 无 `FLAG_FULLSCREEN`、无 edge-to-edge，只按系统栏取色 |
| 启动图标 | `application-label:'四象限日记'`，图标为自适应图标 |

反证：`aapt2 dump badging` 的输出里不存在任何浏览器类 intent-filter，
也没有 `WebViewActivity` 之类的第三方壳组件，整个 APK 只有一个 dex、一个 Activity。

置信度：**高**（结构性保证，不依赖运行）。真机复核见文末。

### 2. 日期左右切换、四象限选择、输入、滚动

页面逻辑一行未改，所以用「等价路径」验证：把 `app/src/main/assets/` 按 `/assets/` 挂到本地 HTTP 上，
按 AssetLoader 完全相同的路径 `/assets/web/index.html` 打开：

```
title         四象限日记 · QUAD DIARY
JSZip         3.10.1（来自本地 vendor，非 CDN）
四屏切换       timetable / tasks / review / diary 全部命中
404           0 条
控制台错误    0 条
localStorage  可读写
manifest      解析为 /assets/web/manifest.json
```

WebView 侧 `javaScriptEnabled` / `domStorageEnabled` / `databaseEnabled` 全开，
缩放三项全关（`supportZoom` / `builtInZoomControls` / `displayZoomControls`）。

置信度：**高**（渲染与交互来自同一个引擎，Chromium 与系统 WebView 同源）。

### 3. 键盘不遮挡输入区

`aapt2 dump xmltree` 读到的实际值：

```
windowSoftInputMode = 0x00000010   →  adjustResize
```

配合两点保证有效：

- 没有启用 edge-to-edge（targetSdk 34，且未调用 `setDecorFitsSystemWindows(false)`），
  否则 `adjustResize` 在新系统上会失效，需要自己处理 insets；
- 没有 `FLAG_LAYOUT_NO_LIMITS` 之类会把内容顶到键盘下面的标志。

置信度：**中高**。老系统（API 24–29）上 `adjustResize` 是标准行为；
个别国产 ROM 有「软键盘全屏」的坑，需要真机确认（见文末第 3 条）。

### 4. 杀进程重开后数据仍在

localStorage 落在 `/data/data/io.github.fanqin314.quaddiary/app_webview/Default/Local Storage/`，
只要不主动清理就会一直在。本工程里**没有**调用过：

`clearCache()` / `clearHistory()` / `clearFormData()` / `WebStorage.deleteAllData()` /
`deleteDatabase()` / `setIncognito(true)`

`onDestroy()` 只做 `loadUrl("about:blank")` + `destroy()`，不碰存储。
`allowBackup="false"` 只影响系统备份，不影响本地数据。

置信度：**高**（删掉数据的代码根本不存在）。

### 5. 飞行模式下能打开并记录

最硬的一条证据：

```
$ aapt2 dump permissions app-release.apk
package: io.github.fanqin314.quaddiary
```

**一个权限都没有。** 没有 `INTERNET`，这个应用在系统层面就无法发起网络请求，
资源全部来自 APK 里的 `assets/web/`（8 个文件，全部 `Stored` 未压缩）：
`index.html` / `manifest.json` / `sw.js` / `kcb-data.js` / `vendor/jszip.min.js` / 3 张图标。

另外线上版本实测过「外部请求 0 条」，页面本身也没有任何 CDN 依赖
（JSZip 已在上一轮从 jsdelivr 换成本地 vendor 文件）。

置信度：**高**。飞行模式对它没有影响，因为它压根没有联网能力。

### 6. 返回键逻辑

```java
@Override public void onBackPressed() {
    if (webView != null && webView.canGoBack()) { webView.goBack(); return; }
    super.onBackPressed();
}
```

`configChanges` 包含 `orientation|screenSize|keyboard|keyboardHidden|screenLayout|density|fontScale`，
不会因为键盘弹出、旋转（已锁）、分屏而重建 Activity，因此不存在「返回一下黑屏」的重建空窗。

`launchMode="singleTask"` 避免从通知/桌面反复点图标叠出多个实例。

置信度：**高**。

### 7. 竖屏锁定 + 深色可读

```
screenOrientation = 1        →  SCREEN_ORIENTATION_PORTRAIT
uses-feature: android.hardware.screen.portrait
```

深色主题由三层配合：

1. `values-night/themes.xml` 把父主题换成 `Theme.Material.NoActionBar`（深色），
   这样 WebView 的 `prefers-color-scheme` 才会报 `dark`，网页的「自动」主题才能跟随系统；
2. `values-night/colors.xml` 把启动底色换成 `#1B2038`，与网页深色 `--bg` 一致，冷启动不闪白；
3. 网页自己的深色配色此前已在 8 组视口（320/360/390/430 × 深浅）回归过，无对比度问题。

`configChanges` 特意**不包含 `uiMode`**：系统切深浅时会重建一次 Activity，
主题才能跟着换（代价是回到首页，可接受）。

置信度：**高**（配置层保证），实际观感需真机看一眼。

---

## 需要真机确认的三项

APK 已经能装，下面三条我没有设备，不能替你打包票。装好后按这个顺序点一遍：

```bash
adb install -r dist/四象限日记-1.0.0-release.apk
```

| # | 动作 | 期望 |
| --- | --- | --- |
| 3 | 在一个象限里加备注，让键盘弹起来 | 输入框整体上移，仍然完整可见；收起键盘后布局回落 |
| 4 | 记三条（含标签和备注）→ 设置里「强行停止」→ 重开 | 三条都在，日期停在那一天 |
| 7 | 打开系统深色模式 → 回到 App | 启动无白闪，页面自动变深色，文字可读 |

补充三个容易漏的点，也建议顺手试：

- **导出**：回看页 → 备份 → 导出 ZIP。应弹出「已保存到 下载/四象限日记/…zip」，
  用系统文件管理器能打开。这是外壳额外补的 `blob` 下载能力，最值得试。
- **导入**：同一个面板里点「导入恢复」，应弹出系统文件选择器（外壳实现了 `onShowFileChooser`）。
- **外链**：页面里如果有任何 http 外链，点了应该跳到浏览器，而不是把壳顶掉。

---

## 环境与命令（复现用）

```bash
# 本机实际使用的组合
JDK          D:/AndroidStudio/jbr（OpenJDK 21.0.10）
Gradle       8.9
AGP          8.5.2
compileSdk   34（platforms/android-34）
build-tools  34.0.0
minSdk       24 / targetSdk 34

cd android
export JAVA_HOME="D:/AndroidStudio/jbr"
export ANDROID_HOME="C:/Users/29799/AppData/Local/Android/Sdk"
./gradlew assembleRelease
```

构建环境里踩到的两个坑（已在配置里绕开）：

1. **工程在中文目录**：AGP 8.5 直接报 `Your project path contains non-ASCII characters`。
   加 `android.overridePathCheck=true` 解决，aapt2 处理中文路径实测正常。
   换机器若仍报错，把 `android/` 挪到纯英文路径即可。
2. **直连 Maven Central 会卡死**：表现为几十个 `CLOSE_WAIT`、下载几分钟不动。
   `settings.gradle` 已把阿里云镜像放在 `google()` / `mavenCentral()` 之前，官方仓库留作兜底。
