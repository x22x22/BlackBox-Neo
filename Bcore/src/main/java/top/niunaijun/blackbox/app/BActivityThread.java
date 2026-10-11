package top.niunaijun.blackbox.app;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.app.Service;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.WebView;

import java.io.File;
import java.lang.reflect.Method;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import black.android.app.ActivityThreadAppBindDataContext;
import black.android.app.BRActivity;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadAppBindData;
import black.android.app.BRActivityThreadNMR1;
import black.android.app.BRActivityThreadQ;
import black.android.app.BRContextImpl;
import black.android.app.BRLoadedApk;
import black.android.app.BRService;
import black.android.app.LoadedApk;
import black.android.content.BRBroadcastReceiver;
import black.android.content.BRContentProviderClient;
import black.android.graphics.BRCompatibility;
import black.android.security.net.config.BRNetworkSecurityConfigProvider;
import black.com.android.internal.content.BRReferrerIntent;
import black.dalvik.system.BRVMRuntime;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.AppLifecycleCallback;
import top.niunaijun.blackbox.app.dispatcher.AppServiceDispatcher;
import top.niunaijun.blackbox.core.CrashHandler;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.core.NativeCore;
import top.niunaijun.blackbox.core.env.VirtualRuntime;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.entity.am.ReceiverData;

import top.niunaijun.blackbox.fake.delegate.AppInstrumentation;
import top.niunaijun.blackbox.fake.delegate.ContentProviderDelegate;

import top.niunaijun.blackbox.fake.hook.HookManager;
import top.niunaijun.blackbox.fake.service.HCallbackProxy;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.SafeContextWrapper;
import top.niunaijun.blackbox.utils.GlobalContextWrapper;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.ActivityManagerCompat;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ContextCompat;
import top.niunaijun.blackbox.utils.compat.StrictModeCompat;
import top.niunaijun.blackbox.core.system.JarManager;


