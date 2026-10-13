package tv.danmaku.ijk.media.player.misc;

import android.annotation.TargetApi;
import android.text.TextUtils;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import tv.danmaku.ijk.media.player.IjkMediaCodecInfo;
import tv.danmaku.ijk.media.player.IjkMediaMeta;

/* loaded from: classes.dex */
public class IjkMediaFormat implements IMediaFormat {
    public static final String CODEC_NAME_H264 = "h264";
    public static final String KEY_IJK_BIT_RATE_UI = "ijk-bit-rate-ui";
    public static final String KEY_IJK_CHANNEL_UI = "ijk-channel-ui";
    public static final String KEY_IJK_CODEC_LONG_NAME_UI = "ijk-codec-long-name-ui";
    public static final String KEY_IJK_CODEC_NAME_UI = "ijk-codec-name-ui";
    public static final String KEY_IJK_CODEC_PIXEL_FORMAT_UI = "ijk-pixel-format-ui";
    public static final String KEY_IJK_CODEC_PROFILE_LEVEL_UI = "ijk-profile-level-ui";
    public static final String KEY_IJK_FRAME_RATE_UI = "ijk-frame-rate-ui";
    public static final String KEY_IJK_RESOLUTION_UI = "ijk-resolution-ui";
    public static final String KEY_IJK_SAMPLE_RATE_UI = "ijk-sample-rate-ui";
    public static final Map<String, AbstractC1115j> sFormatterMap = new HashMap();
    public final IjkMediaMeta.IjkStreamMeta mMediaFormat;

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$a */
    /* loaded from: classes.dex */
    public class C1106a extends AbstractC1115j {
        public C1106a() {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            return IjkMediaFormat.this.mMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_LONG_NAME);
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$b */
    /* loaded from: classes.dex */
    public class C1107b extends AbstractC1115j {
        public C1107b() {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            return IjkMediaFormat.this.mMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$c */
    /* loaded from: classes.dex */
    public class C1108c extends AbstractC1115j {
        public C1108c(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            int integer = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_BITRATE);
            if (integer <= 0) {
                return null;
            }
            return integer < 1000 ? String.format(Locale.US, "%d bit/s", Integer.valueOf(integer)) : String.format(Locale.US, "%d kb/s", Integer.valueOf(integer / IjkMediaCodecInfo.RANK_MAX));
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$d */
    /* loaded from: classes.dex */
    public class C1109d extends AbstractC1115j {
        public C1109d(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            String str;
            switch (ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_CODEC_PROFILE_ID)) {
                case 44:
                    str = "CAVLC 4:4:4";
                    break;
                case 66:
                    str = "Baseline";
                    break;
                case 77:
                    str = "Main";
                    break;
                case 88:
                    str = "Extended";
                    break;
                case 100:
                    str = "High";
                    break;
                case 110:
                    str = "High 10";
                    break;
                case 122:
                    str = "High 4:2:2";
                    break;
                case IjkMediaMeta.FF_PROFILE_H264_HIGH_444 /* 144 */:
                    str = "High 4:4:4";
                    break;
                case IjkMediaMeta.FF_PROFILE_H264_HIGH_444_PREDICTIVE /* 244 */:
                    str = "High 4:4:4 Predictive";
                    break;
                case IjkMediaMeta.FF_PROFILE_H264_CONSTRAINED_BASELINE /* 578 */:
                    str = "Constrained Baseline";
                    break;
                case IjkMediaMeta.FF_PROFILE_H264_HIGH_10_INTRA /* 2158 */:
                    str = "High 10 Intra";
                    break;
                case IjkMediaMeta.FF_PROFILE_H264_HIGH_422_INTRA /* 2170 */:
                    str = "High 4:2:2 Intra";
                    break;
                case IjkMediaMeta.FF_PROFILE_H264_HIGH_444_INTRA /* 2292 */:
                    str = "High 4:4:4 Intra";
                    break;
                default:
                    return null;
            }
            StringBuilder m1755a = new StringBuilder(str);
            String string = ijkMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_NAME);
            if (!TextUtils.isEmpty(string) && string.equalsIgnoreCase(IjkMediaFormat.CODEC_NAME_H264)) {
                int integer = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_CODEC_LEVEL);
                if (integer < 10) {
                    return m1755a.toString();
                }
                m1755a.append(" Profile Level ");
                m1755a.append((integer / 10) % 10);
                int i = integer % 10;
                if (i != 0) {
                    m1755a.append(".");
                    m1755a.append(i);
                }
            }
            return m1755a.toString();
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$e */
    /* loaded from: classes.dex */
    public class C1110e extends AbstractC1115j {
        public C1110e(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            return ijkMediaFormat.getString(IjkMediaMeta.IJKM_KEY_CODEC_PIXEL_FORMAT);
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$f */
    /* loaded from: classes.dex */
    public class C1111f extends AbstractC1115j {
        public C1111f(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            int integer = ijkMediaFormat.getInteger("width");
            int integer2 = ijkMediaFormat.getInteger("height");
            int integer3 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_SAR_NUM);
            int integer4 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_SAR_DEN);
            if (integer <= 0 || integer2 <= 0) {
                return null;
            }
            return (integer3 <= 0 || integer4 <= 0) ? String.format(Locale.US, "%d x %d", Integer.valueOf(integer), Integer.valueOf(integer2)) : String.format(Locale.US, "%d x %d [SAR %d:%d]", Integer.valueOf(integer), Integer.valueOf(integer2), Integer.valueOf(integer3), Integer.valueOf(integer4));
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$g */
    /* loaded from: classes.dex */
    public class C1112g extends AbstractC1115j {
        public C1112g(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            int integer = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_FPS_NUM);
            int integer2 = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_FPS_DEN);
            if (integer <= 0 || integer2 <= 0) {
                return null;
            }
            return String.valueOf(integer / integer2);
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$h */
    /* loaded from: classes.dex */
    public class C1113h extends AbstractC1115j {
        public C1113h(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            int integer = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_SAMPLE_RATE);
            if (integer <= 0) {
                return null;
            }
            return String.format(Locale.US, "%d Hz", Integer.valueOf(integer));
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$i */
    /* loaded from: classes.dex */
    public class C1114i extends AbstractC1115j {
        public C1114i(IjkMediaFormat ijkMediaFormat) {
            super(null);
        }

        @Override // tv.danmaku.ijk.media.player.misc.IjkMediaFormat.AbstractC1115j
        /* renamed from: a */
        public String mo2687a(IjkMediaFormat ijkMediaFormat) {
            int integer = ijkMediaFormat.getInteger(IjkMediaMeta.IJKM_KEY_CHANNEL_LAYOUT);
            if (integer <= 0) {
                return null;
            }
            long j = integer;
            return j == 4 ? "mono" : j == 3 ? "stereo" : String.format(Locale.US, "%x", Integer.valueOf(integer));
        }
    }

    /* renamed from: tv.danmaku.ijk.media.player.misc.IjkMediaFormat$j */
    /* loaded from: classes.dex */
    public static abstract class AbstractC1115j {
        public AbstractC1115j() {
        }

        public /* synthetic */ AbstractC1115j(C1106a c1106a) {
        }

        /* renamed from: a */
        public abstract String mo2687a(IjkMediaFormat ijkMediaFormat);
    }

    public IjkMediaFormat(IjkMediaMeta.IjkStreamMeta ijkStreamMeta) {
        sFormatterMap.put(KEY_IJK_CODEC_LONG_NAME_UI, new C1106a());
        sFormatterMap.put(KEY_IJK_CODEC_NAME_UI, new C1107b());
        sFormatterMap.put(KEY_IJK_BIT_RATE_UI, new C1108c(this));
        sFormatterMap.put(KEY_IJK_CODEC_PROFILE_LEVEL_UI, new C1109d(this));
        sFormatterMap.put(KEY_IJK_CODEC_PIXEL_FORMAT_UI, new C1110e(this));
        sFormatterMap.put(KEY_IJK_RESOLUTION_UI, new C1111f(this));
        sFormatterMap.put(KEY_IJK_FRAME_RATE_UI, new C1112g(this));
        sFormatterMap.put(KEY_IJK_SAMPLE_RATE_UI, new C1113h(this));
        sFormatterMap.put(KEY_IJK_CHANNEL_UI, new C1114i(this));
        this.mMediaFormat = ijkStreamMeta;
    }

    @Override // tv.danmaku.ijk.media.player.misc.IMediaFormat
    @TargetApi(16)
    public int getInteger(String str) {
        IjkMediaMeta.IjkStreamMeta ijkStreamMeta = this.mMediaFormat;
        if (ijkStreamMeta == null) {
            return 0;
        }
        return ijkStreamMeta.getInt(str);
    }

    @Override // tv.danmaku.ijk.media.player.misc.IMediaFormat
    public String getString(String str) {
        if (this.mMediaFormat == null) {
            return null;
        }
        if (!sFormatterMap.containsKey(str)) {
            return this.mMediaFormat.getString(str);
        }
        String mo2687a = sFormatterMap.get(str).mo2687a(this);
        return TextUtils.isEmpty(mo2687a) ? "N/A" : mo2687a;
    }
}
