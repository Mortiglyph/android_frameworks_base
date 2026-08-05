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

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import android.content.Context;
import android.content.res.Configuration;
import android.util.Slog;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.annotations.ExternalThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellInit;

/** Class that manages the OneStep sidebar zoom DisplayArea transform. */
public class SidebarZoomOutController implements RemoteCallable<SidebarZoomOutController>,
        DisplayChangeController.OnDisplayChangingListener {

    private static final String TAG = "SidebarZoomOutController";

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final SidebarZoomOutDisplayAreaOrganizer mDisplayAreaOrganizer;
    private final ShellExecutor mMainExecutor;
    private final SidebarZoomOutImpl mImpl = new SidebarZoomOutImpl();
    private boolean mDisplayAreaOrganizerRegistered;
    private boolean mSidebarTransformEnabled;
    private float mSidebarScaleX = 1f;
    private float mSidebarScaleY = 1f;
    private float mSidebarOffsetX;
    private float mSidebarOffsetY;

    private final DisplayController.OnDisplaysChangedListener mDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    if (displayId != DEFAULT_DISPLAY) {
                        return;
                    }
                    updateDisplayLayout(displayId);
                }

                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId != DEFAULT_DISPLAY) {
                        return;
                    }
                    updateDisplayLayout(displayId);
                }
            };

    public static SidebarZoomOutController create(Context context, ShellInit shellInit,
            DisplayController displayController, DisplayLayout displayLayout,
            @ShellMainThread ShellExecutor mainExecutor) {
        SidebarZoomOutDisplayAreaOrganizer displayAreaOrganizer =
                new SidebarZoomOutDisplayAreaOrganizer(context, displayLayout, mainExecutor);
        return new SidebarZoomOutController(context, shellInit, displayController,
                displayAreaOrganizer, mainExecutor);
    }

    SidebarZoomOutController(Context context, ShellInit shellInit,
            DisplayController displayController,
            SidebarZoomOutDisplayAreaOrganizer displayAreaOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        mContext = context;
        mDisplayController = displayController;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mMainExecutor = mainExecutor;
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        mDisplayController.addDisplayWindowListener(mDisplaysChangedListener);
        mDisplayController.addDisplayChangingController(this);
        updateDisplayLayout(mContext.getDisplayId());
    }

    public SidebarZoomOut asSidebarZoomOut() {
        return mImpl;
    }

    private void setSidebarTransform(float scaleX, float scaleY, float offsetX, float offsetY,
            boolean enabled) {
        if (enabled && !registerDisplayAreaOrganizerIfNeeded()) {
            return;
        }
        if (!mDisplayAreaOrganizerRegistered) {
            return;
        }
        mDisplayAreaOrganizer.setSidebarTransform(scaleX, scaleY, offsetX, offsetY, enabled);
        mSidebarTransformEnabled = enabled;
        mSidebarScaleX = enabled ? scaleX : 1f;
        mSidebarScaleY = enabled ? scaleY : 1f;
        mSidebarOffsetX = enabled ? offsetX : 0f;
        mSidebarOffsetY = enabled ? offsetY : 0f;
        if (!enabled) {
            unregisterDisplayAreaOrganizerIfNeeded();
        }
    }

    private void updateDisplayLayout(int displayId) {
        final DisplayLayout newDisplayLayout = mDisplayController.getDisplayLayout(displayId);
        if (newDisplayLayout == null) {
            Slog.w(TAG, "Failed to get new DisplayLayout.");
            return;
        }
        mDisplayAreaOrganizer.setDisplayLayout(newDisplayLayout);
        reapplySidebarTransformIfNeeded();
    }

    @Override
    public void onDisplayChange(int displayId, int fromRotation, int toRotation,
            @Nullable DisplayAreaInfo newDisplayAreaInfo, WindowContainerTransaction wct) {
        if (displayId != DEFAULT_DISPLAY || toRotation == ROTATION_UNDEFINED) {
            return;
        }
        mDisplayAreaOrganizer.onRotateDisplay(mContext, toRotation);
        reapplySidebarTransformIfNeeded();
    }

    private void reapplySidebarTransformIfNeeded() {
        if (!mDisplayAreaOrganizerRegistered || !mSidebarTransformEnabled) {
            return;
        }
        mDisplayAreaOrganizer.setSidebarTransform(mSidebarScaleX, mSidebarScaleY,
                mSidebarOffsetX, mSidebarOffsetY, true);
    }

    private boolean registerDisplayAreaOrganizerIfNeeded() {
        if (mDisplayAreaOrganizerRegistered) {
            return true;
        }
        try {
            mDisplayAreaOrganizer.registerOrganizer();
            mDisplayAreaOrganizerRegistered = true;
            return true;
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to register sidebar zoom organizer", e);
            return false;
        }
    }

    private void unregisterDisplayAreaOrganizerIfNeeded() {
        if (!mDisplayAreaOrganizerRegistered) {
            return;
        }
        try {
            mDisplayAreaOrganizer.unregisterOrganizer();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to unregister sidebar zoom organizer", e);
        } finally {
            mDisplayAreaOrganizerRegistered = false;
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    @ExternalThread
    private class SidebarZoomOutImpl implements SidebarZoomOut {
        @Override
        public void setSidebarTransform(float scaleX, float scaleY, float offsetX, float offsetY,
                boolean enabled) {
            mMainExecutor.execute(() -> SidebarZoomOutController.this.setSidebarTransform(scaleX,
                    scaleY, offsetX, offsetY, enabled));
        }
    }
}
