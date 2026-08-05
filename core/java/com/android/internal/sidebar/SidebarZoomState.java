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

package com.android.internal.sidebar;

import android.content.ContentResolver;
import android.graphics.Rect;
import android.provider.Settings;

/** Shared sidebar zoom state used by system gesture routing. */
public final class SidebarZoomState {
    public static final String SIDEBAR_ZOOM_TYPE = "side_bar_zoom_type";
    public static final String SIDEBAR_ZOOM_SCALE_X = "side_bar_zoom_scale_x";
    public static final String SIDEBAR_ZOOM_SCALE_Y = "side_bar_zoom_scale_y";
    public static final String SIDEBAR_ZOOM_OFFSET_X = "side_bar_zoom_offset_x";
    public static final String SIDEBAR_ZOOM_OFFSET_Y = "side_bar_zoom_offset_y";
    public static final String SIDEBAR_ZOOM_DISPLAY_WIDTH = "side_bar_zoom_display_width";
    public static final String SIDEBAR_ZOOM_DISPLAY_HEIGHT = "side_bar_zoom_display_height";
    public static final int TYPE_ZOOM_INVALID = -1;

    private SidebarZoomState() {
    }

    public static void write(ContentResolver resolver, int type, float scaleX, float scaleY,
            float offsetX, float offsetY, int displayWidth, int displayHeight) {
        if (resolver == null || type == TYPE_ZOOM_INVALID) {
            clear(resolver);
            return;
        }
        Settings.Global.putInt(resolver, SIDEBAR_ZOOM_TYPE, type);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_SCALE_X, scaleX);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_SCALE_Y, scaleY);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_OFFSET_X, offsetX);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_OFFSET_Y, offsetY);
        Settings.Global.putInt(resolver, SIDEBAR_ZOOM_DISPLAY_WIDTH, Math.max(0, displayWidth));
        Settings.Global.putInt(resolver, SIDEBAR_ZOOM_DISPLAY_HEIGHT, Math.max(0, displayHeight));
    }

    public static void clear(ContentResolver resolver) {
        if (resolver == null) {
            return;
        }
        Settings.Global.putInt(resolver, SIDEBAR_ZOOM_TYPE, TYPE_ZOOM_INVALID);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_SCALE_X, 1f);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_SCALE_Y, 1f);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_OFFSET_X, 0f);
        Settings.Global.putFloat(resolver, SIDEBAR_ZOOM_OFFSET_Y, 0f);
        Settings.Global.putInt(resolver, SIDEBAR_ZOOM_DISPLAY_WIDTH, 0);
        Settings.Global.putInt(resolver, SIDEBAR_ZOOM_DISPLAY_HEIGHT, 0);
    }

    public static boolean isZoomEnabled(ContentResolver resolver) {
        return resolver != null && Settings.Global.getInt(resolver, SIDEBAR_ZOOM_TYPE,
                TYPE_ZOOM_INVALID) != TYPE_ZOOM_INVALID;
    }

    public static boolean getVisibleFrame(ContentResolver resolver, int displayWidth,
            int displayHeight, Rect outFrame) {
        if (outFrame == null) {
            return false;
        }
        outFrame.setEmpty();
        if (!isZoomEnabled(resolver) || displayWidth <= 0 || displayHeight <= 0) {
            return false;
        }
        final float scaleX = Settings.Global.getFloat(resolver, SIDEBAR_ZOOM_SCALE_X, 1f);
        final float scaleY = Settings.Global.getFloat(resolver, SIDEBAR_ZOOM_SCALE_Y, 1f);
        if (!isValidScale(scaleX) || !isValidScale(scaleY)) {
            return false;
        }
        final int storedWidth = Settings.Global.getInt(resolver, SIDEBAR_ZOOM_DISPLAY_WIDTH,
                displayWidth);
        final int storedHeight = Settings.Global.getInt(resolver, SIDEBAR_ZOOM_DISPLAY_HEIGHT,
                displayHeight);
        final float widthRatio = storedWidth > 0 ? displayWidth / (float) storedWidth : 1f;
        final float heightRatio = storedHeight > 0 ? displayHeight / (float) storedHeight : 1f;
        final float offsetX = Settings.Global.getFloat(resolver, SIDEBAR_ZOOM_OFFSET_X, 0f)
                * widthRatio;
        final float offsetY = Settings.Global.getFloat(resolver, SIDEBAR_ZOOM_OFFSET_Y, 0f)
                * heightRatio;

        final int left = clamp(Math.round(offsetX), 0, displayWidth);
        final int top = clamp(Math.round(offsetY), 0, displayHeight);
        final int right = clamp(Math.round(offsetX + displayWidth * scaleX), 0, displayWidth);
        final int bottom = clamp(Math.round(offsetY + displayHeight * scaleY), 0, displayHeight);
        if (right <= left || bottom <= top) {
            return false;
        }
        outFrame.set(left, top, right, bottom);
        return true;
    }

    private static boolean isValidScale(float scale) {
        return scale > 0f && scale <= 1f && !Float.isNaN(scale) && !Float.isInfinite(scale);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
