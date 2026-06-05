package com.android.server.sidebar;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.sidebar.ISidebar;
import com.android.internal.sidebar.ISidebarService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Minimal system_server bridge for OneStep.
 *
 * UI, task embedding, and compatibility logic stay in the Sidebar app. The framework side only
 * owns the privileged system gesture to app Binder bridge.
 */
public final class SidebarManagerService extends ISidebarService.Stub {
    private static final String TAG = "SidebarManagerService";
    private static final String SIDEBAR_ENABLED = "side_bar_mode";
    private static final String SIDEBAR_ZOOM_TYPE = "side_bar_zoom_type";
    private static final int MODE_RIGHT = 2;

    private final Context mContext;
    private final Object mLock = new Object();
    private ISidebar mSidebar;
    private IBinder mSidebarTaskViewShell;

    public SidebarManagerService(Context context) {
        mContext = context;
    }

    @Override
    public void registerSidebar(ISidebar sidebar) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SIDEBAR_SERVICE, "registerSidebar");
        synchronized (mLock) {
            mSidebar = sidebar;
            Slog.i(TAG, "registerSidebar: " + (sidebar != null));
            if (sidebar != null) {
                try {
                    sidebar.asBinder().linkToDeath(this::clearSidebar, 0);
                } catch (RemoteException e) {
                    mSidebar = null;
                }
            }
        }
    }

    @Override
    public void registerSidebarTaskViewShell(IBinder shell) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.MANAGE_ACTIVITY_TASKS,
                "registerSidebarTaskViewShell");
        synchronized (mLock) {
            mSidebarTaskViewShell = shell;
            Slog.i(TAG, "registerSidebarTaskViewShell: " + (shell != null));
            if (shell != null) {
                try {
                    shell.linkToDeath(this::clearSidebarTaskViewShell, 0);
                } catch (RemoteException e) {
                    mSidebarTaskViewShell = null;
                }
            }
        }
    }

    @Override
    public IBinder getSidebarTaskViewShell() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SIDEBAR_SERVICE, "getSidebarTaskViewShell");
        synchronized (mLock) {
            return mSidebarTaskViewShell;
        }
    }

    @Override
    public void enterRightSidebar(int flags) {
        enterSidebar(MODE_RIGHT, flags);
    }

    @Override
    public void exitSidebar(int flags) {
        ISidebar sidebar = getSidebar();
        if (sidebar == null) {
            return;
        }
        try {
            sidebar.onExitSidebarMode(flags);
        } catch (RemoteException e) {
            Slog.w(TAG, "exitSidebar failed", e);
            clearSidebar();
        }
    }

    @Override
    public void handleSidebarShareList() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SIDEBAR_SERVICE, "handleSidebarShareList");
        ISidebar sidebar = getSidebar();
        if (sidebar == null) {
            return;
        }
        try {
            sidebar.handleSidebarShareList();
        } catch (RemoteException e) {
            Slog.w(TAG, "handleSidebarShareList failed", e);
            clearSidebar();
        }
    }

    @Override
    public void showGlobalShare(Intent intent) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SIDEBAR_SERVICE, "showGlobalShare");
        ISidebar sidebar = getSidebar();
        if (sidebar == null) {
            return;
        }
        try {
            sidebar.showGlobalShare(intent);
        } catch (RemoteException e) {
            Slog.w(TAG, "showGlobalShare failed", e);
            clearSidebar();
        }
    }

    @Override
    public boolean handleRightEdgeSwipe() {
        if (Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1) != 1) {
            Slog.d(TAG, "handleRightEdgeSwipe ignored: disabled");
            return false;
        }
        return enterSidebar(MODE_RIGHT, 1);
    }

    @Override
    public boolean handleZoomOutTopRightSwipeUp() {
        if (Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1) != 1) {
            Slog.d(TAG, "handleZoomOutTopRightSwipeUp ignored: disabled");
            return false;
        }
        if (Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ZOOM_TYPE, -1)
                != MODE_RIGHT) {
            Slog.d(TAG, "handleZoomOutTopRightSwipeUp ignored: not in right zoom");
            return false;
        }
        exitSidebar(1);
        return true;
    }

    private boolean enterSidebar(int mode, int flags) {
        ISidebar sidebar = getSidebar();
        if (sidebar == null) {
            Slog.w(TAG, "enterSidebar ignored: no registered sidebar callback");
            return false;
        }
        try {
            sidebar.onEnterSidebarMode(mode, flags);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "enterSidebar failed", e);
            clearSidebar();
            return false;
        }
    }

    private ISidebar getSidebar() {
        synchronized (mLock) {
            return mSidebar;
        }
    }

    private void clearSidebar() {
        synchronized (mLock) {
            Slog.i(TAG, "clearSidebar");
            mSidebar = null;
        }
    }

    private void clearSidebarTaskViewShell() {
        synchronized (mLock) {
            Slog.i(TAG, "clearSidebarTaskViewShell");
            mSidebarTaskViewShell = null;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump sidebar from pid=" + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }
        pw.println("SidebarManagerService:");
        pw.println("  enabled="
                + (Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1)
                        == 1));
        pw.println("  registered=" + (getSidebar() != null));
        synchronized (mLock) {
            pw.println("  shellTaskViewRegistered=" + (mSidebarTaskViewShell != null));
        }
    }

    @Override
    public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply,
            int flags) throws RemoteException {
        if (code == 11) {
            exitSidebar(1);
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
        if (code == 12) {
            enterRightSidebar(data.dataAvail() >= 4 ? data.readInt() : 1);
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }
}
