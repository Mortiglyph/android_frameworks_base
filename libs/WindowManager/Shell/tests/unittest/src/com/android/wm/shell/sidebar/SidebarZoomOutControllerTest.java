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

import static android.view.Display.DEFAULT_DISPLAY;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.view.Display;
import android.view.Surface;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.sysui.ShellInit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 *  atest WMShellUnitTests_sidebar
 *  atest WMShellUnitTests:SidebarZoomOutControllerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class SidebarZoomOutControllerTest extends ShellTestCase {

    private static final float SCALE_X = 0.75f;
    private static final float SCALE_Y = 0.75f;
    private static final float OFFSET_X = 16f;
    private static final float OFFSET_Y = 24f;

    @Mock private DisplayController mDisplayController;
    @Mock private SidebarZoomOutDisplayAreaOrganizer mDisplayAreaOrganizer;

    private TestShellExecutor mExecutor;
    private ShellInit mShellInit;
    private SidebarZoomOutController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mExecutor = new TestShellExecutor();
        mShellInit = new ShellInit(mExecutor);
        when(mDisplayController.getDisplayLayout(anyInt())).thenReturn(createDisplayLayout());

        mController = new SidebarZoomOutController(mContext, mShellInit, mDisplayController,
                mDisplayAreaOrganizer, mExecutor);
    }

    @Test
    public void init_registersDisplayCallbacksAndAppliesInitialDisplayLayout() {
        mShellInit.init();

        verify(mDisplayController).addDisplayWindowListener(
                org.mockito.ArgumentMatchers.any());
        verify(mDisplayController).addDisplayChangingController(mController);
        verify(mDisplayAreaOrganizer).setDisplayLayout(org.mockito.ArgumentMatchers.any());
    }

    @Test
    public void setSidebarTransform_enabled_registersOrganizerAndAppliesTransform() {
        mController.asSidebarZoomOut().setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
        mExecutor.flushAll();

        verify(mDisplayAreaOrganizer).registerOrganizer();
        verify(mDisplayAreaOrganizer).setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
    }

    @Test
    public void setSidebarTransform_enabledTwice_registersOrganizerOnce() {
        mController.asSidebarZoomOut().setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
        mController.asSidebarZoomOut().setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
        mExecutor.flushAll();

        verify(mDisplayAreaOrganizer).registerOrganizer();
        verify(mDisplayAreaOrganizer, org.mockito.Mockito.times(2)).setSidebarTransform(SCALE_X,
                SCALE_Y, OFFSET_X, OFFSET_Y, true);
    }

    @Test
    public void setSidebarTransform_disabledAfterEnabled_appliesResetAndUnregistersOrganizer() {
        mController.asSidebarZoomOut().setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
        mExecutor.flushAll();

        mController.asSidebarZoomOut().setSidebarTransform(1f, 1f, 0f, 0f, false);
        mExecutor.flushAll();

        verify(mDisplayAreaOrganizer).setSidebarTransform(1f, 1f, 0f, 0f, false);
        verify(mDisplayAreaOrganizer).unregisterOrganizer();
    }

    @Test
    public void setSidebarTransform_registerFails_doesNotApplyTransform() {
        doThrow(new RuntimeException("register failed")).when(mDisplayAreaOrganizer)
                .registerOrganizer();

        mController.asSidebarZoomOut().setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
        mExecutor.flushAll();

        verify(mDisplayAreaOrganizer, never()).setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X,
                OFFSET_Y, true);
        verify(mDisplayAreaOrganizer, never()).unregisterOrganizer();
    }

    @Test
    public void onDefaultDisplayRotationChanged_rotatesOrganizer() {
        mController.onDisplayChange(DEFAULT_DISPLAY, Surface.ROTATION_0, Surface.ROTATION_90,
                null, new WindowContainerTransaction());

        verify(mDisplayAreaOrganizer).onRotateDisplay(mContext, Surface.ROTATION_90);
    }

    @Test
    public void onDefaultDisplayRotationChanged_whileEnabled_reappliesCurrentTransform() {
        mController.asSidebarZoomOut().setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X, OFFSET_Y,
                true);
        mExecutor.flushAll();

        mController.onDisplayChange(DEFAULT_DISPLAY, Surface.ROTATION_0, Surface.ROTATION_90,
                null, new WindowContainerTransaction());

        verify(mDisplayAreaOrganizer).onRotateDisplay(mContext, Surface.ROTATION_90);
        verify(mDisplayAreaOrganizer, times(2)).setSidebarTransform(SCALE_X, SCALE_Y, OFFSET_X,
                OFFSET_Y, true);
    }

    @Test
    public void onSecondaryDisplayRotationChanged_doesNotRotateOrganizer() {
        mController.onDisplayChange(DEFAULT_DISPLAY + 1, Surface.ROTATION_0, Surface.ROTATION_90,
                null, new WindowContainerTransaction());

        verify(mDisplayAreaOrganizer, never()).onRotateDisplay(mContext, Surface.ROTATION_90);
    }

    private DisplayLayout createDisplayLayout() {
        final Display display = mContext.getDisplay();
        return new DisplayLayout(mContext, display);
    }
}
