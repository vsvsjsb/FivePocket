package dev.recompbootstrap.smoke;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.View;

/**
 * qol-touch: draws TouchPad's controls over the game picture (our own drawing, no game asset). It takes no touches itself: the game
 * SurfaceView's listener (QolTouch) routes every finger, so this view is transparent to input. Animation (the sticks' knob and ring,
 * fades) runs at the display's rate through postInvalidateOnAnimation, independent of the game's frame rate (SPEC 4).
 * Style (SPEC 3.5, the GTA-mobile look, reference/NOTES.md; the host preview TouchRender.swift draws the same): the universal controls are
 * San Andreas mobile style discs (a clear middle, a white ring inside a black ring) with white outlined silhouettes (TouchIcons), the
 * sticks a black ring and a white knob; held = a white middle and a black glyph, a pressed pedal tinted (gas green, brake red). The 360
 * pad keeps its letters in coloured rings.
 */
final class TouchPadView extends View {
    private final TouchPad pad;
    private final Paint fill=new Paint(Paint.ANTI_ALIAS_FLAG),rim=new Paint(Paint.ANTI_ALIAS_FLAG),glow=new Paint(Paint.ANTI_ALIAS_FLAG),
            text=new Paint(Paint.ANTI_ALIAS_FLAG),small=new Paint(Paint.ANTI_ALIAS_FLAG),knob=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private final RectF arc=new RectF();

