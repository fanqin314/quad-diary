# 四象限日记 · Android 壳

一个纯 WebView 外壳：界面、数据、逻辑全部来自 `app/src/main/assets/web/` 里的网页，
原生这边只做「加载 + 补齐 WebView 缺的能力」，没有改动网页的任何一行 HTML/CSS/JS 业务代码。

---

## ⚠️ 先读这一条：加载地址不能换

日记存在 **localStorage**，而 localStorage 是**按 origin（协议 + 域名 + 端口）隔离**的。
本工程用的是离线方案 B，页面的 origin 固定为：

```
https://appassets.androidplatform.net
```

**一旦上线给用户装了，就不要再改成别的地址。** 具体说，下面这些操作都会让用户「打开发现日记全没了」：

| 改动 | 后果 |
| --- | --- |
| 改成在线壳 `loadUrl("https://fanqin314.github.io/quad-diary/")` | 换成了 `fanqin314.github.io` 这个 origin，旧数据读不到 |
| 改成 `file:///android_asset/...` | `file://` 是 null origin，localStorage 直接不可用 |
| 换成 Capacitor / Cordova 之类的默认 `localhost` 方案 | 又是新 origin |
| 改包名重装 | 应用数据目录跟着变，等于换了台设备 |

数据其实还躺在原来的 webview 数据目录里，但对用户来说就是「全丢了」。

确实要迁移，只有一条安全路径：
**旧版本里先「导出 ZIP 备份」→ 换新地址装新版本 → 在新版本里「导入恢复」。**

---

## 目录结构

```
android/
├── app/
│   ├── build.gradle                     包名 / 版本号 / SDK 版本 / 签名
│   └── src/main/
│       ├── AndroidManifest.xml          无 INTERNET 权限、锁竖屏、adjustResize
│       ├── assets/web/                  ★ 整个网站（离线资源，改网页就换这里）
│       ├── java/io/github/fanqin314/quaddiary/MainActivity.java
│       └── res/                         图标、启动底色、主题
├── keystore/quaddiary-release.jks       签名密钥（不在版本库里，务必备份）
├── keystore.properties                  密钥密码（不在版本库里）
├── tools/sync-web.py                    把上一级目录的网页同步进 assets/web
├── gradle/ + gradlew + gradlew.bat      Gradle Wrapper 8.9
└── settings.gradle / build.gradle / gradle.properties
```

---

## 编译

### 用 Android Studio

直接 `File → Open` 选 `android/` 这个目录，等 Gradle Sync 完，点 Run。

### 用命令行

```bash
cd android
export JAVA_HOME="D:/AndroidStudio/jbr"      # 任意 JDK 17+ 都行；本机用的是 Android Studio 自带 JBR 21

./gradlew assembleDebug                       # 调试包，可直接装
./gradlew assembleRelease                     # 正式包，用 keystore.properties 里的密钥签名
```

产物：

```
app/build/outputs/apk/release/app-release.apk
```

> 第一次构建要联网拉 AGP 与 androidx.webkit，之后就走本地缓存。

---

## 签名

密钥已经生成好了：`keystore/quaddiary-release.jks`，别名 `quaddiary`，
密码见 `keystore.properties`（当前是 `quaddiary2026`）。

重新生成一份（换机器、或想要自己的）：

```bash
keytool -genkeypair -v \
  -keystore keystore/quaddiary-release.jks \
  -alias quaddiary \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码 \
  -dname "CN=你的名字, OU=Personal, O=quad-diary, L=城市, ST=省, C=CN"
```

然后照 `keystore.properties.example` 填 `keystore.properties`。

**这把 jks 必须自己备份好。** 它相当于这个 App 的身份证：

- 丢了 → 以后打出来的包签名不同，用户手机上的旧版**无法覆盖安装**，只能卸载重装（数据一起没）
- 泄露 → 别人可以伪造「升级包」发给你的用户

所以 `keystore/` 和 `keystore.properties` 都在 `.gitignore` 里，不进公开仓库。

---

## 改包名

包名出现在两个地方，必须一起改：

1. `app/build.gradle`
   ```groovy
   namespace 'io.github.fanqin314.quaddiary'
   applicationId "io.github.fanqin314.quaddiary"
   ```