public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";

    private static BActivityThread sBActivityThread;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private AppConfig mAppConfig;
    private final List<ProviderInfo> mProviders = new ArrayList<>();
    private final Handler mH = BlackBoxCore.get().getHandler();
    private static final Object mConfigLock = new Object();

    public static boolean isThreadInit() {
        return sBActivityThread != null;
    }

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static AppConfig getAppConfig() {
        synchronized (mConfigLock) {
            return currentActivityThread().mAppConfig;
        }
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    /**
     * 当前沙箱进程所承载的目标应用的 ClassLoader。
     *
     * <p>为什么需要它：Application 实例是在 bindApplication 里「attachBaseContext
     * 成功之后」才赋值给 mInitialApplication 的，而部分应用（典型是走 Tinker 的
     * 微信 com.tencent.mm）会在自己的 attachBaseContext 内部就调用系统服务——
     * 例如 MMApplicationLike.onBaseContextAttached() 会用 sendBroadcast 通知它
     * 自己的启动骨架。这个时间点 getApplication() 仍然是 null，
     * getApplication().getClassLoader() 必然 NPE。
     *
     * <p>LoadedApk 的 ClassLoader 在更早就绪（handleBindApplication 一开始就从
     * mBoundApplication.info 取过），所以这里直接取它，保证整个绑定流程内可用。
     */
    public static ClassLoader getAppClassLoader() {
        try {
            BActivityThread at = currentActivityThread();
            if (at == null) {
                return null;
            }
            if (at.mBoundApplication != null && at.mBoundApplication.info != null) {
                ClassLoader cl = BRLoadedApk.get(at.mBoundApplication.info).getClassLoader();
                if (cl != null) {
                    return cl;
                }
            }
            if (at.mInitialApplication != null) {
                return at.mInitialApplication.getClassLoader();
            }
        } catch (Throwable t) {
            Slog.w(TAG, "getAppClassLoader failed: " + t);
        }
        return null;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getBUid() {
        return getAppConfig() == null ? BUserHandle.AID_APP_START : getAppConfig().buid;
    }

    public static int getBAppId() {
        return BUserHandle.getAppId(getBUid());
    }

    public static int getCallingBUid() {
        return getAppConfig() == null ? BlackBoxCore.getHostUid() : getAppConfig().callingBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public void initProcess(AppConfig appConfig) {
        synchronized (mConfigLock) {
            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }
            this.mAppConfig = appConfig;
            IBinder iBinder = asBinder();
            try {
                iBinder.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        synchronized (mConfigLock) {
                            try {
                                iBinder.linkToDeath(this, 0);
                            } catch (RemoteException ignored) {
                            }
                            mAppConfig = null;
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        Service service;
        try {
            service = (Service) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (ClassNotFoundException e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services class not found, skipping: " + serviceInfo.name);
                return null;
            }
            e.printStackTrace();
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to instantiate service " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    token,
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            // Android 17 适配：腾讯会议 SDK lateinit 兜底。
            // :wemeet 进程（ProxyService）在 handleBindApplication 之后极短时间内就会
            // 创建 WeMeetService，早于异步探针执行；而 WeMeetService.onCreate 的静态
            // 初始化会读取 TMSDK 的 lateinit mApplication（dl2.b），为空时抛
            // UninitializedPropertyAccessException 导致整个子进程崩溃（实测 PID 27975/28566）。
            ensureTmsdkFieldSync(mInitialApplication);
            service.onCreate();
            return service;
        } catch (Exception e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services service creation failed, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.w(TAG, "Service creation failed, but continuing: " + serviceInfo.name + " - " + e.getMessage());
            return null;
        }
    }

    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader = BRLoadedApk.get(mBoundApplication.info).getClassLoader();
        JobService service;
        try {
            service = (JobService) classLoader.loadClass(serviceInfo.name).newInstance();
        } catch (ClassNotFoundException e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services JobService class not found, skipping: " + serviceInfo.name);
                return null;
            }
            e.printStackTrace();
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            Slog.e(TAG, "Unable to create JobService " + serviceInfo.name
                    + ": " + e.toString());
            return null;
        }

        try {
            Context context = BlackBoxCore.getContext().createPackageContext(
                    serviceInfo.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
            BRContextImpl.get(context).setOuterContext(service);
            BRService.get(service).attach(
                    context,
                    BlackBoxCore.mainThread(),
                    serviceInfo.name,
                    BActivityThread.currentActivityThread().getActivityThread(),
                    mInitialApplication,
                    BRActivityManagerNative.get().getDefault()
            );
            ContextCompat.fix(context);
            service.onCreate();
            service.onBind(null);
            return service;
        } catch (Exception e) {
            
            if (serviceInfo.name.contains("google.android.gms") || 
                serviceInfo.name.contains("google.android.location")) {
                Slog.w(TAG, "Google Play Services JobService creation failed, skipping: " + serviceInfo.name);
                return null;
            }
            Slog.w(TAG, "JobService creation failed, but continuing: " + serviceInfo.name + " - " + e.getMessage());
            return null;
        }
    }

    public void bindApplication(final String packageName, final String processName) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            BlackBoxCore.get().getHandler().post(() -> {
                
                Object bindData = createBindApplicationData(packageName, processName);
                handleBindApplication(packageName, processName);
                conditionVariable.open();
            });
            conditionVariable.block();
        } else {
            
            Object bindData = createBindApplicationData(packageName, processName);
            handleBindApplication(packageName, processName);
        }
    }
    
    
    private Object createBindApplicationData(String packageName, String processName) {
        try {
            
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, getUserId());
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            
            
            
            return new Object() {
                public ApplicationInfo getInfo() { return applicationInfo; }
                public List<ProviderInfo> getProviders() { 
                    return packageInfo.providers != null ? Arrays.asList(packageInfo.providers) : new ArrayList<>();
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Error creating bind application data", e);
            
            return new Object() {
                public ApplicationInfo getInfo() { return null; }
                public List<ProviderInfo> getProviders() { return new ArrayList<>(); }
            };
        }
    }

    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {
            CrashHandler.create();
        } catch (Throwable ignored) {
        }

        PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
        ApplicationInfo applicationInfo = packageInfo.applicationInfo;
        Slog.w(TAG, "[BB-BIND] appInfo.className=" + applicationInfo.className
                + " | appInfo.packageName=" + applicationInfo.packageName
                + " | appInfo.dataDir=" + applicationInfo.dataDir
                + " | appInfo.nativeLibraryDir=" + applicationInfo.nativeLibraryDir);
        // 诊断：企业微信这类应用的自定义 View 依赖 ContentProvider 触发的初始化
        // （androidx.startup / XWeb / 各类 InitProvider）。若这里 providers 为空，
        // 后续 View 构造会因"未初始化"抛 IllegalStateException。
        if (packageInfo.providers == null) {
            Slog.w(TAG, "[BB-PROVIDER] getPackageInfo(" + packageName + ", GET_PROVIDERS) -> providers == NULL");
            packageInfo.providers = new ProviderInfo[]{};
        } else {
            Slog.w(TAG, "[BB-PROVIDER] getPackageInfo(" + packageName + ", GET_PROVIDERS) -> "
                    + packageInfo.providers.length + " providers");
            // 注意：这里原本逐个打印 49 个 Provider，日志量过大曾把 logcat 缓冲区
            // 冲爆，导致后续关键锚点（a7/installProviders）看起来"从未执行"。
            // 排查此类问题时务必控制单次日志量。
        }
        mProviders.addAll(Arrays.asList(packageInfo.providers));
        Slog.w(TAG, "[BB-BIND] step1: got " + mProviders.size() + " providers");

        Object boundApplication = BRActivityThread.get(BlackBoxCore.mainThread()).mBoundApplication();

        Slog.w(TAG, "[BB-BIND] a1: before createPackageContext");
        Context packageContext = createPackageContext(applicationInfo);
        Slog.w(TAG, "[BB-BIND] a2: packageContext=" + packageContext);
        Object loadedApk = BRContextImpl.get(packageContext).mPackageInfo();
        Slog.w(TAG, "[BB-BIND] a3: loadedApk=" + loadedApk);
        BRLoadedApk.get(loadedApk)._set_mSecurityViolation(false);
        
        BRLoadedApk.get(loadedApk)._set_mApplicationInfo(applicationInfo);

        int targetSdkVersion = applicationInfo.targetSdkVersion;
        if (targetSdkVersion < Build.VERSION_CODES.GINGERBREAD) {
            StrictMode.ThreadPolicy newPolicy = new StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build();
            StrictMode.setThreadPolicy(newPolicy);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (targetSdkVersion < Build.VERSION_CODES.N) {
                StrictModeCompat.disableDeathOnFileUriExposure();
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WebView.setDataDirectorySuffix(getUserId() + ":" + packageName + ":" + processName);
        }

        VirtualRuntime.setupRuntime(processName, applicationInfo);

        BRVMRuntime.get(BRVMRuntime.get().getRuntime()).setTargetSdkVersion(applicationInfo.targetSdkVersion);
        if (BuildCompat.isS()) {
            BRCompatibility.get().setTargetSdkVersion(applicationInfo.targetSdkVersion);
        }

        Slog.w(TAG, "[BB-BIND] a4: before NativeCore.init");
        NativeCore.init(Build.VERSION.SDK_INT);
        Slog.w(TAG, "[BB-BIND] a5: after NativeCore.init");
        assert packageContext != null;
        // 【关键修复】清理 Tinker 热修复遗留的文件锁。
        // 企业微信用 Tinker 做热修复，其 WwApplication.attachBaseContext 会通过
        // <dataDir>/tinker/info.lock 做多进程文件锁同步。在沙箱的 IO 重定向下，
        // 主进程与 :patch 进程会互等这把锁，使 attachBaseContext 永久阻塞
        // （实测 CPU 0%、等待 60s 无进展），进而 makeApplication 永不返回、
        // Provider 不安装，最终崩溃在「MUST init ... binder」。删除该锁后
        // handleBindApplication 立刻走完；且补丁在沙箱内本就无法正确生效，
        // 因此这里每次启动都主动清理。
        try {
            File bbDataDir = top.niunaijun.blackbox.core.env.BEnvironment
                    .getDataDir(packageName, getUserId());
            File tinkerDir = new File(bbDataDir, "tinker");
            File tinkerLock = new File(tinkerDir, "info.lock");
            // 先清掉可能已存在的锁文件/空目录
            if (tinkerLock.exists()) {
                tinkerLock.delete();
            }
            // 关键：把 info.lock 建成「同名目录」占位。
            // 单纯删除锁只治标 —— :patch 进程会立刻重建它，主进程下次启动照样死锁；
            // 而占位目录会让 Tinker 打开该路径时直接失败，从而跳过文件锁逻辑，
            // 主进程与 :patch 进程不再互等。
            tinkerLock.mkdirs();
            Slog.w(TAG, "[BB-TINKER] info.lock placeholder dir=" + tinkerLock.isDirectory()
                    + " path=" + tinkerLock);
            File lastCrash = new File(new File(bbDataDir, "tinker_temp"), "tinker_last_crash");
            if (lastCrash.exists()) {
                lastCrash.delete();
            }
        } catch (Throwable t) {
            Slog.e(TAG, "[BB-TINKER] failed to prepare tinker state", t);
        }

        Slog.w(TAG, "[BB-BIND] a6: before enableRedirect");
        try {
            IOCore.get().enableRedirect(packageContext);
        } catch (Throwable t) {
            // 关键修复：enableRedirect() 内部的 try 只包住了前半段，
            // NativeCore.enableIO() 等收尾调用在 try 之外。一旦它抛出的是
            // Error（native 层 UnsatisfiedLinkError 等），既不会被
            // catch(Exception) 捕获，也会直接中断 handleBindApplication ——
            // 后果是后面的 installProviders() 永远不执行，沙箱应用的
            // ContentProvider（androidx.startup.InitializationProvider、
            // XWebCoreContentProvider 等）全部不初始化，最终表现为自定义 View
            // 构造时抛「MUST init ... binder」并崩溃（企业微信即此症状）。
            // IO 重定向失败只影响文件隔离，不应阻断应用启动。
            Slog.e(TAG, "[BB-BIND] enableRedirect FAILED (continuing to install providers)", t);
        }
        Slog.w(TAG, "[BB-BIND] a7: after enableRedirect");

        AppBindData bindData = new AppBindData();
        bindData.appInfo = applicationInfo;
        bindData.processName = processName;
        bindData.info = loadedApk;
        bindData.providers = mProviders;

        ActivityThreadAppBindDataContext activityThreadAppBindData = BRActivityThreadAppBindData.get(boundApplication);
        activityThreadAppBindData._set_instrumentationName(new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
        activityThreadAppBindData._set_appInfo(bindData.appInfo);
        activityThreadAppBindData._set_info(bindData.info);
        activityThreadAppBindData._set_processName(bindData.processName);
        activityThreadAppBindData._set_providers(bindData.providers);

        mBoundApplication = bindData;
        Slog.w(TAG, "[BB-BIND] step2: mBoundApplication set");

        
        Slog.w(TAG, "[BB-BIND] step2a: before NetworkSecurityConfigProvider");
        if (BRNetworkSecurityConfigProvider.getRealClass() != null) {
            Security.removeProvider("AndroidNSSP");
            BRNetworkSecurityConfigProvider.get().install(packageContext);
        }
        Slog.w(TAG, "[BB-BIND] step2b: after NetworkSecurityConfigProvider");
        Application application = null;
        try {
            Slog.w(TAG, "[BB-BIND] step2c: before onBeforeCreateApplication");
            onBeforeCreateApplication(packageName, processName, packageContext);
            Slog.w(TAG, "[BB-BIND] step2d: after onBeforeCreateApplication");
            
            
            try {
                Slog.w(TAG, "[BB-BIND] step2e: before makeApplication");
                // 探针：先单独取一次 ClassLoader，区分卡在「类加载」还是
                // WwApplication.attachBaseContext（加固壳常在这两处做多进程同步）。
                try {
                    ClassLoader probeCl = BRLoadedApk.get(loadedApk).getClassLoader();
                    Slog.w(TAG, "[BB-BIND] step2e1: classLoader ok");
                    Class<?> probeCls = Class.forName(applicationInfo.className, false, probeCl);
                    Slog.w(TAG, "[BB-BIND] step2e2: classFound=" + probeCls);
                } catch (Throwable pe) {
                    Slog.e(TAG, "[BB-BIND] step2e-probe failed", pe);
                }
                // 带超时地执行 makeApplication。
                // 企业微信的 WwApplication.attachBaseContext（加固壳）在沙箱主进程中
                // 存在不确定性阻塞：实测 CPU 0%、可无限期挂起，导致主进程
                // handleBindApplication 永不返回 → Provider 不安装 →
                // 崩溃在「MUST init ... binder」。放到独立线程 + 超时，
                // 保证绑定流程无论如何都能继续走完。
                // 诊断：列出运行时 LoadedApk 上真实的 makeApplication 方法签名。
                // 编译期接口是按 SDK 35 生成的；若 Android 17 改了签名，
                // BlackReflection 会静默找不到方法并返回 null（实测 6ms 返回 null）。
                try {
                    for (Method m0 : loadedApk.getClass().getDeclaredMethods()) {
                        if (m0.getName().contains("makeApplication")) {
                            Slog.w(TAG, "[BB-MKAPP] runtime method: " + m0);
                        }
                    }
                } catch (Throwable mt) {
                    Slog.e(TAG, "[BB-MKAPP] list methods failed", mt);
                }
                final Object[] appHolder = new Object[1];
                final Throwable[] appErr = new Throwable[1];
                final java.util.concurrent.atomic.AtomicBoolean appDone =
                        new java.util.concurrent.atomic.AtomicBoolean(false);
                Thread mkThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Slog.w(TAG, "[BB-MKAPP] thread: calling makeApplication");
                            appHolder[0] = BRLoadedApk.get(loadedApk).makeApplication(false, null);
                            Slog.w(TAG, "[BB-MKAPP] thread: makeApplication returned "
                                    + (appHolder[0] == null ? "NULL" : appHolder[0].getClass().getName()));
                        } catch (Throwable e) {
                            appErr[0] = e;
                            Slog.e(TAG, "[BB-MKAPP] thread: makeApplication THREW", e);
                        } finally {
                            appDone.set(true);
                            Slog.w(TAG, "[BB-MKAPP] thread: done");
                        }
                    }
                }, "bb-makeApplication");
                mkThread.setDaemon(true);
                mkThread.start();
                long mkDeadline = System.currentTimeMillis() + 8000;
                while (!appDone.get() && System.currentTimeMillis() < mkDeadline) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                if (appDone.get() && appHolder[0] != null) {
                    application = (Application) appHolder[0];
                } else if (true) {
                    // Android 17 的 LoadedApk.makeApplication 会直接返回 null
                    // （实测 6ms 返回，无异常；方法签名本身是存在的，是内部
                    // makeApplicationInner 的判定导致）。这里改为手动走等价流程，
                    // 并把结果同步回 LoadedApk.mApplication，保证后续逻辑拿到实例。
                    try {
                        Slog.w(TAG, "[BB-MANUAL] creating application manually");
                        ClassLoader manCl = BRLoadedApk.get(loadedApk).getClassLoader();
                        final Class<?> manCls = Class.forName(applicationInfo.className, true, manCl);
                        // attachBaseContext 声明在父类 ContextWrapper 上且是 protected：
                        // getDeclaredMethod(Application.class, ...) 会抛 NoSuchMethodException，
                        // getMethod 又只找 public。因此沿继承链逐级 getDeclaredMethod 查找。
                        Method attachTmp = null;
                        Class<?> ac = manCls;
                        while (ac != null && attachTmp == null) {
                            try {
                                attachTmp = ac.getDeclaredMethod("attachBaseContext", Context.class);
                            } catch (NoSuchMethodException ignore) {
                                ac = ac.getSuperclass();
                            }
                        }
                        if (attachTmp == null) {
                            throw new NoSuchMethodException("attachBaseContext not found in hierarchy");
                        }
                        final Method attachM = attachTmp;
                        attachM.setAccessible(true);

                        // ★★★ Android 17 + Tinker 适配（2026-10-11，微信 com.tencent.mm）
                        // Tinker 的 TinkerApplication.onBaseContextAttached 会创建
                        // mInlineFence —— 一个绑定「当前线程 Looper」的 Handler，
                        // 并且强制要求在主线程初始化，非主线程直接抛
                        //   TinkerRuntimeException: Should init within main thread!
                        // 更关键的是：如果在子线程初始化，这个 Handler 会绑到一个
                        // 随后立刻退出的临时 Looper，微信后续的初始化推进
                        // （MMApplicationLike.onCreate 等）就再也继续不下去，
                        // 最终 LauncherUI.onCreate 检查到启动骨架未就绪而闪退：
                        //   b96.b: Skeleton not initialized!
                        // bindApplication 本身就跑在主线程，所以这里优先同步创建。
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                            try {
                                long mtStart = System.currentTimeMillis();
                                Slog.w(TAG, "[BB-MANUAL] main-thread create: before attachBaseContext");
                                Application mtApp = (Application) manCls.newInstance();
                                attachM.invoke(mtApp, packageContext);
                                Slog.w(TAG, "[BB-MANUAL] main-thread create: after attachBaseContext");
                                BRLoadedApk.get(loadedApk)._set_mApplication(mtApp);
                                application = mtApp;
                                Slog.w(TAG, "[BB-MANUAL] main-thread create SUCCESS in "
                                        + (System.currentTimeMillis() - mtStart) + "ms -> "
                                        + mtApp.getClass().getName());
                            } catch (Throwable mtEx) {
                                Throwable mtCause = mtEx;
                                if (mtCause instanceof java.lang.reflect.InvocationTargetException
                                        && mtCause.getCause() != null) {
                                    mtCause = mtCause.getCause();
                                }
                                Slog.e(TAG, "[BB-MANUAL] main-thread create FAILED: " + mtCause, mtCause);
                            }
                        } else {
                            Slog.w(TAG, "[BB-MANUAL] not on main thread; will use worker thread path");
                        }

                        // 回退路径：attachBaseContext 必须满足两个条件
                        //   ① 所在线程要有 Looper —— 企业微信走 Tinker，其
                        //      TinkerApplicationInlineFence 构造函数会 new Handler()，
                        //      无线程 Looper 时会抛 "Can't create handler inside thread
                        //      ... that has not called Looper.prepare()"；
                        //   ② 又要能超时重试 —— 主进程若早于 :patch 进程启动，
                        //      attachBaseContext 会一直等（实测永久阻塞、CPU 0%）。
                        // 因此放到独立线程里先 Looper.prepare()，再加超时重试。
                        for (int attempt = 0; attempt < 4 && application == null; attempt++) {
                            final Object[] res = new Object[1];
                            final Throwable[] err = new Throwable[1];
                            final java.util.concurrent.atomic.AtomicBoolean done =
                                    new java.util.concurrent.atomic.AtomicBoolean(false);
                            Thread att = new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    android.os.Looper.prepare();
                                    try {
                                        Application a = (Application) manCls.newInstance();
                                        attachM.invoke(a, packageContext);
                                        res[0] = a;
                                    } catch (Throwable e) {
                                        Throwable c = e;
                                        if (c instanceof java.lang.reflect.InvocationTargetException
                                                && c.getCause() != null) {
                                            c = c.getCause();
                                        }
                                        err[0] = c;
                                    } finally {
                                        done.set(true);
                                    }
                                }
                            }, "bb-attach-" + attempt);
                            att.setDaemon(true);
                            att.start();
                            long attDeadline = System.currentTimeMillis() + 6000;
                            while (!done.get() && System.currentTimeMillis() < attDeadline) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                            if (done.get() && res[0] != null) {
                                Application okApp = (Application) res[0];
                                BRLoadedApk.get(loadedApk)._set_mApplication(okApp);
                                application = okApp;
                                Slog.w(TAG, "[BB-MANUAL] SUCCESS on attempt " + attempt
                                        + " -> " + okApp.getClass().getName());
                            } else if (done.get()) {
                                Slog.e(TAG, "[BB-MANUAL] attempt " + attempt + " threw: " + err[0], err[0]);
                            } else {
                                Slog.w(TAG, "[BB-MANUAL] attempt " + attempt + " TIMED OUT (blocked)");
                            }
                        }
                        if (application == null) {
                            Slog.e(TAG, "[BB-MANUAL] all attempts exhausted, application stays null");
                        }
                    } catch (Throwable manEx) {
                        Slog.e(TAG, "[BB-MANUAL] manual creation FAILED", manEx);
                        application = null;
                    }
                } else {
                    Slog.e(TAG, "[BB-MKAPP] makeApplication did NOT finish within 8s (stuck in attachBaseContext); "
                            + "proceeding with null to keep binding alive", appErr[0]);
                    application = null;
                }
                Slog.w(TAG, "[BB-BIND] step2f: after makeApplication=" + (application == null ? "null" : application.getClass().getName()));
            } catch (Exception makeAppException) {
                Slog.e(TAG, "Failed to makeApplication, trying fallback approach", makeAppException);
                application = null;
            }
            
            
            if (application == null) {
                Slog.w(TAG, "makeApplication returned null, attempting fallback creation");
                
                
                try {
                    application = BRLoadedApk.get(loadedApk).makeApplication(true, null);
                } catch (Exception e) {
                    Slog.e(TAG, "Fallback makeApplication also failed", e);
                }
                
                
                if (application == null) {
                    Slog.w(TAG, "Creating minimal application context as fallback");
                    try {
                        
                        application = (Application) packageContext;
                        if (application == null) {
                            Slog.e(TAG, "Even package context is null, this is critical");
                            throw new RuntimeException("Unable to create application context");
                        }
                    } catch (Exception contextException) {
                        Slog.e(TAG, "Failed to create fallback application context", contextException);
                        throw new RuntimeException("Unable to makeApplication - all fallback attempts failed", contextException);
                    }
                }
            }
            
            if (application == null) {
                Slog.e(TAG, "makeApplication application Error! All attempts failed");
                throw new RuntimeException("Unable to create application - all creation methods failed");
            }
            Slog.w(TAG, "[BB-BIND] step3: application=" + application.getClass().getName());
            
            mInitialApplication = application;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
            ContextCompat.fix((Context) BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext());
            ContextCompat.fix(mInitialApplication);
            Slog.w(TAG, "[BB-BIND] app=" + application.getClass().getName()
                    + " providers(mProviders)=" + mProviders.size()
                    + " providers(bindData)=" + (bindData.providers == null ? "null" : bindData.providers.size()));
            installProviders(mInitialApplication, bindData.processName, bindData.providers);
            Slog.w(TAG, "[BB-BIND] installProviders returned");

            onBeforeApplicationOnCreate(packageName, processName, application);
            long appOnCreateStart = System.currentTimeMillis();
            Slog.w(TAG, "[BB-APP] before callApplicationOnCreate");
            AppInstrumentation.get().callApplicationOnCreate(application);
            Slog.w(TAG, "[BB-APP] after callApplicationOnCreate, cost="
                    + (System.currentTimeMillis() - appOnCreateStart) + "ms");
            onAfterApplicationOnCreate(packageName, processName, application);

            // Android 17 适配：补齐腾讯会议 SDK（TMSDK）在沙箱内缺失的初始化。
            //
            // 现象：企业微信「选择企业 -> 点击进入」必崩，堆栈为
            //   kotlin.UninitializedPropertyAccessException:
            //       lateinit property mApplication has not been initialized
            //     at com.tencent.wemeet.tmsdk.TMSDK.isInitialized
            //     at ...TencentMeetingApiImpl.IsInMeeting
            //     at ...MeetingApiImpl.checkNoActiveVoiceCallBeforeAccountChange
            //     at ...NormalEnterpriseInfoActivity.onClick
            // TMSDK 在沙箱进程内从未 init，而 isInitialized() 直接读取 lateinit
            // 字段 mApplication，于是抛异常并杀掉进程。
            probeAndFixTmsdk(application);

            HookManager.get().checkEnv(HCallbackProxy.class);
        } catch (Exception e) {
            Slog.e(TAG, "Critical error in handleBindApplication", e);
            throw new RuntimeException("Unable to makeApplication", e);
        } catch (Throwable t) {
            // 原实现只捕获 Exception：NoSuchMethodError / ExceptionInInitializerError
            // 这类 Error 会绕过日志直接中断整个绑定流程，导致 Provider 从未安装，
            // 应用的自定义 View 因"未初始化"而崩溃（企业微信即此症状）。
            Slog.e(TAG, "Critical THROWABLE in handleBindApplication (bypassed by Exception catch)", t);
            throw new RuntimeException("Unable to makeApplication (Throwable)", t);
        }
    }
    
    
    /**
     * 探测并修复腾讯会议 SDK（com.tencent.wemeet.tmsdk.TMSDK）的 lateinit 状态。
     *
     * 该 SDK 在沙箱内不会被企业微信初始化，而企业微信在
     * NormalEnterpriseInfoActivity 点击「进入」时调用
     * MeetingApiImpl.checkNoActiveVoiceCallBeforeAccountChange ->
     * TencentMeetingApiImpl.IsInMeeting -> TMSDK.isInitialized()，
     * 后者直接读取 lateinit 的 mApplication 字段并抛
     * UninitializedPropertyAccessException，导致进程闪退。
     *
     * 这里延迟到 Application.onCreate 之后，用应用的 ClassLoader 加载该类，
     * 打印其结构（便于诊断），并把值为 null 的 Context/Application 类型字段
     * 补成当前 Application，使 isInitialized() 不再抛异常。
     */
    private static void probeAndFixTmsdk(final Application application) {
        try {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(500);
                    } catch (Throwable ignored) {
                    }
                    final ClassLoader cl = application.getClassLoader();

                    // 第一步：走官方初始化入口 initOnApplicationCreate(Application)。
                    // 只给 lateinit 字段赋值虽然能让 isInitialized() 不再抛异常，但 TMSDK
                    // 内部状态（Handler / 服务缓存 / 弱引用表）仍为空，后续
                    // getInMeetingService() 之类调用可能再次失败。
                    // TMSDK 内部会创建 Handler，因此必须在带 Looper 的主线程调用。
                    try {
                        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Class<?> tmsdkCls = Class.forName("com.tencent.wemeet.tmsdk.TMSDK", true, cl);
                                    java.lang.reflect.Field instField = tmsdkCls.getDeclaredField("INSTANCE");
                                    instField.setAccessible(true);
                                    Object tmsdkInst = instField.get(null);
                                    try {
                                        java.lang.reflect.Method m = tmsdkCls.getDeclaredMethod(
                                                "initOnApplicationCreate", android.app.Application.class);
                                        m.setAccessible(true);
                                        m.invoke(tmsdkInst, application);
                                        Slog.w(TAG, "[BB-TMSDK] initOnApplicationCreate(app) OK");
                                    } catch (Throwable e1) {
                                        Slog.w(TAG, "[BB-TMSDK] initOnApplicationCreate(app) failed: " + e1);
                                        try {
                                            java.lang.reflect.Method m2 = tmsdkCls.getDeclaredMethod(
                                                    "initOnApplicationCreate",
                                                    android.app.Application.class, boolean.class);
                                            m2.setAccessible(true);
                                            m2.invoke(tmsdkInst, application, false);
                                            Slog.w(TAG, "[BB-TMSDK] initOnApplicationCreate(app,false) OK");
                                        } catch (Throwable e2) {
                                            Slog.w(TAG, "[BB-TMSDK] initOnApplicationCreate(app,false) failed: " + e2);
                                        }
                                    }
                                } catch (Throwable e) {
                                    Slog.w(TAG, "[BB-TMSDK] official init failed: " + e);
                                }
                            }
                        });
                    } catch (Throwable e) {
                        Slog.w(TAG, "[BB-TMSDK] post init failed: " + e);
                    }

                    String[] classNames = new String[]{
                            "com.tencent.wemeet.tmsdk.TMSDK",
                            "dl2"
                    };
                    for (String cn : classNames) {
                        Class<?> k;
                        try {
                            k = Class.forName(cn, true, cl);
                        } catch (Throwable e) {
                            Slog.w(TAG, "[BB-TMSDK] load " + cn + " failed: " + e);
                            continue;
                        }
                        Object inst = null;
                        try {
                            java.lang.reflect.Field instField = k.getDeclaredField("INSTANCE");
                            instField.setAccessible(true);
                            inst = instField.get(null);
                        } catch (Throwable ignored) {
                        }
                        Slog.w(TAG, "[BB-TMSDK] " + cn + " inst=" + inst
                                + " fields=" + k.getDeclaredFields().length
                                + " methods=" + k.getDeclaredMethods().length);
                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                            Slog.w(TAG, "[BB-TMSDK] " + cn + ".field "
                                    + f.getType().getName() + " " + f.getName()
                                    + " static=" + java.lang.reflect.Modifier.isStatic(f.getModifiers()));
                        }
                        for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                            Slog.w(TAG, "[BB-TMSDK] " + cn + ".method " + m.getName()
                                    + java.util.Arrays.toString(m.getParameterTypes()));
                        }
                        for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                            if (!android.content.Context.class.isAssignableFrom(f.getType())) {
                                continue;
                            }
                            if (java.lang.reflect.Modifier.isFinal(f.getModifiers())) {
                                continue;
                            }
                            try {
                                f.setAccessible(true);
                                Object cur = inst != null ? f.get(inst) : f.get(null);
                                if (cur == null) {
                                    if (inst != null) {
                                        f.set(inst, application);
                                    } else {
                                        f.set(null, application);
                                    }
                                    Slog.w(TAG, "[BB-TMSDK] FIXED " + cn + "." + f.getName());
                                }
                            } catch (Throwable e) {
                                Slog.w(TAG, "[BB-TMSDK] set " + cn + "." + f.getName() + " failed: " + e);
                            }
                        }
                    }
                }
            }, "bb-tmsdk-probe");
            t.setDaemon(true);
            t.start();
        } catch (Throwable e) {
            Slog.w(TAG, "[BB-TMSDK] probe start failed", e);
        }
    }

    /**
     * 同步的 TMSDK lateinit 兜底：把 dl2.b（即 com.tencent.wemeet.tmsdk.TMSDK 的
     * lateinit mApplication，混淆后字段名为 b）补成当前 Application。
     *
     * 与异步探针不同，这个方法必须能在任意线程、任意时刻快速返回，
     * 因为 :wemeet 子进程会在启动后数百毫秒内创建 WeMeetService。
     */
    private static void ensureTmsdkFieldSync(Application application) {
        if (application == null) {
            return;
        }
        try {
            ClassLoader cl = application.getClassLoader();
            Class<?> d = Class.forName("dl2", true, cl);
            java.lang.reflect.Field b = d.getDeclaredField("b");
            b.setAccessible(true);
            if (b.get(null) == null) {
                b.set(null, application);
                Slog.w(TAG, "[BB-TMSDK] sync-fixed dl2.b");
            }
        } catch (Throwable ignored) {
            // TMSDK 类尚未加载（非企业微信进程）时属正常情况，静默忽略
        }
    }

    private void initializeJarEnvironment() {
        try {
            Slog.d(TAG, "Initializing JAR environment for DEX loading");
            
            
            JarManager jarManager = JarManager.getInstance();
            if (!jarManager.isReady()) {
                Slog.d(TAG, "JarManager not ready, initializing synchronously");
                jarManager.initializeSync();
            }
            
            
            File emptyJar = jarManager.getEmptyJar();
            if (emptyJar == null || !emptyJar.exists()) {
                Slog.w(TAG, "Empty JAR not available, attempting to recreate");
                jarManager.clearCache();
                jarManager.initializeSync();
                emptyJar = jarManager.getEmptyJar();
            }
            
            if (emptyJar != null && emptyJar.exists()) {
                Slog.d(TAG, "Empty JAR verified: " + emptyJar.getAbsolutePath());
            } else {
                Slog.w(TAG, "Empty JAR still not available after retry");
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error initializing JAR environment", e);
        }
    }
    
    
    private Application createApplicationWithFallback(android.content.pm.ApplicationInfo appInfo) {
        try {
            
            Application application = createApplication(appInfo);
            if (application != null) {
                Slog.d(TAG, "Application created successfully: " + appInfo.className);
                return application;
            }
        } catch (Exception e) {
            Slog.w(TAG, "Failed to create application normally: " + e.getMessage());
        }
        
        try {
            
            Slog.d(TAG, "Attempting fallback application creation");
            ClassLoader classLoader = getClassLoader(appInfo);
            if (classLoader == null) {
                Slog.w(TAG, "ClassLoader is null, using system class loader");
                classLoader = ClassLoader.getSystemClassLoader();
            }
            
            Class<?> appClass = classLoader.loadClass(appInfo.className);
            Application application = (Application) appClass.newInstance();
            
            
            ensureApplicationBaseContext(application, appInfo);
            
            Slog.d(TAG, "Fallback application creation successful");
            return application;
            
        } catch (Exception e) {
            Slog.e(TAG, "Fallback application creation failed: " + e.getMessage());
            
            
            try {
                Slog.d(TAG, "Creating minimal application wrapper");
                Application wrapper = new Application() {
                    @Override
                    public void onCreate() {
                        super.onCreate();
                        Slog.d(TAG, "Minimal application wrapper onCreate called");
                    }
                };
                
                
                ensureApplicationBaseContext(wrapper, appInfo);
                
                return wrapper;
            } catch (Exception wrapperException) {
                Slog.e(TAG, "Failed to create minimal application wrapper", wrapperException);
                return null;
            }
        }
    }
    
    
    private void installContentProvidersWithFallback(Application application, Object data) {
        try {
            List<android.content.pm.ProviderInfo> providers = getProviderInfoList(data);
            if (providers == null || providers.isEmpty()) {
                Slog.d(TAG, "No content providers to install");
                return;
            }
            
            Slog.d(TAG, "Installing " + providers.size() + " content providers");
            
            for (android.content.pm.ProviderInfo providerInfo : providers) {
                try {
                    installContentProvider(application, providerInfo);
                    Slog.d(TAG, "Successfully installed provider: " + providerInfo.name);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to install provider " + providerInfo.name + ": " + e.getMessage());
                    
                }
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error installing content providers", e);
        }
    }
    
    
    private android.content.pm.ApplicationInfo getApplicationInfo(Object data) {
        try {
            
            if (data != null) {
                try {
                    
                    Method getInfoMethod = data.getClass().getMethod("getInfo");
                    ApplicationInfo appInfo = (ApplicationInfo) getInfoMethod.invoke(data);
                    if (appInfo != null) {
                        return appInfo;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error getting info from custom data object: " + e.getMessage());
                }
            }
            
            
            String packageName = BlackBoxCore.getAppPackageName();
            if (packageName != null) {
                PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, 0, getUserId());
                return packageInfo.applicationInfo;
            }
            
            return null;
        } catch (Exception e) {
            Slog.e(TAG, "Error getting application info", e);
            return null;
        }
    }
    
    
    private ClassLoader getClassLoader(android.content.pm.ApplicationInfo appInfo) {
        try {
            
            String sourceDir = appInfo.sourceDir;
            if (sourceDir != null) {
                return new dalvik.system.PathClassLoader(sourceDir, ClassLoader.getSystemClassLoader());
            }
            
            
            return ClassLoader.getSystemClassLoader();
        } catch (Exception e) {
            Slog.w(TAG, "Error getting class loader: " + e.getMessage());
            return ClassLoader.getSystemClassLoader();
        }
    }

    
    private Application createApplication(android.content.pm.ApplicationInfo appInfo) {
        try {
            
            ClassLoader classLoader = getClassLoader(appInfo);
            Class<?> appClass = classLoader.loadClass(appInfo.className);
            Application application = (Application) appClass.newInstance();
            
            
            ensureApplicationBaseContext(application, appInfo);
            
            return application;
        } catch (Exception e) {
            Slog.e(TAG, "Error creating application: " + e.getMessage());
            return null;
        }
    }
    
    
    private void ensureApplicationBaseContext(Application application, android.content.pm.ApplicationInfo appInfo) {
        try {
            
            if (application.getBaseContext() != null) {
                Slog.d(TAG, "Application already has base context: " + appInfo.className);
                return;
            }
            
            
            Context packageContext = createPackageContext(appInfo);
            if (packageContext == null) {
                Slog.w(TAG, "Could not create package context for application: " + appInfo.className + ", using fallback");
                
                packageContext = createFallbackContext(appInfo.packageName);
            }
            
            
            if (packageContext == null) {
                Slog.e(TAG, "Failed to create any context for application: " + appInfo.className);
                return;
            }
            
            
            try {
                Method attachBaseContext = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
                attachBaseContext.setAccessible(true);
                attachBaseContext.invoke(application, packageContext);
                Slog.d(TAG, "Successfully attached base context to application: " + appInfo.className);
            } catch (Exception e) {
                Slog.w(TAG, "Could not attach base context to application: " + e.getMessage());
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Error ensuring application base context: " + e.getMessage());
        }
    }
    
    
    private Context createFallbackContext(String packageName) {
        try {
            Context baseContext = BlackBoxCore.getContext();
            if (baseContext == null) {
                Slog.e(TAG, "BlackBoxCore.getContext() is null, cannot create fallback context");
                return null;
            }
            
            
            return new ContextWrapper(baseContext) {
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public android.content.pm.PackageManager getPackageManager() {
                    try {
                        return baseContext.getPackageManager();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting package manager from base context: " + e.getMessage());
                        return null;
                    }
                }
                
                @Override
                public android.content.res.Resources getResources() {
                    try {
                        return baseContext.getResources();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting resources from base context: " + e.getMessage());
                        try {
                            return android.content.res.Resources.getSystem();
                        } catch (Exception e2) {
                            Slog.e(TAG, "Error getting system resources: " + e2.getMessage());
                            return null;
                        }
                    }
                }
                
                @Override
                public ClassLoader getClassLoader() {
                    try {
                        return baseContext.getClassLoader();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting class loader from base context: " + e.getMessage());
                        try {
                            return ClassLoader.getSystemClassLoader();
                        } catch (Exception e2) {
                            Slog.e(TAG, "Error getting system class loader: " + e2.getMessage());
                            return null;
                        }
                    }
                }
                
                @Override
                public Context getApplicationContext() {
                    try {
                        return baseContext.getApplicationContext();
                    } catch (Exception e) {
                        Slog.w(TAG, "Error getting application context from base context: " + e.getMessage());
                        return this;
                    }
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create fallback context for " + packageName + ": " + e.getMessage());
            return null;
        }
    }
    
    
    private List<android.content.pm.ProviderInfo> getProviderInfoList(Object data) {
        try {
            
            if (data != null) {
                try {
                    
                    Method getProvidersMethod = data.getClass().getMethod("getProviders");
                    List<ProviderInfo> providers = (List<ProviderInfo>) getProvidersMethod.invoke(data);
                    if (providers != null) {
                        return providers;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Error getting providers from custom data object: " + e.getMessage());
                }
            }
            
            
            return new ArrayList<>();
        } catch (Exception e) {
            Slog.e(TAG, "Error getting provider info list", e);
            return new ArrayList<>();
        }
    }
    
    
    private void installContentProvider(Application application, android.content.pm.ProviderInfo providerInfo) {
        try {

            if (application == null) {
                Slog.w(TAG, "Application is null, cannot install content provider: " + providerInfo.name);
                return;
            }

            // Skip known anti-virtual-environment detection providers.
            // These providers detect the sandbox and call System.exit() to kill the app.
            // By skipping them, the app continues to run normally.
            if (providerInfo.name != null && isAntiDetectProvider(providerInfo.name)) {
                Slog.w(TAG, "Skipping anti-detect ContentProvider: " + providerInfo.name);
                return;
            }

            ClassLoader classLoader = application.getClassLoader();
            if (classLoader == null) {
                Slog.w(TAG, "Application class loader is null, using system class loader for: " + providerInfo.name);
                classLoader = ClassLoader.getSystemClassLoader();
            }

            android.content.ContentProvider provider = (android.content.ContentProvider) classLoader
                .loadClass(providerInfo.name).newInstance();

            provider.attachInfo(application, providerInfo);

            Slog.d(TAG, "Content provider installed: " + providerInfo.name);

        } catch (Exception e) {
            Slog.e(TAG, "Error installing content provider " + providerInfo.name, e);
        }
    }

    /**
     * Check if a ContentProvider is a known anti-virtual-environment detector.
     * These providers detect sandboxed environments and force-kill the app.
     */
    private static boolean isAntiDetectProvider(String className) {
        String lower = className.toLowerCase();
        // Meituan Hades anti-cheat
        return lower.contains("hades")
                || lower.contains("ztuni")
                // Douyin/TikTok security
                || lower.contains("securityguard")
                || lower.contains("avdetector")
                // Common detection patterns
                || lower.contains("virtualdetect")
                || lower.contains("sandboxdetect")
                || lower.contains("emulatordetect")
                || lower.contains("fridadetect")
                || lower.contains("hookdetect")
                || lower.contains("xposeddetect")
                || lower.contains("magiskdetect");
    }
    
    
    private void setApplication(Application application) {
        try {
            mInitialApplication = application;
            BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(application);
            Slog.d(TAG, "Application set in ActivityThread successfully");
        } catch (Exception e) {
            Slog.e(TAG, "Error setting application in ActivityThread", e);
        }
    }

    private void handleSecurityException(SecurityException se, String packageName, String processName, Context packageContext) {
        Slog.w(TAG, "Handling SecurityException for " + packageName);
        
        
        try {
                            Application basicApp = createMinimalApplication(packageContext, packageName);
            if (basicApp != null) {
                mInitialApplication = basicApp;
                BRActivityThread.get(BlackBoxCore.mainThread())._set_mInitialApplication(mInitialApplication);
                ContextCompat.fix(mInitialApplication);
                
                
                Slog.w(TAG, "Created basic application, skipping problematic operations");
                return;
            }
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create basic application after SecurityException: " + e.getMessage());
        }
        
        
        throw new RuntimeException("Unable to handle SecurityException", se);
    }

    private void installProvidersWithErrorHandling(Context context, String processName, List<ProviderInfo> providers) {
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : providers) {
                try {
                    if (processName.equals(providerInfo.processName) ||
                            providerInfo.processName.equals(context.getPackageName()) || providerInfo.multiprocess) {
                        installProvider(BlackBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (SecurityException se) {
                    Slog.w(TAG, "SecurityException installing provider " + providerInfo.name + ": " + se.getMessage());
                    
                } catch (Throwable t) {
                    Slog.w(TAG, "Error installing provider " + providerInfo.name + ": " + t.getMessage());
                    
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            try {
                ContentProviderDelegate.init();
            } catch (Exception e) {
                Slog.w(TAG, "Error initializing ContentProviderDelegate: " + e.getMessage());
            }
        }
    }

    public static Context createPackageContext(ApplicationInfo info) {
        try {
            return BlackBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    
    private static Context createMinimalPackageContext(ApplicationInfo info) {
        try {
            
            Context baseContext = BlackBoxCore.getContext();
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, 0);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with minimal flags for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with minimal flags for " + info.packageName + ": " + e.getMessage());
            }
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, Context.CONTEXT_IGNORE_SECURITY);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with ignore security for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with ignore security for " + info.packageName + ": " + e.getMessage());
            }
            
            
            try {
                Context packageContext = baseContext.createPackageContext(info.packageName, Context.CONTEXT_INCLUDE_CODE);
                if (packageContext != null) {
                    Slog.d(TAG, "Successfully created package context with include code for " + info.packageName);
                    return packageContext;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Failed to create package context with include code for " + info.packageName + ": " + e.getMessage());
            }
            
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create minimal package context for " + info.packageName + ": " + e.getMessage());
        }
        
        
        Slog.w(TAG, "Using base context as fallback for " + info.packageName);
        return createWrappedBaseContext(info.packageName);
    }

    
    private static Context createWrappedBaseContext(String packageName) {
        try {
            Context baseContext = BlackBoxCore.getContext();
            
            
            return new ContextWrapper(baseContext) {
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public PackageManager getPackageManager() {
                    return baseContext.getPackageManager();
                }
                
                @Override
                public Resources getResources() {
                    return baseContext.getResources();
                }
                
                @Override
                public ClassLoader getClassLoader() {
                    return baseContext.getClassLoader();
                }
                
                @Override
                public Context getApplicationContext() {
                    return baseContext.getApplicationContext();
                }
            };
        } catch (Exception e) {
            Slog.e(TAG, "Failed to create wrapped base context for " + packageName + ": " + e.getMessage());
            
            return BlackBoxCore.getContext();
        }
    }

    private void installProviders(Context context, String processName, List<ProviderInfo> provider) {
        Slog.w(TAG, "[BB-PROVIDER] installProviders process=" + processName
                + " count=" + (provider == null ? "null" : provider.size()));
        long origId = Binder.clearCallingIdentity();
        try {
            for (ProviderInfo providerInfo : provider) {
                try {
                    // Skip known anti-virtual-environment detection providers.
                    if (providerInfo.name != null && isAntiDetectProvider(providerInfo.name)) {
                        Slog.w(TAG, "Skipping anti-detect ContentProvider: " + providerInfo.name);
                        continue;
                    }
                    boolean processMatch = processName.equals(providerInfo.processName)
                            || providerInfo.processName.equals(context.getPackageName())
                            || providerInfo.multiprocess;
                    if (providerInfo.name.contains("InitializationProvider")
                            || providerInfo.name.contains("XWebCoreContentProvider")
                            || providerInfo.name.contains("InitProvider")) {
                        Slog.w(TAG, "[BB-PROVIDER] check " + providerInfo.name
                                + " | provider.process=" + providerInfo.processName
                                + " | current.process=" + processName
                                + " | ctx.pkg=" + context.getPackageName()
                                + " | multiprocess=" + providerInfo.multiprocess
                                + " | MATCH=" + processMatch);
                    }
                    if (processMatch) {
                        installProvider(BlackBoxCore.mainThread(), context, providerInfo, null);
                    }
                } catch (Throwable t) {
                    // 原来这里是 catch (Throwable ignored) {}，Provider 安装失败完全静默，
                    // 导致上层只能看到"自定义 View 未初始化"这种二次症状。
                    Slog.e(TAG, "[BB-PROVIDER] FAILED to install " + providerInfo.name, t);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(origId);
            ContentProviderDelegate.init();
        }
    }

    public Object getPackageInfo() {
        return mBoundApplication.info;
    }

    public static void installProvider(Object mainThread, Context context, ProviderInfo providerInfo, Object holder) throws Throwable {
        Method installProvider = Reflector.findMethodByFirstName(mainThread.getClass(), "installProvider");
        if (installProvider == null) {
            Slog.e(TAG, "[BB-PROVIDER] installProvider method NOT FOUND on " + mainThread.getClass().getName()
                    + " (Android 17 signature change?) for " + providerInfo.name);
            return;
        }
        installProvider.setAccessible(true);
        // 关键修复：第 4 个参数 local 必须为 true。
        // AOSP ActivityThread.installProvider(context, holder, providerInfo,
        //        local, noisy, noReleaseNeeded) 中，local=false 会走"远程 Provider"分支：
        // 只登记 binder、不创建本地实例、也不会调用 ContentProvider.onCreate()。
        // 后果是 androidx.startup.InitializationProvider / XWebCoreContentProvider 等
        // 初始化型 Provider 全部形同虚设 —— 上层表现为
        // "MUST init com.tencent.wework.common.views.PhotoImageView binder" 并崩溃。
        Object result = installProvider.invoke(mainThread, context, holder, providerInfo, true, true, true);
        Slog.w(TAG, "[BB-PROVIDER] installed " + providerInfo.name + " -> " + result);
    }



    @Override
    public IBinder getActivityThread() {
        return BRActivityThread.get(BlackBoxCore.mainThread()).getApplicationThread();
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    @Override
    public void stopService(Intent intent) {
        AppServiceDispatcher.get().stopService(intent);
    }

    @Override
    public void restartJobService(String selfId) throws RemoteException {

    }

    @Override
    public IBinder acquireContentProviderClient(ProviderInfo providerInfo) throws RemoteException {
        if (!isInit()) {
            bindApplication(BActivityThread.getAppConfig().packageName, BActivityThread.getAppConfig().processName);
        }
        String[] split = providerInfo.authority.split(";");
        for (String auth : split) {
            ContentProviderClient contentProviderClient = BlackBoxCore.getContext()
                    .getContentResolver().acquireContentProviderClient(auth);
            IInterface iInterface = BRContentProviderClient.get(contentProviderClient).mContentProvider();
            if (iInterface == null)
                continue;
            return iInterface.asBinder();
        }
        return null;
    }

    @Override
    public IBinder peekService(Intent intent) {
        return AppServiceDispatcher.get().peekService(intent);
    }

    @Override
    public void finishActivity(final IBinder token) {
        mH.post(() -> {
            Map<IBinder, Object> activities = BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
            if (activities.isEmpty())
                return;
            Object clientRecord = activities.get(token);
            if (clientRecord == null)
                return;
            Activity activity = getActivityByToken(token);

            while (activity.getParent() != null) {
                activity = activity.getParent();
            }

            int resultCode = BRActivity.get(activity).mResultCode();
            Intent resultData = BRActivity.get(activity).mResultData();
            ActivityManagerCompat.finishActivity(token, resultCode, resultData);
            BRActivity.get(activity)._set_mFinished(true);
        });
    }

    @Override
    public void handleNewIntent(final IBinder token, final Intent intent) {
        mH.post(() -> {
            Intent newIntent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                newIntent = BRReferrerIntent.get()._new(intent, BlackBoxCore.getHostPkg());
            } else {
                newIntent = intent;
            }
            Object mainThread = BlackBoxCore.mainThread();
            if (BRActivityThread.get(BlackBoxCore.mainThread())._check_performNewIntents(null, null) != null) {
                BRActivityThread.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent)
                );
            } else if (BRActivityThreadNMR1.get(mainThread)._check_performNewIntents(null, null, false) != null) {
                BRActivityThreadNMR1.get(mainThread).performNewIntents(
                        token,
                        Collections.singletonList(newIntent),
                        true);
            } else if (BRActivityThreadQ.get(mainThread)._check_handleNewIntent(null, null) != null) {
                BRActivityThreadQ.get(mainThread).handleNewIntent(token, Collections.singletonList(newIntent));
            }
        });
    }

    @Override
    public void scheduleReceiver(ReceiverData data) throws RemoteException {
        if (!isInit()) {
            bindApplication();
        }
        mH.post(() -> {
            BroadcastReceiver mReceiver = null;
            Intent intent = data.intent;
            ActivityInfo activityInfo = data.activityInfo;
            BroadcastReceiver.PendingResult pendingResult = data.data.build();

            try {
                Context baseContext = mInitialApplication.getBaseContext();
                ClassLoader classLoader = baseContext.getClassLoader();
                intent.setExtrasClassLoader(classLoader);

                mReceiver = (BroadcastReceiver) classLoader.loadClass(activityInfo.name).newInstance();
                BRBroadcastReceiver.get(mReceiver).setPendingResult(pendingResult);
                mReceiver.onReceive(baseContext, intent);
                BroadcastReceiver.PendingResult finish = BRBroadcastReceiver.get(mReceiver).getPendingResult();
                if (finish != null) {
                    finish.finish();
                }
                BlackBoxCore.getBActivityManager().finishBroadcast(data.data);
            } catch (Throwable throwable) {
                throwable.printStackTrace();
                Slog.e(TAG,
                        "Error receiving broadcast " + intent
                                + " in " + mReceiver);
            }
        });
    }

    public static Activity getActivityByToken(IBinder token) {
        Map<IBinder, Object> iBinderObjectMap =
                BRActivityThread.get(BlackBoxCore.mainThread()).mActivities();
        return BRActivityThreadActivityClientRecord.get(iBinderObjectMap.get(token)).activity();
    }

    private void onBeforeCreateApplication(String packageName, String processName, Context context) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeCreateApplication(packageName, processName, context, BActivityThread.getUserId());
        }
    }

    private void onBeforeApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.beforeApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    private void onAfterApplicationOnCreate(String packageName, String processName, Application application) {
        for (AppLifecycleCallback appLifecycleCallback : BlackBoxCore.get().getAppLifecycleCallbacks()) {
            appLifecycleCallback.afterApplicationOnCreate(packageName, processName, application, BActivityThread.getUserId());
        }
    }

    
    public static void ensureActivityContext(Activity activity) {
        if (activity == null) {
            return;
        }
        
        try {
            
            Context currentContext = activity.getBaseContext();
            if (currentContext != null) {
                Slog.d(TAG, "Activity already has context: " + activity.getClass().getName());
                return;
            }
            
            Slog.w(TAG, "Activity has null context, ensuring valid context: " + activity.getClass().getName());
            
            
            Context validContext = null;
            try {
                validContext = getApplication();
                if (validContext == null) {
                    validContext = BlackBoxCore.getContext();
                }
            } catch (Exception e) {
                Slog.w(TAG, "Could not get application context: " + e.getMessage());
                validContext = BlackBoxCore.getContext();
            }
            
            if (validContext != null) {
                
                try {
                    Context packageContext = validContext.createPackageContext(
                        activity.getPackageName(),
                        Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
                    );
                    
                    
                    java.lang.reflect.Method attachBaseContext = Activity.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attachBaseContext.setAccessible(true);
                    attachBaseContext.invoke(activity, packageContext);
                    Slog.d(TAG, "Successfully attached package context to activity: " + activity.getClass().getName());
                } catch (Exception e) {
                    Slog.w(TAG, "Could not attach base context to activity: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error ensuring activity context: " + e.getMessage());
        }
    }

    
    public static void hookActivityThread() {
        try {
            
            Object activityThread = BlackBoxCore.mainThread();
            if (activityThread != null) {
                
                Instrumentation instrumentation = BRActivityThread.get(activityThread).mInstrumentation();
                if (instrumentation != null) {
                    Slog.d(TAG, "Found ActivityThread instrumentation, ensuring it's our AppInstrumentation");
                    
                    
                    if (!(instrumentation instanceof AppInstrumentation)) {
                        Slog.w(TAG, "ActivityThread instrumentation is not our AppInstrumentation, attempting to replace");
                        
                        
                        try {
                            AppInstrumentation appInstrumentation = AppInstrumentation.get();
                            appInstrumentation.injectHook();
                            Slog.d(TAG, "Successfully replaced ActivityThread instrumentation with AppInstrumentation");
                        } catch (Exception e) {
                            Slog.w(TAG, "Could not replace ActivityThread instrumentation: " + e.getMessage());
                        }
                    } else {
                        Slog.d(TAG, "ActivityThread instrumentation is already our AppInstrumentation");
                    }
                } else {
                    Slog.w(TAG, "ActivityThread instrumentation is null");
                }
            } else {
                Slog.w(TAG, "ActivityThread is null");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Error hooking ActivityThread: " + e.getMessage());
        }
    }

    
    private Application createMinimalApplication(Context packageContext, String packageName) {
        try {
            Slog.d(TAG, "Creating minimal application for " + packageName);
            
            
            Application app = new Application() {
                @Override
                public void onCreate() {
                    super.onCreate();
                    Slog.d(TAG, "Minimal application onCreate called for " + packageName);
                }
                
                @Override
                public String getPackageName() {
                    return packageName;
                }
                
                @Override
                public Context getApplicationContext() {
                    return this;
                }
            };
            
            
            if (packageContext != null) {
                try {
                    Method attachBaseContext = Application.class.getDeclaredMethod("attachBaseContext", Context.class);
                    attachBaseContext.setAccessible(true);
                    attachBaseContext.invoke(app, packageContext);
                    Slog.d(TAG, "Successfully attached base context to minimal application for " + packageName);
                } catch (Exception e) {
                    Slog.w(TAG, "Could not attach base context to minimal application: " + e.getMessage());
                }
            } else {
                Slog.w(TAG, "Package context is null, cannot attach base context to minimal application");
            }
            
            Slog.d(TAG, "Minimal application created successfully for " + packageName);
            return app;
        } catch (Exception e) {
            Slog.e(TAG, "Error creating minimal application for " + packageName, e);
            return null;
        }
    }

    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }
}
