package com.android.internal.sidebar;

import android.content.Intent;
import android.os.IBinder;
import com.android.internal.sidebar.ISidebar;

/** @hide */
interface ISidebarService {
    void registerSidebar(in ISidebar sidebar);
    void registerSidebarTaskViewShell(in IBinder shell);
    IBinder getSidebarTaskViewShell();
    void enterRightSidebar(int flags);
    void exitSidebar(int flags);
    void handleSidebarShareList();
    void showGlobalShare(in Intent intent);
    boolean handleRightEdgeSwipe();
    boolean handleZoomOutTopRightSwipeUp();
}
