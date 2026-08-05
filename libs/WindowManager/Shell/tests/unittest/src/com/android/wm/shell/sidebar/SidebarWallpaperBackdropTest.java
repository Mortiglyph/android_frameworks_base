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

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class SidebarWallpaperBackdropTest extends ShellTestCase {

    private static final float TOLERANCE = 0.001f;

    @Test
    public void getBackdropScrimAlphaForScale_atFullScale_isTransparent() {
        assertThat(SidebarWallpaperBackdrop.getBackdropScrimAlphaForScale(1f, 1f))
                .isWithin(TOLERANCE)
                .of(0f);
    }

    @Test
    public void getBackdropScrimAlphaForScale_atSidebarScale_reachesReadableScrim() {
        assertThat(SidebarWallpaperBackdrop.getBackdropScrimAlphaForScale(0.82f, 0.82f))
                .isWithin(TOLERANCE)
                .of(0.24f);
    }

    @Test
    public void getBackdropScrimAlphaForScale_belowSidebarScale_clampsToReadableScrim() {
        assertThat(SidebarWallpaperBackdrop.getBackdropScrimAlphaForScale(0.7f, 0.7f))
                .isWithin(TOLERANCE)
                .of(0.24f);
    }

    @Test
    public void shouldReloadStaticWallpaper_afterStaticWallpaperInvalidated() {
        assertThat(SidebarWallpaperBackdrop.shouldSubmitStaticWallpaperBuffer(
                1600, 2560, false, 1600, 2560, 0, 0)).isFalse();

        assertThat(SidebarWallpaperBackdrop.shouldSubmitStaticWallpaperBuffer(
                1600, 2560, false, 0, 0, 0, 0)).isTrue();
    }
}
