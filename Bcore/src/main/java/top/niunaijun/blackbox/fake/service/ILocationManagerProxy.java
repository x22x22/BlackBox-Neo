package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.location.LocationManager;
import android.os.IInterface;
import android.util.Log;

import top.niunaijun.blackbox.utils.Slog;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;

import black.android.location.BRILocationListener;
import black.android.location.BRILocationManagerStub;
import black.android.location.provider.BRProviderProperties;
import black.android.location.provider.ProviderProperties;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.location.BLocation;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class ILocationManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ILocationManagerProxy";

    public ILocationManagerProxy() {
        super(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRILocationManagerStub.get().asInterface(BRServiceManager.get().getService(Context.LOCATION_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.LOCATION_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 诊断：打印所有被调用的定位接口方法名。
        // 用于确认地图类 App 到底走的是哪个定位入口 —— 若某 App 完全不经过
        // 系统 ILocationManager（例如百度地图只用自己的定位 SDK + 网络反查），
        // 系统层的虚拟定位就无法覆盖它，需要换思路。
        Slog.d(TAG, "invoke: " + method.getName() + ", argc=" + (args == null ? 0 : args.length));

        MethodParameterUtils.replaceFirstAppPkg(args);
        
        
        String packageName = BActivityThread.getAppPackageName();
        if (packageName != null && packageName.equals("com.google.android.gms")) {
            
            if (method.getName().equals("getLastLocation") || 
                method.getName().equals("getLastKnownLocation") ||
                method.getName().equals("requestLocationUpdates")) {
                Log.w(TAG, "Blocking location request from Google Play Services to prevent crash");
                return null;
            }
        }
        
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("registerGnssStatusCallback")
    public static class RegisterGnssStatusCallback extends MethodHook {

        /**
         * Android 17 适配：GNSS 状态回调改为放行（走真实系统）。
         *
         * 原实现直接 return true（吞掉注册、永不回调）。GnssStatus 只描述卫星状态
         * （星数/信噪比），**不包含任何位置信息**，所以放行不会泄露真实坐标。
         *
         * 而腾讯定位 SDK（企业微信打卡、腾讯地图类应用）会用“能否收到 GNSS 状态回调”
         * 来判断设备是否具备可用的 GPS：回调被吞掉时它直接放弃 GPS、只用网络定位，
         * 于是沙箱内已被接管的系统位置（虚拟坐标）对打卡判定完全不起作用 ——
         * 实测把虚拟位置设成杭州 / 长沙梅溪湖 / 北京，打卡页一律显示“不在打卡范围内”。
         * 放行后 SDK 才会走 GPS 链路，从而收到 onLocationChanged 里的虚拟位置。
         */
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "registerGnssStatusCallback: pass-through (satellite status only, no position)");
            return method.invoke(who, args);
        }
    }

    /**
     * Android 17 适配：虚拟定位开启时同样要拦掉 GNSS 原始数据回调。
     *
     * 实测高德地图在沙箱内除了调用 getLastLocation（已被接管为虚拟坐标），
     * 还会调用 registerGnssNmeaCallback 订阅 NMEA 原始语句。NMEA 由 GPS 芯片
     * 输出、由 App 自行解析，完全不经过 ILocationManager 的位置方法，
     * 原实现只拦了 registerGnssStatusCallback，NMEA 这条链路是漏的。
     */
    @ProxyMethod("registerGnssNmeaCallback")
    public static class RegisterGnssNmeaCallback extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                Slog.d(TAG, "registerGnssNmeaCallback blocked (fake location enabled)");
                return true;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getLastLocation")
    public static class GetLastLocation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean fake = BLocationManager.isFakeLocationEnable();
            BLocation value = fake
                    ? BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName())
                    : null;
            Slog.d(TAG, "getLastLocation: fake=" + fake + ", config=" + value);
            if (fake) {
                // 原实现直接对返回值调 convert2SystemLocation()，配置缺失时会 NPE，
                // 异常会冒泡给地图 App，反而让它回退到自家网络定位。
                if (value != null) {
                    return value.convert2SystemLocation();
                }
                Log.w(TAG, "getLastLocation: fake enabled but no location configured, falling back");
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied, returning null for getLastLocation");
                    return null;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("getLastKnownLocation")
    public static class GetLastKnownLocation extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean fake = BLocationManager.isFakeLocationEnable();
            BLocation value = fake
                    ? BLocationManager.get().getLocation(BActivityThread.getUserId(), BActivityThread.getAppPackageName())
                    : null;
            Slog.d(TAG, "getLastKnownLocation: fake=" + fake + ", config=" + value);
            if (fake && value != null) {
                return value.convert2SystemLocation();
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied, returning null for getLastKnownLocation");
                    return null;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("requestLocationUpdates")
    public static class RequestLocationUpdates extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean fake = BLocationManager.isFakeLocationEnable();
            Slog.d(TAG, "requestLocationUpdates: fake=" + fake + ", argc=" + (args == null ? 0 : args.length));
            if (fake) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof IInterface) {
                        // 不写死 args[1]：不同 Android 版本 ILocationManager
                        // .requestLocationUpdates 的参数顺序不同（LocationRequest /
                        // ILocationListener / packageName / featureId 的排列有过调整），
                        // 写死下标会导致 hook 静默失效、地图 App 拿到真实位置。
                        Slog.d(TAG, "requestLocationUpdates: attaching fake listener at args[" + i + "]");
                        BLocationManager.get().requestLocationUpdates(((IInterface) args[i]).asBinder());
                        return 0;
                    }
                }
                Log.w(TAG, "requestLocationUpdates: no ILocationListener found in args, falling back");
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied for requestLocationUpdates, returning 0");
                    return 0;
                }
                throw e;
            }
        }
    }

    /**
     * Android 17 适配：★ 企业微信打卡定位走的入口。
     *
     * 实测企业微信（打卡 -> 右上角 -> 进入应用）完全不走 requestLocationUpdates，
     * 而是调用 Android 14+ 新增的 registerLocationListener：
     *   ILocationManagerProxy: invoke: registerLocationListener, argc=3（共 20 次）
     * 原实现只拦了 requestLocationUpdates，于是这条链路直接落到真实系统定位，
     * 页面显示真实位置（梅溪湖）且“不在打卡范围内”。
     *
     * 处理方式与 requestLocationUpdates 一致：不写死下标，扫描 args 里的
     * ILocationListener，改接到沙箱的虚拟定位任务上，并返回成功。
     */
    @ProxyMethod("registerLocationListener")
    public static class RegisterLocationListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            boolean fake = BLocationManager.isFakeLocationEnable();
            Slog.d(TAG, "registerLocationListener: fake=" + fake + ", argc=" + (args == null ? 0 : args.length));
            if (fake && args != null) {
                for (int i = 0; i < args.length; i++) {
                    if (args[i] instanceof IInterface) {
                        Slog.d(TAG, "registerLocationListener: attaching fake listener at args[" + i + "]");
                        BLocationManager.get().requestLocationUpdates(((IInterface) args[i]).asBinder());
                        return 0;
                    }
                }
                Log.w(TAG, "registerLocationListener: no ILocationListener found in args, falling back");
            }
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Log.w(TAG, "Location permission denied for registerLocationListener, returning 0");
                    return 0;
                }
                throw e;
            }
        }
    }

    @ProxyMethod("unregisterLocationListener")
    public static class UnregisterLocationListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof IInterface) {
                        BLocationManager.get().removeUpdates(((IInterface) arg).asBinder());
                        return 0;
                    }
                }
            }
            return method.invoke(who, args);
        }
    }

    /**
     * Android 17 适配：拦掉 GNSS 原始测量数据。
     *
     * addGnssMeasurementsListener 交付的是伪距/载波相位等原始观测值，App 可据此
     * 自行解算位置，完全不经过 ILocationManager 的位置方法。虚拟定位开启时应一并阻断，
     * 否则具备 RTK 解算能力的 App 仍能拿到真实坐标。
     */
    @ProxyMethod("addGnssMeasurementsListener")
    public static class AddGnssMeasurementsListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                Slog.d(TAG, "addGnssMeasurementsListener blocked (fake location enabled)");
                return true;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("removeGnssMeasurementsListener")
    public static class RemoveGnssMeasurementsListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                return null;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("removeUpdates")
    public static class RemoveUpdates extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args[0] instanceof IInterface) {
                IInterface listener = (IInterface) args[0];
                BLocationManager.get().removeUpdates(listener.asBinder());
                return 0;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderProperties")
    public static class GetProviderProperties extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Object providerProperties = method.invoke(who, args);
            if (BLocationManager.isFakeLocationEnable()) {
                BRProviderProperties.get(providerProperties)._set_mHasNetworkRequirement(false);
                if (BLocationManager.get().getCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName()) == null) {
                    BRProviderProperties.get(providerProperties)._set_mHasCellRequirement(false);
                }
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("removeGpsStatusListener")
    public static class RemoveGpsStatusListener extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return 0;
        }
    }

    @ProxyMethod("getBestProvider")
    public static class GetBestProvider extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                return LocationManager.GPS_PROVIDER;
            }
            return method.invoke(who, args);
        }
    }

    /**
     * Android 17 适配：与真机保持一致。
     *
     * 原实现硬编码返回 [gps, network]，而真机（OPPO PMA120 / Android 17）实际返回
     * [passive, fused, gps, network]。provider 列表属于系统能力画像，是设备指纹的
     * 常见维度之一，两者不一致会被判定为"不同设备"，故此处对齐真机。
     */
    @ProxyMethod("getAllProviders")
    public static class GetAllProviders extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return Arrays.asList(
                    LocationManager.PASSIVE_PROVIDER,
                    "fused",
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER);
        }
    }

    /**
     * Android 17 适配：与真机保持一致。
     *
     * 真机上 gps / network / passive 均为 enabled=true；
     * 原实现只对 gps 返回 true，导致 network / passive 与真机不一致。
     */
    @ProxyMethod("isProviderEnabledForUser")
    public static class isProviderEnabledForUser extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String provider = args != null && args.length > 0 ? (String) args[0] : null;
            return LocationManager.GPS_PROVIDER.equals(provider)
                    || LocationManager.NETWORK_PROVIDER.equals(provider)
                    || LocationManager.PASSIVE_PROVIDER.equals(provider)
                    || "fused".equals(provider);
        }
    }

    @ProxyMethod("setExtraLocationControllerPackageEnabled")
    public static class setExtraLocationControllerPackageEnabled extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }
}
