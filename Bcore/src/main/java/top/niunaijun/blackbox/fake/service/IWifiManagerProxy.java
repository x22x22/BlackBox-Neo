package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.util.Log;

import top.niunaijun.blackbox.utils.Slog;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import black.android.net.wifi.BRIWifiManagerStub;
import black.android.net.wifi.BRWifiInfo;
import black.android.net.wifi.BRWifiSsid;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;


public class IWifiManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IWifiManagerProxy";

    public IWifiManagerProxy() {
        super(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIWifiManagerStub.get().asInterface(BRServiceManager.get().getService(Context.WIFI_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getScanResults")
    public static class GetScanResults extends MethodHook {
        /**
         * Android 17 适配：虚拟定位开启时屏蔽周边 WiFi 扫描结果。
         *
         * 高德/百度等地图 App 会把周边 BSSID 列表（getScanResults）连同基站信息
         * 上报自家服务器做网络反查定位。这条链路完全不经过系统 LocationManager，
         * 系统层的虚拟定位无法覆盖它，最终仍显示真实位置（实测：沙箱内高德进程的
         * gps provider 已是虚拟坐标，界面上却定在真实城市）。
         * 屏蔽后地图类 App 只能回退到系统定位，而系统定位已被虚拟定位接管。
         *
         * 返回类型注意：Android 17 的 WiFi 是 Mainline 模块，IWifiManager
         * .getScanResults() 声明的返回类型是
         *   com.android.wifi.x.com.android.modules.utils.ParceledListSlice
         * 而不是 List。直接返回 ArrayList 会让 Binder 侧抛
         *   ClassCastException: Couldn't convert result of type java.util.ArrayList
         *     to com.android.wifi.x.com.android.modules.utils.ParceledListSlice
         * 所以这里按「原方法的返回类型」反射构造一个空的 ParceledListSlice，
         * 既屏蔽了真实扫描结果，又保持返回类型正确。
         */
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                Slog.d(TAG, "getScanResults blocked (fake location enabled)");
                try {
                    Object empty = method.getReturnType()
                            .getConstructor(List.class)
                            .newInstance(new ArrayList<ScanResult>());
                    return empty;
                } catch (Throwable e) {
                    Log.w(TAG, "failed to build empty ParceledListSlice, fallback to null", e);
                    return null;
                }
            }
            return method.invoke(who, args);
        }
    }

    /**
     * Android 17 适配：屏蔽“已保存的 WiFi 网络列表”。
     *
     * getScanResults 只覆盖“周边扫描到的 AP”，而 getConfiguredNetworks 返回用户
     * 保存过的 WiFi（每条含 BSSID/SSID），同样是 WiFi 指纹定位的输入。腾讯定位
     * SDK 会把它一并上报做反查 —— 实测企业微信仍能定位到真实地点，这是一条漏网链路。
     * 虚拟定位开启时返回空列表，使该输入失效。
     */
    @ProxyMethod("getConfiguredNetworks")
    public static class GetConfiguredNetworks extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                Slog.d(TAG, "getConfiguredNetworks blocked (fake location enabled)");
                return new ArrayList<>();
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getConnectionInfo")
    public static class GetConnectionInfo extends MethodHook {
        
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            WifiInfo wifiInfo = (WifiInfo) method.invoke(who, args);
            if (wifiInfo == null) {
                return null;
            }
            // Android 17 适配：★ 企业微信打卡定位显示真实地址的根因所在。
            //
            // 原实现无条件把当前连接 WiFi 的 BSSID/MAC 改成硬编码的
            // ac:62:5a:82:65:c4、SSID 改成 BlackBox_Wifi。该 MAC 格式合法，
            // 一旦被腾讯/高德等 WiFi 指纹库收录，定位 SDK 就会拿它做网络反查，
            // 返回该 AP 的真实坐标（精度可达门牌号），从而完全覆盖掉系统层
            // 已经被接管的虚拟位置 —— 实测企业微信「打卡 -> 进入应用」显示
            // “湖南省长沙市岳麓区梅溪湖路梅溪湖路132号”且判定“不在打卡范围内”。
            //
            // 虚拟定位开启时把 WiFi 指纹清空（BSSID/MAC/SSID），使网络反查失去
            // 输入；SDK 无法凭 WiFi 定位，只能回退到已被虚拟定位接管的系统定位。
            if (BLocationManager.isFakeLocationEnable()) {
                BRWifiInfo.get(wifiInfo)._set_mBSSID("");
                BRWifiInfo.get(wifiInfo)._set_mMacAddress("");
                BRWifiInfo.get(wifiInfo)._set_mWifiSsid(null);
                Slog.d(TAG, "getConnectionInfo: wifi fingerprint cleared (fake location enabled)");
                return wifiInfo;
            }
            BRWifiInfo.get(wifiInfo)._set_mBSSID("ac:62:5a:82:65:c4");
            BRWifiInfo.get(wifiInfo)._set_mMacAddress("ac:62:5a:82:65:c4");
            BRWifiInfo.get(wifiInfo)._set_mWifiSsid(BRWifiSsid.get().createFromAsciiEncoded("BlackBox_Wifi"));
            return wifiInfo;
        }

        public static String intIP2StringIP(int ip) {
            return (ip & 0xFF) + "." +
                    ((ip >> 8) & 0xFF) + "." +
                    ((ip >> 16) & 0xFF) + "." +
                    (ip >> 24 & 0xFF);
        }

        public static int ip2Int(String ipString) {
            
            String[] ipSlices = ipString.split("\\.");
            int rs = 0;
            for (int i = 0; i < ipSlices.length; i++) {
                
                int intSlice = Integer.parseInt(ipSlices[i]) << 8 * i;
                
                rs = rs | intSlice;
            }
            return rs;
        }
    }
}
