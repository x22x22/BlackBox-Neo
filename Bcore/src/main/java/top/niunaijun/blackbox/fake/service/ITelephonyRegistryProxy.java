package top.niunaijun.blackbox.fake.service;

import android.util.Log;

import top.niunaijun.blackbox.utils.Slog;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import black.com.android.internal.telephony.BRITelephonyRegistryStub;
import top.niunaijun.blackbox.fake.frameworks.BLocationManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.MethodParameterUtils;


public class ITelephonyRegistryProxy extends BinderInvocationStub {
    public ITelephonyRegistryProxy() {
        super(BRServiceManager.get().getService("telephony.registry"));
    }

    @Override
    protected Object getWho() {
        return BRITelephonyRegistryStub.get().asInterface(BRServiceManager.get().getService("telephony.registry"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("telephony.registry");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    /**
     * TelephonyRegistry 的 LISTEN_CELL_INFO 事件位。
     * 注册它之后，系统会通过 notifyCellInfo() 主动把真实基站推送给 App，
     * 这条路径完全不经过 getCellLocation / getAllCellInfo / requestCellInfoUpdate，
     * 是基站数据的第三条通道。虚拟定位开启时必须剥掉该位。
     */
    private static final int LISTEN_CELL_INFO = 1 << 10;   // 1024

    private static void stripCellInfoFlag(Object[] args) {
        if (args == null || !BLocationManager.isFakeLocationEnable()) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Integer) {
                int v = (Integer) args[i];
                if ((v & LISTEN_CELL_INFO) != 0) {
                    args[i] = v & ~LISTEN_CELL_INFO;
                    Slog.d(TAG, "stripped LISTEN_CELL_INFO at args[" + i + "]: "
                            + v + " -> " + args[i]);
                }
            }
        }
    }

    @ProxyMethod("listenForSubscriber")
    public static class ListenForSubscriber extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            stripCellInfoFlag(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("listen")
    public static class Listen extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            stripCellInfoFlag(args);
            return method.invoke(who, args);
        }
    }
}
