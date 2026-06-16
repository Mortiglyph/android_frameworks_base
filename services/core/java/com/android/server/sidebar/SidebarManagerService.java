package com.android.server.sidebar;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.SparseIntArray;
import android.util.SparseLongArray;
import android.util.Slog;

import com.android.internal.sidebar.ISidebar;
import com.android.internal.sidebar.ISidebarService;
import com.android.server.LocalServices;
import com.android.server.wm.ActivityTaskManagerInternal;

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
    private static final String SIDEBAR_SWITCH_STATUS = "sidebar_switch_status";
    private static final String SIDEBAR_ZOOM_TYPE = "side_bar_zoom_type";
    private static final String ACTION_BOOM_TEXT = "smartisanos.intent.action.BOOM_TEXT";
    private static final String ACTION_BOOM_IMAGE = "smartisanos.intent.action.BOOM_IMAGE";
    private static final String EXTRA_GLOBAL_TYPE = "global_type";
    private static final int GLOBAL_SHARE_TYPE_TEXT = 1;
    private static final int GLOBAL_SHARE_TYPE_IMAGE = 2;
    private static final int MODE_RIGHT = 2;
    private static final long EXTERNAL_DRAG_REQUEST_WINDOW_MS = 10_000L;
    private static final int EXTERNAL_DRAG_MAX_REQUESTS_PER_WINDOW = 4;
    private static final int EXTERNAL_SHARE_MAX_REQUESTS_PER_WINDOW = 8;
    private static final ComponentName SIDEBAR_SERVICE_COMPONENT = new ComponentName(
            "com.smartisanos.sidebar", "com.smartisanos.sidebar.SidebarService");

    private final Context mContext;
    private final Object mLock = new Object();
    private final SparseLongArray mExternalDragWindowStart = new SparseLongArray();
    private final SparseIntArray mExternalDragWindowCount = new SparseIntArray();
    private final SparseLongArray mExternalShareWindowStart = new SparseLongArray();
    private final SparseIntArray mExternalShareWindowCount = new SparseIntArray();
    private ISidebar mSidebar;
    private IBinder mSidebarTaskViewShell;
    private boolean mHasPendingEnter;
    private int mPendingEnterMode;
    private int mPendingEnterFlags;

    public SidebarManagerService(Context context) {
        mContext = context;
        registerUserUnlockedReceiver();
    }

    @Override
    public void registerSidebar(ISidebar sidebar) {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SIDEBAR_SERVICE, "registerSidebar");
        boolean dispatchPendingEnter = false;
        int pendingMode = 0;
        int pendingFlags = 0;
        synchronized (mLock) {
            mSidebar = sidebar;
            Slog.i(TAG, "registerSidebar: " + (sidebar != null));
            if (sidebar != null) {
                final IBinder sidebarBinder = sidebar.asBinder();
                try {
                    sidebarBinder.linkToDeath(() -> clearSidebar(sidebarBinder), 0);
                    if (mHasPendingEnter) {
                        dispatchPendingEnter = true;
                        pendingMode = mPendingEnterMode;
                        pendingFlags = mPendingEnterFlags;
                        clearPendingEnterLocked();
                    }
                } catch (RemoteException e) {
                    mSidebar = null;
                }
            }
        }
        if (dispatchPendingEnter) {
            notifyEnterSidebar(sidebar, pendingMode, pendingFlags);
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
                    shell.linkToDeath(() -> clearSidebarTaskViewShell(shell), 0);
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
            clearSidebar(sidebar.asBinder());
        }
    }

    @Override
    public Bundle createExternalDragFile(String displayName, String mimeType) {
        if (!isExternalDragAllowedForCaller("createExternalDragFile")
                || !consumeExternalDragRequestQuota(Binder.getCallingUid())) {
            return null;
        }
        ISidebar sidebar = getSidebar();
        if (sidebar == null) {
            return null;
        }
        try {
            return sidebar.createExternalDragFile(displayName, mimeType);
        } catch (RemoteException e) {
            Slog.w(TAG, "createExternalDragFile failed", e);
            clearSidebar(sidebar.asBinder());
            return null;
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
            clearSidebar(sidebar.asBinder());
        }
    }

    @Override
    public void showGlobalShare(Intent intent) {
        if (!hasSidebarServicePermission() && !isExternalGlobalShareAllowed(intent)) {
            Slog.w(TAG, "showGlobalShare ignored: caller has no permission");
            return;
        }
        ISidebar sidebar = getSidebar();
        if (sidebar == null) {
            return;
        }
        try {
            sidebar.showGlobalShare(intent);
        } catch (RemoteException e) {
            Slog.w(TAG, "showGlobalShare failed", e);
            clearSidebar(sidebar.asBinder());
        }
    }

    @Override
    public boolean handleRightEdgeSwipe() {
        Slog.d(TAG, "handleRightEdgeSwipe ignored: right edge is reserved for back gestures");
        return false;
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
            if (!isCurrentUserUnlockingOrUnlocked()) {
                Slog.d(TAG, "enterSidebar ignored: current user is locked");
                return false;
            }
            Slog.w(TAG, "enterSidebar pending: no registered sidebar callback");
            setPendingEnter(mode, flags);
            startSidebarServiceForCurrentUser();
            return false;
        }
        return notifyEnterSidebar(sidebar, mode, flags);
    }

    private ISidebar getSidebar() {
        synchronized (mLock) {
            return mSidebar;
        }
    }

    private void clearSidebar(IBinder sidebarBinder) {
        synchronized (mLock) {
            if (mSidebar != null && mSidebar.asBinder() != sidebarBinder) {
                return;
            }
            Slog.i(TAG, "clearSidebar");
            mSidebar = null;
        }
        if (isSidebarEnabled()) {
            startSidebarServiceForCurrentUser();
        }
    }

    private void clearSidebarTaskViewShell(IBinder shell) {
        synchronized (mLock) {
            if (mSidebarTaskViewShell != shell) {
                return;
            }
            Slog.i(TAG, "clearSidebarTaskViewShell");
            mSidebarTaskViewShell = null;
        }
    }

    private void registerUserUnlockedReceiver() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())
                        || !isSidebarEnabled()) {
                    return;
                }
                int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                        UserHandle.USER_SYSTEM);
                startSidebarService(UserHandle.of(userId));
            }
        }, filter, null /* broadcastPermission */, null /* scheduler */);
    }

    private boolean notifyEnterSidebar(ISidebar sidebar, int mode, int flags) {
        try {
            sidebar.onEnterSidebarMode(mode, flags);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "enterSidebar failed", e);
            clearSidebar(sidebar.asBinder());
            return false;
        }
    }

    private void setPendingEnter(int mode, int flags) {
        synchronized (mLock) {
            mHasPendingEnter = true;
            mPendingEnterMode = mode;
            mPendingEnterFlags = flags;
        }
    }

    private void clearPendingEnterLocked() {
        mHasPendingEnter = false;
        mPendingEnterMode = 0;
        mPendingEnterFlags = 0;
    }

    private boolean isSidebarEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1) == 1;
    }

    private boolean isExternalDragAllowed() {
        return isSidebarEnabled()
                && Settings.Global.getInt(mContext.getContentResolver(),
                        SIDEBAR_SWITCH_STATUS, 0) == 1
                && Settings.Global.getInt(mContext.getContentResolver(),
                        "sidebar_innerdrag_state", 0) == 1;
    }

    private boolean isExternalDragAllowedForCaller(String reason) {
        if (!isExternalDragAllowed()) {
            return false;
        }
        final int callingUid = Binder.getCallingUid();
        final ActivityTaskManagerInternal atm =
                LocalServices.getService(ActivityTaskManagerInternal.class);
        if (atm == null || !atm.isUidForeground(callingUid)) {
            Slog.w(TAG, reason + " ignored: caller uid is not foreground, uid=" + callingUid);
            return false;
        }
        return true;
    }

    private boolean consumeExternalDragRequestQuota(int uid) {
        return consumeExternalRequestQuota(uid, mExternalDragWindowStart, mExternalDragWindowCount,
                EXTERNAL_DRAG_MAX_REQUESTS_PER_WINDOW, "createExternalDragFile");
    }

    private boolean consumeExternalShareRequestQuota(int uid) {
        return consumeExternalRequestQuota(uid, mExternalShareWindowStart, mExternalShareWindowCount,
                EXTERNAL_SHARE_MAX_REQUESTS_PER_WINDOW, "showGlobalShare");
    }

    private boolean consumeExternalRequestQuota(int uid, SparseLongArray windowStart,
            SparseIntArray windowCount, int maxRequests, String reason) {
        final long now = SystemClock.uptimeMillis();
        synchronized (mLock) {
            final int index = windowStart.indexOfKey(uid);
            if (index < 0
                    || now - windowStart.valueAt(index) > EXTERNAL_DRAG_REQUEST_WINDOW_MS) {
                windowStart.put(uid, now);
                windowCount.put(uid, 1);
                return true;
            }
            final int count = windowCount.get(uid, 0);
            if (count >= maxRequests) {
                Slog.w(TAG, reason + " ignored: rate limited uid=" + uid);
                return false;
            }
            windowCount.put(uid, count + 1);
            return true;
        }
    }

    private boolean hasSidebarServicePermission() {
        return mContext.checkCallingOrSelfPermission(android.Manifest.permission.SIDEBAR_SERVICE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isExternalGlobalShareAllowed(Intent intent) {
        if (!isExternalDragAllowedForCaller("showGlobalShare") || intent == null) {
            return false;
        }
        final String action = intent.getAction();
        if (!ACTION_BOOM_TEXT.equals(action) && !ACTION_BOOM_IMAGE.equals(action)) {
            return false;
        }
        final int globalType = intent.getIntExtra(EXTRA_GLOBAL_TYPE, 0);
        return (globalType == GLOBAL_SHARE_TYPE_TEXT || globalType == GLOBAL_SHARE_TYPE_IMAGE)
                && consumeExternalShareRequestQuota(Binder.getCallingUid());
    }

    private void startSidebarServiceForCurrentUser() {
        final int userId = ActivityManager.getCurrentUser();
        if (!isUserUnlockingOrUnlocked(userId)) {
            Slog.d(TAG, "startSidebarService ignored: current user is locked");
            return;
        }
        startSidebarService(UserHandle.of(userId));
    }

    private void startSidebarService(UserHandle user) {
        Intent intent = new Intent();
        intent.setComponent(SIDEBAR_SERVICE_COMPONENT);
        try {
            resetSidebarUiState();
            mContext.startServiceAsUser(intent, user);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to start SidebarService", e);
        }
    }

    private void resetSidebarUiState() {
        Settings.Global.putInt(mContext.getContentResolver(), SIDEBAR_SWITCH_STATUS, 0);
        Settings.Global.putInt(mContext.getContentResolver(), SIDEBAR_ZOOM_TYPE, -1);
    }

    private boolean isCurrentUserUnlockingOrUnlocked() {
        return isUserUnlockingOrUnlocked(ActivityManager.getCurrentUser());
    }

    private boolean isUserUnlockingOrUnlocked(int userId) {
        UserManager userManager = UserManager.get(mContext);
        return userManager != null && userManager.isUserUnlockingOrUnlocked(userId);
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
            pw.println("  pendingEnter=" + mHasPendingEnter);
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
