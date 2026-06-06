/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT;
import static android.view.MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE;

import android.annotation.NonNull;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.GestureDetector;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;
import android.widget.OverScroller;

import java.io.PrintWriter;

/**
 * Listens for system-wide input gestures, firing callbacks when detected.
 * @hide
 */
class SystemGesturesPointerEventListener implements PointerEventListener {
    private static final String TAG = "SystemGestures";
    private static final boolean DEBUG = false;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int MAX_TRACKED_POINTERS = 32;  // max per input system
    private static final int UNTRACKED_POINTER = -1;
    private static final int MAX_FLING_TIME_MILLIS = 5000;

    private static final int SWIPE_NONE = 0;
    private static final int SWIPE_FROM_TOP = 1;
    private static final int SWIPE_FROM_BOTTOM = 2;
    private static final int SWIPE_FROM_RIGHT = 3;
    private static final int SWIPE_FROM_LEFT = 4;
    private static final int SWIPE_UP_FROM_ZOOM_OUT_TOP_RIGHT = 5;
    private static final int SWIPE_THUMB_PULL_DOWN = 6;
    private static final int SWIPE_FROM_TOP_RIGHT_DIAGONAL = 7;

    private static final int SIDEBAR_CONTENT_WIDTH_NUMERATOR = 807;
    private static final int SIDEBAR_DESIGN_WIDTH = 1080;
    private static final int TOP_RIGHT_DIAGONAL_MIN_INWARD_PX = 80;
    private static final int TOP_RIGHT_DIAGONAL_MIN_DOWN_PX = 30;
    private static final float TOP_RIGHT_DIAGONAL_HOT_WIDTH_RATIO = 0.30f;
    private static final float TOP_RIGHT_DIAGONAL_HOT_HEIGHT_RATIO = 0.36f;
    private static final float TOP_RIGHT_DIAGONAL_MAX_RATIO = 5.0f;
    private static final String SIDEBAR_ENABLED = "side_bar_mode";
    private static final String THUMB_PUSH_DOWN = "thumb_push_down";
    private static final String THUMB_TRIGGER_AREA = "thumb_trigger_area";
    private static final long THUMB_PULL_DOWN_TIMEOUT_MS = 1000;
    private static final int THUMB_PULL_DOWN_MIN_START_Y = 150;
    private static final int THUMB_PULL_DOWN_TOUCH_SIZE_MIN_DISTANCE_PX = 180;
    private static final int THUMB_PULL_DOWN_FALLBACK_MIN_DISTANCE_PX = 180;
    private static final float THUMB_PULL_DOWN_TOUCH_SIZE_VERTICAL_RATIO = 1.8f;
    private static final float THUMB_PULL_DOWN_FALLBACK_VERTICAL_RATIO = 2.4f;
    private static final float THUMB_PULL_DOWN_MAX_HORIZONTAL_RATIO = 0.12f;
    private static final float THUMB_SIDE_TRACK_EXTRA_RATIO = 0.05f;
    private static final float[] THUMB_TOUCH_SIZE_THRESHOLDS = {
            0.31f, 0.36f, 0.40f, 0.46f, 0.50f
    };
    private static final float[] THUMB_SIDE_AREA_RATIOS = {
            0.05f, 0.07f, 0.10f, 0.13f, 0.16f
    };

    private static final int TRACKPAD_SWIPE_NONE = 0;
    private static final int TRACKPAD_SWIPE_FROM_TOP = 1;
    private static final int TRACKPAD_SWIPE_FROM_BOTTOM = 2;
    private static final int TRACKPAD_SWIPE_FROM_RIGHT = 3;
    private static final int TRACKPAD_SWIPE_FROM_LEFT = 4;

    private final Context mContext;
    private final Handler mHandler;
    private int mDisplayCutoutTouchableRegionSize;
    // The thresholds for each edge of the display
    private final Rect mSwipeStartThreshold = new Rect();
    private int mSwipeDistanceThreshold;
    private final Callbacks mCallbacks;
    private final int[] mDownPointerId = new int[MAX_TRACKED_POINTERS];
    private final float[] mDownX = new float[MAX_TRACKED_POINTERS];
    private final float[] mDownY = new float[MAX_TRACKED_POINTERS];
    private final long[] mDownTime = new long[MAX_TRACKED_POINTERS];

