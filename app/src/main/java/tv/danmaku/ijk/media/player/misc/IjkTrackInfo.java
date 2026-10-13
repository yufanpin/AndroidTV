package tv.danmaku.ijk.media.player.misc;

import android.text.TextUtils;
import tv.danmaku.ijk.media.player.IjkMediaMeta;

/* loaded from: classes.dex */
public class IjkTrackInfo implements ITrackInfo {
    public IjkMediaMeta.IjkStreamMeta mStreamMeta;
    public int mTrackType = 0;

    public IjkTrackInfo(IjkMediaMeta.IjkStreamMeta ijkStreamMeta) {
        this.mStreamMeta = ijkStreamMeta;
    }

    @Override // tv.danmaku.ijk.media.player.misc.ITrackInfo
    public IMediaFormat getFormat() {
        return new IjkMediaFormat(this.mStreamMeta);
    }

    @Override // tv.danmaku.ijk.media.player.misc.ITrackInfo
    public String getInfoInline() {
        String resolutionInline;
        StringBuilder sb = new StringBuilder(128);
        int i = this.mTrackType;
        if (i == 1) {
            sb.append("VIDEO");
            sb.append(", ");
            sb.append(this.mStreamMeta.getCodecShortNameInline());
            sb.append(", ");
            sb.append(this.mStreamMeta.getBitrateInline());
            sb.append(", ");
            resolutionInline = this.mStreamMeta.getResolutionInline();
        } else if (i == 2) {
            sb.append("AUDIO");
            sb.append(", ");
            sb.append(this.mStreamMeta.getCodecShortNameInline());
            sb.append(", ");
            sb.append(this.mStreamMeta.getBitrateInline());
            sb.append(", ");
            resolutionInline = this.mStreamMeta.getSampleRateInline();
        } else if (i != 3) {
            resolutionInline = i != 4 ? "UNKNOWN" : "SUBTITLE";
        } else {
            sb.append("TIMEDTEXT");
            sb.append(", ");
            resolutionInline = this.mStreamMeta.mLanguage;
        }
        sb.append(resolutionInline);
        return sb.toString();
    }

    @Override // tv.danmaku.ijk.media.player.misc.ITrackInfo
    public String getLanguage() {
        IjkMediaMeta.IjkStreamMeta ijkStreamMeta = this.mStreamMeta;
        return (ijkStreamMeta == null || TextUtils.isEmpty(ijkStreamMeta.mLanguage)) ? "und" : this.mStreamMeta.mLanguage;
    }

    @Override // tv.danmaku.ijk.media.player.misc.ITrackInfo
    public int getTrackType() {
        return this.mTrackType;
    }

    public void setMediaMeta(IjkMediaMeta.IjkStreamMeta ijkStreamMeta) {
        this.mStreamMeta = ijkStreamMeta;
    }

    public void setTrackType(int i) {
        this.mTrackType = i;
    }

    public String toString() {
        return IjkTrackInfo.class.getSimpleName() + '{' + getInfoInline() + "}";
    }
}
