/* //device/java/android/android/app/IServiceConnection.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.app;

import android.content.ComponentName;
import android.app.IBinderSession;

/**
 * @hide
 *
 * 注意：Android 17 (API 37) 起新增 IBinderSession 参数：
 *   Android 16-  : connected(ComponentName, IBinder, boolean)
 *   Android 17+  : connected(ComponentName, IBinder, IBinderSession, boolean)
 * 本文件按 Android 17 定义声明；ServiceConnectionDelegate 同时保留旧签名以兼容旧系统。
 */
interface IServiceConnection {
    void connected(in ComponentName name, IBinder service, in IBinderSession session, boolean dead);
}
