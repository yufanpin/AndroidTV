package tv.danmaku.ijk.media.player.misc;

/* loaded from: classes.dex */
public interface IMediaDataSource {
    void close();

    long getSize();

    int readAt(long j, byte[] bArr, int i, int i2);
}
