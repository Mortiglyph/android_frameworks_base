/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.systemui.statusbar.phone

import android.app.StatusBarManager.WINDOW_STATUS_BAR
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import android.view.Display.DEFAULT_DISPLAY
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.VisibleForTesting
import com.android.systemui.Gefingerpoken
import com.android.systemui.battery.BatteryMeterView
import com.android.systemui.display.dagger.SystemUIDisplaySubcomponent.DisplayAware
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.ui.view.WindowRootView
import com.android.systemui.shade.ShadeController
import com.android.systemui.shade.ShadeExpandsOnStatusBarLongPress
import com.android.systemui.shade.ShadeLogger
import com.android.systemui.shade.ShadeViewController
import com.android.systemui.shade.StatusBarLongPressGestureDetector
import com.android.systemui.shade.data.repository.ShadeDisplaysRepository
import com.android.systemui.shade.display.StatusBarTouchShadeDisplayPolicy
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.ShadeModeInteractor
import com.android.systemui.shade.shared.flag.ShadeWindowGoesAround
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController
import com.android.systemui.statusbar.data.repository.StatusBarContentInsetsProviderStore
import com.android.systemui.statusbar.policy.Clock
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.statusbar.window.StatusBarWindowStateController
import com.android.systemui.unfold.UNFOLD_STATUS_BAR
import com.android.systemui.unfold.util.ScopedUnfoldTransitionProgressProvider
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel
import com.android.systemui.util.ViewController
import com.android.systemui.util.kotlin.getOrNull
import com.android.systemui.util.view.ViewUtil
import com.android.internal.sidebar.SidebarZoomState
import dagger.Lazy
import java.util.Optional
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

private const val TAG = "PhoneStatusBarViewController"
private const val SIDEBAR_ENABLED = "side_bar_mode"
private const val SIDEBAR_TOP_RIGHT_GESTURE_HOT_SIZE_MM = 10f
private const val MM_PER_INCH = 25.4f
private const val SIDEBAR_TOP_RIGHT_GESTURE_MIN_HORIZONTAL_RATIO = 0.75f
private const val SIDEBAR_TOP_RIGHT_GESTURE_MAX_HORIZONTAL_RATIO = 5.0f
private const val SIDEBAR_TOP_RIGHT_SHADE_MIN_VERTICAL_TOUCH_SLOP_MULTIPLIER = 3f
private const val SIDEBAR_TOP_RIGHT_SHADE_MAX_HORIZONTAL_RATIO = 0.35f

private enum class SidebarTopRightGestureState {
    NONE,
    DEFER,
    IGNORE,
}

