# NotifySync 协议规范 v1

本文档是 Android 端（Kotlin）与 Windows 端（Node/Electron）共同遵守的字节级协议契约。
两端实现必须严格按本文件实现；任何修改需同步修改两端并在本文档登记。

## 1. 总览

- 同一局域网内 P2P 全互通（2 部 Android 手机 + 1 台 Windows 电脑）。
- 设备发现：UDP 广播，端口 **37311**。
- 数据通道：TCP，监听端口 **37312**，长度前缀帧。
- 加密：P-256 静态 ECDH + AES-256-GCM。
- 配对：4 位十六进制验证码人工比对，之后 pin 对方公钥。
- 所有 JSON 一律 UTF-8 编码。

## 2. 设备身份

每台设备首次运行时生成 P-256 密钥对（软件密钥，存于本机私有目录）：

- 公钥：**SPKI DER** 格式，Base64 标准编码（无换行）。
- 私钥：**PKCS#8 DER** 格式，Base64 标准编码。
- 设备 ID：UUID v4 小写字符串（首启生成，持久保存）。
- 设备名：Android 用 `Build.MODEL`，Windows 用主机名，用户可改。
- 角色 role：`"phone"` 或 `"pc"`。

身份文件 `identity.json`：

```json
{"id":"<uuid4>","name":"Pixel 8","role":"phone","pub":"<b64 SPKI>","priv":"<b64 PKCS8>"}
```

已配对设备 `peers.json`（数组）：

```json
[{"id":"<uuid4>","name":"NOTIFY-PC","role":"pc","pub":"<b64 SPKI>","pairedAt":1730000000000}]
```

## 3. UDP 发现（端口 37311）

每台设备每 **5 秒**向 `255.255.255.255:37311` 广播 presence 报文（单包 < 1KB）：

```json
{"v":1,"type":"presence","id":"<uuid>","name":"Pixel 8","role":"phone","port":37312}
```

规则：

- 收到 presence 记录/刷新 `lastSeen`；`lastSeen` 超过 30 秒视为发现过期（从"新设备"列表移除）。
- 已配对且当前无活跃连接 → 主动向该地址发起 TCP 连接；**每设备拨号间隔 ≥ 10 秒**。
- Windows 端额外向每个非内部 IPv4 接口的子网定向广播地址发送同样报文（多网卡场景）。
- presence 为明文，不含任何敏感内容。

## 4. TCP 帧格式（端口 37312）

所有帧（明文与加密阶段相同外层）：

```
[4 字节大端长度 N][N 字节载荷]
```

- 长度 = 后续载荷字节数，上限 **1 MB**，超限断开连接。
- 明文阶段载荷 = JSON UTF-8。
- 加密阶段载荷 = `[12 字节随机 nonce][AES-256-GCM 密文||16 字节 tag]`，无 AAD。

## 5. 连接建立与配对

### 5.1 hello（明文）

TCP 连接建立后双方立即互发 hello（拨号方在 connect 成功后发送，接受方在 accept 后发送）：

```json
{"v":1,"type":"hello","id":"<uuid>","name":"Pixel 8","role":"phone",
 "pub":"<b64 SPKI>","nonce":"<b64 16字节随机>","dialer":true}
```

`dialer`：本连接是否由自己主动拨号（拨号方 true，接受方 false）。

### 5.2 密钥派生（两端一致，先于配对完成即可计算）

```
shared = ECDH(本机私钥, 对方公钥)          // P-256, x 坐标
sharedX = shared 左侧补零/截断到恰好 32 字节   // 归一化，两端必须一致
verCode = SHA-256(sharedX || "notifysync-pair-v1") 的前 2 字节十六进制大写  // 如 "A7F3"
sessionKey = SHA-256(sharedX || "notifysync-session-v1" || min(nA,nB) || max(nA,nB))
```

- `nA`/`nB` = 双方 hello 中的 16 字节 nonce 原始字节；`min/max` 按字节序升序比较排序后先后拼接。
- `sessionKey` 为 32 字节 AES-256 密钥；连接断开后重连时 nonce 变化 → 密钥变化。
- 验证码 `verCode` 供两台设备屏幕上人工比对。

### 5.3 已配对设备的连接

收到 hello 且对方 id 在 peers.json 中：

