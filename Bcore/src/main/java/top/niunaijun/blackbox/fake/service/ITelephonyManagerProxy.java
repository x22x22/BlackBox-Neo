package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.telephony.CellInfo;
import android.telephony.TelephonyManager;
import android.util.Log;

import top.niunaijun.blackbox.utils.Slog;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import black.android.os.BRServiceManager;
import black.com.android.internal.telephony.BRITelephonyStub;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.location.BCell;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Md5Utils;


public class ITelephonyManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ITelephonyManagerProxy";

    public ITelephonyManagerProxy() {
        super(BRServiceManager.get().getService(Context.TELEPHONY_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder telephony = BRServiceManager.get().getService(Context.TELEPHONY_SERVICE);
        return BRITelephonyStub.get().asInterface(telephony);
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.TELEPHONY_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // 诊断：虚拟定位开启时，列出企业微信/腾讯定位 SDK 仍会调用的蜂窝相关接口。
        // 用于找出"基站缓存被重新填充"的来源（getCellLocation/getAllCellInfo 已返回空）。
        if (BLocationManager.isFakeLocationEnable()) {
            String n = method.getName().toLowerCase();
            if (n.contains("cell") || n.contains("service") || n.contains("network")
                    || n.contains("signal") || n.contains("operator")) {
                Slog.d(TAG, "[BB-CELL] invoke: " + method.getName()
                        + ", argc=" + (args == null ? 0 : args.length));
            }
        }
        return super.invoke(proxy, method, args);
    }

    @ProxyMethod("getDeviceId")
    public static class GetDeviceId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {


            return Md5Utils.md5(BlackBoxCore.getHostPkg());
        }
    }

    @ProxyMethod("getImeiForSlot")
    public static class getImeiForSlot extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {


            return Md5Utils.md5(BlackBoxCore.getHostPkg());
        }
    }

    @ProxyMethod("getMeidForSlot")
    public static class GetMeidForSlot extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {


            return Md5Utils.md5(BlackBoxCore.getHostPkg());
        }
    }

    @ProxyMethod("isUserDataEnabled")
    public static class IsUserDataEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return true;
        }
    }


    @ProxyMethod("getLine1NumberForDisplay")
    public static class getLine1NumberForDisplay extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return null;
        }
    }

    @ProxyMethod("getSubscriberId")
    public static class GetSubscriberId extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return Md5Utils.md5(BlackBoxCore.getHostPkg());
        }
    }

    @ProxyMethod("getDeviceIdWithFeature")
    public static class GetDeviceIdWithFeature extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return Md5Utils.md5(BlackBoxCore.getHostPkg());
        }
    }

    @ProxyMethod("getCellLocation")
    public static class GetCellLocation extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "getCellLocation");
            // Android 17 适配：虚拟定位开启时必须无条件屏蔽真实基站。
            // 原实现在「未配置虚拟基站」时会把真实 CellLocation 回传给地图类 App，
            // 高德/百度会把它上报自家服务器做基站反查，得到真实位置并覆盖掉
            // 系统 LocationManager 上的虚拟定位（实测：系统 gps provider 已是虚拟
            // 坐标，但高德仍定位到真实城市）。
            if (BLocationManager.isFakeLocationEnable()) {
                return null;
            }
            return method.invoke(who, args);
        }
    }

    /**
     * Android 17 适配：★ 企业微信定位到真实地点的真正原因。
     *
     * 实测（[BB-CELL] invoke 日志）企业微信/腾讯定位 SDK 走的是 Android 12+ 新增的
     * **异步**基站接口 requestCellInfoUpdate：
     *   TelephonyManager.requestCellInfoUpdate(Executor, CellInfoCallback)
     *     -> ITelephony.requestCellInfoUpdate(subId, ICellInfoCallback, pkg, featureId)
     * 原先只拦了同步的 getCellLocation / getAllCellInfo，这条异步通道完全没覆盖，
     * 于是真实基站被原样返回，并被腾讯定位 SDK 缓存进
     *   shared_prefs/LocationSDK.xml  >  pref_cell_info
     * （实测缓存内容 460,0,6685887,10201112587,-90），随后凭该基站做网络反查
     * 得到真实坐标 —— 系统层已经下发的虚拟位置因此完全不起作用。
     *
     * 处理：虚拟定位开启时不查系统，直接给回调推一个空 CellInfo 列表，
     * 使 SDK 拿不到任何基站，只能回退到已被接管的 GPS 位置。
     */
    @ProxyMethod("requestCellInfoUpdate")
    public static class RequestCellInfoUpdate extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (!BLocationManager.isFakeLocationEnable()) {
                return method.invoke(who, args);
            }
            Slog.d(TAG, "requestCellInfoUpdate blocked (fake location enabled)");
            if (args != null) {
                for (int ai = 0; ai < args.length; ai++) {
                    Object a = args[ai];
                    Slog.d(TAG, "  arg[" + ai + "]=" + (a == null ? "null"
                            : a.getClass().getName() + " ifaces=" + java.util.Arrays.toString(a.getClass().getInterfaces())));
                }
                // 注意：不能按类名过滤。实测回调对象的类名是
                // android.telephony.TelephonyManager$4（ICellInfoCallback.Stub 的匿名子类），
                // 既不含 "CellInfoCallback" 也不实现任何接口（getInterfaces() 为空）。
                // 因此这里按「是否存在 onCellInfo(List) 方法」来识别回调。
                for (Object arg : args) {
                    if (arg == null) {
                        continue;
                    }
                    Method cb = null;
                    for (Method m : arg.getClass().getMethods()) {
                        if ("onCellInfo".equals(m.getName()) && m.getParameterCount() == 1) {
                            cb = m;
                            break;
                        }
                    }
                    if (cb == null) {
                        continue;
                    }
                    try {
                        cb.setAccessible(true);
                        cb.invoke(arg, new ArrayList<CellInfo>());
                        Slog.d(TAG, "requestCellInfoUpdate: pushed empty cell list to "
                                + arg.getClass().getName());
                    } catch (Throwable t) {
                        Log.w(TAG, "requestCellInfoUpdate: callback failed on "
                                + arg.getClass().getName(), t);
                    }
                    break;
                }
            }
            return 0;
        }
    }

    @ProxyMethod("getAllCellInfo")
    public static class GetAllCellInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (BLocationManager.isFakeLocationEnable()) {
                List<BCell> cell = BLocationManager.get().getAllCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                
                // Android 17 适配：getAllCellInfo() 的返回类型必须是系统类型
                // List<CellInfo>。原实现直接返回 List<BCell>，调用方按 CellInfo
                // 取用时会 ClassCastException。虚拟定位开启时统一返回空列表。
                return new ArrayList<CellInfo>();
            }
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return null;
            }
        }
    }

    @ProxyMethod("getNetworkOperator")
    public static class GetNetworkOperator extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "getNetworkOperator");
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getNetworkTypeForSubscriber")
    public static class GetNetworkTypeForSubscriber extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(who, args);
            } catch (Throwable e) {
                return 0;
            }
        }
    }

    @ProxyMethod("getNeighboringCellInfo")
    public static class GetNeighboringCellInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "getNeighboringCellInfo");
            if (BLocationManager.isFakeLocationEnable()) {
                List<BCell> cell = BLocationManager.get().getNeighboringCell(BActivityThread.getUserId(), BActivityThread.getAppPackageName());
                
                return null;
            }
            return method.invoke(who, args);
        }
    }
}