/** Controller for [PhoneStatusBarView]. */
class PhoneStatusBarViewController
private constructor(
    view: PhoneStatusBarView,
    @Named(UNFOLD_STATUS_BAR) private val progressProvider: ScopedUnfoldTransitionProgressProvider?,
    private val centralSurfaces: CentralSurfaces,
    private val statusBarWindowStateController: StatusBarWindowStateController,
    private val shadeController: ShadeController,
    private val shadeViewController: ShadeViewController,
    private val shadeModeInteractor: ShadeModeInteractor,
    private val panelExpansionInteractor: PanelExpansionInteractor,
    private val statusBarLongPressGestureDetector: Provider<StatusBarLongPressGestureDetector>,
    private val windowRootView: Provider<WindowRootView>,
    private val shadeLogger: ShadeLogger,
    private val userChipViewModel: StatusBarUserChipViewModel,
    private val viewUtil: ViewUtil,
    private val configurationController: ConfigurationController,
    private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
    private val darkIconDispatcher: DarkIconDispatcher,
    private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
    private val lazyStatusBarShadeDisplayPolicy: Lazy<StatusBarTouchShadeDisplayPolicy>,
    private val lazyShadeDisplaysRepository: Lazy<ShadeDisplaysRepository>,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
) : ViewController<PhoneStatusBarView>(view) {

    private lateinit var battery: BatteryMeterView
    private lateinit var clock: Clock
    private lateinit var clockCenter: Clock
    private lateinit var clockRight: Clock
    private lateinit var startSideContainer: View
    private lateinit var endSideContainer: View
    private val statusBarContentInsetsProvider
        get() = statusBarContentInsetsProviderStore.forDisplay(context.displayId)

    // Creates a [View.OnTouchListener] that only handles mouse click events.
    private fun createMouseClickListener(onClick: () -> Unit): View.OnTouchListener =
        object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                // We want to handle only mouse events here to avoid stealing finger touches
                // from status bar which expands shade when swiped down on. See b/326097469.
                // We're using onTouchListener instead of onClickListener as the later will lead
                // to isClickable being set to true and hence ALL touches always being
                // intercepted. See [View.OnTouchEvent]
                if (event.source == InputDevice.SOURCE_MOUSE) {
                    if (event.action == MotionEvent.ACTION_UP) {
                        dispatchEventToShadeDisplayPolicy(event)
                        v.performClick()
                        onClick()
                    }
                    return true
                }
                return false
            }
        }

    // Creates a [View.OnTouchListener] that handles mouse clicks and finger taps.
    private fun createClickListener(v: View, onClick: () -> Unit): View.OnTouchListener {
        val gestureDetector =
            GestureDetector(
                mView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean {
                        // Return true here to receive subsequent events, which are then
                        // handled by onSingleTapUp.
                        return true
                    }

                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        dispatchEventToShadeDisplayPolicy(e)
                        v.performClick()
                        onClick()
                        return true
                    }
                },
            )
        return View.OnTouchListener { _, event ->
            // Handle mouse clicks separately.
            if (event.source == InputDevice.SOURCE_MOUSE) {
                if (event.action == MotionEvent.ACTION_UP) {
                    dispatchEventToShadeDisplayPolicy(event)
                    v.performClick()
                    onClick()
                }
                return@OnTouchListener true
            }

            // For all other (touch) events, delegate to the GestureDetector.
            return@OnTouchListener gestureDetector.onTouchEvent(event)
        }
    }

    private fun dispatchEventToShadeDisplayPolicy(event: MotionEvent) {
        if (ShadeWindowGoesAround.isEnabled) {
            // Notify the shade display policy that the status bar was touched. This may cause
            // the shade to change display if the touch was in a display different than the shade
            // one.
            lazyStatusBarShadeDisplayPolicy.get().onStatusBarOrLauncherTouched(event, mView.width)
        }
    }

    private val configurationListener =
        object : ConfigurationController.ConfigurationListener {
            override fun onDensityOrFontScaleChanged() {
                ShadeWindowGoesAround.assertInLegacyMode()
                clock.onDensityOrFontScaleChanged()
            }
        }

    override fun onViewAttached() {
        clock = mView.requireViewById(R.id.clock)
        clockCenter = mView.requireViewById(R.id.clock_center)
        clockRight = mView.requireViewById(R.id.clock_right)
        battery = mView.requireViewById(R.id.battery)

        addDarkReceivers()

        if (
            StatusBarConnectedDisplays.isEnabled && mView.context.getDisplayId() != DEFAULT_DISPLAY
        ) {
            // With the StatusBarConnectedDisplays changes, external status bar elements are not
            // interactive when the shade window can't change displays.
            mView.setIsStatusBarInteractiveSupplier {
                val shadeDisplayPolicy =
                    if (ShadeWindowGoesAround.isEnabled) {
                        lazyShadeDisplaysRepository.get().currentPolicy
                    } else null
                shadeDisplayPolicy is StatusBarTouchShadeDisplayPolicy
            }
        }

        addCursorSupportToIconContainers()
        if (ShadeExpandsOnStatusBarLongPress.isEnabled) {
            mView.setLongPressGestureDetector(statusBarLongPressGestureDetector.get())
        }

        progressProvider?.setReadyToHandleTransition(true)
        if (!ShadeWindowGoesAround.isEnabled) {
            // the clock handles the config change itself.
            configurationController.addCallback(configurationListener)
        }
        if (!StatusBarConnectedDisplays.isEnabled) {
            mView.setStatusBarWindowControllerStore(statusBarWindowControllerStore)
        }
    }

    private fun addCursorSupportToIconContainers() {
        endSideContainer = mView.requireViewById(R.id.system_icons)
        endSideContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createDarkAwareListener(endSideContainer)
        )

        if (statusBarTapToExpandShadeEnabled()) {
            endSideContainer.setOnTouchListener(
                createClickListener(endSideContainer) { animateExpandQs() }
            )
        } else {
            endSideContainer.setOnTouchListener(createMouseClickListener { animateExpandQs() })
        }

        startSideContainer = mView.requireViewById(R.id.status_bar_start_side_content)
        startSideContainer.setOnHoverListener(
            statusOverlayHoverListenerFactory.createDarkAwareListener(
                startSideContainer,
                topHoverMargin = 6,
                bottomHoverMargin = 6,
            )
        )
        if (statusBarTapToExpandShadeEnabled()) {
            startSideContainer.setOnTouchListener(
                createClickListener(startSideContainer) { shadeController.animateExpandShade() }
            )
        } else {
            startSideContainer.setOnTouchListener(
                createMouseClickListener { shadeController.animateExpandShade() }
            )
        }
    }

    private fun statusBarTapToExpandShadeEnabled(): Boolean {
        return context.resources.getBoolean(R.bool.config_statusBarTapToExpandShade)
    }

    private fun animateExpandQs() {
        if (shadeModeInteractor.isDualShade) {
            shadeController.animateExpandQs()
        } else {
            shadeController.animateExpandShade()
        }
    }

    @VisibleForTesting
    public override fun onViewDetached() {
        removeDarkReceivers()
        startSideContainer.setOnHoverListener(null)
        endSideContainer.setOnHoverListener(null)
        progressProvider?.setReadyToHandleTransition(false)
        if (!ShadeWindowGoesAround.isEnabled) {
            configurationController.removeCallback(configurationListener)
        }
    }

    init {
        // These should likely be done in `onInit`, not `init`.
        mView.setTouchEventHandler(PhoneStatusBarViewTouchHandler())
        statusBarContentInsetsProvider?.let {
            mView.setHasCornerCutoutFetcher { it.currentRotationHasCornerCutout() }
            mView.setInsetsFetcher { it.getStatusBarContentInsetsForCurrentRotation() }
        }
        mView.init(userChipViewModel)
    }

    override fun onInit() {}

    fun setImportantForAccessibility(mode: Int) {
        mView.importantForAccessibility = mode
    }

    /**
     * Sends a touch event to the status bar view.
     *
     * This is required in certain cases because the status bar view is in a separate window from
     * the rest of SystemUI, and other windows may decide that their touch should instead be treated
     * as a status bar window touch.
     */
    fun sendTouchToView(ev: MotionEvent): Boolean {
        return mView.dispatchTouchEvent(ev)
    }

    /**
     * Returns true if the given (x, y) point (in screen coordinates) is within the status bar
     * view's range and false otherwise.
     */
    fun touchIsWithinView(x: Float, y: Float): Boolean {
        return viewUtil.touchIsWithinView(mView, x, y)
    }

    /** Called when a touch event occurred on {@link PhoneStatusBarView}. */
    fun onTouch(event: MotionEvent) {
        if (statusBarWindowStateController.windowIsShowing()) {
            val upOrCancel =
                event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL
            centralSurfaces.setInteracting(
                WINDOW_STATUS_BAR,
                !upOrCancel || shadeController.isExpandedVisible,
            )
        }
    }

    private fun addDarkReceivers() {
        darkIconDispatcher.addDarkReceiver(battery)
        darkIconDispatcher.addDarkReceiver(clock)
        darkIconDispatcher.addDarkReceiver(clockCenter)
        darkIconDispatcher.addDarkReceiver(clockRight)
    }

    private fun removeDarkReceivers() {
        darkIconDispatcher.removeDarkReceiver(battery)
        darkIconDispatcher.removeDarkReceiver(clock)
        darkIconDispatcher.removeDarkReceiver(clockCenter)
        darkIconDispatcher.removeDarkReceiver(clockRight)
    }

    inner class PhoneStatusBarViewTouchHandler : Gefingerpoken {
        private val touchSlop = ViewConfiguration.get(mView.context).scaledTouchSlop
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var initialTouchRawX = 0f
        private var initialTouchRawY = 0f
        private var isIntercepting = false
        private var isSidebarTopRightGestureCandidate = false
        private var isIgnoringSidebarTopRightGesture = false
        private var isSidebarTopRightGestureReleasedToShade = false
        private var shouldReplaySidebarTopRightGestureEvents = false
        private val cachedEvents = mutableListOf<MotionEvent>()
        private val sidebarZoomVisibleFrame = Rect()

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (updateSidebarTopRightGestureState(event)) {
                SidebarTopRightGestureState.IGNORE -> {
                    return false
                }
                SidebarTopRightGestureState.DEFER -> {
                    cacheEvent(event)
                    return false
                }
                SidebarTopRightGestureState.NONE -> Unit
            }
            if (event.action == MotionEvent.ACTION_DOWN) {
                dispatchEventToShadeDisplayPolicy(event)
            }

            // Let ShadeViewController intercept touch events when flexiglass is disabled.
            if (!SceneContainerFlag.isEnabled) {
                dispatchCachedInterceptEventsToShade()
                return shadeViewController.handleExternalInterceptTouch(event)
            }

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isIntercepting = false
                    clearCachedEvents()
                    initialTouchX = event.x
                    initialTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.y - initialTouchY
                    if (dy > touchSlop) {
                        if (!isIntercepting) {
                            isIntercepting = true
                            dispatchCachedEvents()
                        }
                        windowRootView.get().dispatchTouchEvent(event)
                        return true
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    clearCachedEvents()
                    isIntercepting = false
                    resetSidebarTopRightGestureState()
                }
            }

            if (!isIntercepting) {
                cacheEvent(event)
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            onTouch(event)
            when (updateSidebarTopRightGestureState(event)) {
                SidebarTopRightGestureState.IGNORE -> {
                    return true
                }
                SidebarTopRightGestureState.DEFER -> {
                    cacheEvent(event)
                    return true
                }
                SidebarTopRightGestureState.NONE -> Unit
            }

            // If panels aren't enabled, ignore the gesture and don't pass it down to the
            // panel view.
            if (!centralSurfaces.commandQueuePanelsEnabled) {
                clearCachedEvents()
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.v(
                        TAG,
                        String.format(
                            "onTouchForwardedFromStatusBar: panel disabled, " +
                                "ignoring touch at (${event.x.toInt()},${event.y.toInt()})"
                        ),
                    )
                }
                return false
            }

            // If scene framework is enabled, route the touch to it and
            // ignore the rest of the gesture.
            if (SceneContainerFlag.isEnabled) {
                if (shouldReplaySidebarTopRightGestureEvents) {
                    dispatchCachedEvents()
                }
                windowRootView.get().dispatchTouchEvent(event)
                return true
            }

            if (event.action == MotionEvent.ACTION_DOWN) {
                // If the view that would receive the touch is disabled, just have status
                // bar eat the gesture.
                if (!shadeViewController.isViewEnabled) {
                    clearCachedEvents()
                    shadeLogger.logMotionEvent(
                        event,
                        "onTouchForwardedFromStatusBar: panel view disabled",
                    )
                    return true
                }
                if (panelExpansionInteractor.isFullyCollapsed && event.y < 1f) {
                    // b/235889526 Eat events on the top edge of the phone when collapsed
                    clearCachedEvents()
                    shadeLogger.logMotionEvent(event, "top edge touch ignored")
                    return true
                }
            }

            if (shouldReplaySidebarTopRightGestureEvents) {
                dispatchCachedEventsToShade()
            }
            return shadeViewController.handleExternalTouch(event)
        }

        private fun cacheEvent(event: MotionEvent) {
            val lastEvent = cachedEvents.lastOrNull()
            if (
                lastEvent != null &&
                    lastEvent.actionMasked == event.actionMasked &&
                    lastEvent.eventTime == event.eventTime &&
                    lastEvent.x == event.x &&
                    lastEvent.y == event.y
            ) {
                return
            }
            cachedEvents.add(MotionEvent.obtain(event))
        }

        private fun dispatchCachedEvents() {
            cachedEvents.forEach { windowRootView.get()?.dispatchTouchEvent(it) }
            clearCachedEvents()
        }

        private fun dispatchCachedInterceptEventsToShade() {
            if (shouldReplaySidebarTopRightGestureEvents) {
                cachedEvents.forEach { shadeViewController.handleExternalInterceptTouch(it) }
                clearCachedEvents()
            }
        }

        private fun dispatchCachedEventsToShade() {
            cachedEvents.forEach { shadeViewController.handleExternalTouch(it) }
            clearCachedEvents()
        }

        private fun clearCachedEvents() {
            cachedEvents.forEach { it.recycle() }
            cachedEvents.clear()
            shouldReplaySidebarTopRightGestureEvents = false
        }

        private fun updateSidebarTopRightGestureState(event: MotionEvent): SidebarTopRightGestureState {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    clearCachedEvents()
                    isIntercepting = false
                    isSidebarTopRightGestureCandidate = isSidebarTopRightGestureRegion(event)
                    isIgnoringSidebarTopRightGesture = false
                    isSidebarTopRightGestureReleasedToShade = false
                    shouldReplaySidebarTopRightGestureEvents = false
                    initialTouchRawX = event.rawX
                    initialTouchRawY = event.rawY
                    initialTouchX = event.x
                    initialTouchY = event.y
                    if (isSidebarTopRightGestureCandidate) {
                        return SidebarTopRightGestureState.DEFER
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (
                        isSidebarTopRightGestureCandidate &&
                            !isIgnoringSidebarTopRightGesture &&
                            !isSidebarTopRightGestureReleasedToShade &&
                            isSidebarTopRightDiagonalGesture(event)
                    ) {
                        isIgnoringSidebarTopRightGesture = true
                        clearCachedEvents()
                        isIntercepting = false
                        shadeController.cancelExpansionAndCollapseShade()
                        return SidebarTopRightGestureState.IGNORE
                    }
                    if (
                        isSidebarTopRightGestureCandidate &&
                            !isIgnoringSidebarTopRightGesture &&
                            !isSidebarTopRightGestureReleasedToShade
                    ) {
                        if (isSidebarTopRightShadeGesture(event)) {
                            isSidebarTopRightGestureReleasedToShade = true
                            shouldReplaySidebarTopRightGestureEvents = true
                        } else {
                            return SidebarTopRightGestureState.DEFER
                        }
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    val wasIgnoring = isIgnoringSidebarTopRightGesture
                    val wasPending =
                        isSidebarTopRightGestureCandidate && !isSidebarTopRightGestureReleasedToShade
                    resetSidebarTopRightGestureState()
                    if (wasIgnoring || wasPending) {
                        clearCachedEvents()
                        return SidebarTopRightGestureState.IGNORE
                    }
                }
            }
            return if (isIgnoringSidebarTopRightGesture) {
                SidebarTopRightGestureState.IGNORE
            } else {
                SidebarTopRightGestureState.NONE
            }
        }

        private fun resetSidebarTopRightGestureState() {
            isSidebarTopRightGestureCandidate = false
            isIgnoringSidebarTopRightGesture = false
            isSidebarTopRightGestureReleasedToShade = false
            shouldReplaySidebarTopRightGestureEvents = false
        }

        private fun isSidebarTopRightGestureRegion(event: MotionEvent): Boolean {
            if (Settings.Global.getInt(context.contentResolver, SIDEBAR_ENABLED, 1) != 1) {
                return false
            }
            val displayMetrics = context.resources.displayMetrics
            if (displayMetrics.widthPixels <= 0 || displayMetrics.heightPixels <= 0) {
                return false
            }
            val hotWidth = mmToPx(SIDEBAR_TOP_RIGHT_GESTURE_HOT_SIZE_MM, displayMetrics.xdpi)
            val hotHeight = mmToPx(SIDEBAR_TOP_RIGHT_GESTURE_HOT_SIZE_MM, displayMetrics.ydpi)
            if (
                SidebarZoomState.getVisibleFrame(
                    context.contentResolver,
                    displayMetrics.widthPixels,
                    displayMetrics.heightPixels,
                    sidebarZoomVisibleFrame,
                )
            ) {
                return event.rawX >= sidebarZoomVisibleFrame.right - hotWidth &&
                    event.rawX <= sidebarZoomVisibleFrame.right + hotWidth &&
                    event.rawY >= sidebarZoomVisibleFrame.top - hotHeight &&
                    event.rawY <= sidebarZoomVisibleFrame.top + hotHeight
            }
            return event.rawX >= displayMetrics.widthPixels - hotWidth && event.rawY <= hotHeight
        }

        private fun mmToPx(mm: Float, dpi: Float): Int {
            val resolvedDpi =
                if (dpi > 0f && !dpi.isNaN() && !dpi.isInfinite()) {
                    dpi
                } else {
                    context.resources.displayMetrics.densityDpi.toFloat().coerceAtLeast(1f)
                }
            return kotlin.math.round(mm * resolvedDpi / MM_PER_INCH).toInt().coerceAtLeast(1)
        }

        private fun isSidebarTopRightDiagonalGesture(event: MotionEvent): Boolean {
            val deltaX = event.rawX - initialTouchRawX
            val deltaY = event.rawY - initialTouchRawY
            val absDeltaX = kotlin.math.abs(deltaX)
            return deltaX < -touchSlop &&
                deltaY > touchSlop &&
                absDeltaX >= deltaY * SIDEBAR_TOP_RIGHT_GESTURE_MIN_HORIZONTAL_RATIO &&
                absDeltaX <= deltaY * SIDEBAR_TOP_RIGHT_GESTURE_MAX_HORIZONTAL_RATIO
        }

        private fun isSidebarTopRightShadeGesture(event: MotionEvent): Boolean {
            val deltaX = event.rawX - initialTouchRawX
            val deltaY = event.rawY - initialTouchRawY
            val absDeltaX = kotlin.math.abs(deltaX)
            return deltaY > touchSlop * SIDEBAR_TOP_RIGHT_SHADE_MIN_VERTICAL_TOUCH_SLOP_MULTIPLIER &&
                absDeltaX <= deltaY * SIDEBAR_TOP_RIGHT_SHADE_MAX_HORIZONTAL_RATIO
        }
    }

    class Factory
    @Inject
    constructor(
        @Named(UNFOLD_STATUS_BAR)
        private val progressProvider: Optional<ScopedUnfoldTransitionProgressProvider>,
        private val userChipViewModel: StatusBarUserChipViewModel,
        private val centralSurfaces: CentralSurfaces,
        @DisplayAware private val statusBarWindowStateController: StatusBarWindowStateController,
        private val shadeController: ShadeController,
        private val shadeViewController: ShadeViewController,
        private val shadeModeInteractor: ShadeModeInteractor,
        private val panelExpansionInteractor: PanelExpansionInteractor,
        private val statusBarLongPressGestureDetector: Provider<StatusBarLongPressGestureDetector>,
        private val windowRootView: Provider<WindowRootView>,
        private val shadeLogger: ShadeLogger,
        private val viewUtil: ViewUtil,
        private val statusBarConfigurationController: StatusBarConfigurationController,
        private val statusOverlayHoverListenerFactory: StatusOverlayHoverListenerFactory,
        @DisplayAware private val darkIconDispatcher: DarkIconDispatcher,
        private val statusBarContentInsetsProviderStore: StatusBarContentInsetsProviderStore,
        private val lazyStatusBarShadeDisplayPolicy: Lazy<StatusBarTouchShadeDisplayPolicy>,
        private val lazyShadeDisplaysRepository: Lazy<ShadeDisplaysRepository>,
        private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    ) {
        fun create(view: PhoneStatusBarView): PhoneStatusBarViewController {
            return PhoneStatusBarViewController(
                view,
                progressProvider.getOrNull(),
                centralSurfaces,
                statusBarWindowStateController,
                shadeController,
                shadeViewController,
                shadeModeInteractor,
                panelExpansionInteractor,
                statusBarLongPressGestureDetector,
                windowRootView,
                shadeLogger,
                userChipViewModel,
                viewUtil,
                statusBarConfigurationController,
                statusOverlayHoverListenerFactory,
                darkIconDispatcher,
                statusBarContentInsetsProviderStore,
                lazyStatusBarShadeDisplayPolicy,
                lazyShadeDisplaysRepository,
                statusBarWindowControllerStore,
            )
        }
    }
}