1. 校验 hello 中 `pub` 与 pinned 公钥完全一致，不一致 → 立即断开（密钥不匹配）。
2. 计算 sessionKey，切换到加密模式，此后所有帧加密。

### 5.4 新设备配对流程

明文控制消息：`pair_request` / `pair_confirm` / `pair_reject` / `deny`。

1. 设备 A 在"设备"页看到 B（来自 UDP 发现），点"配对" → A 拨号 B，依次发送 hello 和 `{"type":"pair_request"}`，同时显示"等待对方确认，验证码 A7F3"。
2. B 收到未配对设备的 hello + pair_request → 弹出对话框：`"Pixel 8 请求配对，验证码 A7F3"`，用户比对两边屏幕验证码：
   - 攻击者无法算出两台真设备一致的 ECDH 验证码，中间人会导致两边码不同。
   - B 对同一 id 30 秒内只弹一次；弹窗 60 秒无操作自动拒绝（deny + 断开）。
3. B 点"确认" → 发送明文 `pair_confirm` → 将 A 写入 peers.json → 切换加密模式。
   B 点"拒绝" → 发送 `pair_reject` 并断开。
4. A 收到 `pair_confirm` → 将 B 写入 peers.json → 切换加密模式 → 发送加密帧 `pair_done`。
5. 配对完成（A 收到本地 paired 事件即成；B 以首个可解密帧为准）。

**自动 deny 规则**：收到未配对设备的 hello 后 2 秒内没有 pair_request，且回复方没有进行中的配对意图 → 回 `{"type":"deny","reason":"unpaired"}` 并关闭。收到已配对对端的 deny → 说明对方已单方面删除配对（残留状态），本地同步删除并提示。

**重配对自愈**：A 曾配对 B、B 侧记录丢失（或反向）时，A 以已配对身份重连 B；B 若仍有 A 的记录且公钥一致，收到 A 的 pair_request 后直接回 pair_confirm，双向自动恢复。公钥不一致则立即断开（pub-mismatch）。

**双方同时点配对**：互发的 pair_request 被视为互相确认，双方直接完成配对。

**配对取消**：A 侧等待确认超过 120 秒自动取消（pair-timeout）。

### 5.6 解密失败的特殊处理

加密态收到无法解密的帧时，先尝试按明文 JSON 解析：若是 `deny` 或 `pair_request`（对端尚在明文阶段的控制消息）则正常处理，否则断开（bad-decrypt）。明文态收到无法按 JSON 解析的帧（对端已进入加密态的心跳）直接忽略。

### 5.5 重复连接仲裁

同一 peer id 出现两条活跃连接（双方同时互拨）时，**保留 dialer 设备 id 字典序较大的一条**，另一条发 `bye` 后关闭。每条连接的"发起者 id"：自己拨的 = 自己 id，对方拨来的 = 对方 id（由 hello.dialer 判断）。两端各自独立计算，结论一致。

## 6. 加密阶段消息（JSON）

### 6.1 通知

```json
{"type":"notif","nid":"<uuid4>","originId":"<发送方uuid>","originName":"Pixel 8",
 "pkg":"com.tencent.mm","app":"微信","title":"妈妈","text":"晚上回来吃饭吗",
 "subText":"","convTitle":"家人群","ts":1730000000000,"ongoing":false,
 "otp":"883211"}
```

- `ts`：通知原始时间（System.currentTimeMillis / whenPosted），毫秒。
- `convTitle`：通知自带的会话标题（MessagingStyle 的 conversationTitle），无则空字符串。
- `otp`：发送方已提取的验证码，无则字段值为 `null`（不是缺省，显式 null）。
- 接收方会话分组键：`convKey = pkg + "|" + (convTitle 非空 ? convTitle : (subText 非空 ? subText : (title 非空 ? title : "")))`。
- 去重：接收方数据库 `UNIQUE(originId, nid)`，INSERT OR IGNORE。

### 6.2 心跳与维护

```json
{"type":"ping","ts":1730000000000}
{"type":"pong","ts":1730000000000}
{"type":"ack","nid":"<收到的通知的 nid>"}
{"type":"peers","list":[{"id":"<uuid>","name":"Pixel 8","role":"phone","address":"192.168.1.8","port":37312}]}
{"type":"pair_done"}
{"type":"bye","reason":"shutdown"}
```

