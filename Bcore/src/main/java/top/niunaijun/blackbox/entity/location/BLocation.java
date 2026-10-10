
package top.niunaijun.blackbox.entity.location;

import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;


public class BLocation implements Parcelable {

    private double mLatitude = 0.0;
    private double mLongitude = 0.0;
    private double mAltitude = 0.0f;
    private float mSpeed = 0.0f;
    private float mBearing = 0.0f;
    private float mAccuracy = 0.0f;





    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeDouble(this.mLatitude);
        dest.writeDouble(this.mLongitude);
        dest.writeDouble(this.mAltitude);
        dest.writeFloat(this.mSpeed);
        dest.writeFloat(this.mBearing);
        dest.writeFloat(this.mAccuracy);
    }

    public double getLatitude() {
        return mLatitude;
    }

    public double getLongitude() {
        return mLongitude;
    }

    public BLocation() {
    }

    public BLocation(double latitude, double mLongitude) {
        this.mLatitude = latitude;
        this.mLongitude = mLongitude;
    }

    public BLocation(Parcel in) {
        this.mLatitude = in.readDouble();
        this.mLongitude = in.readDouble();
        this.mAltitude = in.readDouble();
        // 读取顺序必须与 writeToParcel 完全一致：speed → bearing → accuracy。
        // 原实现读成 accuracy/speed/bearing，导致三个字段整体错位。
        this.mSpeed = in.readFloat();
        this.mBearing = in.readFloat();
        this.mAccuracy = in.readFloat();
    }

    public boolean isEmpty() {
        return mLatitude == 0 && mLongitude == 0;
    }

    public static final Parcelable.Creator<BLocation> CREATOR = new Parcelable.Creator<BLocation>() {
        @Override
        public BLocation createFromParcel(Parcel source) {
            return new BLocation(source);
        }

        @Override
        public BLocation[] newArray(int size) {
            return new BLocation[size];
        }
    };

    @Override
    public String toString() {
        return "BLocation{" +
                "latitude: " + mLatitude +
                ", longitude: " + mLongitude +
                ", altitude: " + mAltitude +
                ", speed: " + mSpeed +
                ", bearing: " + mBearing +
                ", accuracy: " + mAccuracy +
                '}';
    }

    public Location convert2SystemLocation() {
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setLatitude(mLatitude);
        location.setLongitude(mLongitude);
        location.setAltitude(mAltitude);
        location.setSpeed(mSpeed);
        location.setBearing(mBearing);
        // Android 17 适配：精度做高（5m）。腾讯定位 SDK 会按精度做多源融合，
        // 精度差时它会更多采信网络定位（出口 IP，城市级），表现为位置在两个区域
        // 之间「漂移」；精度好时它才稳定采用 GPS 链路（即虚拟坐标）。
        location.setAccuracy(mAccuracy > 0f ? mAccuracy : 5f);
        location.setTime(System.currentTimeMillis());
        // Android 17 适配（关键）：必须补齐 elapsedRealtimeNanos。
        // Android 的 Location 若 elapsedRealtimeNanos 为 0，会被判定为「没有时间戳」，
        // 高德/百度等使用自家融合定位 SDK 的 App 会丢弃该位置不用，
        // 表现为「系统 provider 已返回虚拟坐标、地图界面却仍显示真实位置」。
        location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        // Android 8+ 的精度元数据：缺失时部分定位 SDK 认为该位置不完整。
        location.setVerticalAccuracyMeters(10f);
        location.setSpeedAccuracyMetersPerSecond(1f);
        location.setBearingAccuracyDegrees(1f);
        Bundle extraBundle = new Bundle();
        
        // 卫星数同样影响融合权重：报一个足够好的 GPS 状态，
        // 避免 SDK 认为「GPS 不可用」而退回网络定位。
        int satelliteCount = 18;
        extraBundle.putInt("satellites", satelliteCount);
        extraBundle.putInt("satellitesvalue", satelliteCount);
        extraBundle.putInt("satellitesusedinlastfix", 18);
        extraBundle.putFloat("meanCn0", 38.0f);
        extraBundle.putFloat("maxCn0", 44.0f);
        location.setExtras(extraBundle);
        return location;
    }
}
