# BlackBox-Neo

<p align="center">
  <b>面向新版本 Android 的虚拟应用引擎（沙箱）</b><br/>
  让应用在隔离环境中运行，无需安装 APK、无需 Root
</p>

---

## 这是什么

BlackBox-Neo 是一个 Android 虚拟化沙箱引擎，可以在宿主应用内创建隔离环境，
在其中运行第三方应用（即「虚拟应用」/「多开」）。全部在用户态实现，**不需要 Root**。

本项目是 [ALEX5402/NewBlackbox](https://github.com/ALEX5402/NewBlackbox) 的二次开发分支
（上游血统为 VirtualApp / VirtualAPK），主要围绕 **新版本 Android 的系统行为变化** 与
**系统级虚拟定位的可用性** 做了大量适配与修复。

> 命名说明：`Neo` 表示这是在 BlackBox 基础上重新适配与增强的分支，
> 并非上游 BlackBox 的官方版本。

## 主要特性

*   **虚拟应用运行**：在沙箱内运行已安装应用，支持多开，互不干扰。
*   **系统级虚拟定位**：拦截并接管系统定位链路，对沙箱内应用透明生效。
*   **设备信息伪装**：可定制设备标识，使每次运行表现一致。
*   **多架构支持**：arm64-v8a / armeabi-v7a / x86。
*   **无 Root 要求**：纯用户态实现。

## 相比上游的关键改进

### 1. 新版本 Android 兼容性

*   **Android 17 (API 37) 适配**：AIDL 接口变更（如 `IServiceConnection.connected()`
    新增 `IBinderSession` 参数）。
*   **应用创建流程**：新版 `LoadedApk.makeApplication()` 会直接返回 `null`，
    改为手动实例化并沿继承链反射 `attachBaseContext`。
*   **加固 / 热修复（Tinker）兼容**：规避多进程锁在沙箱 IO 重定向下的死锁，
    并保证 `TinkerApplicationInlineFence` 在有 `Looper` 的线程中构造。
*   **ContentProvider 安装**：修正反射调用参数，确保 Provider 真正实例化并回调 `onCreate`。

### 2. 系统级虚拟定位（本项目重点）

传统沙箱只接管 `ILocationManager`，但国内应用普遍使用自研融合定位 SDK，
会绕过系统定位。本项目把**旁路全部堵住**，使系统层虚拟位置真正生效：

*   **蜂窝基站三通道**：
    *   同步接口 `getCellLocation` / `getAllCellInfo`
    *   **异步接口 `requestCellInfoUpdate`（Android 12+）**，并主动向回调推送空列表
    *   `TelephonyRegistry` 的 `LISTEN_CELL_INFO` 主动推送（剥离标志位）
*   **WiFi 指纹**：`getScanResults`、`getConnectionInfo`（清空 BSSID/MAC/SSID）、
    `getConfiguredNetworks`
*   **GNSS 原始数据**：`registerGnssNmeaCallback`、`addGnssMeasurementsListener`
*   **定位注册入口**：`registerLocationListener` / `unregisterLocationListener`（Android 14+），
    沙箱侧主动以虚拟坐标回调
*   **坐标系换算**：Android 的 `Location` 为 WGS-84，国内地图使用 GCJ-02，
    选点界面统一换算，避免「虚拟位置偏移数百米」的问题

### 3. 地图选点体验

*   选点界面由 osmdroid(OSM) 改为 **腾讯地图 JavaScript API GL**，
    解决国内瓦片加载困难的问题，并支持**关键字搜索**。
*   **API Key / Secret Key 完全由用户自行配置**（见下文），
    仓库中不包含任何密钥。

## 构建

### 环境要求

*   JDK 21
*   Android SDK（compileSdk 见 `app/build.gradle`）
*   Gradle（使用仓库自带 wrapper 即可）

### 命令

```bash
git clone https://github.com/x22x22/BlackBox-Neo.git
cd BlackBox-Neo
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/`。

## 配置腾讯地图（虚拟定位选点）

虚拟定位的**地图选点与搜索**依赖腾讯位置服务，需自行申请 Key：

1.  前往 [腾讯位置服务控制台](https://lbs.qq.com/dev/console/key/manage) 创建 Key；
2.  Key 类型选择 **JavaScript API GL**；
3.  若需要在网页内调试，可一并勾选 **WebServiceAPI**；
4.  打开应用 → **设置 → 腾讯地图**，填入 **Key**（以及开启签名校验时的 **SK**）。

> 未配置时，虚拟定位的地图选点会提示并退出；其余功能不受影响。

## 已知限制

*   虚拟定位作用于**系统定位链路**。少数应用使用纯网络/IP 定位或服务端校验，
    仍可能识别真实位置。
*   部分依赖强力完整性校验（加固、反调试、完整性自校验）的应用可能无法在沙箱内正常运行。
*   本项目仅供学习与研究用途。

## Credits

*   **Original Framework**: [VirtualApp](https://github.com/asLody/VirtualApp), VirtualAPK
*   **Upstream**: [BlackBox](https://github.com/ALEX5402/NewBlackbox) — Copyright 2022 BlackBox

## License

Licensed under the Apache License, Version 2.0.
See [LICENSE](LICENSE) for the full text.

本项目基于上游 Apache-2.0 代码继续开发，**保留了原始版权声明**（见 [NOTICE](NOTICE)）。
