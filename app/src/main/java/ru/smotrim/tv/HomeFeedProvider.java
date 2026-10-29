package ru.smotrim.tv;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.List;

/** Read-only, signature-permission protected bridge to our home screen. */
public final class HomeFeedProvider extends ContentProvider {
    private static final String[] COLUMNS={"_id","kind","key","title","subtitle","image","position","duration"};
    @Override public boolean onCreate(){return true;}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
        if(!uri.equals(HomeFeed.URI))throw new IllegalArgumentException("Unknown feed");
        MatrixCursor cursor=new MatrixCursor(COLUMNS);JSONObject data=HomeFeed.read(getContext());int id=0;
        for(String kind:new String[]{"history","channels"}) {
            JSONArray list=data.optJSONArray(kind);if(list==null)continue;
            for(int i=0;i<list.length();i++) {
                JSONObject row=list.optJSONObject(i);if(row==null)continue;String image=row.optString("image"),art="";
                if(!image.isEmpty()){String name=HomeFeed.hash(image);if(new File(HomeFeed.directory(getContext()),name+".jpg").isFile())art="content://ru.smotrim.tv.home/art/"+name;}
                cursor.addRow(new Object[]{id++,kind,HomeFeed.opaqueKey(kind,row.optString("key")),row.optString("title"),row.optString("subtitle"),art,row.optLong("position"),row.optLong("duration")});
            }
        }
        cursor.setNotificationUri(getContext().getContentResolver(),HomeFeed.URI);return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        List<String> path=uri.getPathSegments();
        if(!"r".equals(mode)||path.size()!=2||!path.get(0).equals("art")||!path.get(1).matches("[0-9a-f]{64}"))throw new FileNotFoundException();
        return ParcelFileDescriptor.open(new File(HomeFeed.directory(getContext()),path.get(1)+".jpg"),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){return uri.getPath().startsWith("/art/")?"image/jpeg":"vnd.android.cursor.dir/vnd.smotrim.home";}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
