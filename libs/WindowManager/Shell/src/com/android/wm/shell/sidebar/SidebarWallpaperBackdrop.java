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

import android.app.WallpaperInfo;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.util.Slog;
import android.util.TypedValue;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;

import java.util.concurrent.Executor;

/**
 * Wallpaper mirror used as the visual backdrop while OneStep sidebar is showing.
 *
 * The backdrop sits below the SidebarZoomOut display area and always fills the physical display.
 * A second wallpaper layer sits above that backdrop and follows the SidebarZoomOut transform, so
 * the exposed sidebar area keeps a stable blurred wallpaper background while the wallpaper behind
 * zoomed content moves with the content.
 */
final class SidebarWallpaperBackdrop {
    private static final String TAG = "SidebarWallpaperBackdrop";

    private static final int BLUR_RADIUS_DP = 44;
    private static final float MIN_SCALE_DELTA_FOR_FULL_ALPHA = 0.18f;
    private static final float MIRROR_ALPHA = 1.0f;
    private static final float BLUR_REGION_ALPHA = 1.0f;
    private static final float BLUR_LAYER_ALPHA = 1.0f;
    private static final float BACKDROP_SCRIM_ALPHA = 0.24f;
    private static final float[] BACKDROP_SCRIM_COLOR = new float[] {0f, 0f, 0f};
    private static final int WALLPAPER_BLUR_DOWNSAMPLE = 18;
    private static final int WALLPAPER_FIT_ALPHA = 210;

