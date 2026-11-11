package ru.southcoast.terrain;

import android.app.Activity;
import android.os.Bundle;
import android.opengl.GLSurfaceView;
import android.opengl.GLU;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.*;
import java.io.*;
import java.nio.*;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.egl.EGLConfig;

public class MainActivity extends Activity {
    private TerrainView terrain;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0,32,0,24);
        TextView title = new TextView(this);
        title.setText("ЮБК · Севастополь — Алушта\nРеальный масштаб высот · 1:1");
        title.setTextSize(16); title.setPadding(20,12,20,12);
        layout.addView(title);
        try {
            terrain = new TerrainView();
            layout.addView(terrain,new LinearLayout.LayoutParams(-1,0,1));
            Button reset = new Button(this); reset.setText("Вернуть общий вид");
            reset.setOnClickListener(v -> terrain.queueEvent(() -> { terrain.render.walkMode=false; terrain.render.walk.forward=0; terrain.render.yaw=0; terrain.render.tilt=55; terrain.render.zoom=1; terrain.requestRender(); }));
            layout.addView(reset);
            LinearLayout controls = new LinearLayout(this);
            Button walk = new Button(this); walk.setText("Прогулка · Ялта");
            walk.setOnClickListener(v -> terrain.queueEvent(() -> {terrain.render.walk.reset();terrain.render.walkMode=true;terrain.requestRender();}));
            controls.addView(walk,new LinearLayout.LayoutParams(0,-2,2));
            for(int direction : new int[]{-1,1}) {
                Button move = new Button(this);move.setText(direction==1 ? "Вперёд" : "Назад");
                move.setOnTouchListener((v,e) -> {
                    int action=e.getActionMasked();
                    if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL){
                        float speed=action==MotionEvent.ACTION_DOWN ? direction : 0;
                        terrain.queueEvent(() -> {terrain.render.walk.forward=speed;terrain.requestRender();});
                    }
                    return true;
                });
                controls.addView(move,new LinearLayout.LayoutParams(0,-2,1));
            }
            layout.addView(controls);
            TextView help = new TextView(this);
            help.setText("Прогулка: взгляд пальцем, движение — удерживайте кнопку.\nПилот Ялты: ≈ 1,6 × 1,1 км. Дома и деревья упрощены.\n© OpenStreetMap contributors · ODbL · openstreetmap.org/copyright\nРельеф: Mapzen / Tilezen · SRTM NASA / USGS");
            help.setPadding(20,8,20,8); layout.addView(help);
        } catch (IOException e) {
            TextView error = new TextView(this); error.setText("Не удалось загрузить рельеф. Пересоберите приложение с terrain.bin."); layout.addView(error);
        }
        setContentView(layout);
    }
    @Override protected void onPause(){ super.onPause(); if(terrain!=null){terrain.queueEvent(() -> {terrain.render.walk.forward=0;terrain.render.walk.lastFrame=0;});terrain.onPause();} }
    @Override protected void onResume(){ super.onResume(); if(terrain!=null)terrain.onResume(); }
    class TerrainView extends GLSurfaceView {
        final TerrainRenderer render;
        final ScaleGestureDetector scale;
        float lastX,lastY;
        TerrainView() throws IOException {
            super(MainActivity.this);
            render = new TerrainRenderer(); setRenderer(render); setRenderMode(RENDERMODE_WHEN_DIRTY);
            scale = new ScaleGestureDetector(MainActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                @Override public boolean onScale(ScaleGestureDetector d){
                    float factor=d.getScaleFactor(); queueEvent(() -> {render.zoom=Math.max(.5f,Math.min(12,render.zoom*factor)); requestRender();}); return true;
                }
            });
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            scale.onTouchEvent(e);
            float x=e.getX(), y=e.getY();
            if(e.getActionMasked()==MotionEvent.ACTION_MOVE && e.getPointerCount()==1 && !scale.isInProgress()){
                float dx=x-lastX,dy=y-lastY;
                queueEvent(() -> {if(render.walkMode){render.walk.heading+=dx*.2f;render.walk.pitch=Math.max(-75,Math.min(75,render.walk.pitch-dy*.2f));}
                    else {render.yaw+=dx*.25f; render.tilt=Math.max(5,Math.min(85,render.tilt+dy*.25f));} requestRender();});
            }
            lastX=x;lastY=y;return true;
        }
    }
    class TerrainRenderer implements GLSurfaceView.Renderer {
        FloatBuffer vertices, colors;
        final WalkScene walk; boolean walkMode=false;
        int count; float yaw=0,tilt=55,zoom=1,aspect=1;
        TerrainRenderer() throws IOException {
            walk=new WalkScene(getAssets());
            try(DataInputStream in=new DataInputStream(getAssets().open("terrain.bin"))){
                int w=in.readInt(),h=in.readInt();
                if(w<2||h<2||w*h>1000000)throw new IOException("Invalid mesh");
                float[][] p=new float[w*h][3];
                for(float[] v:p)for(int k=0;k<3;k++)v[k]=in.readFloat()/1000f;
                count=(w-1)*(h-1)*6;
                vertices=ByteBuffer.allocateDirect(count*12).order(ByteOrder.nativeOrder()).asFloatBuffer();
                colors=ByteBuffer.allocateDirect(count*16).order(ByteOrder.nativeOrder()).asFloatBuffer();
                for(int r=0;r<h-1;r++)for(int c=0;c<w-1;c++){
                    int i=r*w+c; triangle(p[i],p[i+w],p[i+1]); triangle(p[i+1],p[i+w],p[i+w+1]);
                }
                vertices.position(0);colors.position(0);
            }
        }
        void triangle(float[] a,float[] b,float[] c){
            float ux=b[0]-a[0],uy=b[1]-a[1],uz=b[2]-a[2],vx=c[0]-a[0],vy=c[1]-a[1],vz=c[2]-a[2];
            float nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            float len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);
            float shade=.5f+.5f*Math.max(0,(-nx*.4f+ny*.8f+nz*.4f)/len);
            for(float[] p:new float[][]{a,b,c}){
                vertices.put(p); float t=Math.min(1,p[1]/1.6f);
                if(p[1]<=0)colors.put(new float[]{.07f,.28f,.42f,1});
                else colors.put(new float[]{(.2f+.55f*t)*shade,(.48f+.2f*t)*shade,(.25f+.4f*t)*shade,1});
            }
        }
        public void onSurfaceCreated(GL10 gl,EGLConfig config){gl.glClearColor(.035f,.065f,.09f,1);gl.glEnable(GL10.GL_DEPTH_TEST);}
        public void onSurfaceChanged(GL10 gl,int w,int h){gl.glViewport(0,0,w,h);aspect=(float)w/Math.max(1,h);}
        public void onDrawFrame(GL10 gl){
            if(walkMode){walk.draw(gl,aspect);if(walk.forward!=0)terrain.requestRender();return;}
            gl.glClearColor(.035f,.065f,.09f,1);
            gl.glClear(GL10.GL_COLOR_BUFFER_BIT|GL10.GL_DEPTH_BUFFER_BIT);
            gl.glMatrixMode(GL10.GL_PROJECTION);gl.glLoadIdentity();
            float radius=55/zoom;float halfY=radius/Math.min(1,aspect);
            gl.glOrthof(-halfY*aspect,halfY*aspect,-halfY,halfY,1,500);
            gl.glMatrixMode(GL10.GL_MODELVIEW);gl.glLoadIdentity();
            GLU.gluLookAt(gl,0,0,200,0,0,0,0,1,0);
            gl.glRotatef(tilt,1,0,0);gl.glRotatef(yaw,0,1,0);
            gl.glEnableClientState(GL10.GL_VERTEX_ARRAY);gl.glEnableClientState(GL10.GL_COLOR_ARRAY);
            gl.glVertexPointer(3,GL10.GL_FLOAT,0,vertices);gl.glColorPointer(4,GL10.GL_FLOAT,0,colors);
            gl.glDrawArrays(GL10.GL_TRIANGLES,0,count);
        }
    }
}
