package ru.southcoast.terrain;

import android.app.AlertDialog;
import android.test.ActivityInstrumentationTestCase2;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;

public class MapFlowTest extends ActivityInstrumentationTestCase2<MainActivity> {
    public MapFlowTest(){super(MainActivity.class);}
    Object field(MainActivity a,String name) throws Exception {Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(a);}
    void tap(Navigation.MapView map,float x,float y){long t=SystemClock.uptimeMillis();MotionEvent a=MotionEvent.obtain(t,t,0,x,y,0),b=MotionEvent.obtain(t,t+40,1,x,y,0);map.dispatchTouchEvent(a);map.dispatchTouchEvent(b);a.recycle();b.recycle();}
    void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap=getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(getActivity().getExternalFilesDir(null),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
    public void testMapFirstConfirmCancelAndRender() throws Exception {
        MainActivity activity=getActivity();getInstrumentation().waitForIdleSync();
        Navigation.MapView map=(Navigation.MapView)field(activity,"map");
        View mapScreen=(View)field(activity,"mapScreen"),walkScreen=(View)field(activity,"walkScreen");
        MainActivity.TerrainView terrain=(MainActivity.TerrainView)field(activity,"terrain");
        assertEquals(View.VISIBLE,mapScreen.getVisibility());assertEquals(View.GONE,walkScreen.getVisibility());
        screenshot("map-start");
        getInstrumentation().runOnMainSync(() -> {map.transform();tap(map,map.px(terrain.scene.x),map.py(terrain.scene.z));});
        getInstrumentation().waitForIdleSync();AlertDialog dialog=(AlertDialog)field(activity,"teleportDialog");assertNotNull(dialog);assertTrue(dialog.isShowing());assertEquals(View.GONE,walkScreen.getVisibility());
        getInstrumentation().runOnMainSync(() -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        getInstrumentation().waitForIdleSync();SystemClock.sleep(350);
        assertEquals(View.VISIBLE,mapScreen.getVisibility());assertEquals(View.GONE,walkScreen.getVisibility());
        getInstrumentation().runOnMainSync(() -> tap(map,map.px(terrain.scene.x),map.py(terrain.scene.z)));
        getInstrumentation().waitForIdleSync();AlertDialog confirm=(AlertDialog)field(activity,"teleportDialog");assertNotNull(confirm);
        getInstrumentation().runOnMainSync(() -> confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        long deadline=SystemClock.uptimeMillis()+15000;while(terrain.frames==0&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100);
        getInstrumentation().waitForIdleSync();assertTrue("No GL frame rendered",terrain.frames>0);assertEquals("GL error",0,terrain.glError);assertEquals(View.VISIBLE,walkScreen.getVisibility());
        SystemClock.sleep(300);screenshot("walk-fixed-windows");
    }
    void twoFinger(Navigation.MapView map,long down,long time,int action,float span){
        MotionEvent.PointerProperties[] props={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
        for(int i=0;i<2;i++){props[i].id=i;props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;coords[i].x=map.getWidth()/2f+(i==0?-span:span);coords[i].y=map.getHeight()/2f;coords[i].pressure=1;coords[i].size=1;}
        MotionEvent e=MotionEvent.obtain(down,time,action,2,props,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);map.dispatchTouchEvent(e);e.recycle();
    }
    public void testPinchAndDragDoNotTeleport() throws Exception {
        MainActivity a=getActivity();getInstrumentation().waitForIdleSync();Navigation.MapView map=(Navigation.MapView)field(a,"map");
        final long t=SystemClock.uptimeMillis();
        getInstrumentation().runOnMainSync(() -> {
            MotionEvent down=MotionEvent.obtain(t,t,MotionEvent.ACTION_DOWN,map.getWidth()/2f-40,map.getHeight()/2f,0);map.dispatchTouchEvent(down);down.recycle();
            twoFinger(map,t,t+1,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),40);
        });
        for(int step=0;step<5;step++){
            SystemClock.sleep(150);final float span=70+step*30;
            getInstrumentation().runOnMainSync(() -> twoFinger(map,t,SystemClock.uptimeMillis(),MotionEvent.ACTION_MOVE,span));
        }
        getInstrumentation().runOnMainSync(() -> {
            twoFinger(map,t,SystemClock.uptimeMillis(),MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),190);
            MotionEvent up=MotionEvent.obtain(t,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,map.getWidth()/2f-190,map.getHeight()/2f,0);map.dispatchTouchEvent(up);up.recycle();
        });
        getInstrumentation().waitForIdleSync();assertTrue("Pinch did not zoom",map.zoom>1);assertNull(field(a,"teleportDialog"));
        getInstrumentation().runOnMainSync(() -> {
            long dragTime=SystemClock.uptimeMillis();float x=map.getWidth()/2f,y=map.getHeight()/2f;
            for(int i=0;i<3;i++){MotionEvent e=MotionEvent.obtain(dragTime,dragTime+i*30,new int[]{0,2,1}[i],x+i*60,y,0);map.dispatchTouchEvent(e);e.recycle();}
        });
        getInstrumentation().waitForIdleSync();assertNull(field(a,"teleportDialog"));assertEquals(View.VISIBLE,((View)field(a,"mapScreen")).getVisibility());screenshot("map-zoom-street-labels");
    }
}
