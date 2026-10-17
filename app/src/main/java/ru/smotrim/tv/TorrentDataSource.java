package ru.smotrim.tv;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class TorrentDataSource extends BaseDataSource {
    private final TorrentEngine engine;
    private Uri uri;
    private long position,remaining;
    private boolean open;
    public TorrentDataSource(TorrentEngine engine){super(true);this.engine=engine;}
    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);uri=spec.uri;position=spec.position;
        if(position<0||position>engine.size())throw new IOException("Позиция вне видеофайла");
        remaining=spec.length==C.LENGTH_UNSET?engine.size()-position:Math.min(spec.length,engine.size()-position);
        engine.prioritize(position);open=true;transferStarted(spec);return remaining;
    }
    @Override public int read(byte[] b,int offset,int length)throws IOException {
        if(length==0)return 0;if(remaining==0)return C.RESULT_END_OF_INPUT;
        if(!open)throw new InterruptedIOException();
        int n=engine.read(position,b,offset,(int)Math.min(remaining,length));
        if(n>0){position+=n;remaining-=n;bytesTransferred(n);}return n;
    }
    @Override public Uri getUri(){return uri;}
    @Override public void close(){engine.closeReader();uri=null;if(open){open=false;transferEnded();}}
}
