package top.niunaijun.blackbox.fake.service;

import android.content.Context;
import android.os.IBinder;

import java.lang.reflect.Method;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Android 17 适配：拦截系统剪贴板服务。
 *
 * 沙箱内应用以「宿主 uid」运行、但包名是它自己（如 com.tencent.wework），
 * 系统 ClipboardService 会用 callingPackage + uid 做归属校验，校验失败即抛：
 *
 *   java.lang.SecurityException: Package com.tencent.wework does not belong to 10568
 *     at android.content.IClipboard$Stub$Proxy.hasPrimaryClip
 *     at android.content.ClipboardManager.hasPrimaryClip
 *     at android.widget.TextView.canPaste
 *     at android.widget.TextView.onInitializeAccessibilityNodeInfoInternal
 *     at android.view.AccessibilityInteractionController$AccessibilityNodePrefetcher...
 *
 * 触发路径非常隐蔽：只要无障碍服务（屏幕朗读、uiautomator 抓取等）或输入法去
 * 查询一个 EditText 节点，TextView 就会调 canPaste()，进而打到剪贴板服务。
 * 表现为「输入手机号后 / 发起短信验证码时闪退」。
 *
 * 这里直接接管 hasPrimaryClip 返回 false，避免调用真实服务。
 */
public class IClipboardProxy extends BinderInvocationStub {
    public static final String TAG = "IClipboardProxy";

    public IClipboardProxy() {
        super(BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE));
    }

    /**
     * getWho() 必须返回真实的 IInterface，ClassInvocationStub 才会建立
     * @ProxyMethod 的映射；返回 null 会导致 hook 静默失效（实测报
     * "getWho failed: Method was null!"，hasPrimaryClip 依然打到真实服务并崩溃）。
     *
     * 原来的 Reflector.on("android.content.IClipboard$Stub").call("asInterface", ...)
     * 取不到方法，这里改为直接反射，并把诊断信息打全。
     */
    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(Context.CLIPBOARD_SERVICE);
        if (binder == null) {
            Slog.w(TAG, "getWho: clipboard binder is null");
            return null;
        }
        try {
            Class<?> stubCls = Class.forName("android.content.IClipboard$Stub");
            Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
            Object who = asInterface.invoke(null, binder);
            Slog.w(TAG, "getWho ok: " + (who == null ? "null" : who.getClass().getName()));
            return who;
        } catch (Throwable e) {
            Slog.e(TAG, "getWho failed, dumping IClipboard members", e);
            try {
                Class<?> iface = Class.forName("android.content.IClipboard");
                for (Class<?> inner : iface.getDeclaredClasses()) {
                    Slog.w(TAG, "  IClipboard inner: " + inner.getName());
                }
                for (Method m : iface.getMethods()) {
                    if (m.getName().contains("asInterface") || m.getName().contains("hasPrimaryClip")) {
                        Slog.w(TAG, "  IClipboard method: " + m);
                    }
                }
            } catch (Throwable e2) {
                Slog.e(TAG, "dump IClipboard failed", e2);
            }
            return null;
        }
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.CLIPBOARD_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod("hasPrimaryClip")
    public static class HasPrimaryClip extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "hasPrimaryClip intercepted -> false");
            return false;
        }
    }
}
