# Android 手机端构建安装指南

目标：把 NotifySync 装到两部 Android 手机上。整个过程只用 Android Studio 图形界面，不需要命令行。

## 一、安装 Android Studio（一次性）

1. 下载：https://developer.android.com/studio （国内直连可用；若打不开用腾讯镜像 https://mirrors.cloud.tencent.com/AndroidSDK/ 里的 IDE 包）
2. 安装一路默认。首次启动的安装向导（Setup Wizard）也一路默认（会自动下载 SDK、平台组件，等待即可）
3. Android Studio 自带 JDK 17，不需要单独装 Java

## 二、打开工程并构建 APK

1. 启动 Android Studio → **Open** → 选择 `notifysync/android` 这个文件夹（注意是 android 目录，不是里面的 app 目录）
2. 首次打开会自动 **Gradle Sync**（右下角有进度条）：
   - 已配置国内镜像（阿里云 maven + 腾讯 gradle），正常网络几分钟完成
   - 若卡住不动：File → Settings → Build, Execution, Deployment → Build Tools → Gradle，确认 "Gradle JDK" 选的是 jbr-17
3. Sync 完成后（底部 Build 输出无红色报错）：菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. 完成后右下角弹通知，点 **locate** 找到 APK：
   `notifysync/android/app/build/outputs/apk/debug/app-debug.apk`

> 本工程未包含 gradlew 脚本（wrapper jar 需从外网下载）。用 Android Studio 打开不受影响；如需命令行构建，在 Android Studio 底部 Terminal 里执行一次 `gradle wrapper --gradle-version 8.5` 生成。

## 三、装到手机

1. 把 `app-debug.apk` 发到两部手机（微信文件传输助手/数据线/局域网共享均可）
2. 手机上点开 APK → 允许"安装未知来源应用" → 安装
3. **两部手机装同一个 APK**

## 四、首次设置（每部手机都要做，直接决定同步可靠性）

打开 App 后：

1. 弹出"允许通知"权限 → **允许**
2. 进 **设置 → 保活自检**，逐项处理：
   - ✅→⚠️ **通知使用权**：点"去开启" → 找到"通知同步"→ 开启（这是读取通知的开关，最关键）
   - ⚠️ **电池无限制**：点"去设置" → 允许忽略电池优化
   - ⚠️ **厂商自启动**：点"尝试跳转" → 进到手机管家后手动允许"通知同步"自启动 + 后台运行（小米/OPPO/vivo/华为路径各不相同，找不到就在手机管家里搜索"自启动"）
3. 下拉状态栏应看到常驻通知：**"通知同步 · 运行中"** —— 它存在 = 同步活着

## 五、配对

1. 三台设备（2 手机 + 电脑）连**同一个 WiFi**
2. 手机 App → **设备** 页 → 等 5~10 秒，"发现的新设备"里出现其他设备 → 点**配对**
3. **两台设备的屏幕会各显示一个 4 位验证码，必须一致**才在对方设备上点"确认配对"（防止局域网内有人冒充）
4. 配对成功后顶栏设备芯片变绿，通知开始实时同步

## 六、跑单元测试（可选，验证加密实现与电脑端一致）

Android Studio 左侧项目树 → `app/src/test/java/com/notifysync/android/CryptoTest` → 右键 → Run 'CryptoTest'，应全绿。
