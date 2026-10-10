package top.niunaijun.blackbox.utils.compat;


import android.app.Activity;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.WindowManager;

import black.android.app.BRActivity;
import black.com.android.internal.BRRstyleable;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.utils.DrawableUtils;


public class ActivityCompat {

    /**
     * Android 17 (API 37) 适配：让沙箱内应用窗口与真机行为一致。
     *
     * 背景：Android 15 (API 35) 起，系统按应用的 targetSdk 强制 edge-to-edge。
     * 宿主 BlackBox 的 targetSdk 为 28，因此宿主进程不受该强制；
     * 而沙箱内应用运行在宿主进程/宿主窗口配置下，导致其窗口仍按"非 edge-to-edge"
     * 布局——系统栏占据空间，DisplayMetrics.heightPixels 比真机少 162px
     * （实测：真机 2376 / 沙箱 2214），构成设备指纹不一致。
     *
     * 修复：对 targetSdk >= 35 的子应用主动开启 edge-to-edge，
     * 使其 DisplayMetrics 与真机上（被系统强制 edge-to-edge）保持一致。
     */
    private static void applyEdgeToEdge(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        if (isHostUiActivity(activity)) {
            // 宿主自身的 BlackBox 管理界面必须保持常规窗口布局。
            // 否则内容顶到状态栏下方，顶部按钮（返回/菜单/过滤）会落进系统
            // 状态栏的触摸区域被优先消费，人工根本点不到。
            return;
        }
        try {
            activity.getWindow().setDecorFitsSystemWindows(false);
            alignDisplayMetrics(activity);
        } catch (Throwable t) {
            android.util.Log.e("BB-DisplayFix", "error", t);
        }
    }

    /**
     * 判断是否为宿主自身的 UI（BlackBox 管理界面，包名 top.niunaijun.blackboxa）。
     *
     * 这类界面**不能**套用为沙箱子应用准备的显示适配：
     *  1) edge-to-edge 会让内容顶进状态栏，按钮被系统状态栏吃掉触摸；
     *  2) 改写 DisplayMetrics 为整屏高度后，按 heightPixels 排版的宿主界面
     *     会把底部内容推出可视区域。
     * 沙箱子应用的 Activity 返回的是它自己的包名，因此不受影响。
     */
    private static boolean isHostUiActivity(Activity activity) {
        try {
            String pkg = activity.getPackageName();
            if (pkg == null) {
                return false;
            }
            return pkg.equals(BlackBoxCore.getHostPkg());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把 Resources 的 DisplayMetrics 对齐到"真实屏幕尺寸"。
     *
     * 背景：Android 15 (API 35) 起，Display.getMetrics() 与 Resources.getDisplayMetrics()
     * 返回的是"应用可用区域"还是"整屏"，取决于**进程的 targetSdk**：
     *   - targetSdk >= 35：系统强制 edge-to-edge，返回整屏；
     *   - targetSdk <  35：返回扣除状态栏+导航栏的可用区域。
     *
     * 宿主 BlackBox 的 targetSdk 为 28（这是原作者为避免 Android 14+ 各类新限制而刻意保留的），
     * 因此整个沙箱进程拿到的是 2214 而非整屏 2376，子应用继承后与真机不一致，
     * 构成设备指纹差异（screen.heightPixels）。
     *
     * 这里在 Activity 创建时用 getRealMetrics() 的结果覆盖 DisplayMetrics，
     * 使其与真机（targetSdk 35 应用）一致。
     */
    private static void alignDisplayMetrics(Activity activity) {
        android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        android.util.DisplayMetrics real = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getRealMetrics(real);
        if (dm.widthPixels != real.widthPixels || dm.heightPixels != real.heightPixels) {
            int oldW = dm.widthPixels, oldH = dm.heightPixels;
            dm.widthPixels = real.widthPixels;
            dm.heightPixels = real.heightPixels;
            android.util.Log.i("BB-DisplayFix", "aligned " + oldW + "x" + oldH
                    + " -> " + real.widthPixels + "x" + real.heightPixels
                    + " (" + activity.getClass().getSimpleName() + ")");
        } else {
            android.util.Log.i("BB-DisplayFix", "already " + dm.widthPixels + "x" + dm.heightPixels
                    + " (" + activity.getClass().getSimpleName() + ")");
        }
    }

    public static void fix(Activity activity) {
        
        BRActivity.get(activity).mActivityInfo();

        applyEdgeToEdge(activity);

        Context baseContext = activity.getBaseContext();
        try {
            TypedArray typedArray = activity.obtainStyledAttributes((BRRstyleable.get().Window()));
            if (typedArray != null) {
                boolean showWallpaper = typedArray.getBoolean(BRRstyleable.get().Window_windowShowWallpaper(),
                        false);
                if (showWallpaper) {
                    activity.getWindow().setBackgroundDrawable(WallpaperManager.getInstance(activity).getDrawable());
                }
                boolean fullscreen = typedArray.getBoolean(BRRstyleable.get().Window_windowFullscreen(), false);
                if (fullscreen) {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                }
                typedArray.recycle();
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = activity.getIntent();
            ApplicationInfo applicationInfo = baseContext.getApplicationInfo();
            PackageManager pm = activity.getPackageManager();
            if (intent != null && activity.isTaskRoot()) {
                try {
                    String label = TaskDescriptionCompat.getTaskDescriptionLabel(
                            BlackBoxCore.getUserId(), applicationInfo.loadLabel(pm));

                    Bitmap icon = null;
                    Drawable drawable = getActivityIcon(activity);
                    if (drawable != null) {
                        ActivityManager am = (ActivityManager) baseContext.getSystemService(Context.ACTIVITY_SERVICE);
                        int iconSize = am.getLauncherLargeIconSize();
                        icon = DrawableUtils.drawableToBitmap(drawable, iconSize, iconSize);
                    }

                    activity.setTaskDescription(new ActivityManager.TaskDescription(label, icon));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private static Drawable getActivityIcon(Activity activity) {
        PackageManager pm = activity.getPackageManager();
        try {
            Drawable icon = pm.getActivityIcon(activity.getComponentName());
            if (icon != null)
                return icon;
        } catch (PackageManager.NameNotFoundException ignore) {
        }

        ApplicationInfo applicationInfo = activity.getApplicationInfo();
        return applicationInfo.loadIcon(pm);
    }
}