- `ack`：收到 `notif` 后立即在同连接上回执（v1.5 起），发送方用于确认送达。
- **通知中转（v2.4 起）**：收到新通知（去重后首次见到）时，转发给除来源连接外的其他加密连接。用于路由器 AP 隔离等设备间无法直连的场景（例：手机 A、B 都只与电脑相连，A 的通知经电脑转发到 B）。去重规则保证不会成环：Android 依赖数据库 (originId, nid) 唯一约束，Node 端引擎维护 5000 条上限的已见集合且发送方预先记入自己发出的 nid。
- `peers`（v2.3 起）：**熟人引荐**。每台设备每 20 秒把自己的已知设备列表（已配对设备的最后已知地址 + 发现的设备）通过加密连接发给在线对端。接收方合并进自己的地址表/发现列表（跳过自己和已配对者），用于：UDP 广播投递不可靠时仍能发现并直连设备。列表只包含自己的一手信息，不转发收到的引荐（防环）。
- 心跳周期 **15 秒**；加密态下 **40 秒**未收到任何帧判定掉线（配对等待阶段依赖 TCP keepalive，不受此限）。
- 掉线后：标记离线 → 更新状态 UI → 3s 后重拨，退避 5s、8s，封顶 10s；收到 presence 立即重拨（受 §3 的 10 秒间隔约束）。
- `bye` 收到后立即标记离线，不弹"离线"提醒（对端是主动退出）。

未知 `type`：静默忽略（向前兼容）。

## 7. 历史与保留

- 各设备独立保存收到的全部通知（含本机通知）。
- 默认保留 **30 天**，启动时与每天 0 点清理。

## 8. 验证码提取规则（两端实现必须一致）

输入 title + text，返回验证码字符串或 null：

1. 关键词正则（忽略大小写）：`验证码|校验码|动态码|识别码|安全码|确认码|口令|verification|verify|security code|one-time|otp|pin code|code`。
2. 若关键词不匹配，但 `text.trim()` 整体恰为 4-8 位纯数字（不含空格）→ 直接返回该数字。
3. 否则用 `(?<!\d)(\d(?:[ ]?\d){3,7})(?!\d)` 提取候选（允许数字间夹单个空格，如 `730 122`，去空格后长度须为 4-8）；返回**位于关键词首次出现位置之后 30 字符以内**、距离最近的候选；无则返回 null。
4. 数字长度偏好：同等距离时优先 4-6 位。

## 9. 通道端口与常量汇总

| 常量 | 值 |
|---|---|
| UDP 发现端口 | 37311 |
| TCP 数据端口 | 37312 |
| presence 广播间隔 | 5000 ms |
| presence 过期 | 30000 ms |
| 拨号最小间隔（每 peer） | 10000 ms |
| 心跳间隔 | 15000 ms |
| 掉线判定 | 40000 ms 无帧 |
| 帧最大长度 | 1 MB |
| 重连退避 | 3s → 5s → 8s → 10s（封顶） |
| 历史保留 | 30 天（可设置） |

## 10. 测试向量（KDF / 验证码 / 加解密）

两端单元测试必须通过以下向量（由 Windows 端 node:crypto 生成，RFC 兼容的 P-256 实现）。

### 10.1 密钥对 A（模拟设备 A）

```
pubA (SPKI DER, b64):
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA
（见 test-vectors.json，此处以文件为准）
```

**注**：具体向量值较大，统一存放在 `docs/test-vectors.json`，包含：

- `keyA` / `keyB`：PEM 私钥对（测试专用，勿用于生产）。
- `nonceA` / `nonceB`：16 字节固定 nonce（b64）。
- `sharedX`：归一化后的 32 字节 ECDH 共享秘密（hex）。
- `verCode`：期望验证码（4 位大写 hex）。
- `sessionKey`：期望会话密钥（hex，32 字节）。
- `frame`：用 sessionKey 加密明文 `{"type":"ping","ts":1730000000000}` 的完整密文帧：`[12B nonce hex][ciphertext+tag hex]`，nonce 固定为 `000102030405060708090a0b`。
- `otpCases`：验证码提取的输入输出用例数组。

Android 端 `CryptoTest.kt` 与 Windows 端 `test/loopback.js` 均断言这些值。
