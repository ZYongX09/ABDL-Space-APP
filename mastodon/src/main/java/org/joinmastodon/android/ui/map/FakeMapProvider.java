package org.joinmastodon.android.ui.map;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import java.util.function.Consumer;
import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

import org.joinmastodon.android.model.map.MapModels;

/** Deterministic provider for UI and lifecycle tests until a licensed SDK is selected. */
public final class FakeMapProvider implements MapProvider {
    private final TextView view;
    private CameraState camera;
    private Listener listener;
    private final List<MapModels.Point> points=new ArrayList<>();
    public FakeMapProvider(Context context){
        view=new TextView(context);
        view.setText("地图服务准备中");
        view.setTextColor(Color.DKGRAY);
        view.setGravity(android.view.Gravity.CENTER);
        view.setBackgroundColor(0xffe9eef2);
    }
    @Override public View getView(){ return view; }
    @Override public void initialize(Listener listener){ this.listener=listener; }
    @Override public void setCamera(CameraState camera){ this.camera=camera; if(listener!=null) listener.onCameraIdle(camera); }
    @Override public CameraState getCamera(){ return camera; }
    @Override public void render(List<MapModels.Point> points){ this.points.clear(); if(points!=null) this.points.addAll(points); view.setText(points==null || points.isEmpty() ? "地图服务准备中\n暂无附近用户" : "地图服务准备中\n附近用户："+points.size()); }
    @Override public void clear(){ points.clear(); view.setText("地图服务准备中"); }
    @Override public void requestSnapshot(Consumer<Bitmap> callback){ callback.accept(null); }
    @Override public void destroy(){ listener=null; points.clear(); }
}
