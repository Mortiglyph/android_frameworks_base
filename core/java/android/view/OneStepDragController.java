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

package android.view;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.sidebar.ISidebarService;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Lightweight OneStep drag source support for application windows while the sidebar is open.
 */
final class OneStepDragController {
    private static final String TAG = "OneStepDragController";
    private static final String SIDEBAR_SERVICE = "sidebar";
    private static final String SIDEBAR_PACKAGE = "com.smartisanos.sidebar";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String SIDEBAR_ENABLED = "side_bar_mode";
    private static final String SIDEBAR_SWITCH_STATUS = "sidebar_switch_status";
    private static final String SIDEBAR_INNER_DRAG_STATE = "sidebar_innerdrag_state";
    private static final String ACTION_BOOM_TEXT = "smartisanos.intent.action.BOOM_TEXT";
    private static final String EXTRA_GLOBAL_TYPE = "global_type";
    private static final String EXTRA_X = "x";
    private static final String EXTRA_Y = "y";
    private static final String EXTRA_DRAG_URI = "uri";
    private static final String EXTRA_DRAG_FD = "fd";
    private static final int MAX_TEXT_LENGTH = 32 * 1024;
    private static final int MAX_IMAGE_EDGE = 1024;
    private static final int MAX_PNG_PIXELS = 512 * 512;
    private static final long LARGE_TOUCH_HOLD_MS = 360L;
    private static final long MOVE_START_HOLD_MS = 120L;
    private static final long LONG_PRESS_DRAG_HOLD_MS = 450L;
    private static final float LARGE_TOUCH_SIZE_THRESHOLD = 0.04f;
    private static final float LARGE_TOUCH_MAJOR_THRESHOLD_DP = 24f;
    private static final float PRE_LARGE_TOUCH_CANCEL_SLOP_MULTIPLIER = 2.5f;
    private static final int IMAGE_DRAG_FLAGS = View.DRAG_FLAG_GLOBAL
            | View.DRAG_FLAG_GLOBAL_URI_READ;
    private static final int TEXT_DRAG_FLAGS = View.DRAG_FLAG_GLOBAL;
    private static final int DRAG_SOURCE_TEXT = 1;
    private static final int DRAG_SOURCE_IMAGE = 2;
    private static final float DRAG_TOUCH_POINT_OFFSET_DP = 40f;
    private static final float TEXT_DRAG_ARROW_POINT_OFFSET_DP = 26f;
    private static final float IMAGE_DRAG_ARROW_POINT_OFFSET_DP = 37f;

    private final ViewRootImpl mViewRoot;
    private final Context mContext;
    private final Handler mHandler;
    private final int mTouchSlop;
    private final float mLargeTouchMajorThreshold;
    private final Runnable mStartDragRunnable = this::startPendingDrag;
    private final Runnable mLongPressDragRunnable = this::markLongPressDragReady;

    private DragSource mPendingSource;
    private float mDownX;
    private float mDownY;
    private long mDownTime;
    private int mPointerId = -1;
    private boolean mLargeTouchAreaReached;
    private boolean mStartDragScheduled;
    private boolean mLongPressDragReady;
    private boolean mDragStarted;

    OneStepDragController(ViewRootImpl viewRoot) {
        mViewRoot = viewRoot;
        mContext = viewRoot.mContext;
        mHandler = viewRoot.mHandler;
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        mLargeTouchMajorThreshold = LARGE_TOUCH_MAJOR_THRESHOLD_DP
                * mContext.getResources().getDisplayMetrics().density;
    }

    void onTouchEvent(MotionEvent event) {
        if (!shouldHandleWindow()) {
            cancelPendingDrag();
            return;
        }
        final int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(event);
                break;
            case MotionEvent.ACTION_MOVE:
                handleActionMove(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                cancelPendingDrag();
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == mPointerId) {
                    cancelPendingDrag();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelPendingDrag();
                break;
        }
    }

    void onDetachedFromWindow() {
        cancelPendingDrag();
    }

