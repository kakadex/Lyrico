LyricoX v0.09.2  ——  签名 release 包（可直接安装）
================================================

构建时间   2026-10-24 22:59 +0800
代码版本   branch LyricoX @ 41cec10
versionCode   35
versionName   0.09.2-41cec10
applicationId com.lonx.lyrico
应用显示名   LyricoX
minSdk ?   targetSdk 36
签名      自签 RSA 4096（证书 SHA-256 见下）
证书指纹  SHA-256 88b312e17f75f0490cd69129ea177ea6d8baab648bb1979c756048d047d00a86

文件
----
Lyrico-0.09.2-41cec10-arm64-v8a.apk   15946784 字节 (15.2 MiB)
  SHA-256 4e1dc9f8092672923c8e3e204090a9f8427110b7ecee0b730b12960ae7680f2e

本包为 arm64-v8a（64 位）单包，minSdk 28 及以上的 64 位设备直接安装。

安装
----
1) 手机端直接点开 APK 安装（需允许"未知来源"）。
2) 命令行：adb install -r Lyrico-0.09.2-41cec10-arm64-v8a.apk

注意：本包与官方 Lyrico 的签名不同。
      · 装过官方版（同包名 com.lonx.lyrico）会提示签名冲突 → 先卸载官方版再装。
      · 与本项目此前版本同签名、versionCode 更大，可直接覆盖安装，无需卸载。

本版内容（v0.09.2）
-----------------
# LyricoX v0.09.2

## 修复

- 「使用指南」入口改指向**我们自己的使用指南**（覆盖 LyricoX 全部功能：标签/歌词/封面编辑、批量任务、写入撤销、查重、文件名清理、目录整理等）；此前误指上游文档站，其中不包含本 fork 的新功能。

## 安装

直接覆盖安装（versionCode 递增、证书与历史版本一致，不丢失设置）。

来源
----
https://github.com/kakadex/LyricoX  （fork of Replica0110/Lyrico，Apache-2.0）
