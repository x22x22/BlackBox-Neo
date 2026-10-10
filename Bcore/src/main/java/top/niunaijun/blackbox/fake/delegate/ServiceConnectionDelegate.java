package top.niunaijun.blackbox.fake.delegate;

import android.app.IBinderSession;
import android.app.IServiceConnection;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.HashMap;
import java.util.Map;

import black.android.app.BRIServiceConnectionO;
import top.niunaijun.blackbox.utils.compat.BuildCompat;


/**
 * 修正 bindService 时传给应用侧 ServiceConnection 的 ComponentName。
 *
 * 【Android 17 适配说明】
 * Android 17 (API 37) 起，@hide 接口 android.app.IServiceConnection 的签名发生变化：
 *   Android 16 及以前: void connected(in ComponentName, IBinder service, boolean dead);
 *   Android 17 及以后: void connected(in ComponentName, IBinder service, in IBinderSession session, boolean dead);
 *
 * 由于 android.app.IServiceConnection 是 bootclasspath 类，运行时生效的是系统版本，
 * 若本类未实现新版抽象方法，系统 Stub.onTransact 分派时会抛 AbstractMethodError，
 * 导致沙箱内任何调用 bindService 的应用崩溃（如高德地图）。
 *
 * 因此这里同时实现新旧两套签名，并按运行时 SDK 版本选择转发方式。
 */
public class ServiceConnectionDelegate extends IServiceConnection.Stub {
    private static final Map<IBinder, ServiceConnectionDelegate> sServiceConnectDelegate = new HashMap<>();
    private final IServiceConnection mConn;
    private final ComponentName mComponentName;

    /** Android 17 (API 37) 引入 IBinderSession 参数 */
    private static final boolean HAS_BINDER_SESSION = Build.VERSION.SDK_INT >= 37;

    private ServiceConnectionDelegate(IServiceConnection mConn, ComponentName targetComponent) {
        this.mConn = mConn;
        this.mComponentName = targetComponent;
    }

    public static ServiceConnectionDelegate getDelegate(IBinder iBinder) {
        return sServiceConnectDelegate.get(iBinder);
    }

    public static IServiceConnection createProxy(IServiceConnection base, Intent intent) {
        final IBinder iBinder = base.asBinder();
        ServiceConnectionDelegate delegate = sServiceConnectDelegate.get(iBinder);
        if (delegate == null) {
            try {
                iBinder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        sServiceConnectDelegate.remove(iBinder);
                        iBinder.unlinkToDeath(this, 0);
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            delegate = new ServiceConnectionDelegate(base, intent.getComponent());
            sServiceConnectDelegate.put(iBinder, delegate);
        }
        return delegate;
    }

    /**
     * Android 17+ (API 37+)：系统 Stub 分派到本方法。
     */
    @Override
    public void connected(ComponentName name, IBinder service, IBinderSession session, boolean dead)
            throws RemoteException {
        if (HAS_BINDER_SESSION) {
            mConn.connected(mComponentName, service, session, dead);
        }
    }

    /**
     * Android 8.0 ~ Android 16：旧签名实现。
     * 在 Android 17 上系统 Stub 不会分派到本方法（保留仅为兼容旧系统运行时）。
     */
    public void connected(ComponentName name, IBinder service, boolean dead) throws RemoteException {
        if (!HAS_BINDER_SESSION) {
            if (BuildCompat.isOreo()) {
                BRIServiceConnectionO.get(mConn).connected(mComponentName, service, dead);
            }
        }
    }

    /**
     * Android 7.x 及以前：两参签名实现。
     */
    public void connected(ComponentName name, IBinder service) throws RemoteException {
        connected(name, service, false);
    }
}
