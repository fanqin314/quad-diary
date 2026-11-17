package io.github.fanqin314.quaddiary;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewFeature;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 纯 WebView 外壳：页面本身不做任何改动，全部逻辑仍在 assets/web/index.html 里。
 *
 * 为什么要自己写下载和文件选择：
 *   WebView 默认「不支持 blob: 下载」也「不弹文件选择器」。而页面里
 *   导出 ZIP / 导出长图走的是 a[download] + URL.createObjectURL，
 *   导入备份和选照片走的是 <input type="file">。不接这两个口子，
 *   壳子看着能用，用户一导出就没有反应 —— 备份等于废掉。
 *
 *   所以这里做两件事：
 *   1) 页面加载完后注入一小段「下载桥」，拦下 a[download] 的点击，
 *      把 Blob 分块读成 base64 交给原生落盘；
 *   2) 实现 onShowFileChooser，把系统文件选择器的结果回传给 <input type="file">。
 *   两者都不改页面文件，只补 WebView 缺的能力。
 */
public class MainActivity extends Activity {

    /** 本地站点映射出来的域名，只有它会留在 WebView 里 */
    private static final String APP_HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + APP_HOST + "/assets/web/index.html";

    /** 与网页 :root 的 --bg / [data-theme=dark] 的 --bg 一致，避免启动白闪 */
    private static final String COLOR_LIGHT = "#F4F0E4";
    private static final String COLOR_DARK = "#1B2038";

    /** 导出的文件落在「下载/四象限日记/」下面，好找 */
    private static final String DOWNLOAD_SUBDIR = "四象限日记";
    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private ValueCallback<Uri[]> pendingFileCallback;

    // ---- 下载桥的分块写入状态（@JavascriptInterface 在 JavaBridge 线程回调，写盘要自己同步）----
    private final Object writeLock = new Object();
    private OutputStream pendingOut;
    private File pendingTemp;
    private String pendingName;
    private String pendingMime;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean night = isNightMode();
        final int chromeColor = Color.parseColor(night ? COLOR_DARK : COLOR_LIGHT);

        // 窗口底色先铺上，冷启动时第一帧不会是刺眼的白
        getWindow().setBackgroundDrawable(new ColorDrawable(chromeColor));
        applySystemBars(night);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setBackgroundColor(chromeColor);
        configure(webView);

        setContentView(webView);
        webView.loadUrl(START_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
        // 从别处切回来时系统栏可能被改过，重刷一遍
        applySystemBars(isNightMode());
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        closePendingStream();
        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) parent.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    /** 返回键：能退就退历史，退不动才交还系统（否则会白屏/黑屏退出） */
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ WebView 配置

    @SuppressLint("SetJavaScriptEnabled")
    private void configure(final WebView wv) {
        WebSettings s = wv.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // localStorage —— 整本日记就住在这里，绝不能关
        s.setDatabaseEnabled(true);

        // 页面是按 390px 手机宽设计的固定版式，禁掉一切缩放，免得双指一捏就花
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);

        s.setAllowFileAccess(false);       // 走 AssetLoader，不需要 file://
        s.setAllowContentAccess(false);
        s.setGeolocationEnabled(false);
        s.setSaveFormData(false);
        s.setMediaPlaybackRequiresUserGesture(true);

