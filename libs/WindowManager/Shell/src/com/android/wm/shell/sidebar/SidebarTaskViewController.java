/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.sidebar;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.appzoomout.AppZoomOut;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.io.PrintWriter;
import java.util.List;
import java.util.Optional;

/**
 * WMShell owner for OneStep sidebar task slots.
 *
 * OneStep supplies SurfaceView surfaces for the three side slots. This controller keeps task
 * organizing inside ShellTaskOrganizer, reparents task leashes under those slot surfaces, and uses
 * WCT only for task hierarchy moves.
 */
public final class SidebarTaskViewController implements RemoteCallable<SidebarTaskViewController>,
        Transitions.TransitionHandler {
    private static final String TAG = "SidebarTaskView";
    private static final int SLOT_COUNT = 3;
    private static final long[] REPARENT_RETRY_DELAYS_MS = {80L, 250L, 600L};
    private static final long[] FULLSCREEN_RESET_RETRY_DELAYS_MS = {80L, 250L, 600L};
    private static final long NO_OP_HOME_TRANSITION_TIMEOUT_MS = 1200L;
    private static final long TASK_LEASH_ANIMATION_DURATION_MS = 180L;
    private static final long TASK_LEASH_ANIMATION_FRAME_MS = 16L;
    private static final float SLOT_ENTER_START_SCALE = 0.92f;
    private static final float FULLSCREEN_RESTORE_START_ALPHA = 1f;

    private final Context mContext;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final ShellController mShellController;
    private final ShellCommandHandler mShellCommandHandler;
    private final ShellExecutor mMainExecutor;
    private final Optional<AppZoomOut> mAppZoomOut;
    private final Transitions mTransitions;
    private final SparseArray<SlotRecord> mSlots = new SparseArray<>();
    private final SparseArray<Integer> mTaskToSlot = new SparseArray<>();
    private final SparseArray<SurfaceControl> mTaskLeashes = new SparseArray<>();
    private final ArraySet<IBinder> mNoOpHomeTransitions = new ArraySet<>();
    private boolean mConsumeNextHomeTransition;
    private boolean mHasLoggedSidebarZoomState;
    private boolean mLastLoggedSidebarZoomEnabled;
    private int mHomeTransitionGeneration;

    private final ShellCommandHandler.ShellCommandActionHandler mDumpCommandHandler =
            new ShellCommandHandler.ShellCommandActionHandler() {
                @Override
                public boolean onShellCommand(String[] args, PrintWriter pw) {
                    dump(pw, "");
                    return true;
                }

                @Override
                public void printShellCommandHelp(PrintWriter pw, String prefix) {
                    pw.println(prefix + "Dump OneStep sidebar task view state");
                }
            };

    public SidebarTaskViewController(Context context, ShellInit shellInit,
            ShellController shellController, ShellCommandHandler shellCommandHandler,
            ShellTaskOrganizer taskOrganizer, ShellExecutor mainExecutor,
            Optional<AppZoomOut> appZoomOut, Transitions transitions) {
        mContext = context;
        mShellController = shellController;
        mShellCommandHandler = shellCommandHandler;
        mTaskOrganizer = taskOrganizer;
        mMainExecutor = mainExecutor;
        mAppZoomOut = appZoomOut;
        mTransitions = transitions;
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        mShellCommandHandler.addCommandCallback("sidebar-taskview", mDumpCommandHandler, this);
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mShellController.addExternalInterface(ISidebarTaskView.DESCRIPTOR,
                this::createExternalInterface, this);
        mTransitions.addHandler(this);
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new ISidebarTaskViewImpl(this);
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    public void setSlotSurface(int slotIndex, SurfaceControl surface, Rect bounds, int displayId) {
        if (!isValidSlot(slotIndex)) {
            releaseSurface(surface);
            Slog.w(TAG, "setSlotSurface: invalid slot=" + slotIndex);
            return;
        }
        final SlotRecord slot = getOrCreateSlot(slotIndex);
        if (slot.mSurface != surface) {
            releaseSurface(slot.mSurface);
            slot.mSurface = surface;
            if (slot.mSurface != null && slot.mSurface.isValid()) {
                final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                t.unsetColor(slot.mSurface);
                t.setOpaque(slot.mSurface, false);
                t.setAlpha(slot.mSurface, 1f);
                t.apply();
            }
        }
        slot.mBounds.set(bounds != null ? bounds : new Rect());
        slot.mDisplayId = displayId;
        Slog.i(TAG, "setSlotSurface slot=" + slotIndex + " displayId=" + displayId
                + " bounds=" + slot.mBounds + " surface=" + surface);
        reparentSlotLeash(slot, false /* animate */);
        scheduleReparentSlotLeash(slot);
    }

    public void clearSlotSurface(int slotIndex) {
        final SlotRecord slot = mSlots.get(slotIndex);
        if (slot == null) return;
        Slog.i(TAG, "clearSlotSurface slot=" + slotIndex);
        releaseSurface(slot.mSurface);
        slot.mSurface = null;
    }

    public void moveTaskToSlot(int taskId, int slotIndex) {
        if (taskId <= 0 || !isValidSlot(slotIndex)) {
            Slog.w(TAG, "moveTaskToSlot invalid taskId=" + taskId + " slot=" + slotIndex);
            return;
        }
        final SlotRecord slot = getOrCreateSlot(slotIndex);
        final ActivityManager.RunningTaskInfo taskInfo = findTaskInfo(taskId);
        if (taskInfo == null || taskInfo.token == null) {
            if (slot.mNoOpHomeTransitionTaskId == taskId) {
                slot.mNoOpHomeTransitionTaskId = -1;
            }
            Slog.w(TAG, "moveTaskToSlot: task not found taskId=" + taskId);
            return;
        }

        SlotRecord oldSlotToEmpty = null;
        final Integer oldSlotIndex = mTaskToSlot.get(taskId);
        final boolean slotWasEmpty = slot.mTaskId <= 0 && slot.mTaskToken == null;
        final boolean shouldNoOpNextHomeTransition = slotWasEmpty && oldSlotIndex == null;
        ensureRootTask(slot);
        if (slot.mRootToken == null) {
            slot.mPendingTaskId = taskId;
            if (shouldNoOpNextHomeTransition && slot.mNoOpHomeTransitionTaskId != taskId) {
                markNextHomeTransitionNoOp();
                slot.mNoOpHomeTransitionTaskId = taskId;
            }
            Slog.i(TAG, "moveTaskToSlot pending root slot=" + slotIndex + " taskId=" + taskId);
            return;
        }
        if (oldSlotIndex != null && oldSlotIndex != slotIndex) {
            final SlotRecord oldSlot = mSlots.get(oldSlotIndex);
            if (oldSlot != null && oldSlot.mTaskId == taskId) {
                if (oldSlot.mTaskLeash != null && oldSlot.mTaskLeash.isValid()) {
                    mTaskLeashes.put(taskId, oldSlot.mTaskLeash);
                }
                mTaskOrganizer.removeListener(oldSlot.mTaskListener);
                oldSlot.mAnimationGeneration++;
                oldSlot.mAnimatingLeash = false;
                oldSlot.mTaskId = -1;
                oldSlot.mTaskToken = null;
                oldSlot.mTaskLeash = null;
                oldSlotToEmpty = oldSlot;
            }
        }

        slot.mTaskId = taskId;
        slot.mTaskToken = taskInfo.token;
        slot.mPendingTaskId = -1;
        mTaskToSlot.put(taskId, slotIndex);
        try {
            mTaskOrganizer.addListenerForTaskId(slot.mTaskListener, taskId);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "moveTaskToSlot: listener already owned for taskId=" + taskId, e);
        }

        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setFocusable(slot.mRootToken, false /* focusable */);
        wct.setHidden(slot.mRootToken, false /* hidden */);
        wct.setAlwaysOnTop(slot.mRootToken, true /* alwaysOnTop */);
        wct.reorder(slot.mRootToken, true /* onTop */);
        wct.reparent(taskInfo.token, slot.mRootToken, true /* onTop */);
        wct.setFocusable(taskInfo.token, false /* focusable */);
        wct.setBounds(taskInfo.token, getFullscreenBounds());
        wct.setHidden(taskInfo.token, false /* hidden */);
        wct.reorder(taskInfo.token, true /* onTop */);
        if (oldSlotToEmpty != null && oldSlotToEmpty.mRootToken != null) {
            wct.setFocusable(oldSlotToEmpty.mRootToken, false /* focusable */);
            wct.setAlwaysOnTop(oldSlotToEmpty.mRootToken, false /* alwaysOnTop */);
            wct.setHidden(oldSlotToEmpty.mRootToken, true /* hidden */);
            wct.reorder(oldSlotToEmpty.mRootToken, false /* onTop */);
        }
        keepOccupiedSlotsVisible(wct, null /* excludedSlot */);
        mTaskOrganizer.applyTransaction(wct);
        keepOccupiedSlotsFromAffectingSystemUi(null /* excludedSlot */);
        Slog.i(TAG, "moveTaskToSlot taskId=" + taskId + " -> slot=" + slotIndex);
        if (shouldNoOpNextHomeTransition && slot.mNoOpHomeTransitionTaskId != taskId) {
            markNextHomeTransitionNoOp();
        }
        slot.mNoOpHomeTransitionTaskId = -1;

        final SurfaceControl knownLeash = getCachedTaskLeash(taskId);
        if (knownLeash != null && knownLeash.isValid()) {
            slot.mTaskLeash = knownLeash;
            reparentSlotLeash(slot, true /* animate */);
            scheduleReparentSlotLeash(slot);
        }
    }

    public void releaseTaskFromSlot(int taskId) {
        releaseTaskFromSlot(taskId, true /* toTop */);
    }

    private void releaseTaskFromSlot(int taskId, boolean toTop) {
        final Integer slotIndex = mTaskToSlot.get(taskId);
        if (slotIndex == null) {
            Slog.w(TAG, "releaseTaskFromSlot: task not tracked taskId=" + taskId);
            return;
        }
        final SlotRecord slot = mSlots.get(slotIndex);
        if (slot == null || slot.mTaskToken == null) {
            mTaskToSlot.remove(taskId);
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.reparent(slot.mTaskToken, null /* parent */, toTop);
        wct.setFocusable(slot.mTaskToken, true /* focusable */);
        wct.setBounds(slot.mTaskToken, null);
        wct.setHidden(slot.mTaskToken, false /* hidden */);
        wct.reorder(slot.mTaskToken, toTop /* onTop */, true /* includingParents */);
        if (slot.mRootToken != null) {
            wct.setFocusable(slot.mRootToken, false /* focusable */);
            wct.setAlwaysOnTop(slot.mRootToken, false /* alwaysOnTop */);
            wct.setHidden(slot.mRootToken, true /* hidden */);
            wct.reorder(slot.mRootToken, false /* onTop */);
        }
        keepOccupiedSlotsVisible(wct, slot /* excludedSlot */);
        final SurfaceControl releasedLeash = resetSlotTaskLeash(slot, taskId);
        mTaskOrganizer.applyTransaction(wct);
        finishFullscreenRestoreAnimation(releasedLeash);
        scheduleFullscreenRestoreReset(taskId, releasedLeash);
        setCanAffectSystemUiFlags(slot.mTaskToken, true /* canAffectSystemUiFlags */);
        keepOccupiedSlotsFromAffectingSystemUi(slot /* excludedSlot */);
        clearTaskFromSlot(slot, false /* releaseLeash */, true /* keepLeashCache */);
        Slog.i(TAG, "releaseTaskFromSlot taskId=" + taskId + " toTop=" + toTop);
    }

    @Override
    public WindowContainerTransaction handleRequest(IBinder transition,
            TransitionRequestInfo request) {
        if (!mConsumeNextHomeTransition || request == null) {
            return null;
        }
        final ActivityManager.RunningTaskInfo triggerTask = request.getTriggerTask();
        final int type = request.getType();
        if ((type != TRANSIT_OPEN && type != TRANSIT_TO_FRONT)
                || triggerTask == null
                || triggerTask.getActivityType() != ACTIVITY_TYPE_HOME) {
            return null;
        }
        mConsumeNextHomeTransition = false;
        mNoOpHomeTransitions.add(transition);
        Slog.i(TAG, "noOpHomeTransition request taskId=" + triggerTask.taskId
                + " type=" + type);
        return new WindowContainerTransaction();
    }

    @Override
    public boolean startAnimation(IBinder transition, TransitionInfo info,
            SurfaceControl.Transaction startTransaction,
            SurfaceControl.Transaction finishTransaction,
            Transitions.TransitionFinishCallback finishCallback) {
        if (!mNoOpHomeTransitions.remove(transition)) {
            return false;
        }
        Slog.i(TAG, "noOpHomeTransition start debugId="
                + (info != null ? info.getDebugId() : -1));
        startTransaction.apply();
        finishCallback.onTransitionFinished(null /* wct */);
        return true;
    }

    @Override
    public void onTransitionConsumed(IBinder transition, boolean aborted,
            SurfaceControl.Transaction finishTransaction) {
        mNoOpHomeTransitions.remove(transition);
    }

    public void setSidebarZoom(float scaleX, float scaleY, float offsetX, float offsetY,
            boolean enabled) {
        if (mAppZoomOut.isEmpty()) {
            Slog.w(TAG, "setSidebarZoom ignored: AppZoomOut unavailable enabled=" + enabled);
            return;
        }
        final float safeScaleX = sanitizeScale(scaleX);
        final float safeScaleY = sanitizeScale(scaleY);
        mAppZoomOut.get().setSidebarTransform(safeScaleX, safeScaleY, offsetX, offsetY,
                enabled);
        if (!mHasLoggedSidebarZoomState || mLastLoggedSidebarZoomEnabled != enabled) {
            mHasLoggedSidebarZoomState = true;
            mLastLoggedSidebarZoomEnabled = enabled;
            Slog.i(TAG, "setSidebarZoom enabled=" + enabled + " scale=" + safeScaleX + "x"
                    + safeScaleY + " offset=" + offsetX + "," + offsetY);
        }
    }

    public void releaseAllTasksFromSlots() {
        releaseAllTasksFromSlots(true /* toTop */);
    }

    public void releaseAllTasksFromSlotsToBack() {
        releaseAllTasksFromSlots(false /* toTop */);
    }

    private void releaseAllTasksFromSlots(boolean toTop) {
        final int[] taskIds = new int[mTaskToSlot.size()];
        for (int i = 0; i < mTaskToSlot.size(); i++) {
            taskIds[i] = mTaskToSlot.keyAt(i);
        }
        for (int taskId : taskIds) {
            releaseTaskFromSlot(taskId, toTop);
        }
    }

    private void markNextHomeTransitionNoOp() {
        final int generation = ++mHomeTransitionGeneration;
        mConsumeNextHomeTransition = true;
        mMainExecutor.executeDelayed(() -> {
            if (mHomeTransitionGeneration == generation) {
                mConsumeNextHomeTransition = false;
            }
        }, NO_OP_HOME_TRANSITION_TIMEOUT_MS);
        Slog.i(TAG, "markNextHomeTransitionNoOp generation=" + generation);
    }

    private SlotRecord getOrCreateSlot(int slotIndex) {
        SlotRecord slot = mSlots.get(slotIndex);
        if (slot == null) {
            slot = new SlotRecord(slotIndex);
            mSlots.put(slotIndex, slot);
        }
        return slot;
    }

    private void ensureRootTask(SlotRecord slot) {
        if (slot.mRootToken != null || slot.mCreatingRoot) return;
        slot.mCreatingRoot = true;
        mTaskOrganizer.createRootTask(slot.mDisplayId, WINDOWING_MODE_MULTI_WINDOW,
                slot.mRootListener, true /* removeWithTaskOrganizer */);
        Slog.i(TAG, "ensureRootTask slot=" + slot.mSlotIndex + " displayId=" + slot.mDisplayId);
    }

    private ActivityManager.RunningTaskInfo findTaskInfo(int taskId) {
        try {
            for (int i = 0; i < mSlots.size(); i++) {
                final SlotRecord slot = mSlots.valueAt(i);
                final List<ActivityManager.RunningTaskInfo> rootTasks =
                        mTaskOrganizer.getRootTasks(slot.mDisplayId, null);
                if (rootTasks == null) continue;
                for (ActivityManager.RunningTaskInfo root : rootTasks) {
                    if (root.taskId == taskId) return root;
                    final ActivityManager.RunningTaskInfo child =
                            findChildTask(root.token, taskId);
                    if (child != null) return child;
                }
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "findTaskInfo failed taskId=" + taskId, e);
        }
        return null;
    }

    private ActivityManager.RunningTaskInfo findChildTask(WindowContainerToken rootToken,
            int taskId) {
        if (rootToken == null) return null;
        try {
            final List<ActivityManager.RunningTaskInfo> children =
                    mTaskOrganizer.getChildTasks(rootToken, null);
            if (children == null) return null;
            for (ActivityManager.RunningTaskInfo child : children) {
                if (child.taskId == taskId) return child;
            }
        } catch (RuntimeException e) {
            Slog.w(TAG, "findChildTask failed taskId=" + taskId, e);
        }
        return null;
    }

    private Rect getFullscreenBounds() {
        final Display display = mContext.getDisplay();
        if (display != null) {
            final Point size = new Point();
            display.getRealSize(size);
            if (size.x > 0 && size.y > 0) {
                return new Rect(0, 0, size.x, size.y);
            }
        }
        return new Rect(0, 0, Math.max(1, mContext.getResources().getDisplayMetrics().widthPixels),
                Math.max(1, mContext.getResources().getDisplayMetrics().heightPixels));
    }

    private void onRootTaskAppeared(SlotRecord slot, ActivityManager.RunningTaskInfo taskInfo) {
        slot.mRootToken = taskInfo.token;
        slot.mCreatingRoot = false;
        Slog.i(TAG, "rootTaskAppeared slot=" + slot.mSlotIndex + " rootTaskId="
                + taskInfo.taskId + " token=" + taskInfo.token);
        if (slot.mPendingTaskId > 0) {
            final int pendingTaskId = slot.mPendingTaskId;
            slot.mPendingTaskId = -1;
            moveTaskToSlot(pendingTaskId, slot.mSlotIndex);
        } else {
            updateEmptySlotRoot(slot);
        }
    }

    private void onTaskAppeared(SlotRecord slot, ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl leash) {
        final int taskId = taskInfo.taskId;
        slot.mTaskId = taskId;
        slot.mTaskToken = taskInfo.token;
        slot.mTaskLeash = leash;
        mTaskToSlot.put(taskId, slot.mSlotIndex);
        mTaskLeashes.put(taskId, leash);
        Slog.i(TAG, "taskAppeared taskId=" + taskId + " slot=" + slot.mSlotIndex
                + " leash=" + leash);
        reparentSlotLeash(slot, true /* animate */);
        scheduleReparentSlotLeash(slot);
    }

    private void onTaskVanished(SlotRecord slot, ActivityManager.RunningTaskInfo taskInfo) {
        final int taskId = taskInfo.taskId;
        if (slot.mTaskId != taskId) return;
        Slog.i(TAG, "taskVanished taskId=" + taskId + " slot=" + slot.mSlotIndex);
        clearTaskFromSlot(slot, false /* releaseLeash */);
        updateEmptySlotRoot(slot);
    }

    private void clearTaskFromSlot(SlotRecord slot, boolean releaseLeash) {
        clearTaskFromSlot(slot, releaseLeash, false /* keepLeashCache */);
    }

    private void clearTaskFromSlot(SlotRecord slot, boolean releaseLeash,
            boolean keepLeashCache) {
        final int oldTaskId = slot.mTaskId;
        if (slot.mTaskId > 0) {
            mTaskToSlot.remove(slot.mTaskId);
            mTaskOrganizer.removeListener(slot.mTaskListener);
        }
        if (releaseLeash) {
            releaseSurface(slot.mTaskLeash);
        }
        if (oldTaskId > 0 && !keepLeashCache) {
            mTaskLeashes.remove(oldTaskId);
        }
        slot.mAnimatingLeash = false;
        slot.mTaskId = -1;
        slot.mTaskToken = null;
        slot.mTaskLeash = null;
    }

    private void updateEmptySlotRoot(SlotRecord slot) {
        if (slot == null || slot.mRootToken == null || slot.mTaskId > 0
                || slot.mTaskToken != null) {
            return;
        }
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setFocusable(slot.mRootToken, false /* focusable */);
        wct.setAlwaysOnTop(slot.mRootToken, false /* alwaysOnTop */);
        wct.setHidden(slot.mRootToken, true /* hidden */);
        wct.reorder(slot.mRootToken, false /* onTop */);
        mTaskOrganizer.applyTransaction(wct);
    }

    private void keepOccupiedSlotsVisible(WindowContainerTransaction wct, SlotRecord excludedSlot) {
        for (int i = 0; i < mSlots.size(); i++) {
            final SlotRecord slot = mSlots.valueAt(i);
            if (slot == excludedSlot || slot.mRootToken == null || slot.mTaskToken == null
                    || slot.mTaskId <= 0) {
                continue;
            }
            wct.setFocusable(slot.mRootToken, false /* focusable */);
            wct.setHidden(slot.mRootToken, false /* hidden */);
            wct.setAlwaysOnTop(slot.mRootToken, true /* alwaysOnTop */);
            wct.reorder(slot.mRootToken, true /* onTop */);
            wct.setFocusable(slot.mTaskToken, false /* focusable */);
            wct.setBounds(slot.mTaskToken, getFullscreenBounds());
            wct.setHidden(slot.mTaskToken, false /* hidden */);
            wct.reorder(slot.mTaskToken, true /* onTop */);
        }
    }

    private void keepOccupiedSlotsFromAffectingSystemUi(SlotRecord excludedSlot) {
        for (int i = 0; i < mSlots.size(); i++) {
            final SlotRecord slot = mSlots.valueAt(i);
            if (slot == excludedSlot || slot.mTaskToken == null || slot.mTaskId <= 0) {
                continue;
            }
            setCanAffectSystemUiFlags(slot.mTaskToken, false /* canAffectSystemUiFlags */);
        }
    }

    private void setCanAffectSystemUiFlags(WindowContainerToken token,
            boolean canAffectSystemUiFlags) {
        if (token == null) {
            return;
        }
        try {
            mTaskOrganizer.setCanAffectSystemUiFlags(token, canAffectSystemUiFlags);
        } catch (RuntimeException e) {
            Slog.w(TAG, "setCanAffectSystemUiFlags failed token=" + token
                    + " canAffect=" + canAffectSystemUiFlags, e);
        }
    }

    private void reparentSlotLeash(SlotRecord slot, boolean animate) {
        if (slot.mSurface == null || !slot.mSurface.isValid()
                || slot.mTaskLeash == null || !slot.mTaskLeash.isValid()) {
            return;
        }
        if (!animate && slot.mAnimatingLeash) {
            return;
        }
        final Rect bounds = slot.mBounds;
        final int width = Math.max(1, bounds.width());
        final int height = Math.max(1, bounds.height());
        final Rect taskBounds = getFullscreenBounds();
        final int taskWidth = Math.max(1, taskBounds.width());
        final int taskHeight = Math.max(1, taskBounds.height());
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(slot.mTaskLeash, slot.mSurface);
        t.setPosition(slot.mTaskLeash, 0, 0);
        t.setLayer(slot.mTaskLeash, Integer.MAX_VALUE);
        t.setWindowCrop(slot.mTaskLeash, taskWidth, taskHeight);
        if (animate) {
            startTaskLeashAnimation(slot, t, width, height, taskWidth, taskHeight,
                    true /* enteringSlot */);
        } else {
            t.setAlpha(slot.mTaskLeash, 1f);
            t.setMatrix(slot.mTaskLeash, width / (float) taskWidth, 0f, 0f,
                    height / (float) taskHeight);
            t.setCornerRadius(slot.mTaskLeash, 0f);
            t.show(slot.mTaskLeash);
            t.apply();
        }
        Slog.i(TAG, "reparentTaskLeash slot=" + slot.mSlotIndex + " taskId=" + slot.mTaskId
                + " slotSize=" + width + "x" + height);
    }

    private void scheduleReparentSlotLeash(SlotRecord slot) {
        if (slot == null || slot.mSurface == null || slot.mTaskLeash == null
                || slot.mTaskId <= 0) {
            return;
        }
        final int taskId = slot.mTaskId;
        final SurfaceControl surface = slot.mSurface;
        final SurfaceControl taskLeash = slot.mTaskLeash;
        for (long delayMillis : REPARENT_RETRY_DELAYS_MS) {
            mMainExecutor.executeDelayed(() -> {
                if (slot.mTaskId != taskId || slot.mSurface != surface
                        || slot.mTaskLeash != taskLeash) {
                    return;
                }
                reparentSlotLeash(slot, false /* animate */);
            }, delayMillis);
        }
    }

    private SurfaceControl resetSlotTaskLeash(SlotRecord slot, int taskId) {
        if (slot == null) {
            return null;
        }
        final SurfaceControl leash = getTaskLeash(slot, taskId);
        if (leash == null || !leash.isValid()) {
            Slog.w(TAG, "resetTaskLeash skipped: no valid leash taskId=" + taskId
                    + " slot=" + slot.mSlotIndex);
            return null;
        }
        slot.mTaskLeash = leash;
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.reparent(leash, null);
        t.setPosition(leash, 0, 0);
        t.setWindowCrop(leash, null);
        t.setCornerRadius(leash, 0f);
        startTaskLeashAnimation(slot, t, 1, 1, 1, 1, false /* enteringSlot */);
        Slog.i(TAG, "resetTaskLeash taskId=" + taskId + " slot=" + slot.mSlotIndex);
        return leash;
    }

    private void startTaskLeashAnimation(SlotRecord slot, SurfaceControl.Transaction startT,
            int targetWidth, int targetHeight, int taskWidth, int taskHeight,
            boolean enteringSlot) {
        final SurfaceControl leash = slot.mTaskLeash;
        if (leash == null || !leash.isValid()) {
            startT.apply();
            return;
        }
        final int generation = ++slot.mAnimationGeneration;
        final int taskId = slot.mTaskId;
        slot.mAnimatingLeash = true;
        final float targetScaleX = targetWidth / (float) Math.max(1, taskWidth);
        final float targetScaleY = targetHeight / (float) Math.max(1, taskHeight);
        final float startScaleX = enteringSlot ? targetScaleX * SLOT_ENTER_START_SCALE : 1f;
        final float startScaleY = enteringSlot ? targetScaleY * SLOT_ENTER_START_SCALE : 1f;
        final float startAlpha = enteringSlot ? 0f : FULLSCREEN_RESTORE_START_ALPHA;
        startT.setAlpha(leash, startAlpha);
        setTaskLeashAnimationTransform(startT, leash, targetWidth, targetHeight,
                taskWidth, taskHeight, startScaleX, startScaleY, enteringSlot);
        startT.show(leash);
        startT.apply();
        Slog.i(TAG, "animateTaskLeash taskId=" + slot.mTaskId + " slot=" + slot.mSlotIndex
                + " enteringSlot=" + enteringSlot);
        animateTaskLeashFrame(slot, leash, generation, taskId, targetScaleX, targetScaleY,
                startScaleX, startScaleY, startAlpha, targetWidth, targetHeight,
                taskWidth, taskHeight, enteringSlot, 0L);
    }

    private void animateTaskLeashFrame(SlotRecord slot, SurfaceControl leash, int generation,
            int taskId, float targetScaleX, float targetScaleY, float startScaleX,
            float startScaleY, float startAlpha, int targetWidth, int targetHeight,
            int taskWidth, int taskHeight, boolean enteringSlot, long elapsedMs) {
        mMainExecutor.executeDelayed(() -> {
            if (leash == null || !leash.isValid()) {
                return;
            }
            if (slot.mAnimationGeneration != generation
                    || (enteringSlot && slot.mTaskLeash != leash)) {
                if (!enteringSlot && mTaskToSlot.get(taskId) == null) {
                    finishFullscreenRestoreAnimation(leash);
                }
                return;
            }
            final float rawProgress = Math.min(1f,
                    elapsedMs / (float) TASK_LEASH_ANIMATION_DURATION_MS);
            final float progress = 1f - ((1f - rawProgress) * (1f - rawProgress));
            final float scaleX = lerp(startScaleX, targetScaleX, progress);
            final float scaleY = lerp(startScaleY, targetScaleY, progress);
            final float alpha = lerp(startAlpha, 1f, progress);
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setAlpha(leash, alpha);
            setTaskLeashAnimationTransform(t, leash, targetWidth, targetHeight,
                    taskWidth, taskHeight, scaleX, scaleY, enteringSlot);
            t.show(leash);
            t.apply();
            if (rawProgress < 1f) {
                animateTaskLeashFrame(slot, leash, generation, taskId, targetScaleX, targetScaleY,
                        startScaleX, startScaleY, startAlpha, targetWidth, targetHeight,
                        taskWidth, taskHeight, enteringSlot,
                        elapsedMs + TASK_LEASH_ANIMATION_FRAME_MS);
            } else {
                slot.mAnimatingLeash = false;
            }
        }, elapsedMs == 0L ? 0L : TASK_LEASH_ANIMATION_FRAME_MS);
    }

    private void setTaskLeashAnimationTransform(SurfaceControl.Transaction t, SurfaceControl leash,
            int targetWidth, int targetHeight, int taskWidth, int taskHeight,
            float scaleX, float scaleY, boolean enteringSlot) {
        if (enteringSlot) {
            final float offsetX = (targetWidth - (taskWidth * scaleX)) / 2f;
            final float offsetY = (targetHeight - (taskHeight * scaleY)) / 2f;
            t.setPosition(leash, offsetX, offsetY);
        } else {
            t.setPosition(leash, 0f, 0f);
        }
        t.setMatrix(leash, scaleX, 0f, 0f, scaleY);
        // Smooth visual corners are provided by Sidebar's trusted wallpaper overlay. Do not apply a
        // SurfaceControl circular corner here, because it clips pixels the overlay expects to mask.
        t.setCornerRadius(leash, 0f);
    }

    private SurfaceControl getTaskLeash(SlotRecord slot, int taskId) {
        if (slot != null && slot.mTaskLeash != null && slot.mTaskLeash.isValid()) {
            return slot.mTaskLeash;
        }
        return getCachedTaskLeash(taskId);
    }

    private SurfaceControl getCachedTaskLeash(int taskId) {
        final SurfaceControl leash = mTaskLeashes.get(taskId);
        if (leash == null) {
            return null;
        }
        if (!leash.isValid()) {
            mTaskLeashes.remove(taskId);
            return null;
        }
        return leash;
    }

    private void scheduleFullscreenRestoreReset(int taskId, SurfaceControl leash) {
        if (leash == null || !leash.isValid()) {
            return;
        }
        for (long delayMillis : FULLSCREEN_RESET_RETRY_DELAYS_MS) {
            mMainExecutor.executeDelayed(() -> {
                if (leash.isValid() && mTaskToSlot.get(taskId) == null) {
                    finishFullscreenRestoreAnimation(leash);
                }
            }, delayMillis);
        }
    }

    private void finishFullscreenRestoreAnimation(SurfaceControl leash) {
        if (leash == null || !leash.isValid()) {
            return;
        }
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.setOpaque(leash, false);
        t.setAlpha(leash, 1f);
        t.setPosition(leash, 0f, 0f);
        t.setMatrix(leash, 1f, 0f, 0f, 1f);
        t.setWindowCrop(leash, null);
        t.setCornerRadius(leash, 0f);
        t.show(leash);
        t.apply();
    }

    private float lerp(float start, float end, float progress) {
        return start + ((end - start) * progress);
    }

    private boolean isValidSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < SLOT_COUNT;
    }

    private boolean isOrganizerRootTask(ActivityManager.RunningTaskInfo taskInfo) {
        return taskInfo != null
                && !taskInfo.hasParentTask()
                && taskInfo.baseActivity == null
                && taskInfo.topActivity == null
                && taskInfo.numActivities == 0;
    }

    private float sanitizeScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return 1f;
        }
        return Math.max(0.1f, Math.min(1f, scale));
    }

    private void releaseSurface(SurfaceControl surface) {
        if (surface == null) return;
        try {
            surface.release();
        } catch (RuntimeException e) {
            Slog.w(TAG, "releaseSurface failed", e);
        }
    }

    private void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "SidebarTaskViewController:");
        for (int i = 0; i < mSlots.size(); i++) {
            final SlotRecord slot = mSlots.valueAt(i);
            pw.println(prefix + "  slot=" + slot.mSlotIndex
                    + " displayId=" + slot.mDisplayId
                    + " root=" + slot.mRootToken
                    + " taskId=" + slot.mTaskId
                    + " bounds=" + slot.mBounds
                    + " surface=" + slot.mSurface);
        }
    }

    private final class SlotRecord {
        final int mSlotIndex;
        final Rect mBounds = new Rect();
        final ShellTaskOrganizer.TaskListener mRootListener = new RootTaskListener(this);
        final ShellTaskOrganizer.TaskListener mTaskListener = new SidebarTaskListener(this);

        int mDisplayId;
        int mTaskId = -1;
        int mRootTaskId = -1;
        int mPendingTaskId = -1;
        int mNoOpHomeTransitionTaskId = -1;
        int mAnimationGeneration;
        boolean mCreatingRoot;
        boolean mAnimatingLeash;
        WindowContainerToken mRootToken;
        WindowContainerToken mTaskToken;
        SurfaceControl mSurface;
        SurfaceControl mTaskLeash;

        SlotRecord(int slotIndex) {
            mSlotIndex = slotIndex;
            mDisplayId = mContext.getDisplayId();
        }
    }

    private final class RootTaskListener implements ShellTaskOrganizer.TaskListener {
        private final SlotRecord mSlot;

        RootTaskListener(SlotRecord slot) {
            mSlot = slot;
        }

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl leash) {
            if (mSlot.mRootTaskId > 0 && mSlot.mRootTaskId != taskInfo.taskId) {
                Slog.w(TAG, "ignore non-root task appeared on root listener slot="
                        + mSlot.mSlotIndex + " taskId=" + taskInfo.taskId
                        + " rootTaskId=" + mSlot.mRootTaskId);
                return;
            }
            if (mSlot.mRootTaskId <= 0 && !isOrganizerRootTask(taskInfo)) {
                Slog.w(TAG, "ignore non-empty root task appeared slot=" + mSlot.mSlotIndex
                        + " taskId=" + taskInfo.taskId + " parentTaskId="
                        + taskInfo.parentTaskId + " topActivity=" + taskInfo.topActivity);
                return;
            }
            mSlot.mRootTaskId = taskInfo.taskId;
            onRootTaskAppeared(mSlot, taskInfo);
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            if (mSlot.mRootToken != null && mSlot.mRootToken.equals(taskInfo.token)) {
                mSlot.mRootToken = null;
                mSlot.mRootTaskId = -1;
                mSlot.mCreatingRoot = false;
            }
        }
    }

    private final class SidebarTaskListener implements ShellTaskOrganizer.TaskListener {
        private final SlotRecord mSlot;

        SidebarTaskListener(SlotRecord slot) {
            mSlot = slot;
        }

        @Override
        public void onTaskAppeared(ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl leash) {
            SidebarTaskViewController.this.onTaskAppeared(mSlot, taskInfo, leash);
        }

        @Override
        public void onTaskInfoChanged(ActivityManager.RunningTaskInfo taskInfo) {
            if (mSlot.mTaskId == taskInfo.taskId) {
                mSlot.mTaskToken = taskInfo.token;
                reparentSlotLeash(mSlot, false /* animate */);
                scheduleReparentSlotLeash(mSlot);
            }
        }

        @Override
        public void onTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
            SidebarTaskViewController.this.onTaskVanished(mSlot, taskInfo);
        }
    }

    private static final class ISidebarTaskViewImpl extends ISidebarTaskView.Stub
            implements ExternalInterfaceBinder {
        private SidebarTaskViewController mController;

        ISidebarTaskViewImpl(SidebarTaskViewController controller) {
            mController = controller;
        }

        @Override
        public void invalidate() {
            mController = null;
        }

        @Override
        public void setSlotSurface(int slotIndex, SurfaceControl surface, Rect bounds,
                int displayId) throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "setSlotSurface",
                    controller -> controller.setSlotSurface(slotIndex, surface, bounds, displayId));
        }

        @Override
        public void clearSlotSurface(int slotIndex) throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "clearSlotSurface",
                    controller -> controller.clearSlotSurface(slotIndex));
        }

        @Override
        public void moveTaskToSlot(int taskId, int slotIndex) throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "moveTaskToSlot",
                    controller -> controller.moveTaskToSlot(taskId, slotIndex));
        }

        @Override
        public void releaseTaskFromSlot(int taskId) throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "releaseTaskFromSlot",
                    controller -> controller.releaseTaskFromSlot(taskId));
        }

        @Override
        public void releaseAllTasksFromSlots() throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "releaseAllTasksFromSlots",
                    SidebarTaskViewController::releaseAllTasksFromSlots);
        }

        @Override
        public void releaseAllTasksFromSlotsToBack() throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "releaseAllTasksFromSlotsToBack",
                    SidebarTaskViewController::releaseAllTasksFromSlotsToBack);
        }

        @Override
        public void setSidebarZoom(float scaleX, float scaleY, float offsetX, float offsetY,
                boolean enabled) throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "setSidebarZoom",
                    controller -> controller.setSidebarZoom(scaleX, scaleY, offsetX, offsetY,
                            enabled));
        }
    }
}
