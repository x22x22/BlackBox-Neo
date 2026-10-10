package top.niunaijun.blackbox.fake.service;

import android.content.pm.PackageInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;

import android.os.IBinder;

import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.Slog;


/**
 * Android 17 适配：让 WebView 更新服务 hook 真正生效。
 *
 * 原实现继承 ClassInvocationStub 且 getWho()/inject() 都是空实现，
 * 而 ClassInvocationStub.injectHook() 在 getWho() 返回 null 时会直接 return，
 * 于是下面 9 个 @ProxyMethod 一个都没注册 —— 整个 WebView hook 从未工作过。
 * 这里改为 BinderInvocationStub，替换 webviewupdate 服务。
 */
public class IWebViewUpdateServiceProxy extends BinderInvocationStub {
    public static final String TAG = "IWebViewUpdateServiceProxy";

    private static final String SERVICE_NAME = "webviewupdate";

    public IWebViewUpdateServiceProxy() {
        super(BRServiceManager.get().getService(SERVICE_NAME));
    }

    @Override
    protected Object getWho() {
        IBinder binder = BRServiceManager.get().getService(SERVICE_NAME);
        if (binder == null) {
            Slog.w(TAG, "getWho: webviewupdate binder is null");
            return null;
        }
        try {
            Class<?> stubCls = Class.forName("android.webkit.IWebViewUpdateService$Stub");
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
        replaceSystemService(SERVICE_NAME);
        Slog.w(TAG, "webviewupdate service replaced");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    
    @ProxyMethod("getCurrentWebViewPackage")
    public static class GetCurrentWebViewPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "WebViewUpdateService: getCurrentWebViewPackage called");
            
            try {
                
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "WebViewUpdateService: Successfully got current WebView package");
                    return result;
                }
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to get current WebView package", e);
            }
            
            
            Slog.d(TAG, "WebViewUpdateService: Returning null to use system default");
            return null;
        }
    }

    
    @ProxyMethod("getValidWebViewPackages")
    public static class GetValidWebViewPackages extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "WebViewUpdateService: getValidWebViewPackages called");
            
            try {
                
                Object result = method.invoke(who, args);
                if (result != null && result instanceof PackageInfo[]) {
                    PackageInfo[] packages = (PackageInfo[]) result;
                    if (packages.length > 0) {
                        Slog.d(TAG, "WebViewUpdateService: Found " + packages.length + " valid WebView packages");
                        return result;
                    }
                }
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to get valid WebView packages", e);
            }
            
            
            Slog.d(TAG, "WebViewUpdateService: Returning empty array to prevent conflicts");
            return new PackageInfo[0];
        }
    }

    
    @ProxyMethod("isMultiProcessEnabled")
    public static class IsMultiProcessEnabled extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "WebViewUpdateService: isMultiProcessEnabled called, returning false for single-process mode");
            return false;
        }
    }

    
    @ProxyMethod("getWebViewPackages")
    public static class GetWebViewPackages extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "WebViewUpdateService: getWebViewPackages called");
            
            try {
                
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "WebViewUpdateService: Successfully got WebView packages");
                    return result;
                }
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to get WebView packages", e);
            }
            
            
            return new ArrayList<>();
        }
    }

    
    @ProxyMethod("getWebViewProviderInfo")
    public static class GetWebViewProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "WebViewUpdateService: getWebViewProviderInfo called");
            
            try {
                
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "WebViewUpdateService: Successfully got WebView provider info");
                    return result;
                }
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to get WebView provider info", e);
            }
            
            
            return null;
        }
    }

    
    @ProxyMethod("isWebViewPackage")
    public static class IsWebViewPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                String packageName = (String) args[0];
                Slog.d(TAG, "WebViewUpdateService: isWebViewPackage called for: " + packageName);
                
                
                if (isKnownWebViewPackage(packageName)) {
                    Slog.d(TAG, "WebViewUpdateService: " + packageName + " is a known WebView package");
                    return true;
                }
            }
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to check WebView package", e);
                return false;
            }
        }
        
        private boolean isKnownWebViewPackage(String packageName) {
            if (packageName == null) return false;
            
            
            return packageName.equals("com.google.android.webview") ||
                   packageName.equals("com.google.android.webview.dev") ||
                   packageName.equals("com.google.android.webview.beta") ||
                   packageName.equals("com.google.android.webview.canary") ||
                   packageName.equals("com.android.webview") ||
                   packageName.equals("com.huawei.webview") ||
                   packageName.equals("com.samsung.android.webview") ||
                   packageName.equals("com.oneplus.webview");
        }
    }

    
    @ProxyMethod("getWebViewProvider")
    public static class GetWebViewProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Slog.d(TAG, "WebViewUpdateService: getWebViewProvider called");
            
            try {
                
                Object result = method.invoke(who, args);
                if (result != null) {
                    Slog.d(TAG, "WebViewUpdateService: Successfully got WebView provider");
                    return result;
                }
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to get WebView provider", e);
            }
            
            
            return null;
        }
    }

    
    @ProxyMethod("enableWebViewPackage")
    public static class EnableWebViewPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                String packageName = (String) args[0];
                Slog.d(TAG, "WebViewUpdateService: enableWebViewPackage called for: " + packageName);
            }
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to enable WebView package", e);
                
                return true;
            }
        }
    }

    
    @ProxyMethod("disableWebViewPackage")
    public static class DisableWebViewPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (args != null && args.length > 0) {
                String packageName = (String) args[0];
                Slog.d(TAG, "WebViewUpdateService: disableWebViewPackage called for: " + packageName);
            }
            
            try {
                return method.invoke(who, args);
            } catch (Exception e) {
                Slog.w(TAG, "WebViewUpdateService: Failed to disable WebView package", e);
                
                return true;
            }
        }
    }
}
