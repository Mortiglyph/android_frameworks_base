package android.view;

import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
public class FooDisplayResultInfo implements Parcelable {
    public static final String FOO_RESULT_DISPLAY_WINDOW_TITLE = "FooResultDisplay";
    public static final String FOO_RESULT_DISPLAY_WINDOW_TITLE_2 = "FooResultDisplay2";

    public static final Creator<FooDisplayResultInfo> CREATOR =
            new Creator<FooDisplayResultInfo>() {
                @Override
                public FooDisplayResultInfo createFromParcel(Parcel in) {
                    FooDisplayResultInfo info = new FooDisplayResultInfo();
                    info.readFromParcel(in);
                    return info;
                }

                @Override
                public FooDisplayResultInfo[] newArray(int size) {
                    return new FooDisplayResultInfo[size];
                }
            };

    public int displayId;
    public boolean fromFooDisplay;
    public int pointX;
    public int pointY;
    public Rect rect;
    public CharSequence result;
    public boolean valid;
    public CharSequence windowTitle;

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(windowTitle);
        dest.writeInt(valid ? 1 : 0);
        dest.writeCharSequence(result);
        dest.writeParcelable(rect, flags);
        dest.writeInt(fromFooDisplay ? 1 : 0);
        dest.writeInt(displayId);
        dest.writeInt(pointX);
        dest.writeInt(pointY);
    }

    public void readFromParcel(Parcel in) {
        windowTitle = in.readCharSequence();
        valid = in.readInt() == 1;
        result = in.readCharSequence();
        rect = in.readParcelable(Rect.class.getClassLoader(), Rect.class);
        fromFooDisplay = in.readInt() == 1;
        displayId = in.readInt();
        pointX = in.readInt();
        pointY = in.readInt();
    }
}
