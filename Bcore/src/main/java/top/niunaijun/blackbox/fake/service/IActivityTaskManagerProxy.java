package top.niunaijun.blackbox.fake.service;

import android.Manifest;
import android.app.ActivityManager;
import android.content.pm.PackageManager;
import android.util.Log;

import java.lang.reflect.Method;

import black.android.app.BRActivityTaskManager;
import black.android.app.BRIActivityTaskManagerStub;
import black.android.os.BRServiceManager;
import black.android.util.BRSingleton;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.utils.compat.TaskDescriptionCompat;


@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityTaskManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ActivityTaskManager";

    public IActivityTaskManagerProxy() {
        super(BRServiceManager.get().getService("activity_task"));
    }

    @Override
    protected Object getWho() {
        return BRIActivityTaskManagerStub.get().asInterface(BRServiceManager.get().getService("activity_task"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("activity_task");
        BRActivityTaskManager.get().getService();
        Object o = BRActivityTaskManager.get().IActivityTaskManagerSingleton();
        BRSingleton.get(o)._set_mInstance(BRIActivityTaskManagerStub.get().asInterface(this));
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    /**
     * Android 17 适配：补齐定位权限。
     *
     * Android 14+ 的 ContextImpl.checkPermission() 走的是
     * ActivityTaskManager.getService().checkPermission()（即 activity_task 服务），
     * 而不是 activity 或 permissionmgr 服务。此前 BlackBox 只在
     * IActivityManagerProxy / IPackageManagerProxy 中放行了权限，本服务没有 hook，
     * 因此沙箱内 checkSelfPermission(ACCESS_FINE_LOCATION) 一直返回 DENIED，
     * 与真机（已授予）不一致，并连带影响 LocationManager 的 provider 列表行为。
     */
    @ProxyMethod("checkPermission")
    public static class CheckPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = args != null && args.length > 0 ? (String) args[0] : null;
            if (isLocationPermission(permission)) {
                Log.d(TAG, "activity_task checkPermission: granting location permission: " + permission);
                return PackageManager.PERMISSION_GRANTED;
            }
            return method.invoke(who, args);
        }
    }

    private static boolean isLocationPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(Manifest.permission.ACCESS_FINE_LOCATION)
                || permission.equals(Manifest.permission.ACCESS_COARSE_LOCATION)
                || permission.equals(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                || permission.equals("android.permission.ACCESS_LOCATION_EXTRA_COMMANDS")
                || permission.equals("android.permission.ACCESS_MEDIA_LOCATION")
                || permission.equals("android.permission.ACCESS_LAST_KNOWN_LOCATION");
    }

    @ProxyMethod("setTaskDescription")
    public static class SetTaskDescription extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ActivityManager.TaskDescription td = (ActivityManager.TaskDescription) args[1];
            args[1] = TaskDescriptionCompat.fix(td);
            return method.invoke(who, args);
        }
    }
}
