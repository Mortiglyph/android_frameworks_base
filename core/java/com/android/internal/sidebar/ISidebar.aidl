package com.android.internal.sidebar;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.FooDisplayResultInfo;
import com.android.internal.sidebar.ILauncher;

/** @hide */
interface ISidebar {
    void contentWindowOnTouch(int action, in float[] points);
    void dismissFooResultDisplay();
    void fooDisplay(in FooDisplayResultInfo info);
    Bitmap getSidebarBackground();
    void handleSidebarShareList();
    Bundle noticeSidebarIconFloat(in ILauncher launcher, in Bundle bundle);
    void onEnterSidebarMode(int mode, int flags);
    void onExitSidebarMode(int flags);
    void resumeSidebar();
    void setEnabled(boolean enabled);
    void showGlobalShare(in Intent intent);
    void updateOngoing(in ComponentName componentName, int id, int userId,
            CharSequence title, int number);
}
