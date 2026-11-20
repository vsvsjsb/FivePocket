package dev.recompbootstrap.smoke;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** In-window GTA5 ALG touch controller. Layout is stored separately from game saves. */
final class GameTouchOverlay extends View {
    interface Listener { void state(int buttons, int lx, int ly, int rx, int ry); }
    private static final int A=0x1000, B=0x2000, X=0x4000, Y=0x8000;
    private final Paint paint=new Paint(3);
    private final SharedPreferences prefs;
    private final Listener listener;
    private float stickX,stickY,stickRadius,buttonRadius;
    private final float[] bx=new float[4], by=new float[4];
    private final int[] masks={X,Y,B,A};
    private final String[] labels={"X","Y","B","A"};
    private int stickPointer=-1,cameraPointer=-1,buttonPointer=-1,buttonIndex=-1,editPointer=-1,editControl=-1;
    private float stickDX,stickDY,cameraDX,cameraDY;
    private int buttons;
    private int lx,ly,rx,ry;
    private boolean editing;
    private float editDownX,editDownY;
    private float alpha=0.72f;

    GameTouchOverlay(Context context, Listener listener) {
        super(context);
        this.listener=listener;
        prefs=context.getSharedPreferences("gta5_alg_touch_layout",Context.MODE_PRIVATE);
        alpha=prefs.getFloat("alpha",0.72f);
        setLayerType(View.LAYER_TYPE_SOFTWARE,null);
    }
    private float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private void loadPosition(String key,float fallbackX,float fallbackY,float[] out){
        out[0]=prefs.getFloat(key+"_x",fallbackX);out[1]=prefs.getFloat(key+"_y",fallbackY);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){
        super.onSizeChanged(w,h,oldw,oldh);
        if(w<=0||h<=0)return;
        stickRadius=58*getResources().getDisplayMetrics().density;
        buttonRadius=27*getResources().getDisplayMetrics().density;
        float[] p=new float[2];
        loadPosition("stick",0.17f,0.78f,p);stickX=p[0]*w;stickY=p[1]*h;
        float[][] defaults={{0.79f,0.73f},{0.89f,0.65f},{0.89f,0.81f},{0.98f,0.73f}};
        for(int i=0;i<4;i++){loadPosition("button"+i,defaults[i][0],defaults[i][1],p);bx[i]=p[0]*w;by[i]=p[1]*h;}
    }
    private void saveLayout(){
        SharedPreferences.Editor e=prefs.edit().putFloat("alpha",alpha)
            .putFloat("stick_x",stickX/getWidth()).putFloat("stick_y",stickY/getHeight());
        for(int i=0;i<4;i++)e.putFloat("button"+i+"_x",bx[i]/getWidth()).putFloat("button"+i+"_y",by[i]/getHeight());
        e.apply();
    }
    private void emit(){
        if(listener!=null)listener.state(buttons,lx,ly,rx,ry);
        invalidate();
    }
    private static int axis(float value,float range){
        float n=Math.max(-1f,Math.min(1f,value/range));
        return Math.round(n*(n<0?32768:32767));
    }
    private int hitButton(float x,float y){
        for(int i=0;i<4;i++)if(Math.hypot(x-bx[i],y-by[i])<=buttonRadius*1.55f)return i;
        return -1;
    }
    private int hitControl(float x,float y){
        if(Math.hypot(x-stickX,y-stickY)<=stickRadius*1.8f)return 0;
        int b=hitButton(x,y);return b<0?-1:b+1;
    }
    private boolean editButton(float x,float y){return x>getWidth()-100*getResources().getDisplayMetrics().density && y<75*getResources().getDisplayMetrics().density;}
    private void releasePointer(int id){
        if(id==stickPointer){stickPointer=-1;lx=ly=0;}
        if(id==cameraPointer){cameraPointer=-1;rx=ry=0;}
        if(id==buttonPointer){buttonPointer=-1;if(buttonIndex>=0)buttons&=~masks[buttonIndex];buttonIndex=-1;}
        if(id==editPointer){editPointer=-1;saveLayout();}
        emit();
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        int action=event.getActionMasked(),index=event.getActionIndex(),id=event.getPointerId(index);
        float x=event.getX(index),y=event.getY(index);
        if(action==MotionEvent.ACTION_DOWN||action==MotionEvent.ACTION_POINTER_DOWN){
            if(editButton(x,y)){editing=!editing;if(!editing)saveLayout();invalidate();return true;}
            if(editing){
                int control=hitControl(x,y);
                if(control>=0){editPointer=id;editControl=control;editDownX=x;editDownY=y;return true;}
                return true;
            }
            if(stickPointer<0 && Math.hypot(x-stickX,y-stickY)<=stickRadius*1.8f){
                stickPointer=id;stickDX=x;stickDY=y;updateStick(x,y);return true;
            }
            int b=hitButton(x,y);
            if(b>=0 && buttonPointer<0){buttonPointer=id;buttonIndex=b;buttons|=masks[b];emit();return true;}
            if(cameraPointer<0 && x>getWidth()*0.43f && y<getHeight()*0.80f){
                cameraPointer=id;cameraDX=x;cameraDY=y;rx=ry=0;return true;
            }
            return false;
        }
        if(action==MotionEvent.ACTION_MOVE){
            for(int i=0;i<event.getPointerCount();i++){
                int pid=event.getPointerId(i);float px=event.getX(i),py=event.getY(i);
                if(pid==stickPointer)updateStick(px,py);
                if(pid==cameraPointer){rx=axis(px-cameraDX,stickRadius*1.25f);ry=axis(-(py-cameraDY),stickRadius*1.25f);emit();}
                if(pid==editPointer){
                    float dx=px-editDownX,dy=py-editDownY;editDownX=px;editDownY=py;
                    if(editControl==0){stickX=clamp(stickX+dx,stickRadius,getWidth()-stickRadius);stickY=clamp(stickY+dy,stickRadius,getHeight()-stickRadius);}
                    else {int b=editControl-1;bx[b]=clamp(bx[b]+dx,buttonRadius,getWidth()-buttonRadius);by[b]=clamp(by[b]+dy,buttonRadius,getHeight()-buttonRadius);}
                    invalidate();
                }
            }
            return true;
        }
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_POINTER_UP||action==MotionEvent.ACTION_CANCEL){
            if(action==MotionEvent.ACTION_CANCEL){
                stickPointer=cameraPointer=buttonPointer=editPointer=-1;buttonIndex=-1;buttons=lx=ly=rx=ry=0;saveLayout();emit();return true;
            }
            releasePointer(id);return true;
        }
        return true;
    }
    private void updateStick(float x,float y){
        float dx=x-stickX,dy=y-stickY;double length=Math.hypot(dx,dy);
        if(length>stickRadius){dx*=stickRadius/length;dy*=stickRadius/length;}
        lx=axis(dx,stickRadius);ly=axis(-dy,stickRadius);emit();
    }
    @Override protected void onDraw(Canvas c){
        super.onDraw(c);
        float d=getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);paint.setColor(0x88000000);paint.setAlpha((int)(255*alpha));
        c.drawRoundRect(new RectF(getWidth()-92*d,14*d,getWidth()-12*d,56*d),18*d,18*d,paint);
        paint.setColor(-1);paint.setTextSize(14*d);paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(editing?"DONE":"EDIT",getWidth()-52*d,41*d,paint);
        paint.setColor(0x66333333);paint.setStyle(Paint.Style.FILL);c.drawCircle(stickX,stickY,stickRadius,paint);
        paint.setColor(0x99FFFFFF);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*d);c.drawCircle(stickX,stickY,stickRadius,paint);
        paint.setStyle(Paint.Style.FILL);paint.setColor(0xCCFFFFFF);c.drawCircle(stickX+lx/32767f*stickRadius*0.65f,stickY-ly/32767f*stickRadius*0.65f,stickRadius*0.34f,paint);
        for(int i=0;i<4;i++){
            paint.setStyle(Paint.Style.FILL);paint.setColor((buttons&masks[i])!=0?0xDDA6D6FF:0x88777777);
            c.drawCircle(bx[i],by[i],buttonRadius,paint);
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2*d);paint.setColor(0xDDFFFFFF);c.drawCircle(bx[i],by[i],buttonRadius,paint);
            paint.setStyle(Paint.Style.FILL);paint.setTextSize(17*d);paint.setTextAlign(Paint.Align.CENTER);paint.setColor(-1);
            c.drawText(labels[i],bx[i],by[i]+6*d,paint);
        }
        if(editing){
            paint.setColor(0xAAFFFFFF);paint.setTextSize(12*d);paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("اسحب الأزرار لتغيير مكانها",getWidth()/2f,34*d,paint);
        }
    }
}
