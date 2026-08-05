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

package com.android.server.wm;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.DisplayInfo;
import android.view.MotionEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.runner.AndroidJUnit4;

/**
 * Build/Install/Run:
 *  atest WmTests:com.android.server.wm.SystemGesturesPointerEventListenerTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemGesturesPointerEventListenerTest {

    private static final String SIDEBAR_ENABLED = "side_bar_mode";
    private static final String SIDEBAR_ZOOM_TYPE = "side_bar_zoom_type";
    private static final String SIDEBAR_ZOOM_SCALE_X = "side_bar_zoom_scale_x";
    private static final String SIDEBAR_ZOOM_SCALE_Y = "side_bar_zoom_scale_y";
    private static final String SIDEBAR_ZOOM_OFFSET_X = "side_bar_zoom_offset_x";
    private static final String SIDEBAR_ZOOM_OFFSET_Y = "side_bar_zoom_offset_y";
    private static final String SIDEBAR_ZOOM_DISPLAY_WIDTH = "side_bar_zoom_display_width";
    private static final String SIDEBAR_ZOOM_DISPLAY_HEIGHT = "side_bar_zoom_display_height";
    private static final int SIDEBAR_MODE_RIGHT = 2;
    private static final int DISPLAY_WIDTH = 1600;
    private static final int DISPLAY_HEIGHT = 2560;

    private Context mContext;
    private TestCallbacks mCallbacks;
    private SystemGesturesPointerEventListener mListener;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mCallbacks = new TestCallbacks();
        mListener = new SystemGesturesPointerEventListener(mContext,
                new Handler(Looper.getMainLooper()), mCallbacks);

        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.logicalWidth = DISPLAY_WIDTH;
        displayInfo.logicalHeight = DISPLAY_HEIGHT;
        mListener.onDisplayInfoChanged(displayInfo);

        Settings.Global.putInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1);
    }

    @After
    public void tearDown() {
        clearGlobalSetting(SIDEBAR_ZOOM_TYPE);
        clearGlobalSetting(SIDEBAR_ZOOM_SCALE_X);
        clearGlobalSetting(SIDEBAR_ZOOM_SCALE_Y);
        clearGlobalSetting(SIDEBAR_ZOOM_OFFSET_X);
        clearGlobalSetting(SIDEBAR_ZOOM_OFFSET_Y);
        clearGlobalSetting(SIDEBAR_ZOOM_DISPLAY_WIDTH);
        clearGlobalSetting(SIDEBAR_ZOOM_DISPLAY_HEIGHT);
    }

    @Test
    public void zoomOutTopRightExitGesture_usesVisibleZoomFrame() {
        writeZoomState();

        sendTouch(MotionEvent.ACTION_DOWN, 980f, 240f, 0L);
        sendTouch(MotionEvent.ACTION_MOVE, 970f, 80f, 80L);

        assertThat(mCallbacks.mSwipeUpFromZoomOutTopRightCount).isEqualTo(1);
    }

    @Test
    public void zoomOutTopRightExitGesture_isNotConsumedByRightEdgeGesture() {
        writeZoomState();

        sendTouch(MotionEvent.ACTION_DOWN, 980f, 240f, 0L);
        sendTouch(MotionEvent.ACTION_MOVE, 900f, 80f, 80L);

        assertThat(mCallbacks.mSwipeUpFromZoomOutTopRightCount).isEqualTo(1);
        assertThat(mCallbacks.mSwipeFromRightCount).isEqualTo(0);
    }

    @Test
    public void zoomOutTopEdgeGesture_usesVisibleZoomFrame() {
        writeZoomState();

        sendTouch(MotionEvent.ACTION_DOWN, 400f, 220f, 0L);
        sendTouch(MotionEvent.ACTION_MOVE, 400f, 360f, 80L);

        assertThat(mCallbacks.mSwipeFromTopCount).isEqualTo(1);
    }

    @Test
    public void zoomOutRightEdgeGesture_usesVisibleZoomFrame() {
        writeZoomState();

        sendTouch(MotionEvent.ACTION_DOWN, 980f, 1000f, 0L);
        sendTouch(MotionEvent.ACTION_MOVE, 820f, 1000f, 80L);

        assertThat(mCallbacks.mSwipeFromRightCount).isEqualTo(1);
    }

    private void sendTouch(int action, float x, float y, long eventTime) {
        final MotionEvent event = MotionEvent.obtain(0L, eventTime, action, x, y, 0);
        mListener.onPointerEvent(event);
        event.recycle();
    }

    private void writeZoomState() {
        Settings.Global.putInt(mContext.getContentResolver(), SIDEBAR_ZOOM_TYPE,
                SIDEBAR_MODE_RIGHT);
        Settings.Global.putFloat(mContext.getContentResolver(), SIDEBAR_ZOOM_SCALE_X, 0.6f);
        Settings.Global.putFloat(mContext.getContentResolver(), SIDEBAR_ZOOM_SCALE_Y, 0.7f);
        Settings.Global.putFloat(mContext.getContentResolver(), SIDEBAR_ZOOM_OFFSET_X, 30f);
        Settings.Global.putFloat(mContext.getContentResolver(), SIDEBAR_ZOOM_OFFSET_Y, 200f);
        Settings.Global.putInt(mContext.getContentResolver(), SIDEBAR_ZOOM_DISPLAY_WIDTH,
                DISPLAY_WIDTH);
        Settings.Global.putInt(mContext.getContentResolver(), SIDEBAR_ZOOM_DISPLAY_HEIGHT,
                DISPLAY_HEIGHT);
    }

    private void clearGlobalSetting(String key) {
        Settings.Global.putString(mContext.getContentResolver(), key, null);
    }

    private static final class TestCallbacks
            implements SystemGesturesPointerEventListener.Callbacks {
        int mSwipeFromTopCount;
        int mSwipeFromRightCount;
        int mSwipeUpFromZoomOutTopRightCount;

        @Override
        public void onSwipeFromTop() {
            mSwipeFromTopCount++;
        }

        @Override
        public void onSwipeFromBottom() {
        }

        @Override
        public void onSwipeFromRight() {
            mSwipeFromRightCount++;
        }

        @Override
        public void onSwipeFromLeft() {
        }

        @Override
        public void onSwipeUpFromZoomOutTopRight() {
            mSwipeUpFromZoomOutTopRightCount++;
        }

        @Override
        public void onThumbPullDown() {
        }

        @Override
        public void onSwipeFromTopRightDiagonal() {
        }

        @Override
        public void onFling(int durationMs) {
        }

        @Override
        public void onDown() {
        }

        @Override
        public void onUpOrCancel() {
        }

        @Override
        public void onMouseHoverAtLeft() {
        }

        @Override
        public void onMouseHoverAtTop() {
        }

        @Override
        public void onMouseHoverAtRight() {
        }

        @Override
        public void onMouseHoverAtBottom() {
        }

        @Override
        public void onMouseLeaveFromLeft() {
        }

        @Override
        public void onMouseLeaveFromTop() {
        }

        @Override
        public void onMouseLeaveFromRight() {
        }

        @Override
        public void onMouseLeaveFromBottom() {
        }

        @Override
        public void onDebug() {
        }
    }
}