        // 不开多窗口：target="_blank" 会让它在当前 WebView 里导航，
        // 再由 shouldOverrideUrlLoading 统一交给系统浏览器，行为更可控。
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        // 本站全在本地，不存在 http 混用；有的话一律拒绝
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // 关掉「强制暗化」：页面自己有一套深色配色，被系统再算一遍就脏了
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, false);
        }

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return openOutside(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 每次加载完都补一次下载桥；脚本里有幂等标记，重复注入无副作用
                view.evaluateJavascript(DOWNLOAD_BRIDGE_JS, null);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    pendingFileCallback = null;
                    toast("打不开文件选择器");
                    return false;
                }
                try {
                    startActivityForResult(intent, REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    pendingFileCallback = null;
                    toast("这台设备没有文件选择器");
                    return false;
                }
                return true;
            }
        });

        // 兜底：万一下载桥没装上（例如页面还没触发 onPageFinished 就点了导出），
        // blob 交给脚本再取一次；其它链接一律丢给系统浏览器
        wv.setDownloadListener((url, ua, disposition, mime, length) -> {
            if (url != null && url.startsWith("blob:")) {
                wv.evaluateJavascript(blobFallbackJs(url, mime), null);
            } else if (url != null) {
                openOutside(Uri.parse(url));
            }
        });

        // 长按：输入框以外的位置全部吞掉（不弹「保存图片 / 复制链接」，
        // 也不让普通文字起选择手柄），输入框内返回 false 保证能长按粘贴
        wv.setOnLongClickListener(v -> {
            WebView.HitTestResult hit = wv.getHitTestResult();
            int type = (hit == null) ? WebView.HitTestResult.UNKNOWN_TYPE : hit.getType();
            return type != WebView.HitTestResult.EDIT_TEXT_TYPE;
        });

        wv.addJavascriptInterface(new SaveBridge(), "QDSaver");
    }

    // ------------------------------------------------------------------ 外链

    /** 返回 true 表示「这次导航被我们接管了，WebView 不要自己去加载」 */
    private boolean openOutside(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;

        boolean web = "http".equals(scheme) || "https".equals(scheme);
        if (web && APP_HOST.equals(uri.getHost())) {
            return false;   // 自家页面，留在壳里
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            toast("没有能打开这个链接的应用");
        }
        return true;
    }

    // ------------------------------------------------------------------ 系统栏

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    @SuppressWarnings("deprecation")
    private void applySystemBars(boolean night) {
        int color = Color.parseColor(night ? COLOR_DARK : COLOR_LIGHT);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);

        View decor = getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (night) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (night) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            else flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    // ------------------------------------------------------------------ 文件选择回传

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE_CHOOSER) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (pendingFileCallback == null) return;

        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++) {
                    result[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{ data.getData() };
            }
        }
        pendingFileCallback.onReceiveValue(result);
        pendingFileCallback = null;
    }

    // ------------------------------------------------------------------ 下载落盘

    /**
     * 页面通过 QDSaver.begin / chunk / end 三段式把 blob 交过来。
     * 分块是为了大备份（几十 MB 照片）时不在 JS 与 Java 之间一次性搬一个
     * 超长 base64 字符串 —— 那样很容易直接把进程撑爆。
     */
    private class SaveBridge {

        @JavascriptInterface
        public void begin(String fileName, String mime) {
            synchronized (writeLock) {
                closePendingStream();
                try {
                    pendingTemp = File.createTempFile("qd-export-", ".bin", getCacheDir());
                    pendingOut = new BufferedOutputStream(new FileOutputStream(pendingTemp), 1 << 16);
                    pendingName = sanitize(fileName);
                    pendingMime = (mime == null || mime.isEmpty()) ? mimeFor(pendingName) : mime;
                } catch (IOException e) {
                    pendingTemp = null;
                    pendingOut = null;
                    toastOnUi("导出失败：无法写入临时文件");
                }
            }
        }

        @JavascriptInterface
        public void chunk(String base64) {
            if (base64 == null || base64.isEmpty()) return;
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            synchronized (writeLock) {
                if (pendingOut == null) return;
                try {
                    pendingOut.write(bytes);
                } catch (IOException e) {
                    closePendingStream();
                    toastOnUi("导出中断：写入失败");
                }
            }
        }

        @JavascriptInterface
        public void end() {
            final File temp;
            final String name;
            final String mime;
            synchronized (writeLock) {
                if (pendingOut == null || pendingTemp == null) return;
                try {
                    pendingOut.flush();
                } catch (IOException ignored) {
                    // 刷不下去也照样尝试收尾，让用户看到失败提示而不是静默
                }
                closePendingStream();
                temp = pendingTemp;
                name = pendingName;
                mime = pendingMime;
                pendingTemp = null;
                pendingName = null;
                pendingMime = null;
            }
            runOnUiThread(() -> publishDownload(temp, name, mime));
        }

        @JavascriptInterface
        public void abort() {
            synchronized (writeLock) {
                closePendingStream();
                if (pendingTemp != null) {
                    //noinspection ResultOfMethodCallIgnored
                    pendingTemp.delete();
                    pendingTemp = null;
                }
            }
            toastOnUi("导出取消");
        }
    }

    /** 把临时文件搬到「下载/四象限日记/」，Android 10 起不需要任何存储权限 */
    private void publishDownload(File temp, String name, String mime) {
        if (temp == null || !temp.exists()) {
            toast("导出失败：没有拿到数据");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_SUBDIR);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri target = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new IOException("MediaStore 拒绝了写入");

                try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) throw new IOException("打不开输出流");
                    copy(temp, out);
                }
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(target, values, null, null);

                toast("已保存到 下载/" + DOWNLOAD_SUBDIR + "/" + name);
            } else {
                // Android 9 及以下没有 MediaStore.Downloads，写公开目录又要存储权限，
                // 所以落在应用自己的外部目录里：不需要权限，文件管理器也能翻到。
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
                if (dir != null && !dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                File out = new File(dir, name);
                try (OutputStream os = new FileOutputStream(out)) {
                    copy(temp, os);
                }
                toast("已保存到 " + out.getAbsolutePath());
            }
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static void copy(File src, OutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    private void closePendingStream() {
        if (pendingOut != null) {
            try {
                pendingOut.close();
            } catch (IOException ignored) {
                // 关不掉也没别的办法，忽略
            }
            pendingOut = null;
        }
    }

    // ------------------------------------------------------------------ 小工具

    private static String sanitize(String name) {
        String base = (name == null || name.trim().isEmpty()) ? "export.bin" : name.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_");
        if (base.length() > 120) base = base.substring(base.length() - 120);
        return base;
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void toastOnUi(String msg) {
        runOnUiThread(() -> toast(msg));
    }

    /**
     * 下载桥：拦下所有 a[download] + blob: 的点击，防止 WebView 把它当普通链接忽略。
     * 每次 chunk 是 3MB 二进制（base64 后约 4MB），大文件也不会一次性进内存。
     */
    private static final String DOWNLOAD_BRIDGE_JS =
            "(function(){\n"
          + "  if (window.__qdDownloadBridge) return;\n"
          + "  window.__qdDownloadBridge = true;\n"
          + "  var CHUNK = 3 * 1024 * 1024;\n"
          + "  document.addEventListener('click', function(e){\n"
          + "    var el = e.target;\n"
          + "    while (el && el.nodeType === 1) {\n"
          + "      if (el.tagName === 'A' && el.hasAttribute('download')) break;\n"
          + "      el = el.parentElement;\n"
          + "    }\n"
          + "    if (!el || el.nodeType !== 1) return;\n"
          + "    var href = el.href || '';\n"
          + "    if (href.indexOf('blob:') !== 0) return;\n"
          + "    e.preventDefault();\n"
          + "    e.stopPropagation();\n"
          + "    var name = el.getAttribute('download') || 'export.bin';\n"
          + "    fetch(href).then(function(r){ return r.blob(); }).then(function(b){\n"
          + "      QDSaver.begin(name, b.type || '');\n"
          + "      var off = 0;\n"
          + "      (function step(){\n"
          + "        if (off >= b.size) { QDSaver.end(); return; }\n"
          + "        var part = b.slice(off, Math.min(off + CHUNK, b.size));\n"
          + "        off += CHUNK;\n"
          + "        var fr = new FileReader();\n"
          + "        fr.onload = function(){\n"
          + "          var s = String(fr.result), i = s.indexOf(',');\n"
          + "          QDSaver.chunk(i > 0 ? s.slice(i + 1) : '');\n"
          + "          step();\n"
          + "        };\n"
          + "        fr.onerror = function(){ QDSaver.abort(); };\n"
          + "        fr.readAsDataURL(part);\n"
          + "      })();\n"
          + "    }).catch(function(){ QDSaver.abort(); });\n"
          + "  }, true);\n"
          + "})();";

    /** 兜底：没走 DOM 钩子而直接触发了 DownloadListener 的 blob */
    private static String blobFallbackJs(String blobUrl, String mime) {
        String safeUrl = blobUrl.replace("'", "%27");
        String ext = "application/zip".equals(mime) ? ".zip"
                : ("image/png".equals(mime) ? ".png" : ".bin");
        return "(function(){"
             + "fetch('" + safeUrl + "').then(function(r){return r.blob();}).then(function(b){"
             + "QDSaver.begin('四象限日记-导出-' + Date.now() + '" + ext + "', b.type || '" + mime + "');"
             + "var CHUNK=3*1024*1024,off=0;"
             + "(function step(){"
             + " if(off>=b.size){QDSaver.end();return;}"
             + " var p=b.slice(off,Math.min(off+CHUNK,b.size));off+=CHUNK;"
             + " var fr=new FileReader();"
             + " fr.onload=function(){var s=String(fr.result),i=s.indexOf(',');"
             + "  QDSaver.chunk(i>0?s.slice(i+1):'');step();};"
             + " fr.onerror=function(){QDSaver.abort();};"
             + " fr.readAsDataURL(p);"
             + "})();}).catch(function(){QDSaver.abort();});})();";
    }
}
