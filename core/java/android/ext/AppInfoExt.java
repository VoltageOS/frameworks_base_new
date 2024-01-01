package android.ext;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.AppGlobals;
import android.os.Parcel;
import android.os.Parcelable;

/** @hide */
@SystemApi
public final class AppInfoExt implements Parcelable {
    /** @hide */
    public static final AppInfoExt DEFAULT = new AppInfoExt(PackageId.UNKNOWN, 0);

    private final int packageId;
    private final int flags;

    /** @hide */
    public static final int FLAG_HAS_GMSCORE_CLIENT_LIBRARY = 0;

    public AppInfoExt(int packageId, int flags) {
        this.packageId = packageId;
        this.flags = flags;
    }

    /**
     * One of {@link android.ext.PackageId} int constants.
     */
    public int getPackageId() {
        return packageId;
    }

    public static int getInitialPackageId() {
        return AppGlobals.getInitialPackageId();
    }

    public boolean hasFlag(int flag) {
        return (flags & (1 << flag)) != 0;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int parcelFlags) {
        boolean def = this == DEFAULT;
        dest.writeBoolean(def);
        if (def) {
            return;
        }

        dest.writeInt(packageId);
        dest.writeInt(flags);
    }

    @NonNull
    public static final Creator<AppInfoExt> CREATOR = new Creator<>() {
        @Override
        public AppInfoExt createFromParcel(@NonNull Parcel p) {
            if (p.readBoolean()) {
                return DEFAULT;
            }
            return new AppInfoExt(p.readInt(), p.readInt());
        }

        @Override
        public AppInfoExt[] newArray(int size) {
            return new AppInfoExt[size];
        }
    };
}
