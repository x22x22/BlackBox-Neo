package top.niunaijun.blackbox.fake.frameworks;

import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.location.IBLocationManagerService;
import top.niunaijun.blackbox.entity.location.BCell;
import top.niunaijun.blackbox.entity.location.BLocation;


public class BLocationManager extends BlackManager<IBLocationManagerService> {
    private static final BLocationManager sLocationManager = new BLocationManager();

    public static final int CLOSE_MODE = 0;
    public static final int GLOBAL_MODE = 1;
    public static final int OWN_MODE = 2;

    public static BLocationManager get() {
        return sLocationManager;
    }

    @Override
    protected String getServiceName() {
        return ServiceManager.LOCATION_MANAGER;
    }

    /** 服务调用动作。 */
    private interface ServiceCall<T> {
        T invoke(IBLocationManagerService service) throws Throwable;
    }

    /**
     * Android 17 适配：统一的服务获取入口。
     *
     * BlackManager.getService() 在服务创建失败 / 重试限流时会返回 null，
     * 而本类原先直接写 getService().xxx()，只 catch RemoteException，
     * 导致 NullPointerException 冒泡到调用方。
     * 实际后果：虚拟定位界面 FakeLocationRepository.getInstalledAppList()
     * 中途抛出 NPE，整个列表加载中断，界面显示"空空如也"。
     *
     * 这里改为：服务未就绪时做有界等待 + binder 存活检测。
     * 实测日志中会出现"BlackManager: Creating service for: ...IBLocationManagerService$Stub"
     * 在多个线程中并发打印（服务端会创建出两个实例，先注册的那个 binder 随即失效），
     * 此时 getService() 返回的对象虽然非 null，但实际 transact 会抛
     * DeadObjectException（"Transaction failed on small parcel; remote process
     * probably died"）。因此这里必须用 pingBinder() 探活，而不仅仅判 null。
     */
    private IBLocationManagerService requireService() {
        IBLocationManagerService service = null;
        for (int i = 0; i < 10; i++) {
            service = getService();
            if (service != null) {
                try {
                    if (service.asBinder().pingBinder() && service.asBinder().isBinderAlive()) {
                        return service;
                    }
                } catch (Throwable ignored) {
                    // binder 尚未就绪，继续等待
                }
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
            }
        }
        if (service == null) {
            throw new IllegalStateException("location_manager service is not ready");
        }
        return service;
    }

    /**
     * 带自愈重试的服务调用。
     *
     * 当 binder 已失效（服务端重建实例 / :black 进程抖动）时，
     * 清掉 BlackManager 里缓存的死引用后重试，避免一次性失败
     * 直接导致"设置虚拟定位无效 + 列表加载中断"。
     */
    private <T> T call(ServiceCall<T> action, T fallback) {
        Throwable last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return action.invoke(requireService());
            } catch (Throwable e) {
                last = e;
                clearServiceCache();
                try {
                    Thread.sleep(150L * (attempt + 1));
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (last != null) {
            last.printStackTrace();
        }
        return fallback;
    }

    public static boolean isFakeLocationEnable() {
        return get().getPattern(BActivityThread.getUserId(), BActivityThread.getAppPackageName()) != CLOSE_MODE;
    }

    public static void disableFakeLocation(int userId, String pkg) {
        get().setPattern(userId, pkg, CLOSE_MODE);
    }

    public void setPattern(final int userId, final String pkg, final int pattern) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setPattern(userId, pkg, pattern);
                return null;
            }
        }, null);
    }

    public int getPattern(final int userId, final String pkg) {
        return call(new ServiceCall<Integer>() {
            @Override
            public Integer invoke(IBLocationManagerService service) throws Throwable {
                return service.getPattern(userId, pkg);
            }
        }, CLOSE_MODE);
    }

    public void setCell(final int userId, final String pkg, final BCell cell) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setCell(userId, pkg, cell);
                return null;
            }
        }, null);
    }

    public void setAllCell(final int userId, final String pkg, final List<BCell> cells) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setAllCell(userId, pkg, cells);
                return null;
            }
        }, null);
    }

    public List<BCell> getNeighboringCell(final int userId, final String pkg) {
        return call(new ServiceCall<List<BCell>>() {
            @Override
            public List<BCell> invoke(IBLocationManagerService service) throws Throwable {
                return service.getNeighboringCell(userId, pkg);
            }
        }, null);
    }

    public List<BCell> getGlobalNeighboringCell() {
        return call(new ServiceCall<List<BCell>>() {
            @Override
            public List<BCell> invoke(IBLocationManagerService service) throws Throwable {
                return service.getGlobalNeighboringCell();
            }
        }, null);
    }

    public void setNeighboringCell(final int userId, final String pkg, final List<BCell> cells) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setNeighboringCell(userId, pkg, cells);
                return null;
            }
        }, null);
    }

    public void setGlobalCell(final BCell cell) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setGlobalCell(cell);
                return null;
            }
        }, null);
    }

    public void setGlobalAllCell(final List<BCell> cells) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setGlobalAllCell(cells);
                return null;
            }
        }, null);
    }

    public void setGlobalNeighboringCell(final List<BCell> cells) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setGlobalNeighboringCell(cells);
                return null;
            }
        }, null);
    }

    public BCell getCell(final int userId, final String pkg) {
        return call(new ServiceCall<BCell>() {
            @Override
            public BCell invoke(IBLocationManagerService service) throws Throwable {
                return service.getCell(userId, pkg);
            }
        }, null);
    }

    public List<BCell> getAllCell(final int userId, final String pkg) {
        return call(new ServiceCall<List<BCell>>() {
            @Override
            public List<BCell> invoke(IBLocationManagerService service) throws Throwable {
                return service.getAllCell(userId, pkg);
            }
        }, new ArrayList<BCell>());
    }

    public void setLocation(final int userId, final String pkg, final BLocation location) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setLocation(userId, pkg, location);
                return null;
            }
        }, null);
    }

    public BLocation getLocation(final int userId, final String pkg) {
        return call(new ServiceCall<BLocation>() {
            @Override
            public BLocation invoke(IBLocationManagerService service) throws Throwable {
                return service.getLocation(userId, pkg);
            }
        }, null);
    }

    public void setGlobalLocation(final BLocation location) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.setGlobalLocation(location);
                return null;
            }
        }, null);
    }

    public BLocation getGlobalLocation() {
        return call(new ServiceCall<BLocation>() {
            @Override
            public BLocation invoke(IBLocationManagerService service) throws Throwable {
                return service.getGlobalLocation();
            }
        }, null);
    }

    public void requestLocationUpdates(final IBinder listener) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.requestLocationUpdates(listener, BActivityThread.getAppPackageName(), BActivityThread.getUserId());
                return null;
            }
        }, null);
    }

    public void removeUpdates(final IBinder listener) {
        call(new ServiceCall<Void>() {
            @Override
            public Void invoke(IBLocationManagerService service) throws Throwable {
                service.removeUpdates(listener);
                return null;
            }
        }, null);
    }
}
