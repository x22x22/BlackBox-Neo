package top.niunaijun.blackbox.fake.service;


import java.lang.reflect.Method;

import black.android.os.BRIDeviceIdentifiersPolicyServiceStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Md5Utils;


public class IDeviceIdentifiersPolicyProxy extends BinderInvocationStub {

    public IDeviceIdentifiersPolicyProxy() {
        super(BRServiceManager.get().getService("device_identifiers"));
    }

    @Override
    protected Object getWho() {
        return BRIDeviceIdentifiersPolicyServiceStub.get().asInterface(BRServiceManager.get().getService("device_identifiers"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("device_identifiers");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("getSerialForPackage")
    public static class x extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            /*
             * Android 17 适配：与真机行为保持一致。
             *
             * 真机上普通应用调用 Build.getSerial() 时，系统会检查
             * READ_PRIVILEGED_PHONE_STATE，普通应用不具备该权限，
             * 因此系统侧抛出 SecurityException。
             *
             * 原实现在此返回 md5(宿主包名)，导致沙箱内应用拿到一个"正常值"，
             * 与真机的异常行为不一致；若目标应用对 getSerial 做容错分支，
             * 两次采集会走到完全不同的代码路径，从而被判定为"设备变更"。
             *
             * 这里改为抛出与真机一致的 SecurityException。
             */
            throw new SecurityException(
                    "getSerialForPackage: The caller " + BlackBoxCore.getHostPkg()
                            + " does not meet the requirements to access device identifiers.");
        }
    }
}
