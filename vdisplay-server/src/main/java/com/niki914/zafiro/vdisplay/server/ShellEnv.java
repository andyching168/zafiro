package com.niki914.zafiro.vdisplay.server;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Instrumentation;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Looper;
import android.os.Process;
import android.system.Os;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Makes a bare {@code app_process} usable for system-service calls as the shell user: drops root to UID 2000,
 * fakes an ActivityThread and exposes a Context that claims to be {@code com.android.shell}.
 *
 * Adapted from scrcpy (Apache-2.0): server Workarounds.java and FakeContext.java. See NOTICE.
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "SoonBlockedPrivateApi", "DiscouragedPrivateApi"})
final class ShellEnv {

    static final String PACKAGE_NAME = "com.android.shell";

    private static Class<?> activityThreadClass;
    private static Object activityThread;
    private static Context context;

    private ShellEnv() {
    }

    /** Idempotent. Must run on the thread that will become the "main" looper thread. */
    static synchronized void prepare() throws Exception {
        if (context != null) {
            return;
        }
        if (Os.getuid() == 0) {
            // like scrcpy dropRootPrivileges(): the FakeContext owner "com.android.shell" must match the UID
            Os.setgid(2000);
            Os.setuid(2000);
        }
        if (Looper.getMainLooper() == null) {
            Looper.prepare();
            synchronized (Looper.class) {
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            }
        }

        activityThreadClass = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = activityThreadClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        activityThread = ctor.newInstance();
        Field current = activityThreadClass.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, activityThread);
        Field systemThread = activityThreadClass.getDeclaredField("mSystemThread");
        systemThread.setAccessible(true);
        systemThread.setBoolean(activityThread, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            fillConfigurationController();
        }
        fillAppInfo();
        context = new FakeContext(systemContext());
        fillAppContext();
    }

    static Context context() {
        if (context == null) {
            throw new IllegalStateException("ShellEnv.prepare() was not called");
        }
        return context;
    }

    private static Context systemContext() throws Exception {
        Method m = activityThreadClass.getDeclaredMethod("getSystemContext");
        return (Context) m.invoke(activityThread);
    }

    private static void fillAppInfo() {
        try {
            Class<?> appBindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Constructor<?> ctor = appBindDataClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object appBindData = ctor.newInstance();

            ApplicationInfo applicationInfo = new ApplicationInfo();
            applicationInfo.packageName = PACKAGE_NAME;

            Field appInfo = appBindDataClass.getDeclaredField("appInfo");
            appInfo.setAccessible(true);
            appInfo.set(appBindData, applicationInfo);

            Field bound = activityThreadClass.getDeclaredField("mBoundApplication");
            bound.setAccessible(true);
            bound.set(activityThread, appBindData);
        } catch (Throwable t) {
            // workaround only
        }
    }

    private static void fillAppContext() {
        try {
            Application app = Instrumentation.newApplication(Application.class, context);
            Field initial = activityThreadClass.getDeclaredField("mInitialApplication");
            initial.setAccessible(true);
            initial.set(activityThread, app);
        } catch (Throwable t) {
            // workaround only
        }
    }

    private static void fillConfigurationController() {
        try {
            Class<?> controllerClass = Class.forName("android.app.ConfigurationController");
            Class<?> internalClass = Class.forName("android.app.ActivityThreadInternal");
            Constructor<?> ctor = controllerClass.getDeclaredConstructor(internalClass);
            ctor.setAccessible(true);
            Object controller = ctor.newInstance(activityThread);
            Field field = activityThreadClass.getDeclaredField("mConfigurationController");
            field.setAccessible(true);
            field.set(activityThread, controller);
        } catch (Throwable t) {
            // workaround only
        }
    }

    private static final class FakeContext extends ContextWrapper {
        FakeContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public String getOpPackageName() {
            return PACKAGE_NAME;
        }

        @Override
        public AttributionSource getAttributionSource() {
            AttributionSource.Builder builder = new AttributionSource.Builder(Process.SHELL_UID);
            builder.setPackageName(PACKAGE_NAME);
            return builder.build();
        }

        @SuppressWarnings("unused")
        public int getDeviceId() {
            return 0;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public Context createPackageContext(String packageName, int flags) {
            return this;
        }
    }
}