    private GestureDetector mGestureDetector;

    int screenHeight;
    int screenWidth;
    private int mDownPointers;
    private boolean mSwipeFireable;
    private boolean mDebugFireable;
    private boolean mMouseHoveringAtLeft;
    private boolean mMouseHoveringAtTop;
    private boolean mMouseHoveringAtRight;
    private boolean mMouseHoveringAtBottom;
    private long mLastFlingTime;

    SystemGesturesPointerEventListener(Context context, Handler handler, Callbacks callbacks) {
        mContext = checkNull("context", context);
        mHandler = handler;
        mCallbacks = checkNull("callbacks", callbacks);
        onConfigurationChanged();
    }

    void onDisplayInfoChanged(DisplayInfo info) {
        screenWidth = info.logicalWidth;
        screenHeight = info.logicalHeight;
        onConfigurationChanged();
    }

    void onConfigurationChanged() {
        final Resources r = mContext.getResources();
        final int startThreshold = r.getDimensionPixelSize(
                com.android.internal.R.dimen.system_gestures_start_threshold);
        mSwipeStartThreshold.set(startThreshold, startThreshold, startThreshold,
                startThreshold);
        mSwipeDistanceThreshold = r.getDimensionPixelSize(
                com.android.internal.R.dimen.system_gestures_distance_threshold);

        final Display display = DisplayManagerGlobal.getInstance()
                .getRealDisplay(Display.DEFAULT_DISPLAY);
        final DisplayCutout displayCutout = display != null ? display.getCutout() : null;
        if (displayCutout != null) {
            // Expand swipe start threshold such that we can catch touches that just start beyond
            // the notch area
            mDisplayCutoutTouchableRegionSize = r.getDimensionPixelSize(
                    com.android.internal.R.dimen.display_cutout_touchable_region_size);
            final Rect[] bounds = displayCutout.getBoundingRectsAll();
            if (bounds[BOUNDS_POSITION_LEFT] != null) {
                mSwipeStartThreshold.left = Math.max(mSwipeStartThreshold.left,
                        bounds[BOUNDS_POSITION_LEFT].width() + mDisplayCutoutTouchableRegionSize);
            }
            if (bounds[BOUNDS_POSITION_TOP] != null) {
                mSwipeStartThreshold.top = Math.max(mSwipeStartThreshold.top,
                        bounds[BOUNDS_POSITION_TOP].height() + mDisplayCutoutTouchableRegionSize);
            }
            if (bounds[BOUNDS_POSITION_RIGHT] != null) {
                mSwipeStartThreshold.right = Math.max(mSwipeStartThreshold.right,
                        bounds[BOUNDS_POSITION_RIGHT].width() + mDisplayCutoutTouchableRegionSize);
            }
            if (bounds[BOUNDS_POSITION_BOTTOM] != null) {
                mSwipeStartThreshold.bottom = Math.max(mSwipeStartThreshold.bottom,
                        bounds[BOUNDS_POSITION_BOTTOM].height()
                                + mDisplayCutoutTouchableRegionSize);
            }
        }
        if (DEBUG) Slog.d(TAG,  "mSwipeStartThreshold=" + mSwipeStartThreshold
                + " mSwipeDistanceThreshold=" + mSwipeDistanceThreshold);
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    public void systemReady() {
        // GestureDetector records statistics about gesture classification events to inform gesture
        // usage trends. SystemGesturesPointerEventListener creates a lot of noise in these
        // statistics because it passes every touch event though a GestureDetector. By creating an
        // anonymous subclass of GestureDetector, these statistics will be recorded with a unique
        // source name that can be filtered.

        // GestureDetector would get a ViewConfiguration instance by context, that may also
        // create a new WindowManagerImpl for the new display, and lock WindowManagerGlobal
        // temporarily in the constructor that would make a deadlock.
        mHandler.post(() -> {
            final int displayId = mContext.getDisplayId();
            final DisplayInfo info = DisplayManagerGlobal.getInstance().getDisplayInfo(displayId);
            if (info == null) {
                // Display already removed, stop here.
                Slog.w(TAG, "Cannot create GestureDetector, display removed:" + displayId);
                return;
            }
            mGestureDetector = new GestureDetector(mContext, new FlingGestureDetector(), mHandler) {
            };
        });
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        if (mGestureDetector != null && event.isTouchEvent()) {
            mGestureDetector.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeFireable = true;
                mDebugFireable = true;
                mDownPointers = 0;
                captureDown(event, 0);
                if (mMouseHoveringAtLeft) {
                    mMouseHoveringAtLeft = false;
                    mCallbacks.onMouseLeaveFromLeft();
                }
                if (mMouseHoveringAtTop) {
                    mMouseHoveringAtTop = false;
                    mCallbacks.onMouseLeaveFromTop();
                }
                if (mMouseHoveringAtRight) {
                    mMouseHoveringAtRight = false;
                    mCallbacks.onMouseLeaveFromRight();
                }
                if (mMouseHoveringAtBottom) {
                    mMouseHoveringAtBottom = false;
                    mCallbacks.onMouseLeaveFromBottom();
                }
                mCallbacks.onDown();
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                captureDown(event, event.getActionIndex());
                if (mDebugFireable) {
                    mDebugFireable = event.getPointerCount() < 5;
                    if (!mDebugFireable) {
                        if (DEBUG) Slog.d(TAG, "Firing debug");
                        mCallbacks.onDebug();
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSwipeFireable) {
                    int trackpadSwipe = detectTrackpadThreeFingerSwipe(event);
                    mSwipeFireable = trackpadSwipe == TRACKPAD_SWIPE_NONE;
                    if (!mSwipeFireable) {
                        if (trackpadSwipe == TRACKPAD_SWIPE_FROM_TOP) {
                            if (DEBUG) Slog.d(TAG, "Firing onSwipeFromTop from trackpad");
                            mCallbacks.onSwipeFromTop();
                        } else if (trackpadSwipe == TRACKPAD_SWIPE_FROM_BOTTOM) {
                            if (DEBUG) Slog.d(TAG, "Firing onSwipeFromBottom from trackpad");
                            mCallbacks.onSwipeFromBottom();
                        } else if (trackpadSwipe == TRACKPAD_SWIPE_FROM_RIGHT) {
                            if (DEBUG) Slog.d(TAG, "Firing onSwipeFromRight from trackpad");
                            mCallbacks.onSwipeFromRight();
                        } else if (trackpadSwipe == TRACKPAD_SWIPE_FROM_LEFT) {
                            if (DEBUG) Slog.d(TAG, "Firing onSwipeFromLeft from trackpad");
                            mCallbacks.onSwipeFromLeft();
                        }
                        break;
                    }

                    final int swipe = detectSwipe(event);
                    mSwipeFireable = swipe == SWIPE_NONE;
                    if (swipe == SWIPE_FROM_TOP) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromTop");
                        mCallbacks.onSwipeFromTop();
                    } else if (swipe == SWIPE_FROM_BOTTOM) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromBottom");
                        mCallbacks.onSwipeFromBottom();
                    } else if (swipe == SWIPE_FROM_RIGHT) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromRight");
                        mCallbacks.onSwipeFromRight();
                    } else if (swipe == SWIPE_FROM_LEFT) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromLeft");
                        mCallbacks.onSwipeFromLeft();
                    } else if (swipe == SWIPE_UP_FROM_ZOOM_OUT_TOP_RIGHT) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeUpFromZoomOutTopRight");
                        mCallbacks.onSwipeUpFromZoomOutTopRight();
                    } else if (swipe == SWIPE_THUMB_PULL_DOWN) {
                        if (DEBUG) Slog.d(TAG, "Firing onThumbPullDown");
                        mCallbacks.onThumbPullDown();
                    } else if (swipe == SWIPE_FROM_TOP_RIGHT_DIAGONAL) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromTopRightDiagonal");
                        mCallbacks.onSwipeFromTopRightDiagonal();
                    }
                }
                break;
            case MotionEvent.ACTION_HOVER_MOVE:
                if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    final float eventX = event.getX();
                    final float eventY = event.getY();
                    if (!mMouseHoveringAtLeft && eventX == 0) {
                        mCallbacks.onMouseHoverAtLeft();
                        mMouseHoveringAtLeft = true;
                    } else if (mMouseHoveringAtLeft && eventX > 0) {
                        mCallbacks.onMouseLeaveFromLeft();
                        mMouseHoveringAtLeft = false;
                    }
                    if (!mMouseHoveringAtTop && eventY == 0) {
                        mCallbacks.onMouseHoverAtTop();
                        mMouseHoveringAtTop = true;
                    } else if (mMouseHoveringAtTop && eventY > 0) {
                        mCallbacks.onMouseLeaveFromTop();
                        mMouseHoveringAtTop = false;
                    }
                    if (!mMouseHoveringAtRight && eventX >= screenWidth - 1) {
                        mCallbacks.onMouseHoverAtRight();
                        mMouseHoveringAtRight = true;
                    } else if (mMouseHoveringAtRight && eventX < screenWidth - 1) {
                        mCallbacks.onMouseLeaveFromRight();
                        mMouseHoveringAtRight = false;
                    }
                    if (!mMouseHoveringAtBottom && eventY >= screenHeight - 1) {
                        mCallbacks.onMouseHoverAtBottom();
                        mMouseHoveringAtBottom = true;
                    } else if (mMouseHoveringAtBottom && eventY < screenHeight - 1) {
                        mCallbacks.onMouseLeaveFromBottom();
                        mMouseHoveringAtBottom = false;
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mSwipeFireable = false;
                mDebugFireable = false;
                mCallbacks.onUpOrCancel();
                break;
            default:
                if (DEBUG) Slog.d(TAG, "Ignoring " + event);
        }
    }

    private void captureDown(MotionEvent event, int pointerIndex) {
        final int pointerId = event.getPointerId(pointerIndex);
        final int i = findIndex(pointerId);
        if (DEBUG) Slog.d(TAG, "pointer " + pointerId
                + " down pointerIndex=" + pointerIndex + " trackingIndex=" + i);
        if (i != UNTRACKED_POINTER) {
            mDownX[i] = event.getX(pointerIndex);
            mDownY[i] = event.getY(pointerIndex);
            mDownTime[i] = event.getEventTime();
            if (DEBUG) Slog.d(TAG, "pointer " + pointerId
                    + " down x=" + mDownX[i] + " y=" + mDownY[i]);
        }
    }

    protected boolean currentGestureStartedInRegion(Region r) {
        return r.contains((int) mDownX[0], (int) mDownY[0]);
    }

    private int findIndex(int pointerId) {
        for (int i = 0; i < mDownPointers; i++) {
            if (mDownPointerId[i] == pointerId) {
                return i;
            }
        }
        if (mDownPointers == MAX_TRACKED_POINTERS || pointerId == MotionEvent.INVALID_POINTER_ID) {
            return UNTRACKED_POINTER;
        }
        mDownPointerId[mDownPointers++] = pointerId;
        return mDownPointers - 1;
    }

    private int detectTrackpadThreeFingerSwipe(MotionEvent move) {
        if (!isTrackpadThreeFingerSwipe(move)) {
            return TRACKPAD_SWIPE_NONE;
        }

        float dx = move.getX() - mDownX[0];
        float dy = move.getY() - mDownY[0];
        if (Math.abs(dx) < Math.abs(dy)) {
            if (Math.abs(dy) > mSwipeDistanceThreshold) {
                return dy > 0 ? TRACKPAD_SWIPE_FROM_TOP : TRACKPAD_SWIPE_FROM_BOTTOM;
            }
        } else {
            if (Math.abs(dx) > mSwipeDistanceThreshold) {
                return dx > 0 ? TRACKPAD_SWIPE_FROM_LEFT : TRACKPAD_SWIPE_FROM_RIGHT;
            }
        }

        return TRACKPAD_SWIPE_NONE;
    }

    private static boolean isTrackpadThreeFingerSwipe(MotionEvent event) {
        return event.getClassification() == CLASSIFICATION_MULTI_FINGER_SWIPE
                && event.getAxisValue(AXIS_GESTURE_SWIPE_FINGER_COUNT) == 3;
    }

    private int detectSwipe(MotionEvent move) {
        final int historySize = move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (i != UNTRACKED_POINTER) {
                for (int h = 0; h < historySize; h++) {
                    final long time = move.getHistoricalEventTime(h);
                    final float x = move.getHistoricalX(p, h);
                    final float y = move.getHistoricalY(p,  h);
                    final float size = move.getPointerCount() == 1
                            ? move.getHistoricalSize(p, h) : 0f;
                    final int swipe = detectSwipe(i, time, x, y, size);
                    if (swipe != SWIPE_NONE) {
                        return swipe;
                    }
                }
                final float size = move.getPointerCount() == 1 ? move.getSize(p) : 0f;
                final int swipe = detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p),
                        size);
                if (swipe != SWIPE_NONE) {
                    return swipe;
                }
            }
        }
        return SWIPE_NONE;
    }

    private int detectSwipe(int i, long time, float x, float y, float size) {
        final float fromX = mDownX[i];
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "pointer " + mDownPointerId[i]
                + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);
        if (detectSwipeFromTopRightDiagonal(fromX, fromY, x, y, elapsed)) {
            return SWIPE_FROM_TOP_RIGHT_DIAGONAL;
        }
        if (isPendingSwipeFromTopRightDiagonal(fromX, fromY, x, y, elapsed)) {
            return SWIPE_NONE;
        }
        if (fromY <= mSwipeStartThreshold.top
                && y > fromY + mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_TOP;
        }
        if (fromY >= screenHeight - mSwipeStartThreshold.bottom
                && y < fromY - mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_BOTTOM;
        }
        if (fromX >= screenWidth - mSwipeStartThreshold.right
                && x < fromX - mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_RIGHT;
        }
        if (fromX <= mSwipeStartThreshold.left
                && x > fromX + mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_LEFT;
        }
        if (detectSwipeUpFromZoomOutTopRight(fromX, fromY, x, y, elapsed)) {
            return SWIPE_UP_FROM_ZOOM_OUT_TOP_RIGHT;
        }
        if (detectThumbPullDown(fromX, fromY, x, y, elapsed, size)) {
            return SWIPE_THUMB_PULL_DOWN;
        }
        return SWIPE_NONE;
    }

    private boolean detectSwipeUpFromZoomOutTopRight(float fromX, float fromY, float x, float y,
            long elapsed) {
        if (screenWidth <= 0 || screenHeight <= 0 || elapsed >= SWIPE_TIMEOUT_MS) {
            return false;
        }
        final int contentWidth = Math.max(1,
                Math.round(screenWidth * SIDEBAR_CONTENT_WIDTH_NUMERATOR
                        / (float) SIDEBAR_DESIGN_WIDTH));
        final int contentHeight = Math.max(1,
                Math.round(contentWidth * screenHeight / (float) screenWidth));
        final int contentTop = screenHeight - contentHeight;
        final int hotWidth = Math.max(mSwipeDistanceThreshold * 3,
                mSwipeStartThreshold.right * 4);
        final int hotHeight = Math.max(mSwipeDistanceThreshold * 3,
                mSwipeStartThreshold.top * 4);
        final float deltaX = x - fromX;
        final float deltaY = y - fromY;
        return fromX >= contentWidth - hotWidth
                && fromX <= contentWidth + mSwipeStartThreshold.right
                && fromY >= contentTop - mSwipeStartThreshold.top
                && fromY <= contentTop + hotHeight
                && deltaY < -mSwipeDistanceThreshold
                && Math.abs(deltaY) >= Math.abs(deltaX);
    }

    private boolean detectSwipeFromTopRightDiagonal(float fromX, float fromY, float x, float y,
            long elapsed) {
        if (!isSidebarEnabled() || screenWidth <= 0 || screenHeight <= 0
                || elapsed >= SWIPE_TIMEOUT_MS) {
            return false;
        }
        final float deltaX = x - fromX;
        final float deltaY = y - fromY;
        final float absDeltaX = Math.abs(deltaX);
        final float minInward = Math.max(mSwipeDistanceThreshold,
                TOP_RIGHT_DIAGONAL_MIN_INWARD_PX);
        final float minDown = Math.max(mSwipeDistanceThreshold / 2f,
                TOP_RIGHT_DIAGONAL_MIN_DOWN_PX);
        return isFromTopRightDiagonalHotRegion(fromX, fromY)
                && deltaX < -minInward
                && deltaY > minDown
                && absDeltaX <= deltaY * TOP_RIGHT_DIAGONAL_MAX_RATIO;
    }

    private boolean isPendingSwipeFromTopRightDiagonal(float fromX, float fromY, float x, float y,
            long elapsed) {
        if (!isSidebarEnabled() || screenWidth <= 0 || screenHeight <= 0
                || elapsed >= SWIPE_TIMEOUT_MS) {
            return false;
        }
        if (!isFromTopRightDiagonalHotRegion(fromX, fromY)) {
            return false;
        }
        final float deltaX = x - fromX;
        final float deltaY = y - fromY;
        return deltaX <= mSwipeDistanceThreshold
                && deltaY >= 0
                && (Math.abs(deltaX) > mSwipeDistanceThreshold
                        || deltaY > mSwipeDistanceThreshold);
    }

    private boolean isFromTopRightDiagonalHotRegion(float fromX, float fromY) {
        final int hotWidth = Math.max(mSwipeDistanceThreshold * 5,
                Math.round(screenWidth * TOP_RIGHT_DIAGONAL_HOT_WIDTH_RATIO));
        final int hotHeight = Math.max(mSwipeDistanceThreshold * 4,
                Math.round(screenHeight * TOP_RIGHT_DIAGONAL_HOT_HEIGHT_RATIO));
        return fromX >= screenWidth - hotWidth
                && fromY <= hotHeight;
    }

    private boolean detectThumbPullDown(float fromX, float fromY, float x, float y, long elapsed,
            float size) {
        final boolean hasTouchSize = hasTouchSize(size);
        if (!isThumbPullDownEnabled()
                || elapsed >= THUMB_PULL_DOWN_TIMEOUT_MS
                || fromY < Math.max(mSwipeStartThreshold.top * 2, THUMB_PULL_DOWN_MIN_START_Y)
                || !isInThumbSideArea(fromX)
                || (hasTouchSize && !isLargeTouch(size))) {
            return false;
        }
        final float deltaX = x - fromX;
        final float deltaY = y - fromY;
        final float minDistance = hasTouchSize
                ? Math.max(mSwipeDistanceThreshold * 1.5f,
                        THUMB_PULL_DOWN_TOUCH_SIZE_MIN_DISTANCE_PX)
                : Math.max(mSwipeDistanceThreshold * 3f,
                        THUMB_PULL_DOWN_FALLBACK_MIN_DISTANCE_PX);
        final float verticalRatio = hasTouchSize
                ? THUMB_PULL_DOWN_TOUCH_SIZE_VERTICAL_RATIO
                : THUMB_PULL_DOWN_FALLBACK_VERTICAL_RATIO;
        final float maxHorizontalDistance = Math.max(mSwipeDistanceThreshold * 2f,
                screenWidth * THUMB_PULL_DOWN_MAX_HORIZONTAL_RATIO);
        return deltaY > minDistance
                && Math.abs(deltaX) <= maxHorizontalDistance
                && isInThumbSideArea(x, THUMB_SIDE_TRACK_EXTRA_RATIO)
                && deltaY > Math.max(1f, Math.abs(deltaX)) * verticalRatio;
    }

    private boolean hasTouchSize(float size) {
        return size >= 0.10f;
    }

    private boolean isLargeTouch(float size) {
        return size > getThumbTouchSizeThreshold();
    }

    private boolean isInThumbSideArea(float x) {
        return isInThumbSideArea(x, 0f);
    }

    private boolean isInThumbSideArea(float x, float extraRatio) {
        if (screenWidth <= 0) {
            return false;
        }
        final float triggerWidth = screenWidth * (getThumbSideAreaRatio() + extraRatio);
        return x <= triggerWidth || x >= screenWidth - triggerWidth;
    }

    private boolean isThumbPullDownEnabled() {
        return isSidebarEnabled()
                && Boolean.parseBoolean(Settings.Global.getString(mContext.getContentResolver(),
                        THUMB_PUSH_DOWN));
    }

    private boolean isSidebarEnabled() {
        return Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1) == 1;
    }

    private float getThumbTouchSizeThreshold() {
        int index = Settings.Global.getInt(mContext.getContentResolver(), THUMB_TRIGGER_AREA, 2);
        if (index < 0) {
            index = 0;
        } else if (index >= THUMB_TOUCH_SIZE_THRESHOLDS.length) {
            index = THUMB_TOUCH_SIZE_THRESHOLDS.length - 1;
        }
        return THUMB_TOUCH_SIZE_THRESHOLDS[index];
    }

    private float getThumbSideAreaRatio() {
        int index = Settings.Global.getInt(mContext.getContentResolver(), THUMB_TRIGGER_AREA, 2);
        if (index < 0) {
            index = 0;
        } else if (index >= THUMB_SIDE_AREA_RATIOS.length) {
            index = THUMB_SIDE_AREA_RATIOS.length - 1;
        }
        return THUMB_SIDE_AREA_RATIOS[index];
    }

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        final String inner = prefix  + "  ";
        pw.println(prefix + TAG + ":");
        pw.print(inner); pw.print("mDisplayCutoutTouchableRegionSize=");
        pw.println(mDisplayCutoutTouchableRegionSize);
        pw.print(inner); pw.print("mSwipeStartThreshold="); pw.println(mSwipeStartThreshold);
        pw.print(inner); pw.print("mSwipeDistanceThreshold="); pw.println(mSwipeDistanceThreshold);
    }

    private final class FlingGestureDetector extends GestureDetector.SimpleOnGestureListener {

        private OverScroller mOverscroller;

        FlingGestureDetector() {
            mOverscroller = new OverScroller(mContext);
        }

        @Override
        public boolean onSingleTapUp(MotionEvent e) {
            if (!mOverscroller.isFinished()) {
                mOverscroller.forceFinished(true);
            }
            return true;
        }
        @Override
        public boolean onFling(MotionEvent down, MotionEvent up,
                float velocityX, float velocityY) {
            mOverscroller.computeScrollOffset();
            long now = SystemClock.uptimeMillis();

            if (mLastFlingTime != 0 && now > mLastFlingTime + MAX_FLING_TIME_MILLIS) {
                mOverscroller.forceFinished(true);
            }
            mOverscroller.fling(0, 0, (int)velocityX, (int)velocityY,
                    Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
            int duration = mOverscroller.getDuration();
            if (duration > MAX_FLING_TIME_MILLIS) {
                duration = MAX_FLING_TIME_MILLIS;
            }
            mLastFlingTime = now;
            mCallbacks.onFling(duration);
            return true;
        }
    }

    interface Callbacks {
        void onSwipeFromTop();
        void onSwipeFromBottom();
        void onSwipeFromRight();
        void onSwipeFromLeft();
        void onSwipeUpFromZoomOutTopRight();
        void onThumbPullDown();
        void onSwipeFromTopRightDiagonal();
        void onFling(int durationMs);
        void onDown();
        void onUpOrCancel();
        void onMouseHoverAtLeft();
        void onMouseHoverAtTop();
        void onMouseHoverAtRight();
        void onMouseHoverAtBottom();
        void onMouseLeaveFromLeft();
        void onMouseLeaveFromTop();
        void onMouseLeaveFromRight();
        void onMouseLeaveFromBottom();
        void onDebug();
    }
}