    TouchPadView(Context context,TouchPad pad){
        super(context);this.pad=pad;dp=context.getResources().getDisplayMetrics().density;
        setClickable(false);setFocusable(false);setWillNotDraw(false);
        fill.setStyle(Paint.Style.FILL);rim.setStyle(Paint.Style.STROKE);glow.setStyle(Paint.Style.STROKE);knob.setStyle(Paint.Style.FILL);
        final Typeface condensed=Typeface.create("sans-serif-condensed",Typeface.BOLD);
        text.setTypeface(condensed);text.setTextAlign(Paint.Align.CENTER);text.setColor(0xFFFFFFFF);
        small.setTypeface(condensed);small.setTextAlign(Paint.Align.CENTER);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent e){return false;} // (QolTouch routes the touches)
    @Override protected void onSizeChanged(int w,int h,int ow,int oh){
        pad.view(w,h,getResources().getDisplayMetrics().xdpi/25.4f);
        exclusion();
    }
    // touch-120 (2026-11-16): keep Android's edge Back gesture off the lower right edge while the touch controls are on
    // (TouchPad.rightEdgeExclusion); re-sent only when the strip changes (the controls turned on / off, a new size).
    private String exclusionKey="";
    private void exclusion(){
        if(android.os.Build.VERSION.SDK_INT<29)return;
        final float[] r=pad.rightEdgeExclusion(dp);
        final String key=r==null?"none":(Math.round(r[0])+","+Math.round(r[1])+","+Math.round(r[2])+","+Math.round(r[3]));
        if(key.equals(exclusionKey))return;
        exclusionKey=key;
        final java.util.List<android.graphics.Rect> rects=new java.util.ArrayList<>();
        if(r!=null)rects.add(new android.graphics.Rect(Math.round(r[0]),Math.round(r[1]),Math.round(r[2]),Math.round(r[3])));
        setSystemGestureExclusionRects(rects);
        android.util.Log.i("RecompQolTouch","TOUCH_EDGE_EXCLUSION "+key);
    }
    private static int alpha(int argb,float a){return (Math.round(((argb>>>24)&0xFF)*Math.max(0f,Math.min(1f,a)))<<24)|(argb&0xFFFFFF);}
    @Override protected void onDraw(Canvas c){
        final long started=System.nanoTime();
        try{paint(c,SystemClock.uptimeMillis());}
        finally{QolTouch.drawUs.add((System.nanoTime()-started)/1000);}
    }
    private void drawEditor(Canvas c){
        final float left=getWidth()-88*dp,top=10*dp,right=getWidth()-10*dp,bottom=42*dp;
        fill.setColor(0xAA101820);c.drawRoundRect(left,top,right,bottom,10*dp,10*dp,fill);
        rim.setStrokeWidth(1.4f*dp);rim.setColor(0xDDEFFFFFF);c.drawRoundRect(left,top,right,bottom,10*dp,10*dp,rim);
        text.setTextSize(11*dp);text.setColor(0xFFFFFFFF);
        c.drawText(pad.editing()?"DONE":"MOVE", (left+right)*0.5f, top+21*dp,text);
    }
    private long idlePostedFor=Long.MIN_VALUE;
    private void paint(Canvas c,final long t){
        exclusion(); // (touch-120: a settings change can turn the controls on / off)
        final float a=pad.alpha(t);
        if(pad.mode!=TouchPad.Mode.OFF)drawEditor(c);
        if(a>0.01f){
            for(TouchPad.Control k:pad.controls())if(!pad.userHidden() || k.kind==TouchPad.Kind.HIDE)drawControl(c,k,pad.userHidden()?Math.max(a,0.5f):a,t);
            if(!pad.mapShown() && !pad.userHidden() && !pad.sticksBlocked(t)){ // (SPEC 3.8: no sticks while a screen is open)
                drawStick(c,0,a,t);if(pad.rightRingDrawn())drawStick(c,1,a,t);} // (rswipe / a swipe camera: no ring)
        }
        if(pad.animating(t))postInvalidateOnAnimation();
        else { // jank-1117: draw the idle fade when it begins (TouchPad.idleFadeAt), not at the next unrelated redraw
            final long at=pad.idleFadeAt();
            if(at!=Long.MAX_VALUE && at>t && at!=idlePostedFor){idlePostedFor=at;postInvalidateDelayed(at-t+1);}
        }
    }
    private void drawControl(Canvas c,TouchPad.Control k,float a,long t){
        final boolean held=pad.held(k);
        if(k.id.startsWith("prompt_")){drawPrompt(c,k,held);return;} // (map-prompts: drawn at full strength over the map)
        if(pad.mapShown() && k.bits!=TouchPad.START && k.kind!=TouchPad.Kind.HIDE)return;
        final int ring=pad.mode==TouchPad.Mode.PAD && k.color!=TouchPad.GREY?k.color:0xFFFFFFFF; // (the 360 pad: coloured rings)
        if(pad.mode!=TouchPad.Mode.PAD || k.kind==TouchPad.Kind.HIDE || k.bits==TouchPad.BACK || k.bits==TouchPad.START){ // (the GTA-mobile disc and icon; the 360
            final String icon=k.kind==TouchPad.Kind.HIDE && pad.userHidden()?"show":k.id.equals("handbrake") && pad.driveByHeld()?"fire": // pad's BACK / START too, as on iOS;
                    k.id.equals("back")?"cam":k.id.equals("start")?"pause":k.id;                                                           // SPEC 2: LB + RB = drive-by fire)
            final int tint=k.id.equals("call_answer")?0xFF40BF4D:k.id.equals("call_decline")?0xFFD93333:!held?0: // (3.15: a call's buttons always tinted)
                    k.kind==TouchPad.Kind.TRIGGER_R?0xFF40BF4D:k.kind==TouchPad.Kind.TRIGGER_L?0xFFD93333:0;
            drawDisc(c,k.cx,k.cy,k.r,held && tint==0,a,tint);
            if(k.kind==TouchPad.Kind.WEAPON_TAP){drawWeapon(c,k,held,t);return;} // (SPEC 3.12: a weapon panel, not a disc)
            if(k.id.equals("trainer")){drawTrainer(c,k,a);return;} // (SPEC 3.14: the owner's picture or the dotted skull)
            if(k.kind==TouchPad.Kind.IMENU){drawImenu(c,k,a,held);return;} // (SPEC 3.17: our own list glyph, no game art)
            else if(TouchIcons.draw(c,icon,k.cx,k.cy,k.r*TouchIcons.SHARE,a,held && tint==0))return;
        } else {
            fill.setColor(alpha(held?0x73FFFFFF:0x59000000,a));c.drawCircle(k.cx,k.cy,k.r,fill);
            rim.setStrokeWidth(held?3f*dp:2f*dp);rim.setColor(alpha(ring,a*(held?1f:0.7f)));c.drawCircle(k.cx,k.cy,k.r-rim.getStrokeWidth()*0.5f,rim);
            if(held){glow.setStrokeWidth(6*dp);glow.setColor(alpha(ring,a*0.35f));c.drawCircle(k.cx,k.cy,k.r+3*dp,glow);}
            if(k.kind==TouchPad.Kind.DPAD){drawDpad(c,k,a);return;}
        }
        String label=k.kind==TouchPad.Kind.HIDE && pad.userHidden()?"SHOW":k.label;
        if(k.id.equals("handbrake") && pad.driveByHeld())label="FIRE"; // (SPEC 2: LB + RB = drive-by fire)
        final float size=Math.min(k.r*0.42f,Math.max(10*dp,k.r*2.2f/Math.max(3,label.length())));
        text.setTextSize(size);text.setColor(alpha(held?0xFF000000:0xFFFFFFFF,a));
        final boolean face=label.length()==1;
        c.drawText(label,k.cx,k.cy+(face?size*0.36f:size*0.2f),text);
        if(!face){small.setTextSize(Math.max(8*dp,size*0.48f));small.setColor(alpha(ring,a*0.9f));c.drawText(k.key,k.cx,k.cy+size*0.95f,small);}
        if((k.kind==TouchPad.Kind.TRIGGER_L || k.kind==TouchPad.Kind.TRIGGER_R) && held){ // (the analog depth as an arc)
            final int[] s=pad.state(t);final float v=(k.kind==TouchPad.Kind.TRIGGER_L?s[5]:s[6])/255f;
            arc.set(k.cx-k.r+4*dp,k.cy-k.r+4*dp,k.cx+k.r-4*dp,k.cy+k.r-4*dp);
            glow.setStrokeWidth(4*dp);glow.setColor(alpha(ring,a));c.drawArc(arc,-90,360*v,false,glow);
        }
    }
    /** SPEC 3.12 v2: the WEAPON button as the GTA HUD's weapon box: a dark rounded panel with a thin light rim and the weapon's silhouette in
     *  the game's grey, filling its width; never dimmed by the idle fade (TouchPad.weaponAlpha). */
    private void drawWeapon(Canvas c,TouchPad.Control k,boolean held,long t){ // (v15, the owner: "GTA 4 style": no disc, no outline - a big grey
        TouchIcons.draw(c,TouchPad.weaponIcon(pad.weaponGroup()),k.cx,k.cy,k.r*1.15f,pad.weaponAlpha(t),false,held?0xFFFFFF:0xA8A8A8,0.6f); // silhouette, a thin dark edge)
    }
    /** map-prompts: a GTA prompt icon - a face button as a filled circle in its colour with its white letter, a shoulder or trigger as a
     *  dark rounded box with a white rim and its name; held = brighter. At the pad's opacity (not the map's 20 %). */
    private void drawPrompt(Canvas c,TouchPad.Control k,boolean held){
        final float a=pad.opacity,r=k.r*0.82f;final String key=k.label;
        if(key.equals("DPAD")){ // (the game's D-pad prompt: a white cross on a dark disc)
            fill.setColor(alpha(held?0xFF8A8A8A:0xB3000000,a));c.drawCircle(k.cx,k.cy,k.r,fill);drawDpad(c,k,a);return;}
        if(key.equals("BACK")){ // (the game's BACK / View prompt: a white circle with a black left triangle)
            fill.setColor(alpha(held?0xFFBDBDBD:0xFFFFFFFF,a));c.drawCircle(k.cx,k.cy,r,fill);
            final android.graphics.Path p=new android.graphics.Path();p.moveTo(k.cx-0.45f*r,k.cy);p.lineTo(k.cx+0.30f*r,k.cy-0.42f*r);p.lineTo(k.cx+0.30f*r,k.cy+0.42f*r);p.close();
            fill.setColor(alpha(0xFF000000,a));c.drawPath(p,fill);return;}
        final boolean shoulder=key.length()==2;
        if(shoulder){
            arc.set(k.cx-r*1.15f,k.cy-r*0.72f,k.cx+r*1.15f,k.cy+r*0.72f);
            fill.setColor(alpha(held?0xFF8A8A8A:0xFF2E2E2E,a));c.drawRoundRect(arc,r*0.35f,r*0.35f,fill);
            rim.setStrokeWidth(1.8f*dp);rim.setColor(alpha(0xE6FFFFFF,a));c.drawRoundRect(arc,r*0.35f,r*0.35f,rim);
            text.setTextSize(r*0.78f);
        } else {
            fill.setColor(alpha(k.color,a));c.drawCircle(k.cx,k.cy,r,fill);
            if(held){fill.setColor(alpha(0x66FFFFFF,a));c.drawCircle(k.cx,k.cy,r,fill);}
            rim.setStrokeWidth(1.4f*dp);rim.setColor(alpha(0x99000000,a));c.drawCircle(k.cx,k.cy,r,rim);
            text.setTextSize(r*1.05f);
        }
        text.setColor(alpha(0xFFFFFFFF,a));c.drawText(key,k.cx,k.cy+text.getTextSize()*0.36f,text);
    }
    /** The San Andreas mobile disc: black 18 % middle (white 40 % held, or the pedal's tint), a black ring outside a white one. */
    private void drawDisc(Canvas c,float x,float y,float r,boolean held,float a,int tint){
        fill.setColor(alpha(held?0x66FFFFFF:0x2E000000,a));c.drawCircle(x,y,r,fill);
        if(tint!=0){fill.setColor(alpha(tint&0x73FFFFFF,a));c.drawCircle(x,y,r,fill);}
        rim.setStrokeWidth(2.2f*dp);rim.setColor(alpha(0x8C000000,a));c.drawCircle(x,y,r-1.1f*dp,rim);
        rim.setStrokeWidth(1.8f*dp);rim.setColor(alpha(0xE6FFFFFF,a));c.drawCircle(x,y,r-3.1f*dp,rim);
    }
    /** SPEC 3.14: the TRAINER button's face: assets/owner/trainer-icon.png (the owner's own picture, the Thor's Lester button; OWNER BUILDS
     *  ONLY, src/debug) inside the disc, else the Thor's dotted skull and crossbones (TrainerButtonView.DOTS there). */
    private void drawTrainer(Canvas c,TouchPad.Control k,float a){
        if(trainerIcon==null && !trainerIconTried){trainerIconTried=true;
            try(java.io.InputStream in=getContext().getAssets().open("owner/trainer-icon.png")){trainerIcon=android.graphics.BitmapFactory.decodeStream(in);}
            catch(java.io.IOException | RuntimeException none){trainerIcon=null;}}
        final float r=k.r-3.1f*dp;
        if(trainerIcon!=null){
            c.save();clip.reset();clip.addCircle(k.cx,k.cy,r,android.graphics.Path.Direction.CW);c.clipPath(clip);
            arc.set(k.cx-r,k.cy-r,k.cx+r,k.cy+r);img.setAlpha(Math.round(255*Math.max(0f,Math.min(1f,a))));c.drawBitmap(trainerIcon,null,arc,img);
            c.restore();return;
        }
        final float side=k.r*1.3f,cell=side/14f,dot=cell*0.74f,x0=k.cx-13f*cell*0.5f,y0=k.cy-side*0.5f;
        for(int j=0;j<DOTS.length;++j)for(int i=0;i<DOTS[j].length();++i)if(DOTS[j].charAt(i)=='#'){
            fill.setColor(alpha(0xFFFFFFFF,a));final float x=x0+i*cell+(cell-dot)*0.5f,y=y0+j*cell+(cell-dot)*0.5f;c.drawRect(x,y,x+dot,y+dot,fill);}
        fill.setColor(alpha(0xFFEB1F1F,a));
        for(float col:new float[]{4.5f,8.5f}){final float cx=x0+col*cell,cy=y0+4f*cell;c.drawRect(cx-dot*0.6f,cy-dot*0.6f,cx+dot*0.6f,cy+dot*0.6f,fill);}
    }
    /** SPEC 3.17: the INTERACTION MENU button's face, drawn here (no game art): a menu list - three rows, each a dot and a bar - in the
     *  icons' style (white with a black outline; held = black on the white middle). */
    private void drawImenu(Canvas c,TouchPad.Control k,float a,boolean held){
        final float u=k.r*TouchIcons.SHARE/12f; // (the icons' 24-unit box)
        final int ink=held?0xFF000000:0xFFFFFFFF,edge=held?0xFFFFFFFF:0xFF000000;
        for(int pass=0;pass<2;++pass){
            final float grow=pass==0?TouchIcons.OUTLINE*u:0f;fill.setColor(alpha(pass==0?edge:ink,a));
            for(int i=-1;i<=1;++i){final float y=k.cy+i*7f*u;
                c.drawCircle(k.cx-7.5f*u,y,1.9f*u+grow,fill);
                arc.set(k.cx-4f*u-grow,y-1.6f*u-grow,k.cx+9.5f*u+grow,y+1.6f*u+grow);c.drawRoundRect(arc,1.6f*u+grow,1.6f*u+grow,fill);}
        }
    }
    static final String[] DOTS={"...#######...","..#########..",".###########.",".##...#...##.",".##...#...##.",".###########.","..####.####..",
            "...#######...","...#.#.#.#...","#...........#",".##.......##.","...##...##...",".##.......##.","#...........#"};
    private android.graphics.Bitmap trainerIcon;private boolean trainerIconTried;
    private final android.graphics.Path clip=new android.graphics.Path();
    private final Paint img=new Paint(Paint.FILTER_BITMAP_FLAG|Paint.ANTI_ALIAS_FLAG);
    private void drawDpad(Canvas c,TouchPad.Control k,float a){
        final float w=k.r*0.36f,l=k.r*0.82f;
        fill.setColor(alpha(0xCCFFFFFF,a*0.8f));
        c.drawRect(k.cx-w,k.cy-l,k.cx+w,k.cy+l,fill);c.drawRect(k.cx-l,k.cy-w,k.cx+l,k.cy+w,fill);
    }
    /** The San Andreas mobile stick: a black ring with a thin white inner line, a white knob with a black edge, no letters. Only under a finger
     *  and while it fades after the lift (TouchPad.stickAlpha, SPEC 4.2). */
    private void drawStick(Canvas c,int side,float a0,long t){
        final TouchPad.Stick s=pad.sticks[side];
        final float a=a0*pad.stickAlpha(side,t); // (before stickDraw, which ends the release)
        final float[] d=pad.stickDraw(side,t);
        if(a<=0.01f)return;
        fill.setColor(alpha(0x1F000000,a));c.drawCircle(d[0],d[1],s.R,fill);
        final float w=Math.max(3*dp,s.R*0.07f);
        rim.setStrokeWidth(w);rim.setColor(alpha(s.held?0xCC000000:0x99000000,a));c.drawCircle(d[0],d[1],s.R,rim);
        rim.setStrokeWidth(1.2f*dp);rim.setColor(alpha(s.held?0xE6FFFFFF:0x8CFFFFFF,a));c.drawCircle(d[0],d[1],s.R-w*0.5f-0.6f*dp,rim);
        final float kr=s.R*0.36f;
        knob.setColor(alpha(s.held?0xF2FFFFFF:0xCCFFFFFF,a));c.drawCircle(d[0]+d[2],d[1]+d[3],kr,knob);
        rim.setStrokeWidth(1.6f*dp);rim.setColor(alpha(0xB3000000,a));c.drawCircle(d[0]+d[2],d[1]+d[3],kr,rim);
    }
}
