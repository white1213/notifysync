# Windows 端运行指南

## 一、安装 Node.js（一次性）

1. 打开 https://nodejs.org 下载 **LTS 版本**（≥ 18），安装时一路默认
   - 国内也可以用淘宝镜像：https://registry.npmmirror.com/binary.html?path=node/ 下载 `.msi`
2. 装完打开命令行（按 `Win + R`，输入 `cmd` 回车），输入 `node -v`，能看到版本号即成功

## 二、运行 NotifySync

在命令行里：

```bat
cd /d "你的路径\notifysync\windows"
npm config set registry https://registry.npmmirror.com
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
npm install
npm start
```

- `npm install` 只会下载 Electron 本体（约 100MB，用上面镜像很快）
- `npm start` 启动后：
  - 首次运行 Windows 会弹**防火墙提示，必须点"允许"**（勾选专用网络和公用网络都允许最稳妥）
  - 如果错过了弹窗：右键"以管理员身份运行" `windows\tools\allow-firewall.bat`
- 主窗口关闭时最小化到托盘（右下角箭头里），**退出请用托盘图标右键 → 退出**

### 国内网络加速说明

上面三行配置（npm 镜像 + ELECTRON_MIRROR）解决国内下载慢的问题。海外网络可跳过这两行直接 `npm install`。

## 三、日常使用

- 开机自启：托盘右键 → 勾选"开机自启"（或主界面设置里）
- 收到手机通知 → 右下角弹 Windows 通知；含验证码时**自动复制到剪贴板**，直接 Ctrl+V 粘贴
- 设备管理：主界面右上"设备" → 看到手机后点"配对" → 核对两边验证码

## 四、（可选）打包成独立 exe

需要分发或不想每次 npm start 时：

```bat
cd /d "你的路径\notifysync\windows"
npm install --save-dev electron-builder
npx electron-builder --win nsis
```

产物在 `windows/dist/` 目录。打完包后防火墙要重新允许一次。

## 五、跑协议自检

```bat
cd /d "你的路径\notifysync\windows"
npm test
```

会验证加密实现与 Android 端的一致性（配对/同步/断线自愈全流程回环测试），全部 OK 说明本机环境正常。