2. Java 源码的 `package` 声明与目录结构
   `app/src/main/java/io/github/fanqin314/quaddiary/` → 对应新目录

`:app` 的 `namespace` 决定 `R` 类在哪个包，`applicationId` 决定设备上的应用身份。
**改 `applicationId` 等于发布一个全新应用**，旧版本的数据不会跟过来（见文首那条）。

改完记得同步 `AndroidManifest.xml` 里的 `android:name=".MainActivity"`——它相对 `namespace` 解析，通常不用动。

---

## 改图标

图标有两套，都要照顾：

**1）Android 8.0+ 自适应图标（占绝大多数设备）**

| 文件 | 作用 |
| --- | --- |
| `res/drawable/ic_launcher_foreground.xml` | 前景：目前是白/灰四格 |
| `res/values/colors.xml` 里的 `ic_launcher_background` | 背景色：目前 `#171A26` |
| `res/drawable/ic_launcher_monochrome.xml` | Android 13+ 主题图标用的单色版 |

改形状就改 vector 的 `pathData`，改颜色改 `fillColor`。
注意前景内容要留在中间 **66dp / 108dp** 的安全区里，否则会被启动器裁掉。

**2）Android 7.x 用的位图**

`res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png` 与 `ic_launcher_round.png`
（48 / 72 / 96 / 144 / 192 px）。

最省事的做法：Android Studio 里右键 `res` → `New → Image Asset`，选一张 1024×1024 的图，
它会自动把两套都生成好。

---

## 改版本号

`app/build.gradle`：

```groovy
versionCode 1          // 整数，每次发版必须 +1，否则装不上（系统认为没升级）
versionName "1.0.0"    // 给人看的
```

另外记得同步 `MainActivity` 里的 UA 尾巴 `QuadDiary/1.0.0`（可选，只影响调试识别）。

---

## 更新离线网页资源

网页改了之后，**不要手改 assets**，跑同步脚本：

```bash
cd android
python tools/sync-web.py
```

它会从 `../`（也就是 `天天记/` 网页根目录）把 8 个运行时文件拷进 `app/src/main/assets/web/`，
并清掉目录里多余的文件。然后重新 `assembleRelease` 即可。

要看住两个细节：

- **`sw.js` 的 `CACHE` 版本号**。网页的 Service Worker 对 `kcb-data.js` 这类静态资源是
  「缓存优先」，只换文件不换版本号，老用户拿到的是缓存里的旧文件。改过网页资源就把它 `v33 → v34`。
  （`index.html` 本身走的是 navigate 分支，网络优先，不受影响。）
- **不要把 `server.js`、`打开日记.vbs`、`功能计划.md` 拷进去**，同步脚本的 `FILES` 白名单已经排除了。

---

## WebView 侧做了什么

网页本身不需要改，但有四件事 WebView 默认做不到，外壳补上了：

| 能力 | 不补会怎样 | 做法 |
| --- | --- | --- |
| `a[download]` + `blob:` 下载（导出 ZIP / 导出长图） | 点导出毫无反应，备份功能等于废掉 | 页面加载后注入下载桥，拦点击 → 读 Blob → 分 3MB 分块交给原生落盘 |
| `<input type="file">`（导入备份 / 选照片） | 点「导入恢复」不弹选择器 | 实现 `onShowFileChooser` + `onActivityResult` |
| 外链 / `target="_blank"` | 外链把壳顶掉，回不来 | `shouldOverrideUrlLoading` 拦下，`ACTION_VIEW` 交给系统浏览器 |
| 长按弹出「保存图片 / 复制链接」 | 页面像网页而不像应用 | `setOnLongClickListener` 里按 hitTestResult 判断，输入框放行 |

导出的文件落在「**下载/四象限日记/**」下（Android 10+ 走 MediaStore，不需要任何存储权限；
Android 9 及以下落在 `Android/data/io.github.fanqin314.quaddiary/files/Download/`）。

其余配置：JS / DOM Storage / database 全开，缩放全关，`adjustResize` 保证键盘不挡输入框，
返回键先退 WebView 历史再退出，锁竖屏，**不申请 INTERNET 权限**。

---

## 验收结果

见 `VERIFY.md`（逐条对应需求里的七项）。
