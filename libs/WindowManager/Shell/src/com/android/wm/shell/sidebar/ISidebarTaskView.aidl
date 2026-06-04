package com.android.wm.shell.sidebar;

import android.graphics.Rect;
import android.view.SurfaceControl;

/**
 * Interface exposed by WMShell for OneStep sidebar task embedding.
 */
interface ISidebarTaskView {
    oneway void setSlotSurface(int slotIndex, in SurfaceControl surface, in Rect bounds,
            int displayId);
    oneway void clearSlotSurface(int slotIndex);
    oneway void moveTaskToSlot(int taskId, int slotIndex);
    oneway void releaseTaskFromSlot(int taskId);
    oneway void releaseAllTasksFromSlots();
    oneway void releaseAllTasksFromSlotsToBack();
    oneway void setSidebarZoom(float scaleX, float scaleY, float offsetX, float offsetY,
            boolean enabled);
}
