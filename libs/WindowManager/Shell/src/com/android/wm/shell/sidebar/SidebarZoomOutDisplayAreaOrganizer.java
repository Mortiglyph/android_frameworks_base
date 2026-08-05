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

import android.content.Context;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerToken;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.wm.shell.common.DisplayLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Display area organizer that manages the OneStep sidebar zoom transform. */
public class SidebarZoomOutDisplayAreaOrganizer extends DisplayAreaOrganizer {

    private static final float SIDEBAR_CORNER_RADIUS_RATIO = 0.1f;
    private static final float SIDEBAR_SLOT_GAP_DIVISOR = 45.5f;
    private static final boolean ENABLE_SURFACE_CORNER_RADIUS = true;

    private final Context mContext;
    private final DisplayLayout mDisplayLayout = new DisplayLayout();
    private final float mCornerRadius;
    private final SidebarWallpaperBackdrop mWallpaperBackdrop;
    private final Map<WindowContainerToken, SurfaceControl> mDisplayAreaTokenMap =
            new ArrayMap<>();
    private final List<WindowContainerToken> mDisplayAreaTokensInZOrder = new ArrayList<>();

    public SidebarZoomOutDisplayAreaOrganizer(Context context, DisplayLayout displayLayout,
            Executor mainExecutor) {
        super(mainExecutor);
        mContext = context;
        mCornerRadius = ScreenDecorationsUtils.getWindowCornerRadius(mContext);
        mWallpaperBackdrop = new SidebarWallpaperBackdrop(context, mainExecutor);
        setDisplayLayout(displayLayout);
    }

    @Override
    public void onDisplayAreaAppeared(DisplayAreaInfo displayAreaInfo, SurfaceControl leash) {
        if (displayAreaInfo.displayId != mContext.getDisplayId()) {
            return;
        }
        leash.setUnreleasedWarningCallSite(
                "SidebarZoomOutDisplayAreaOrganizer.onDisplayAreaAppeared");
        mDisplayAreaTokenMap.put(displayAreaInfo.token, leash);
        mDisplayAreaTokensInZOrder.remove(displayAreaInfo.token);
        mDisplayAreaTokensInZOrder.add(displayAreaInfo.token);
    }

    @Override
    public void onDisplayAreaVanished(DisplayAreaInfo displayAreaInfo) {
        final SurfaceControl leash = mDisplayAreaTokenMap.get(displayAreaInfo.token);
        if (leash != null) {
            leash.release();
        }
        mDisplayAreaTokenMap.remove(displayAreaInfo.token);
        mDisplayAreaTokensInZOrder.remove(displayAreaInfo.token);
        if (mDisplayAreaTokenMap.isEmpty()) {
            final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            mWallpaperBackdrop.remove(tx);
            tx.apply();
        }
    }

    public void registerOrganizer() {
        final List<DisplayAreaAppearedInfo> displayAreaInfos = registerOrganizer(
                SidebarZoomOutDisplayAreaOrganizer.FEATURE_SIDEBAR_ZOOM_OUT);
        for (int i = 0; i < displayAreaInfos.size(); i++) {
            final DisplayAreaAppearedInfo info = displayAreaInfos.get(i);
            onDisplayAreaAppeared(info.getDisplayAreaInfo(), info.getLeash());
        }
    }

    @Override
    public void unregisterOrganizer() {
        super.unregisterOrganizer();
    }

    void setSidebarTransform(float scaleX, float scaleY, float offsetX, float offsetY,
            boolean enabled) {
        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        mDisplayAreaTokenMap.forEach((token, leash) -> {
            if (enabled) {
                tx
                        .setCrop(leash, 0, 0, mDisplayLayout.width(), mDisplayLayout.height())
                        .setScale(leash, scaleX, scaleY)
                        .setPosition(leash, offsetX, offsetY)
                        .unsetColor(leash)
                        .setOpaque(leash, false)
                        .setCornerRadius(leash, ENABLE_SURFACE_CORNER_RADIUS
                                ? getSidebarCornerRadius(scaleX, scaleY) : 0f);
            } else {
                tx
                        .setCrop(leash, null)
                        .setScale(leash, 1, 1)
                        .setPosition(leash, 0, 0)
                        .setOpaque(leash, true)
                        .setCornerRadius(leash, 0f);
            }
        });
        final SurfaceControl backdropReferenceLeash = getBackdropReferenceLeash();
        if (enabled && backdropReferenceLeash != null) {
            mWallpaperBackdrop.update(tx, backdropReferenceLeash, mContext.getDisplayId(),
                    mDisplayLayout.width(), mDisplayLayout.height(), scaleX, scaleY, offsetX,
                    offsetY, ENABLE_SURFACE_CORNER_RADIUS
                            ? getSidebarCornerRadius(scaleX, scaleY) : 0f);
        } else {
            mWallpaperBackdrop.remove(tx);
        }
        tx.apply();
    }

    void setDisplayLayout(DisplayLayout displayLayout) {
        mDisplayLayout.set(displayLayout);
    }

    void onRotateDisplay(Context context, int toRotation) {
        if (mDisplayLayout.rotation() == toRotation) {
            return;
        }
        mDisplayLayout.rotateTo(context.getResources(), toRotation);
    }

    private SurfaceControl getBackdropReferenceLeash() {
        SurfaceControl bottomLeash = null;
        for (int i = mDisplayAreaTokensInZOrder.size() - 1; i >= 0; i--) {
            final SurfaceControl leash = mDisplayAreaTokenMap.get(mDisplayAreaTokensInZOrder.get(i));
            if (leash == null || !leash.isValid()) {
                continue;
            }
            if (bottomLeash != null) {
                return leash;
            }
            bottomLeash = leash;
        }
        return bottomLeash;
    }

    private float getSidebarCornerRadius(float scaleX, float scaleY) {
        final float safeScale = Math.max(0.0001f,
                Math.min(Math.abs(scaleX), Math.abs(scaleY)));
        final float visibleMainWidth = mDisplayLayout.width() * Math.abs(scaleX);
        final float visibleMainHeight = mDisplayLayout.height() * Math.abs(scaleY);
        final float visibleSideWidth = Math.max(1f, mDisplayLayout.width() - visibleMainWidth);
        final float itemGap = Math.max(1f, visibleSideWidth / SIDEBAR_SLOT_GAP_DIVISOR);
        final float slotWidth = Math.max(1f, visibleSideWidth - itemGap);
        final float slotHeight = Math.max(1f, (visibleMainHeight - (itemGap * 2f)) / 3f);
        final float visibleRadius = Math.min(slotWidth, slotHeight) * SIDEBAR_CORNER_RADIUS_RATIO;
        return Math.max(mCornerRadius, visibleRadius) / safeScale;
    }
}
