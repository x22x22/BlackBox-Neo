package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;
import android.view.inputmethod.EditorInfo;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.hook.ScanClass;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Android 17 适配：修复沙箱内输入法（键盘）不可用。
 *
 * 现象：沙箱内应用能进入输入页，但键盘弹不出来、也无法切换输入法。
 * 系统侧日志（InputMethodManagerService）：
 *
 *   E/InputMethodManagerService: Rejecting this client as it reported an
 *     invalid package name. uid=10568 package=com.tencent.wework
 *
 * 原因：ImmS 会校验「客户端上报的包名」与「窗口属性的 packageName」是否一致。
 * 沙箱里应用以宿主 uid 运行、窗口属于 top.niunaijun.blackbox，而客户端上报的
 * EditorInfo.packageName 是应用自己的包名（com.tencent.wework），两者不一致
 * 就被直接拒绝，IME 连接建立不起来。
 *
 * 两个要点（原实现都缺）：
 *  1) getWho() 必须返回真正的 IInterface —— ClassInvocationStub 靠它建立
 *     @ProxyMethod 映射；返回 IBinder 会让 hook 静默失效。
 *  2) startInputOrWindowGainedFocus 必须把 EditorInfo.packageName 改写成
 *     宿主包名，否则校验照样失败。
 */
@ScanClass(IInputMethodManagerProxy.class)
public class IInputMethodManagerProxy extends BinderInvocationStub {
    public static final String TAG = "IInputMethodManagerProxy";

    public IInputMethodManagerProxy() {
        super(BRServiceManager.get().getService(Context.INPUT_METHOD_SERVICE));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(Context.INPUT_METHOD_SERVICE);
        if (binder == null) {
            Slog.w(TAG, "getWho: input_method binder is null");
            return null;
        }
        try {
            Class<?> stubCls = Class.forName("com.android.internal.view.IInputMethodManager$Stub");
            Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
            Object who = asInterface.invoke(null, binder);
            Slog.w(TAG, "getWho ok: " + (who == null ? "null" : who.getClass().getName()));
            return who;
        } catch (Throwable e) {
            Slog.e(TAG, "getWho failed", e);
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.INPUT_METHOD_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("startInputOrWindowGainedFocus")
    public static class StartInputOrWindowGainedFocus extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof EditorInfo) {
                        EditorInfo ei = (EditorInfo) a;
                        String before = ei.packageName;
                        ei.packageName = BlackBoxCore.getHostPkg();
                        Slog.d(TAG, "startInputOrWindowGainedFocus: EditorInfo.packageName "
                                + before + " -> " + ei.packageName);
                        break;
                    }
                }
            }
            return method.invoke(who, args);
        }
    }
}