    private void handleActionDown(MotionEvent event) {
        cancelPendingDrag();
        if (!isSidebarReady() || isKeyguardLocked()) {
            return;
        }
        final View root = mViewRoot.mView;
        if (root == null) {
            return;
        }
        final DragSource touchedSource = findDragSource(root, event.getX(), event.getY());
        if (touchedSource == null) {
            logDebug("ACTION_DOWN ignored: no draggable source pkg=" + mContext.getPackageName()
                    + " x=" + event.getX() + " y=" + event.getY());
            return;
        }
        mPendingSource = touchedSource;
        mDownX = event.getX();
        mDownY = event.getY();
        mDownTime = event.getEventTime();
        mPointerId = event.getPointerId(0);
        logDebug("ACTION_DOWN source=" + describeSource(touchedSource)
                + " size=" + event.getSize(0)
                + " major=" + event.getTouchMajor(0)
                + " pressure=" + event.getPressure(0));
        if (hasLargeTouchArea(event, 0)) {
            markLargeTouchAreaReached(event, 0);
        } else {
            scheduleLongPressDragReady();
        }
    }

    private void handleActionMove(MotionEvent event) {
        if (mPendingSource == null || mDragStarted) {
            return;
        }
        final int pointerIndex = event.findPointerIndex(mPointerId);
        if (pointerIndex < 0) {
            cancelPendingDrag();
            return;
        }
        final float dx = event.getX(pointerIndex) - mDownX;
        final float dy = event.getY(pointerIndex) - mDownY;
        final float distanceSq = dx * dx + dy * dy;
        if (!mLargeTouchAreaReached && hasLargeTouchArea(event, pointerIndex)) {
            markLargeTouchAreaReached(event, pointerIndex);
        }
        if (!mLargeTouchAreaReached) {
            if (mLongPressDragReady && distanceSq > mTouchSlop * mTouchSlop) {
                mLargeTouchAreaReached = true;
                logDebug("start by long-press drag fallback: distance="
                        + Math.sqrt(distanceSq));
                startPendingDrag();
                return;
            }
            final float cancelSlop = mTouchSlop * PRE_LARGE_TOUCH_CANCEL_SLOP_MULTIPLIER;
            if (distanceSq > cancelSlop * cancelSlop) {
                logDebug("cancel before large-touch: distance=" + Math.sqrt(distanceSq));
                cancelPendingDrag();
            }
            return;
        }
        if (distanceSq > mTouchSlop * mTouchSlop
                && event.getEventTime() - mDownTime >= MOVE_START_HOLD_MS) {
            logDebug("start by move: distance=" + Math.sqrt(distanceSq));
            startPendingDrag();
        }
    }

    private void markLargeTouchAreaReached(MotionEvent event, int pointerIndex) {
        if (mLargeTouchAreaReached) {
            return;
        }
        mLargeTouchAreaReached = true;
        if (mPendingSource != null) {
            mPendingSource.view.cancelLongPress();
        }
        logDebug("large touch reached: size=" + event.getSize(pointerIndex)
                + " major=" + event.getTouchMajor(pointerIndex)
                + " pressure=" + event.getPressure(pointerIndex));
        scheduleStartDrag();
    }

    private void startPendingDrag() {
        final DragSource source = mPendingSource;
        mHandler.removeCallbacks(mStartDragRunnable);
        mStartDragScheduled = false;
        if (source == null || !mLargeTouchAreaReached || !source.view.isShown()
                || !isSidebarReady() || isKeyguardLocked()) {
            logDebug("start ignored: source=" + describeSource(source)
                    + " large=" + mLargeTouchAreaReached);
            cancelPendingDrag();
            return;
        }
        source.view.cancelLongPress();
        boolean started = false;
        if (source.type == DRAG_SOURCE_TEXT) {
            started = startTextDrag(source);
        } else if (source.type == DRAG_SOURCE_IMAGE) {
            started = startImageDrag(source.view);
        }
        logDebug("startDragAndDrop result=" + started + " source=" + describeSource(source));
        mDragStarted = started;
        if (started) {
            showGlobalShare(source.type);
            clearPendingDrag();
        } else {
            cancelPendingDrag();
        }
    }

    private void scheduleStartDrag() {
        if (mStartDragScheduled) {
            return;
        }
        mStartDragScheduled = true;
        mHandler.postDelayed(mStartDragRunnable, LARGE_TOUCH_HOLD_MS);
    }

    private void scheduleLongPressDragReady() {
        mHandler.postDelayed(mLongPressDragRunnable, LONG_PRESS_DRAG_HOLD_MS);
    }

