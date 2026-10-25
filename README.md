# NotifySync · 通知同步

<p align="center">
  <img src="notifysync-banner.png" alt="NotifySync" width="120">
</p>

**局域网端到端加密的手机通知同步工具** —— Android 手机 + Windows 电脑全互通，没有云端服务器，你的通知只在你自己的设备之间流转。

```
┌───────────┐                    ┌───────────┐
│ Android A │◄──── P2P 加密直连 ──►│ Android B │
└─────┬─────┘                    └─────┬─────┘
      │          同一 WiFi               │
      └──────────►┌──────────┐◄─────────┘
                  │ Windows  │
                  └──────────┘
```

## ✨ 功能

- **实时镜像 + 历史回溯**：任何一台设备的通知实时出现在其他设备上，按应用/会话分组聚合、可搜索、可筛选，默认保留 30 天
- **验证码自动提取**：识别中英文验证码（含"730 122"式空格分隔），电脑端**自动复制到剪贴板**，收到直接 Ctrl+V
- **手机 ↔ 手机中转同步**：路由器开了 AP 隔离导致设备间无法直连时，通知可经电脑自动中转（v2.4+）
- **熟人引荐**：设备间互传地址信息，UDP 广播投递不稳定时依然能互相发现（v2.3+）
- **可靠性可视化**：前台常驻服务 + 状态通知实时显示连接情况 + 各厂商 ROM 保活自检引导（小米/OPPO/vivo/华为自启动跳转）——坏了立刻知道，绝不默默失效
- **端到端安全**：P-256 ECDH 密钥交换 + AES-256-GCM 加密，首次配对 4 位验证码人工比对防中间人，全程无第三方服务器

## 📦 安装

到 [Releases](../../releases) 下载最新版本：

| 文件 | 说明 |
|---|---|
| `NotifySync-手机端.apk` | 装到每台 Android 手机（Android 8.0+） |
| `NotifySync-Windows安装包.exe` | 装到 Windows 电脑（Win10+） |

**三步上手**：

1. 所有设备连**同一个 WiFi**，手机装好 App 后进 `设置 → 保活自检`，把通知使用权 / 电池白名单 / 厂商自启动三项全部变绿（国产 ROM 必做，直接决定后台可靠性）
2. 电脑首次运行时防火墙弹窗点**允许**；被拦截时在 App `设备` 页点"修复防火墙"
3. 任意一端在 `设备` 页点**配对** → 对方设备弹出验证码 → **核对两边验证码一致**后确认

## 🔒 安全设计

- 每台设备首启生成 P-256 长期密钥对；配对时双方显示 ECDH 派生的 4 位验证码，**人工比对一致才完成配对**（防中间人）
- 配对后 pin 对方公钥，之后所有通信用会话随机数派生的 AES-256-GCM 密钥加密
- 传输层细节、防环中转、重连仲裁等完整规格见 [docs/PROTOCOL.md](docs/PROTOCOL.md)
- 跨端加密实现的一致性由测试向量保证（`docs/test-vectors.json`），两端单元测试共同校验

## 🔧 源码构建

```bash
# Windows 端（Electron，零原生依赖）
cd windows
npm config set registry https://registry.npmmirror.com   # 国内加速
set ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/
npm install && npm test && npm start
npm run dist                                             # 打包 exe

# Android 端（Kotlin + Compose）
# 用 Android Studio 打开 android/ 目录，Build → Build APK(s)
# 命令行：cd android && gradle assembleDebug
```

已内置国内镜像（阿里云 Maven + 腾讯 Gradle），国内网络可直接同步。

## 🩺 排错速查

| 症状 | 处理 |
|---|---|
| 设备互相看不到 | 同一 WiFi？路由器关"AP 隔离/客户端隔离"；电脑点"修复防火墙" |
| 手机通知不同步 | App 设置 → 保活自检三项全绿；设置 → 发送测试通知（带回执）定位断点 |
| 偶发断连 | 自动重连（3→10s 退避）；状态通知实时显示在线设备数 |
| 深入排查 | 手机"复制诊断报告"；电脑 `%APPDATA%/notifysync-windows/engine.log` |

完整排错手册：[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md)

## ⚠️ 已知限制

- **iOS 不适用**：iOS 不向第三方开放通知读取权限
- **路由器 AP 隔离**：设备间无法直连时，手机间通知经电脑中转（电脑需在线）；关闭路由器的 AP 隔离后可直连配对
- 电脑端为纯接收端，Windows 系统通知不反向同步到手机
- 能同步到的内容取决于各 App 给系统通知的详细程度（如微信部分消息只显示"你收到一条消息"）

## 🗺️ 路线图

- [ ] 双向通知操作（电脑上划掉手机通知）
- [ ] Windows → 手机方向的通知同步
- [ ] 通知关键词/App 级过滤规则
- [ ] 自动备份配对关系（换机迁移）

## 📄 文档

- [协议规范 PROTOCOL.md](docs/PROTOCOL.md) —— 两端实现的字节级契约
- [构建指南（Android）](docs/BUILD-ANDROID.md) · [构建指南（Windows）](docs/BUILD-WINDOWS.md)
- [排错手册](docs/TROUBLESHOOTING.md)

## License

[MIT](LICENSE)