    private final Executor mMainExecutor;
    private final Context mContext;
    private final Rect mDisplayCrop = new Rect();
    private final int mBlurRadius;
    private final BroadcastReceiver mWallpaperChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_WALLPAPER_CHANGED.equals(intent.getAction())) {
                return;
            }
            mMainExecutor.execute(SidebarWallpaperBackdrop.this::onWallpaperChanged);
        }
    };

    private SurfaceControl mContainerLayer;
    private SurfaceControl mWallpaperZoomContainerLayer;
    private SurfaceControl mWallpaperImageLayer;
    private SurfaceControl mZoomWallpaperImageLayer;
    private SurfaceControl mBlurredWallpaperMirrorLayer;
    private SurfaceControl mZoomWallpaperMirrorLayer;
    private SurfaceControl mBlurCarrierLayer;
    private SurfaceControl mScrimLayer;
    private boolean mMirrorUnavailableLogged;
    private boolean mZoomMirrorUnavailableLogged;
    private boolean mStaticWallpaperUnavailable;
    private int mDisplayId = -1;
    private int mDisplayWidth;
    private int mDisplayHeight;
    private int mWallpaperBufferWidth;
    private int mWallpaperBufferHeight;
    private int mPendingWallpaperBufferWidth;
    private int mPendingWallpaperBufferHeight;
    private int mBlurBufferWidth;
    private int mBlurBufferHeight;
    private int mPendingBlurBufferWidth;
    private int mPendingBlurBufferHeight;
    private HardwareBuffer mWallpaperBuffer;
    private HardwareBuffer mBlurBuffer;
    private boolean mVisible;
    private boolean mWallpaperChangeReceiverRegistered;
    private int mGeneration;

    SidebarWallpaperBackdrop(Context context, Executor mainExecutor) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mBlurRadius = Math.max(1, (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, BLUR_RADIUS_DP,
                context.getResources().getDisplayMetrics()));
    }

    void update(SurfaceControl.Transaction tx, SurfaceControl referenceLeash, int displayId,
            int width, int height, float scaleX, float scaleY, float offsetX, float offsetY,
            float cornerRadius) {
        if (referenceLeash == null || width <= 0 || height <= 0) {
            return;
        }
        if (!mVisible) {
            mVisible = true;
            mGeneration++;
            registerWallpaperChangeReceiver();
        }
        ensureSurfaces(tx, displayId, width, height);
        if (mContainerLayer == null || !mContainerLayer.isValid()) {
            return;
        }

        final float alpha = getAlphaForScale(scaleX, scaleY);
        final boolean sizeChanged = width != mDisplayWidth || height != mDisplayHeight;
        mDisplayId = displayId;
        mDisplayWidth = width;
        mDisplayHeight = height;
        mDisplayCrop.set(0, 0, width, height);

        tx.setRelativeLayer(mContainerLayer, referenceLeash, -2)
                .setPosition(mContainerLayer, 0f, 0f)
                .setCrop(mContainerLayer, mDisplayCrop)
                .setAlpha(mContainerLayer, alpha)
                .setOpaque(mContainerLayer, false)
                .show(mContainerLayer);

        updateBlurredWallpaperMirror(tx);

        if (mWallpaperZoomContainerLayer != null && mWallpaperZoomContainerLayer.isValid()) {
            tx.setRelativeLayer(mWallpaperZoomContainerLayer, referenceLeash, -1)
                    .setPosition(mWallpaperZoomContainerLayer, offsetX, offsetY)
                    .setScale(mWallpaperZoomContainerLayer, scaleX, scaleY)
                    .setCrop(mWallpaperZoomContainerLayer, mDisplayCrop)
                    .setCornerRadius(mWallpaperZoomContainerLayer, cornerRadius)
                    .setAlpha(mWallpaperZoomContainerLayer, alpha)
                    .setOpaque(mWallpaperZoomContainerLayer, false)
                    .show(mWallpaperZoomContainerLayer);
            updateZoomWallpaperLayer(tx);
        }

        if (sizeChanged) {
            Slog.d(TAG, "update display bounds " + width + "x" + height);
        }
    }

    void remove(SurfaceControl.Transaction tx) {
        mVisible = false;
        mGeneration++;
        unregisterWallpaperChangeReceiver();
        if (mBlurCarrierLayer != null && mBlurCarrierLayer.isValid()) {
            tx.remove(mBlurCarrierLayer);
        }
        if (mScrimLayer != null && mScrimLayer.isValid()) {
            tx.remove(mScrimLayer);
        }
        if (mZoomWallpaperImageLayer != null && mZoomWallpaperImageLayer.isValid()) {
            tx.remove(mZoomWallpaperImageLayer);
        }
        if (mWallpaperImageLayer != null && mWallpaperImageLayer.isValid()) {
            tx.remove(mWallpaperImageLayer);
        }
        if (mZoomWallpaperMirrorLayer != null && mZoomWallpaperMirrorLayer.isValid()) {
            tx.remove(mZoomWallpaperMirrorLayer);
        }
        if (mBlurredWallpaperMirrorLayer != null && mBlurredWallpaperMirrorLayer.isValid()) {
            tx.remove(mBlurredWallpaperMirrorLayer);
        }
        if (mWallpaperZoomContainerLayer != null && mWallpaperZoomContainerLayer.isValid()) {
            tx.remove(mWallpaperZoomContainerLayer);
        }
        if (mContainerLayer != null && mContainerLayer.isValid()) {
            tx.remove(mContainerLayer);
        }
        mContainerLayer = null;
        mWallpaperZoomContainerLayer = null;
        mWallpaperImageLayer = null;
        mZoomWallpaperImageLayer = null;
        mBlurredWallpaperMirrorLayer = null;
        mZoomWallpaperMirrorLayer = null;
        mBlurCarrierLayer = null;
        mScrimLayer = null;
        mMirrorUnavailableLogged = false;
        mZoomMirrorUnavailableLogged = false;
        mStaticWallpaperUnavailable = false;
        mDisplayId = -1;
        mDisplayWidth = 0;
        mDisplayHeight = 0;
        mWallpaperBufferWidth = 0;
        mWallpaperBufferHeight = 0;
        mPendingWallpaperBufferWidth = 0;
        mPendingWallpaperBufferHeight = 0;
        mBlurBufferWidth = 0;
        mBlurBufferHeight = 0;
        mPendingBlurBufferWidth = 0;
        mPendingBlurBufferHeight = 0;
        if (mWallpaperBuffer != null) {
            mWallpaperBuffer.close();
            mWallpaperBuffer = null;
        }
        if (mBlurBuffer != null) {
            mBlurBuffer.close();
            mBlurBuffer = null;
        }
    }

    private void registerWallpaperChangeReceiver() {
        if (mWallpaperChangeReceiverRegistered) {
            return;
        }
        final IntentFilter filter = new IntentFilter(Intent.ACTION_WALLPAPER_CHANGED);
        mContext.registerReceiver(mWallpaperChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mWallpaperChangeReceiverRegistered = true;
    }

    private void unregisterWallpaperChangeReceiver() {
        if (!mWallpaperChangeReceiverRegistered) {
            return;
        }
        try {
            mContext.unregisterReceiver(mWallpaperChangeReceiver);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Wallpaper change receiver was not registered", e);
        }
        mWallpaperChangeReceiverRegistered = false;
    }

    private void onWallpaperChanged() {
        if (!mVisible || mDisplayId < 0 || mDisplayWidth <= 0 || mDisplayHeight <= 0) {
            return;
        }

        forgetLoadedWallpaper();
        mGeneration++;
        resetStaticWallpaperBufferState();

        final SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        removeWallpaperMirrors(tx);
        if (mContainerLayer != null && mContainerLayer.isValid()) {
            attachWallpaperMirrorsIfNeeded(tx, mDisplayId);
            updateBlurredWallpaperMirror(tx);
        }
        if (mWallpaperZoomContainerLayer != null && mWallpaperZoomContainerLayer.isValid()) {
            updateZoomWallpaperLayer(tx);
        }
        tx.apply();
    }

    private void resetStaticWallpaperBufferState() {
        mStaticWallpaperUnavailable = false;
        mWallpaperBufferWidth = 0;
        mWallpaperBufferHeight = 0;
        mPendingWallpaperBufferWidth = 0;
        mPendingWallpaperBufferHeight = 0;
        if (mWallpaperBuffer != null) {
            mWallpaperBuffer.close();
            mWallpaperBuffer = null;
        }
    }

    private void forgetLoadedWallpaper() {
        try {
            WallpaperManager.getInstance(mContext).forgetLoadedWallpaper();
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to forget cached wallpaper", e);
        }
    }

    private void removeWallpaperMirrors(SurfaceControl.Transaction tx) {
        if (mBlurredWallpaperMirrorLayer != null && mBlurredWallpaperMirrorLayer.isValid()) {
            tx.remove(mBlurredWallpaperMirrorLayer);
        }
        if (mZoomWallpaperMirrorLayer != null && mZoomWallpaperMirrorLayer.isValid()) {
            tx.remove(mZoomWallpaperMirrorLayer);
        }
        mBlurredWallpaperMirrorLayer = null;
        mZoomWallpaperMirrorLayer = null;
        mMirrorUnavailableLogged = false;
        mZoomMirrorUnavailableLogged = false;
    }

    private void ensureSurfaces(SurfaceControl.Transaction tx, int displayId, int width,
            int height) {
        if (mContainerLayer != null && mContainerLayer.isValid()
                && mDisplayId == displayId
                && mDisplayWidth == width
                && mDisplayHeight == height) {
            if (mBlurCarrierLayer == null || !mBlurCarrierLayer.isValid()) {
                mBlurCarrierLayer = newBlurCarrierLayer();
            }
            if (mScrimLayer == null || !mScrimLayer.isValid()) {
                mScrimLayer = newScrimLayer();
            }
            if (mWallpaperImageLayer == null || !mWallpaperImageLayer.isValid()) {
                mWallpaperImageLayer = newWallpaperImageLayer();
            }
            if (mWallpaperZoomContainerLayer == null
                    || !mWallpaperZoomContainerLayer.isValid()) {
                mWallpaperZoomContainerLayer = newWallpaperZoomContainerLayer();
            }
            if (mZoomWallpaperImageLayer == null || !mZoomWallpaperImageLayer.isValid()) {
                mZoomWallpaperImageLayer = newZoomWallpaperImageLayer();
            }
            attachWallpaperMirrorsIfNeeded(tx, displayId);
            return;
        }
        if (mContainerLayer != null) {
            remove(tx);
        }

        mContainerLayer = new SurfaceControl.Builder()
                .setName("OneStep sidebar wallpaper backdrop")
                .setCallsite("SidebarWallpaperBackdrop")
                .setContainerLayer()
                .setHidden(true)
                .build();

        mWallpaperZoomContainerLayer = newWallpaperZoomContainerLayer();
        mWallpaperImageLayer = newWallpaperImageLayer();
        mZoomWallpaperImageLayer = newZoomWallpaperImageLayer();
        mBlurCarrierLayer = newBlurCarrierLayer();
        mScrimLayer = newScrimLayer();

        attachWallpaperMirrorsIfNeeded(tx, displayId);
    }

    private SurfaceControl newWallpaperZoomContainerLayer() {
        return new SurfaceControl.Builder()
                .setName("OneStep sidebar wallpaper zoom")
                .setCallsite("SidebarWallpaperBackdrop")
                .setContainerLayer()
                .setHidden(true)
                .build();
    }

    private SurfaceControl newWallpaperImageLayer() {
        if (mContainerLayer == null || !mContainerLayer.isValid()) {
            return null;
        }
        return new SurfaceControl.Builder()
                .setName("OneStep sidebar wallpaper image")
                .setCallsite("SidebarWallpaperBackdrop")
                .setParent(mContainerLayer)
                .setFormat(PixelFormat.TRANSLUCENT)
                .setBLASTLayer()
                .setHidden(true)
                .build();
    }

    private SurfaceControl newBlurCarrierLayer() {
        if (mContainerLayer == null || !mContainerLayer.isValid()) {
            return null;
        }
        return new SurfaceControl.Builder()
                .setName("OneStep sidebar wallpaper blur")
                .setCallsite("SidebarWallpaperBackdrop")
                .setParent(mContainerLayer)
                .setFormat(PixelFormat.TRANSLUCENT)
                .setBLASTLayer()
                .setHidden(true)
                .build();
    }

    private SurfaceControl newScrimLayer() {
        if (mContainerLayer == null || !mContainerLayer.isValid()) {
            return null;
        }
        return new SurfaceControl.Builder()
                .setName("OneStep sidebar backdrop scrim")
                .setCallsite("SidebarWallpaperBackdrop")
                .setParent(mContainerLayer)
                .setColorLayer()
                .setHidden(true)
                .build();
    }

    private SurfaceControl newZoomWallpaperImageLayer() {
        if (mWallpaperZoomContainerLayer == null || !mWallpaperZoomContainerLayer.isValid()) {
            return null;
        }
        return new SurfaceControl.Builder()
                .setName("OneStep sidebar zoom wallpaper image")
                .setCallsite("SidebarWallpaperBackdrop")
                .setParent(mWallpaperZoomContainerLayer)
                .setFormat(PixelFormat.TRANSLUCENT)
                .setBLASTLayer()
                .setHidden(true)
                .build();
    }

    private void attachWallpaperMirrorsIfNeeded(SurfaceControl.Transaction tx, int displayId) {
        if (mBlurredWallpaperMirrorLayer == null || !mBlurredWallpaperMirrorLayer.isValid()) {
            mBlurredWallpaperMirrorLayer = attachWallpaperMirror(tx, displayId, mContainerLayer,
                    false);
        }
        if (mZoomWallpaperMirrorLayer == null || !mZoomWallpaperMirrorLayer.isValid()) {
            mZoomWallpaperMirrorLayer = attachWallpaperMirror(tx, displayId,
                    mWallpaperZoomContainerLayer, true);
        }
    }

    private SurfaceControl attachWallpaperMirror(SurfaceControl.Transaction tx, int displayId,
            SurfaceControl parentLayer, boolean zoomLayer) {
        if (parentLayer == null || !parentLayer.isValid()) {
            return null;
        }
        final SurfaceControl wallpaperMirrorLayer;
        try {
            wallpaperMirrorLayer = WindowManagerGlobal.getInstance().mirrorWallpaperSurface(
                    displayId);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to mirror wallpaper surface", e);
            return null;
        }

        if (wallpaperMirrorLayer != null && wallpaperMirrorLayer.isValid()) {
            if (zoomLayer) {
                mZoomMirrorUnavailableLogged = false;
            } else {
                mMirrorUnavailableLogged = false;
            }
            tx.reparent(wallpaperMirrorLayer, parentLayer);
            return wallpaperMirrorLayer;
        } else {
            if (zoomLayer) {
                if (!mZoomMirrorUnavailableLogged) {
                    Slog.w(TAG, "Wallpaper mirror unavailable; using static zoom wallpaper");
                    mZoomMirrorUnavailableLogged = true;
                }
            } else if (!mMirrorUnavailableLogged) {
                Slog.w(TAG, "Wallpaper mirror unavailable; leaving sidebar backdrop transparent");
                mMirrorUnavailableLogged = true;
            }
            return null;
        }
    }

    private void updateBlurredWallpaperMirror(SurfaceControl.Transaction tx) {
        submitStaticWallpaperBufferIfNeeded();
        final boolean hasStaticWallpaper = hasStaticWallpaperBuffer();
        if (mWallpaperImageLayer != null && mWallpaperImageLayer.isValid()) {
            tx.setLayer(mWallpaperImageLayer, 0)
                    .setPosition(mWallpaperImageLayer, 0f, 0f)
                    .setBufferSize(mWallpaperImageLayer, mDisplayWidth, mDisplayHeight)
                    .setCrop(mWallpaperImageLayer, mDisplayCrop)
                    .setAlpha(mWallpaperImageLayer, MIRROR_ALPHA)
                    .setOpaque(mWallpaperImageLayer, false);
            if (hasStaticWallpaper) {
                tx.show(mWallpaperImageLayer);
            } else {
                tx.hide(mWallpaperImageLayer);
            }
        }
        if (mBlurredWallpaperMirrorLayer != null && mBlurredWallpaperMirrorLayer.isValid()) {
            tx.setLayer(mBlurredWallpaperMirrorLayer, -1)
                    .setPosition(mBlurredWallpaperMirrorLayer, 0f, 0f)
                    .setScale(mBlurredWallpaperMirrorLayer, 1f, 1f)
                    .setCrop(mBlurredWallpaperMirrorLayer, mDisplayCrop)
                    .setCornerRadius(mBlurredWallpaperMirrorLayer, 0f)
                    .setAlpha(mBlurredWallpaperMirrorLayer, MIRROR_ALPHA)
                    .setOpaque(mBlurredWallpaperMirrorLayer, false);
            if (hasStaticWallpaper) {
                tx.hide(mBlurredWallpaperMirrorLayer);
            } else {
                tx.show(mBlurredWallpaperMirrorLayer);
            }
        }
        if (mBlurCarrierLayer != null && mBlurCarrierLayer.isValid()) {
            submitTransparentBlurBufferIfNeeded();
            tx.setLayer(mBlurCarrierLayer, 1)
                    .setPosition(mBlurCarrierLayer, 0f, 0f)
                    .setBufferSize(mBlurCarrierLayer, mDisplayWidth, mDisplayHeight)
                    .setCrop(mBlurCarrierLayer, mDisplayCrop)
                    .setAlpha(mBlurCarrierLayer, BLUR_LAYER_ALPHA)
                    .setOpaque(mBlurCarrierLayer, false)
                    .setBackgroundBlurRadius(mBlurCarrierLayer, mBlurRadius)
                    .setBlurRegions(mBlurCarrierLayer, new float[][] {
                            new float[] {
                                    mBlurRadius,
                                    BLUR_REGION_ALPHA,
                                    0f,
                                    0f,
                                    mDisplayWidth,
                                    mDisplayHeight,
                                    0f,
                                    0f,
                                    0f,
                                    0f
                            }
                    })
                    .show(mBlurCarrierLayer);
        }
        updateScrimLayer(tx);
    }

    private void updateScrimLayer(SurfaceControl.Transaction tx) {
        if (mScrimLayer == null || !mScrimLayer.isValid()) {
            return;
        }
        tx.setLayer(mScrimLayer, 2)
                .setPosition(mScrimLayer, 0f, 0f)
                .setCrop(mScrimLayer, mDisplayCrop)
                .setColor(mScrimLayer, BACKDROP_SCRIM_COLOR)
                .setAlpha(mScrimLayer, BACKDROP_SCRIM_ALPHA)
                .setOpaque(mScrimLayer, false)
                .show(mScrimLayer);
    }

    private void updateZoomWallpaperLayer(SurfaceControl.Transaction tx) {
        final boolean hasMirror = mZoomWallpaperMirrorLayer != null
                && mZoomWallpaperMirrorLayer.isValid();
        final boolean hasStaticWallpaper = hasStaticWallpaperBuffer();
        if (mZoomWallpaperImageLayer != null && mZoomWallpaperImageLayer.isValid()) {
            tx.setLayer(mZoomWallpaperImageLayer, 0)
                    .setPosition(mZoomWallpaperImageLayer, 0f, 0f)
                    .setBufferSize(mZoomWallpaperImageLayer, mDisplayWidth, mDisplayHeight)
                    .setCrop(mZoomWallpaperImageLayer, mDisplayCrop)
                    .setAlpha(mZoomWallpaperImageLayer, MIRROR_ALPHA)
                    .setOpaque(mZoomWallpaperImageLayer, false);
            if (!hasMirror && hasStaticWallpaper) {
                tx.show(mZoomWallpaperImageLayer);
            } else {
                tx.hide(mZoomWallpaperImageLayer);
            }
        }
        if (hasMirror) {
            tx.setLayer(mZoomWallpaperMirrorLayer, 0)
                    .setPosition(mZoomWallpaperMirrorLayer, 0f, 0f)
                    .setScale(mZoomWallpaperMirrorLayer, 1f, 1f)
                    .setCrop(mZoomWallpaperMirrorLayer, mDisplayCrop)
                    .setCornerRadius(mZoomWallpaperMirrorLayer, 0f)
                    .setAlpha(mZoomWallpaperMirrorLayer, MIRROR_ALPHA)
                    .setOpaque(mZoomWallpaperMirrorLayer, false)
                    .show(mZoomWallpaperMirrorLayer);
        }
    }

    private boolean hasStaticWallpaperBuffer() {
        return mWallpaperImageLayer != null
                && mWallpaperImageLayer.isValid()
                && mWallpaperBufferWidth == mDisplayWidth
                && mWallpaperBufferHeight == mDisplayHeight;
    }

    private void submitStaticWallpaperBufferIfNeeded() {
        if (mWallpaperImageLayer == null || !mWallpaperImageLayer.isValid()
                || !shouldSubmitStaticWallpaperBuffer(mDisplayWidth, mDisplayHeight,
                        mStaticWallpaperUnavailable, mWallpaperBufferWidth,
                        mWallpaperBufferHeight, mPendingWallpaperBufferWidth,
                        mPendingWallpaperBufferHeight)) {
            return;
        }

        final int width = mDisplayWidth;
        final int height = mDisplayHeight;
        final int generation = mGeneration;
        final Bitmap wallpaper = loadStaticWallpaperBitmap();
        if (wallpaper == null || wallpaper.getWidth() <= 0 || wallpaper.getHeight() <= 0) {
            mStaticWallpaperUnavailable = true;
            Slog.w(TAG, "Static wallpaper bitmap unavailable; using wallpaper mirror fallback");
            return;
        }

        final HardwareBuffer buffer;
        try {
            buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_COMPOSER_OVERLAY
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                            | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        } catch (RuntimeException e) {
            mStaticWallpaperUnavailable = true;
            Slog.w(TAG, "Failed to create static wallpaper buffer", e);
            return;
        }

        final HardwareBufferRenderer renderer;
        try {
            renderer = new HardwareBufferRenderer(buffer);
            final RenderNode node = new RenderNode("OneStep sidebar static wallpaper");
            node.setPosition(0, 0, width, height);
            final Canvas canvas = node.beginRecording(width, height);
            drawWallpaperBitmap(canvas, wallpaper, width, height);
            node.endRecording();
            renderer.setContentRoot(node);
        } catch (RuntimeException e) {
            buffer.close();
            mStaticWallpaperUnavailable = true;
            Slog.w(TAG, "Failed to record static wallpaper buffer", e);
            return;
        }

        mPendingWallpaperBufferWidth = width;
        mPendingWallpaperBufferHeight = height;
        try {
            renderer.obtainRenderRequest()
                    .setColorSpace(getWallpaperColorSpace(wallpaper))
                    .draw(mMainExecutor, result -> {
                        try {
                            if (!isCurrentGeneration(generation)) {
                                return;
                            }
                            if (result.getStatus() != HardwareBufferRenderer.RenderResult.SUCCESS) {
                                Slog.w(TAG, "Static wallpaper render failed: "
                                        + result.getStatus());
                                mStaticWallpaperUnavailable = true;
                                return;
                            }
                            if (mWallpaperImageLayer == null || !mWallpaperImageLayer.isValid()
                                    || width != mDisplayWidth || height != mDisplayHeight
                                    || !isCurrentGeneration(generation)) {
                                return;
                            }
                            final HardwareBuffer oldBuffer = mWallpaperBuffer;
                            mWallpaperBuffer = buffer;
                            mWallpaperBufferWidth = width;
                            mWallpaperBufferHeight = height;
                            final SurfaceControl.Transaction tx = new SurfaceControl.Transaction()
                                    .setBuffer(mWallpaperImageLayer, buffer, result.getFence())
                                    .setLayer(mWallpaperImageLayer, 0)
                                    .setPosition(mWallpaperImageLayer, 0f, 0f)
                                    .setBufferSize(mWallpaperImageLayer, width, height)
                                    .setCrop(mWallpaperImageLayer, mDisplayCrop)
                                    .show(mWallpaperImageLayer);
                            if (mBlurredWallpaperMirrorLayer != null
                                    && mBlurredWallpaperMirrorLayer.isValid()) {
                                tx.hide(mBlurredWallpaperMirrorLayer);
                            }
                            if (mZoomWallpaperImageLayer != null
                                    && mZoomWallpaperImageLayer.isValid()) {
                                tx.setBuffer(mZoomWallpaperImageLayer, buffer, result.getFence())
                                        .setLayer(mZoomWallpaperImageLayer, 0)
                                        .setPosition(mZoomWallpaperImageLayer, 0f, 0f)
                                        .setBufferSize(mZoomWallpaperImageLayer, width, height)
                                        .setCrop(mZoomWallpaperImageLayer, mDisplayCrop);
                                if (mZoomWallpaperMirrorLayer != null
                                        && mZoomWallpaperMirrorLayer.isValid()) {
                                    tx.hide(mZoomWallpaperImageLayer);
                                } else {
                                    tx.show(mZoomWallpaperImageLayer);
                                }
                            }
                            tx.apply();
                            if (oldBuffer != null && oldBuffer != buffer) {
                                oldBuffer.close();
                            }
                        } finally {
                            mPendingWallpaperBufferWidth = 0;
                            mPendingWallpaperBufferHeight = 0;
                            renderer.close();
                            if (mWallpaperBuffer != buffer) {
                                buffer.close();
                            }
                        }
                    });
        } catch (RuntimeException e) {
            mPendingWallpaperBufferWidth = 0;
            mPendingWallpaperBufferHeight = 0;
            renderer.close();
            buffer.close();
            mStaticWallpaperUnavailable = true;
            Slog.w(TAG, "Failed to render static wallpaper buffer", e);
        }
    }

    private Bitmap loadStaticWallpaperBitmap() {
        final WallpaperManager wallpaperManager = WallpaperManager.getInstance(mContext);
        try {
            final WallpaperInfo wallpaperInfo =
                    wallpaperManager.getWallpaperInfo(WallpaperManager.FLAG_SYSTEM);
            if (wallpaperInfo != null) {
                return null;
            }
            return wallpaperManager.getBitmap(false, WallpaperManager.FLAG_SYSTEM);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to load static wallpaper bitmap", e);
            return null;
        }
    }

    private ColorSpace getWallpaperColorSpace(Bitmap wallpaper) {
        final ColorSpace colorSpace = wallpaper.getColorSpace();
        return colorSpace != null ? colorSpace : ColorSpace.get(ColorSpace.Named.SRGB);
    }

    private void drawWallpaperBitmap(Canvas canvas, Bitmap wallpaper, int width, int height) {
        canvas.drawColor(Color.BLACK);
        final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        drawBlurredBitmapCenterCrop(canvas, wallpaper, width, height, paint);

        final Paint fitPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        fitPaint.setAlpha(WALLPAPER_FIT_ALPHA);
        drawBlurredBitmapFitCenter(canvas, wallpaper, width, height, fitPaint);
    }

    private void drawBitmapCenterCrop(Canvas canvas, Bitmap bitmap, int width, int height,
            Paint paint) {
        final Matrix matrix = new Matrix();
        final float scale = Math.max(width / (float) bitmap.getWidth(),
                height / (float) bitmap.getHeight());
        matrix.setScale(scale, scale);
        matrix.postTranslate((width - bitmap.getWidth() * scale) * 0.5f,
                (height - bitmap.getHeight() * scale) * 0.5f);
        canvas.drawBitmap(bitmap, matrix, paint);
    }

    private void drawBitmapFitCenter(Canvas canvas, Bitmap bitmap, int width, int height,
            Paint paint) {
        final RectF src = new RectF(0f, 0f, bitmap.getWidth(), bitmap.getHeight());
        final RectF dst = new RectF(0f, 0f, width, height);
        final Matrix matrix = new Matrix();
        matrix.setRectToRect(src, dst, Matrix.ScaleToFit.CENTER);
        canvas.drawBitmap(bitmap, matrix, paint);
    }

    private void drawBlurredBitmapCenterCrop(Canvas canvas, Bitmap bitmap, int width, int height,
            Paint paint) {
        final Bitmap blurred = createDownsampledLayer(width, height);
        if (blurred == null) {
            drawBitmapCenterCrop(canvas, bitmap, width, height, paint);
            return;
        }
        try {
            final Canvas blurCanvas = new Canvas(blurred);
            drawBitmapCenterCrop(blurCanvas, bitmap, blurred.getWidth(), blurred.getHeight(),
                    paint);
            canvas.drawBitmap(blurred, null, new RectF(0f, 0f, width, height), paint);
        } finally {
            blurred.recycle();
        }
    }

    private void drawBlurredBitmapFitCenter(Canvas canvas, Bitmap bitmap, int width, int height,
            Paint paint) {
        final Bitmap blurred = createDownsampledLayer(width, height);
        if (blurred == null) {
            drawBitmapFitCenter(canvas, bitmap, width, height, paint);
            return;
        }
        try {
            final Canvas blurCanvas = new Canvas(blurred);
            drawBitmapFitCenter(blurCanvas, bitmap, blurred.getWidth(), blurred.getHeight(),
                    paint);
            canvas.drawBitmap(blurred, null, new RectF(0f, 0f, width, height), paint);
        } finally {
            blurred.recycle();
        }
    }

    private Bitmap createDownsampledLayer(int width, int height) {
        final int blurWidth = Math.max(1, width / WALLPAPER_BLUR_DOWNSAMPLE);
        final int blurHeight = Math.max(1, height / WALLPAPER_BLUR_DOWNSAMPLE);
        try {
            return Bitmap.createBitmap(blurWidth, blurHeight, Bitmap.Config.ARGB_8888);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to create downsampled wallpaper layer", e);
            return null;
        }
    }

    private void submitTransparentBlurBufferIfNeeded() {
        if (mDisplayWidth <= 0 || mDisplayHeight <= 0
                || (mBlurBufferWidth == mDisplayWidth && mBlurBufferHeight == mDisplayHeight)
                || (mPendingBlurBufferWidth == mDisplayWidth
                        && mPendingBlurBufferHeight == mDisplayHeight)) {
            return;
        }

        final int width = mDisplayWidth;
        final int height = mDisplayHeight;
        final int generation = mGeneration;
        final HardwareBuffer buffer;
        try {
            buffer = HardwareBuffer.create(width, height, HardwareBuffer.RGBA_8888, 1,
                    HardwareBuffer.USAGE_COMPOSER_OVERLAY
                            | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                            | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        } catch (RuntimeException e) {
            Slog.w(TAG, "Failed to create transparent blur buffer", e);
            return;
        }

        final HardwareBufferRenderer renderer;
        try {
            renderer = new HardwareBufferRenderer(buffer);
            final RenderNode node = new RenderNode("OneStep sidebar transparent blur buffer");
            node.setPosition(0, 0, width, height);
            final Canvas canvas = node.beginRecording(width, height);
            canvas.drawColor(Color.TRANSPARENT);
            node.endRecording();
            renderer.setContentRoot(node);
        } catch (RuntimeException e) {
            buffer.close();
            Slog.w(TAG, "Failed to record transparent blur buffer", e);
            return;
        }

        mPendingBlurBufferWidth = width;
        mPendingBlurBufferHeight = height;
        try {
            renderer.obtainRenderRequest()
                    .setColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                    .draw(mMainExecutor, result -> {
                        try {
                            if (!isCurrentGeneration(generation)) {
                                return;
                            }
                            if (result.getStatus() != HardwareBufferRenderer.RenderResult.SUCCESS) {
                                Slog.w(TAG, "Transparent blur buffer render failed: "
                                        + result.getStatus());
                                return;
                            }
                            if (mBlurCarrierLayer == null || !mBlurCarrierLayer.isValid()
                                    || width != mDisplayWidth || height != mDisplayHeight
                                    || !isCurrentGeneration(generation)) {
                                return;
                            }
                            final HardwareBuffer oldBuffer = mBlurBuffer;
                            mBlurBuffer = buffer;
                            mBlurBufferWidth = width;
                            mBlurBufferHeight = height;
                            new SurfaceControl.Transaction()
                                    .setBuffer(mBlurCarrierLayer, buffer, result.getFence())
                                    .show(mBlurCarrierLayer)
                                    .apply();
                            if (oldBuffer != null && oldBuffer != buffer) {
                                oldBuffer.close();
                            }
                        } finally {
                            mPendingBlurBufferWidth = 0;
                            mPendingBlurBufferHeight = 0;
                            renderer.close();
                            if (mBlurBuffer != buffer) {
                                buffer.close();
                            }
                        }
                    });
        } catch (RuntimeException e) {
            mPendingBlurBufferWidth = 0;
            mPendingBlurBufferHeight = 0;
            renderer.close();
            buffer.close();
            Slog.w(TAG, "Failed to render transparent blur buffer", e);
        }
    }

    static float getBackdropScrimAlphaForScale(float scaleX, float scaleY) {
        return BACKDROP_SCRIM_ALPHA * getAlphaForScale(scaleX, scaleY);
    }

    static boolean shouldSubmitStaticWallpaperBuffer(int displayWidth, int displayHeight,
            boolean staticWallpaperUnavailable, int wallpaperBufferWidth,
            int wallpaperBufferHeight, int pendingWallpaperBufferWidth,
            int pendingWallpaperBufferHeight) {
        return displayWidth > 0
                && displayHeight > 0
                && !staticWallpaperUnavailable
                && (wallpaperBufferWidth != displayWidth
                        || wallpaperBufferHeight != displayHeight)
                && (pendingWallpaperBufferWidth != displayWidth
                        || pendingWallpaperBufferHeight != displayHeight);
    }

    private static float getAlphaForScale(float scaleX, float scaleY) {
        final float scale = Math.min(Math.abs(scaleX), Math.abs(scaleY));
        final float progress = (1f - scale) / MIN_SCALE_DELTA_FOR_FULL_ALPHA;
        return Math.max(0f, Math.min(1f, progress));
    }

    private boolean isCurrentGeneration(int generation) {
        return mVisible && generation == mGeneration;
    }

}