    private void markLongPressDragReady() {
        if (mPendingSource == null || mDragStarted || mLargeTouchAreaReached) {
            return;
        }
        mLongPressDragReady = true;
        mPendingSource.view.cancelLongPress();
        logDebug("long-press drag fallback ready");
    }

    private void cancelPendingDrag() {
        mHandler.removeCallbacks(mStartDragRunnable);
        mHandler.removeCallbacks(mLongPressDragRunnable);
        clearPendingDrag();
    }

    private void clearPendingDrag() {
        mPendingSource = null;
        mPointerId = -1;
        mLargeTouchAreaReached = false;
        mStartDragScheduled = false;
        mLongPressDragReady = false;
        mDragStarted = false;
    }

    private boolean startTextDrag(DragSource source) {
        final CharSequence sourceText = getDragText(source.view);
        if (TextUtils.isEmpty(sourceText)) {
            return false;
        }
        final int offset = source.view instanceof TextView
                ? ((TextView) source.view).getOffsetForPosition(source.x, source.y) : 0;
        final String text = trimTextAround(sourceText.toString(), offset);
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        final ClipData data = ClipData.newPlainText(null, text);
        return source.view.startDragAndDrop(data, new TextShadowBuilder(source.view, text),
                null, TEXT_DRAG_FLAGS);
    }

    private boolean startImageDrag(View imageView) {
        final Bitmap bitmap = createBitmapFromImageView(imageView);
        if (bitmap == null) {
            return false;
        }
        final boolean png = bitmap.hasAlpha()
                && bitmap.getWidth() * bitmap.getHeight() <= MAX_PNG_PIXELS;
        final String mimeType = png ? "image/png" : "image/jpeg";
        final String displayName = "onestep_drag_image." + (png ? "png" : "jpg");
        final Bundle bundle = createExternalDragFile(displayName, mimeType);
        if (bundle == null) {
            return false;
        }
        final Uri uri = bundle.getParcelable(EXTRA_DRAG_URI, Uri.class);
        final ParcelFileDescriptor fd = bundle.getParcelable(EXTRA_DRAG_FD,
                ParcelFileDescriptor.class);
        if (uri == null || fd == null) {
            closeQuietly(fd);
            return false;
        }
        final ClipDescription description = new ClipDescription(displayName,
                new String[] { mimeType });
        final ClipData data = new ClipData(description, new ClipData.Item(uri));
        final boolean started = imageView.startDragAndDrop(data,
                new ImageShadowBuilder(imageView, bitmap),
                null, IMAGE_DRAG_FLAGS);
        if (started) {
            writeBitmapAsync(fd, bitmap,
                    png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
                    png ? 100 : 88);
        } else {
            closeQuietly(fd);
        }
        return started;
    }

