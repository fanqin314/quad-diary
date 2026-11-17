#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
把上级目录的网页（四象限日记 PWA）同步到 app/src/main/assets/web/。

为什么要有这个脚本：离线壳是「整站拷进 APK」，网页改了不同步，
用户装到的还是旧的。改完网页跑一次这个，再重新打包即可。

    python tools/sync-web.py

只拷运行时要用的文件，server.js / .vbs / 功能计划.md 这类开发期的东西不带。
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)              # android/
WEB_SRC = os.path.dirname(PROJECT)           # 天天记/（网页根目录）
DEST = os.path.join(PROJECT, 'app', 'src', 'main', 'assets', 'web')

FILES = [
    'index.html',
    'manifest.json',
    'sw.js',
    'kcb-data.js',
    'vendor/jszip.min.js',
    'icons/icon-192.png',
    'icons/icon-512.png',
    'icons/apple-touch-icon.png',
]


def main():
    if not os.path.isfile(os.path.join(WEB_SRC, 'index.html')):
        print('找不到网页源目录：%s' % WEB_SRC, file=sys.stderr)
        return 1

    total = 0
    for rel in FILES:
        src = os.path.join(WEB_SRC, rel.replace('/', os.sep))
        if not os.path.isfile(src):
            print('  [跳过] %s —— 源文件不存在' % rel, file=sys.stderr)
            continue
        dst = os.path.join(DEST, rel.replace('/', os.sep))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(src, dst)
        size = os.path.getsize(dst)
        total += size
        print('  [同步] %-32s %8.1f KB' % (rel, size / 1024.0))

    # 顺手清掉 assets/web 里多余的东西，避免旧版本残留的 js 被打进包
    keep = {os.path.normcase(os.path.join(DEST, r.replace('/', os.sep))) for r in FILES}
    for root, _dirs, names in os.walk(DEST):
        for name in names:
            path = os.path.join(root, name)
            if os.path.normcase(path) not in keep:
                os.remove(path)
                print('  [清理] %s' % os.path.relpath(path, DEST))

    print('完成：%d 个文件，共 %.1f KB -> %s' % (len(FILES), total / 1024.0, DEST))
    print('提醒：改过 sw.js 的 CACHE 版本号的话，用户才拿得到新资源。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