    private Bundle createExternalDragFile(String displayName, String mimeType) {
        final ISidebarService service = ISidebarService.Stub.asInterface(
                ServiceManager.getService(SIDEBAR_SERVICE));
        if (service == null) {
            return null;
        }
        try {
            return service.createExternalDragFile(displayName, mimeType);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "createExternalDragFile failed", e);
            return null;
        }
    }

    private void showGlobalShare(int sourceType) {
        final ISidebarService service = ISidebarService.Stub.asInterface(
                ServiceManager.getService(SIDEBAR_SERVICE));
        if (service == null) {
            return;
        }
        final Point point = new Point();
        mViewRoot.getLastTouchPoint(point);
        final Intent intent = new Intent(ACTION_BOOM_TEXT);
        intent.putExtra(EXTRA_X, point.x);
        intent.putExtra(EXTRA_Y, point.y);
        intent.putExtra(EXTRA_GLOBAL_TYPE,
                sourceType == DRAG_SOURCE_IMAGE ? DRAG_SOURCE_IMAGE : DRAG_SOURCE_TEXT);
        try {
            service.showGlobalShare(intent);
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "showGlobalShare failed", e);
        }
    }

    private DragSource findDragSource(View view, float localX, float localY) {
        if (view == null || !view.canReceivePointerEvents()) {
            return null;
        }
        final boolean isGroup = view instanceof ViewGroup;
        if (view instanceof ViewGroup) {
            final ViewGroup group = (ViewGroup) view;
            final int childCount = group.getChildCount();
            for (int i = childCount - 1; i >= 0; i--) {
                final View child = group.getChildAt(i);
                if (child == null || !child.canReceivePointerEvents()) {
                    continue;
                }
                final PointF childPoint = new PointF();
                if (!group.isTransformedTouchPointInView(localX, localY, child, childPoint)) {
                    continue;
                }
                final DragSource hit = findDragSource(child, childPoint.x, childPoint.y);
                if (hit != null) {
                    return hit;
                }
            }
        }
        if (canDragImage(view)) {
            return new DragSource(view, localX, localY, DRAG_SOURCE_IMAGE);
        }
        if (canDragText(view)) {
            return new DragSource(view, localX, localY, DRAG_SOURCE_TEXT);
        }
        if (isGroup && isFallbackContainer(view)) {
            return findDescendantDragSource((ViewGroup) view);
        }
        return null;
    }

    private DragSource findDescendantDragSource(ViewGroup group) {
        DragSource imageSource = null;
        DragSource textSource = null;
        final int childCount = group.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = group.getChildAt(i);
            if (child == null || !child.canReceivePointerEvents()) {
                continue;
            }
            if (imageSource == null && canDragImage(child)) {
                imageSource = sourceFromCenter(child, DRAG_SOURCE_IMAGE);
            }
            if (textSource == null && canDragText(child)) {
                textSource = sourceFromCenter(child, DRAG_SOURCE_TEXT);
            }
            if (child instanceof ViewGroup) {
                final DragSource nested = findDescendantDragSource((ViewGroup) child);
                if (nested == null) {
                    continue;
                }
                if (nested.type == DRAG_SOURCE_IMAGE && imageSource == null) {
                    imageSource = nested;
                } else if (nested.type == DRAG_SOURCE_TEXT && textSource == null) {
                    textSource = nested;
                }
            }
        }
        return imageSource != null ? imageSource : textSource;
    }

    private DragSource sourceFromCenter(View view, int type) {
        return new DragSource(view, view.getWidth() / 2f, view.getHeight() / 2f, type);
    }

    private boolean isFallbackContainer(View view) {
        if (!view.isClickable()) {
            return false;
        }
        final String className = view.getClass().getName();
        return !className.contains("RecyclerView") && !className.contains("ScrollView")
                && !className.contains("ViewPager");
    }

    private boolean canDragText(View view) {
        if (view instanceof Button || TextUtils.isEmpty(getDragText(view))) {
            return false;
        }
        if (view instanceof TextView) {
            final TextView textView = (TextView) view;
            if (textView.getTransformationMethod() instanceof PasswordTransformationMethod) {
                return false;
            }
            final int variation = textView.getInputType() & InputType.TYPE_MASK_VARIATION;
            return variation != InputType.TYPE_TEXT_VARIATION_PASSWORD
                    && variation != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    && variation != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    && variation != InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return isLikelyTextView(view);
    }

    private CharSequence getDragText(View view) {
        if (view instanceof TextView) {
            return ((TextView) view).getText();
        }
        return view != null ? view.getContentDescription() : null;
    }

    private boolean canDragImage(View view) {
        return view instanceof ImageView || isLikelyImageView(view);
    }

    private boolean hasLargeTouchArea(MotionEvent event, int pointerIndex) {
        if (pointerIndex < 0 || pointerIndex >= event.getPointerCount()) {
            return false;
        }
        if (event.getToolType(pointerIndex) != MotionEvent.TOOL_TYPE_FINGER) {
            return false;
        }
        return event.getSize(pointerIndex) >= LARGE_TOUCH_SIZE_THRESHOLD
                || event.getTouchMajor(pointerIndex) >= mLargeTouchMajorThreshold;
    }

    private boolean shouldHandleWindow() {
        final WindowManager.LayoutParams attrs = mViewRoot.mWindowAttributes;
        if (attrs == null || attrs.type < WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW
                || attrs.type > WindowManager.LayoutParams.LAST_APPLICATION_WINDOW) {
            return false;
        }
        final String packageName = mContext.getPackageName();
        return !SIDEBAR_PACKAGE.equals(packageName)
                && !SYSTEMUI_PACKAGE.equals(packageName)
                && !"android".equals(packageName);
    }

    private boolean isSidebarReady() {
        return Settings.Global.getInt(mContext.getContentResolver(), SIDEBAR_ENABLED, 1) == 1
                && Settings.Global.getInt(mContext.getContentResolver(),
                        SIDEBAR_SWITCH_STATUS, 0) == 1
                && Settings.Global.getInt(mContext.getContentResolver(),
                        SIDEBAR_INNER_DRAG_STATE, 0) == 1;
    }

    private boolean isKeyguardLocked() {
        final KeyguardManager keyguardManager = mContext.getSystemService(KeyguardManager.class);
        return keyguardManager != null && keyguardManager.isKeyguardLocked();
    }

    private static String trimTextAround(String text, int offset) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        final int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int start = Math.max(0, safeOffset - MAX_TEXT_LENGTH / 2);
        int end = Math.min(text.length(), start + MAX_TEXT_LENGTH);
        start = Math.max(0, end - MAX_TEXT_LENGTH);
        return text.substring(start, end);
    }

    private static Bitmap createBitmapFromImageView(View imageView) {
        final Drawable drawable = imageView instanceof ImageView
                ? ((ImageView) imageView).getDrawable() : null;
        Bitmap bitmap = null;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        }
        if (bitmap == null && drawable != null) {
            bitmap = drawDrawable(drawable);
        }
        if (bitmap == null) {
            bitmap = drawView(imageView);
        }
        return scaleBitmap(bitmap, MAX_IMAGE_EDGE);
    }

    private static boolean isLikelyTextView(View view) {
        final String className = view.getClass().getName();
        return className.contains("TextView") || className.contains("Text")
                || className.contains("Label");
    }

    private static boolean isLikelyImageView(View view) {
        final String className = view.getClass().getName();
        return className.contains("ImageView") || className.contains("Image")
                || className.contains("Photo") || className.contains("Picture")
                || className.contains("Drawee") || className.contains("Avatar")
                || className.contains("Thumbnail");
    }

    private static void logDebug(String message) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, message);
        }
    }

    private static String describeSource(DragSource source) {
        if (source == null) {
            return "null";
        }
        return (source.type == DRAG_SOURCE_TEXT ? "text" : "image")
                + " " + source.view.getClass().getName();
    }

    private static Bitmap drawDrawable(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        final float scale = Math.min(1f, (float) MAX_IMAGE_EDGE / Math.max(width, height));
        width = Math.max(1, Math.round(width * scale));
        height = Math.max(1, Math.round(height * scale));
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Rect oldBounds = drawable.copyBounds();
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        drawable.setBounds(oldBounds);
        return bitmap;
    }

    private static Bitmap drawView(View view) {
        final int width = view.getWidth();
        final int height = view.getHeight();
        if (width <= 0 || height <= 0) {
            return null;
        }
        final float scale = Math.min(1f, (float) MAX_IMAGE_EDGE / Math.max(width, height));
        final int bitmapWidth = Math.max(1, Math.round(width * scale));
        final int bitmapHeight = Math.max(1, Math.round(height * scale));
        final Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        canvas.scale(scale, scale);
        view.draw(canvas);
        return bitmap;
    }

    private static Bitmap scaleBitmap(Bitmap bitmap, int maxEdge) {
        if (bitmap == null) {
            return null;
        }
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        final int largestEdge = Math.max(width, height);
        if (largestEdge <= maxEdge || largestEdge <= 0) {
            return bitmap;
        }
        final float scale = (float) maxEdge / largestEdge;
        return Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd == null) {
            return;
        }
        try {
            fd.close();
        } catch (IOException ignored) {
        }
    }

    private static void writeBitmapAsync(ParcelFileDescriptor fd, Bitmap bitmap,
            Bitmap.CompressFormat format, int quality) {
        new Thread(() -> {
            try (FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                if (!bitmap.compress(format, quality, out)) {
                    Log.w(TAG, "Failed to encode OneStep drag image");
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to write OneStep drag image", e);
            }
        }, "OneStepDragImageWriter").start();
    }

    private static final class DragSource {
        final View view;
        final float x;
        final float y;
        final int type;

        DragSource(View view, float x, float y, int type) {
            this.view = view;
            this.x = x;
            this.y = y;
            this.type = type;
        }
    }

    private static final class TextShadowBuilder extends View.DragShadowBuilder {
        private static final String ELLIPSIS = "...";
        private static final float MIN_WIDTH_DP = 120f;
        private static final float MAX_WIDTH_DP = 320f;
        private static final float BUBBLE_HEIGHT_DP = 42f;
        private static final float PADDING_HORIZONTAL_DP = 14f;
        private static final float ARROW_WIDTH_DP = 18f;
        private static final float ARROW_HEIGHT_DP = 10f;
        private static final float STROKE_WIDTH_DP = 1f;
        private final String mText;
        private final float mDensity;
        private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrowPath = new Path();
        private final RectF mRect = new RectF();

        TextShadowBuilder(View view, String text) {
            super(view);
            mDensity = view.getResources().getDisplayMetrics().density;
            mBackgroundPaint.setColor(0xeeffffff);
            mBackgroundPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setColor(0x55b0b0b0);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(STROKE_WIDTH_DP * mDensity);
            mTextPaint.setColor(Color.rgb(42, 42, 42));
            mTextPaint.setTextSize(14f * mDensity);
            mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
            mText = ellipsize(text, (MAX_WIDTH_DP - PADDING_HORIZONTAL_DP * 2f) * mDensity);
        }

        @Override
        public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
            final int paddingH = Math.round(PADDING_HORIZONTAL_DP * mDensity);
            final int width = Math.max(Math.round(MIN_WIDTH_DP * mDensity),
                    Math.min(Math.round(MAX_WIDTH_DP * mDensity),
                            Math.round(mTextPaint.measureText(mText)) + paddingH * 2));
            final int height = Math.round((BUBBLE_HEIGHT_DP + ARROW_HEIGHT_DP) * mDensity);
            outShadowSize.set(width, height);
            outShadowTouchPoint.set(width / 2, Math.round((height / 2f)
                    + (DRAG_TOUCH_POINT_OFFSET_DP + TEXT_DRAG_ARROW_POINT_OFFSET_DP) * mDensity));
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            final float bubbleTop = ARROW_HEIGHT_DP * mDensity;
            final float bubbleBottom = canvas.getHeight() - STROKE_WIDTH_DP * mDensity;
            final float arrowHalfWidth = (ARROW_WIDTH_DP * mDensity) / 2f;
            final float arrowTop = (STROKE_WIDTH_DP * mDensity) / 2f;
            final float arrowBottom = bubbleTop + STROKE_WIDTH_DP * mDensity;
            final float centerX = canvas.getWidth() / 2f;
            final float inset = STROKE_WIDTH_DP * mDensity;
            mRect.set(inset, bubbleTop, canvas.getWidth() - inset, bubbleBottom);
            canvas.drawRoundRect(mRect, 18f * mDensity, 18f * mDensity, mBackgroundPaint);
            canvas.drawRoundRect(mRect, 18f * mDensity, 18f * mDensity, mStrokePaint);

            mArrowPath.reset();
            mArrowPath.moveTo(centerX, arrowTop);
            mArrowPath.lineTo(centerX - arrowHalfWidth, arrowBottom);
            mArrowPath.lineTo(centerX + arrowHalfWidth, arrowBottom);
            mArrowPath.close();
            canvas.drawPath(mArrowPath, mBackgroundPaint);
            canvas.drawPath(mArrowPath, mStrokePaint);

            final Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
            final float textX = 14f * mDensity;
            final float textY = bubbleTop + ((bubbleBottom - bubbleTop - metrics.bottom
                    - metrics.top) / 2f);
            canvas.drawText(mText, textX, textY, mTextPaint);
        }

        private String ellipsize(String text, float maxWidth) {
            if (TextUtils.isEmpty(text) || mTextPaint.measureText(text) <= maxWidth) {
                return text;
            }
            int end = text.length();
            while (end > 0
                    && mTextPaint.measureText(text.substring(0, end) + ELLIPSIS) > maxWidth) {
                end--;
            }
            return end > 0 ? text.substring(0, end) + ELLIPSIS : ELLIPSIS;
        }
    }

    private static final class ImageShadowBuilder extends View.DragShadowBuilder {
        private static final float BUBBLE_SIZE_DP = 96f;
        private static final float IMAGE_SIZE_DP = 78f;
        private static final float ARROW_WIDTH_DP = 20f;
        private static final float ARROW_HEIGHT_DP = 10f;
        private static final float STROKE_WIDTH_DP = 1f;
        private static final float CORNER_RADIUS_DP = 18f;
        private static final float IMAGE_CORNER_RADIUS_DP = 12f;
        private final Bitmap mBitmap;
        private final float mDensity;
        private final Paint mBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mArrowPath = new Path();
        private final Path mImageClipPath = new Path();
        private final Rect mSrc = new Rect();
        private final RectF mBubbleRect = new RectF();
        private final RectF mImageRect = new RectF();

        ImageShadowBuilder(View view, Bitmap bitmap) {
            super(view);
            mBitmap = bitmap;
            mDensity = view.getResources().getDisplayMetrics().density;
            mBackgroundPaint.setColor(0xeeffffff);
            mBackgroundPaint.setStyle(Paint.Style.FILL);
            mStrokePaint.setColor(0x55b0b0b0);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(STROKE_WIDTH_DP * mDensity);
            mBitmapPaint.setFilterBitmap(true);
            mBitmapPaint.setDither(true);
        }

        @Override
        public void onProvideShadowMetrics(Point outShadowSize, Point outShadowTouchPoint) {
            final int width = Math.round(BUBBLE_SIZE_DP * mDensity);
            final int height = Math.round((BUBBLE_SIZE_DP + ARROW_HEIGHT_DP) * mDensity);
            outShadowSize.set(width, height);
            outShadowTouchPoint.set(width / 2, Math.round((height / 2f)
                    + (DRAG_TOUCH_POINT_OFFSET_DP + IMAGE_DRAG_ARROW_POINT_OFFSET_DP) * mDensity));
        }

        @Override
        public void onDrawShadow(Canvas canvas) {
            final float bubbleTop = ARROW_HEIGHT_DP * mDensity;
            final float bubbleBottom = canvas.getHeight() - STROKE_WIDTH_DP * mDensity;
            final float arrowHalfWidth = (ARROW_WIDTH_DP * mDensity) / 2f;
            final float arrowTop = (STROKE_WIDTH_DP * mDensity) / 2f;
            final float arrowBottom = bubbleTop + STROKE_WIDTH_DP * mDensity;
            final float centerX = canvas.getWidth() / 2f;
            final float inset = STROKE_WIDTH_DP * mDensity;
            final float cornerRadius = CORNER_RADIUS_DP * mDensity;
            mBubbleRect.set(inset, bubbleTop, canvas.getWidth() - inset, bubbleBottom);
            canvas.drawRoundRect(mBubbleRect, cornerRadius, cornerRadius, mBackgroundPaint);
            canvas.drawRoundRect(mBubbleRect, cornerRadius, cornerRadius, mStrokePaint);

            mArrowPath.reset();
            mArrowPath.moveTo(centerX, arrowTop);
            mArrowPath.lineTo(centerX - arrowHalfWidth, arrowBottom);
            mArrowPath.lineTo(centerX + arrowHalfWidth, arrowBottom);
            mArrowPath.close();
            canvas.drawPath(mArrowPath, mBackgroundPaint);
            canvas.drawPath(mArrowPath, mStrokePaint);

            final float imageSize = IMAGE_SIZE_DP * mDensity;
            final float imageLeft = (canvas.getWidth() - imageSize) / 2f;
            final float imageTop = bubbleTop + ((bubbleBottom - bubbleTop) - imageSize) / 2f;
            mImageRect.set(imageLeft, imageTop, imageLeft + imageSize, imageTop + imageSize);
            setCenterCropSrc(mBitmap, mSrc);
            final int saveCount = canvas.save();
            mImageClipPath.reset();
            mImageClipPath.addRoundRect(mImageRect, IMAGE_CORNER_RADIUS_DP * mDensity,
                    IMAGE_CORNER_RADIUS_DP * mDensity, Path.Direction.CW);
            canvas.clipPath(mImageClipPath);
            canvas.drawBitmap(mBitmap, mSrc, mImageRect, mBitmapPaint);
            canvas.restoreToCount(saveCount);
        }

        private void setCenterCropSrc(Bitmap bitmap, Rect outSrc) {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            if (width > height) {
                final int left = (width - height) / 2;
                outSrc.set(left, 0, left + height, height);
            } else if (height > width) {
                final int top = (height - width) / 2;
                outSrc.set(0, top, width, top + width);
            } else {
                outSrc.set(0, 0, width, height);
            }
        }
    }
}
