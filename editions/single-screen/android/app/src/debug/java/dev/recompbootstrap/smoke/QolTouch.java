package dev.recompbootstrap.smoke;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * qol-touch (single-screen QoL edition, 2026-10-24): the game view's touches -> QolGesture (long presses for the map and the wheels,
 * the Thor's map touch otherwise) -> the controller state the game already reads. Main thread only: the game activity's views and
 * PrivateGamepadInput's key / motion callbacks share the main looper.
 *
 * Wiring (three calls): PrivateTraceActivity sets open(filesDir) as the game SurfaceView's touch listener; PrivateGamepadInput passes
 * its pad through merge() in publish(), gives attachPad(this::publish) once and calls cancelAll() in clear(). No new native entry: the
 * game sees an ordinary pad state (the gesture's buttons OR'd, the right stick replaced while a push lasts) plus, with a library that
 * has the map-tap macro, the reserved request bit 0x0800 and the touchpad bit 0x0400 the library removes before the game reads it.
 * Log tag RecompQolTouch: one line per gesture state change (and per change of a wheel's stick push), labelled "touch" (never "PHYSICAL" or "SYNTHETIC").
 */
final class QolTouch implements View.OnTouchListener {
    static final String TAG="RecompQolTouch";
    static final String MAP_TAP_MARKER="recomp-map-tap-v1"; // (map_tap.h Marker)
    private static QolTouch current;     // the game view's listener while the game screen is shown
    private static Runnable padChanged;  // PrivateGamepadInput.publish
    private final File filesDir;
    private QolTouch(File filesDir){this.filesDir=filesDir;}
    private final QolGesture g=new QolGesture();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=this::tick;
    private long scheduled=QolGesture.NONE;
    private int pointer=-1,second=-1;
    private QolGesture.State lastState=QolGesture.State.IDLE;private MapTouch.State lastMap=MapTouch.State.IDLE;private int lastRx,lastRy;
    // touch-controls (SPEC: local-analysis/touch-controls-spec/SPEC.md): the on-screen pad, its view, the context poll
    final TouchPad pad=new TouchPad();private TouchPadView view;private static Runnable devicesChanged;
    private final Runnable poll=this::poll;private int polls;private int lastWord=Integer.MIN_VALUE,lastPhysical;private int[] lastPhysicalState;
    /** SPEC 3.14: PrivateTraceActivity's handler of the pad's app buttons ("qsave", "trainer"), run on the main thread after the touch. */
    static volatile java.util.function.Consumer<String> appActions;
    private String app(){ // (Update 1 haptics: returns the app action it handed on, or null)
        final String a=pad.takeAppAction();if(a==null)return null;
        Log.i(TAG,"TOUCH_PAD app "+a+" (touch input)");
        final java.util.function.Consumer<String> h=appActions;if(h!=null)handler.post(()->h.accept(a));
        return a;
    }
    static final String TOUCH_SETTINGS="settings/touch-controls.txt";
    /** SPEC 3.17 (1.1.5): the launcher started this game in Sandbox (GTA Online); PrivateTraceActivity sets it from the start request's
     *  play_mode. Used only when the library has no trainer state (TouchPad.imenuShown). */
    static volatile boolean sandboxLaunch;
    // side-menu (SPEC 5): the menu takes the edge strip's finger, and every finger while it is open (the pad and the gestures none)
    private SideMenu menu;private View menuView;private Runnable menuEvents;private int menuFinger=-1;
    /** PrivateTraceActivity: the side menu, its view, and what runs after each of its gestures (it takes the menu's events). */
    static void attachMenu(SideMenu m,View v,Runnable events){final QolTouch w=current;if(w==null)return;w.menu=m;w.menuView=v;w.menuEvents=events;}
    /** side-menu: a shown touch control under the point (the edge strip leaves it to the pad). */
    static boolean controlAt(float x,float y){final QolTouch w=current;return w!=null && w.pad.controlAt(x,y);}
    /** The menu's events after a key (Back, the gamepad): the same handler as after its touches; an opening lets the touch input go. */
    static void menuEvents(){
        final QolTouch w=current;if(w==null || w.menu==null)return;
        if(w.menu.isOpen()){w.g.cancel();w.pad.cancel(SystemClock.uptimeMillis());w.pointer=w.second=-1;w.after();}
        if(w.menuEvents!=null)w.menuEvents.run();
    }
    /** side-menu: the touch settings were saved (the pad's words again) / Show Touch Controls. */
    /** Update 1 touch item 3: the aim toggles (the side menu's menu.txt), kept for a pad made later. */
    private static volatile boolean adsTurnsSetting=true,fireAimsSetting=false;
    // OWNER ORDER 2026-11-10: Strong aim assist only while touch is the active input (AimInput; library v1171+): the Aim Magnetism choice
    // (0 game, 1 strong; -1 unknown) and the last winning input.
    private static volatile int assistSetting=-1;
    static void assist(int choice){assistSetting=choice;}
    private static final AimInput aimInput=new AimInput();
    private static void aimInputCall(int code,String why){if(code==0)return;final int r=PrivateTraceActivity.aimAssist(code);Log.i(TAG,"TOUCH_PAD aim input "+why+": recomp_aim_assist_v1("+code+") -> "+r);}
    static void aim(boolean adsTurns,boolean fireAims){
        adsTurnsSetting=adsTurns;fireAimsSetting=fireAims;
        final QolTouch w=current;if(w!=null){w.pad.adsTurns=adsTurns;w.pad.fireAims=fireAims;}
    }
    /** Update 1 touch item 4 (Fold check 3 evidence, 2026-11-08): the swipe's turns to the library, summed and logged at most once a
     *  second with the last answer (0 accepted, 1 refused, -100 no export) - one logcat line, never a file. */
    private static long camLogAt;private static float camYaw,camPitch;private static int camCalls;
    private static final boolean CAM_LOG=Log.isLoggable("RecompQolTouchCam",Log.DEBUG);   // (a system property, no file: read once per game process)
    static void camLog(long now,float[] turn,int ret){
        if(!CAM_LOG)return;   // (lead 2026-11-08: off in every build unless switched on: adb shell setprop log.tag.RecompQolTouchCam DEBUG, then restart the game)
        camYaw+=turn[0];camPitch+=turn[1];camCalls++;
        if(now-camLogAt<1000)return;
        Log.i(TAG,"TOUCH_CAMERA turn yaw="+camYaw+" pitch="+camPitch+" calls="+camCalls+" last_ret="+ret+" (touch input)");
        camLogAt=now;camYaw=camPitch=0;camCalls=0;
    }
    /** Update 1 touch item 4: the camera by swipe, speed and invert. swipe: the Camera row (Swipe, the default; Stick = the old floating
     *  stick, opt-in); export: the library has recomp_camera_turn_v1 (the direct turn). 2026-11-09 (the owner: "don't mimic the right
     *  analog stick"): Swipe without the export is the speed stick (TouchPad.swipeStick), never the floating stick. */
    private static volatile boolean camSwipeSetting=true,camExport=false,camInvertSetting=false;private static volatile float camDegSetting=8f;
    static void camera(boolean swipe,boolean export,int speed,boolean invert){
        camSwipeSetting=swipe;camExport=export;camInvertSetting=invert;camDegSetting=MenuWords.CAM_DEG_PER_CM[Math.max(0,Math.min(2,speed))];
        final QolTouch w=current;if(w!=null)w.applyCamera();
    }
    private void applyCamera(){pad.cameraSwipe=camSwipeSetting && camExport;pad.swipeStick=camSwipeSetting;pad.camInvert=camInvertSetting;pad.camDegPerCm=camDegSetting;}
    /** 2026-11-09 (the owner: hold to ADS, the 1-tap latch as a choice; auto-fire on the red reticle): the Aim Mode and Auto-Fire rows. */
    private static volatile boolean aimTapSetting=false,autoFireSetting=false;
    static void aimMode(boolean tap,boolean autoFire){
        aimTapSetting=tap;autoFireSetting=autoFire;
        final QolTouch w=current;if(w!=null){w.pad.aimMode(tap);w.pad.autoFire=autoFire;w.after();}
    }
    // The red-reticle signal (library spec: local-analysis/update-1/touch-subagent-2026-11-09/STATUS.md): recomp_aim_assist_v1(-3) answers
    // bit 0 = aiming, bit 1 = the game's reticle is red; older libraries answer -2 (an unknown mode, no side effect), none -100. Probed with
    // the action-22 poll (~2 s); read every RETICLE_POLL_MS only while Auto-Fire is on and the player aims by touch.
    static final int RETICLE_OP=-3,RETICLE_RED=2;static final long RETICLE_POLL_MS=33;
    private boolean reticleOn;private final Runnable reticleTick=this::reticleTick;
    private boolean reticleWanted(){return pad.autoFire && SideMenuRows.reticleSignal && pad.adsHeld();}
    private void reticleTick(){
        final long now=SystemClock.uptimeMillis();
        if(!reticleWanted()){reticleOn=false;pad.reticle(now,false);changed();return;}
        final int r=PrivateTraceActivity.aimAssist(RETICLE_OP);
        final int[] before=pad.state(now);pad.reticle(now,r>=0 && (r&RETICLE_RED)!=0);
        if(before[6]!=pad.state(now)[6]){Log.i(TAG,"TOUCH_PAD auto-fire "+(pad.state(now)[6]!=0?"on":"off")+" (reticle "+r+")");changed();}
        handler.postDelayed(reticleTick,RETICLE_POLL_MS);
    }
    static void reloadSettings(File filesDir){
        final QolTouch w=current;if(w==null)return;
        w.pad.settings(readSettings(filesDir));w.applyStored();w.zones();if(w.view!=null)w.view.invalidate();
        Log.i(TAG,"TOUCH_PAD settings again: mode="+w.pad.mode+" size="+w.pad.sizeScale+" opacity="+w.pad.opacity);
    }
    static void shown(boolean on){
        final QolTouch w=current;if(w==null)return;
        w.pad.userHidden(!on,SystemClock.uptimeMillis());w.zones();w.after();if(w.view!=null)w.view.invalidate();
    }
    /** The menu's share of a touch event: true when the event is the menu's (the caller then does nothing else with it). */
    private boolean routeMenu(MotionEvent e){
        if(menu==null)return false;
        final long t=e.getEventTime();final int a=e.getActionMasked();
        final boolean wasOpen=menu.isOpen();
        boolean mine=false;
        if(a==MotionEvent.ACTION_DOWN || a==MotionEvent.ACTION_POINTER_DOWN){
            final int k=e.getActionIndex(),id=e.getPointerId(k);
            if(menuFinger<0 && (a==MotionEvent.ACTION_DOWN || wasOpen) && menu.down(id,t,e.getX(k),e.getY(k))){menuFinger=id;mine=true;}
            else mine=wasOpen;
        } else if(menuFinger>=0){
            // (while closed, a move or another finger's lift is the pad's too: a thumb resting in the edge strip never freezes a stick)
            final int k=e.findPointerIndex(menuFinger);
            if(a==MotionEvent.ACTION_MOVE && k>=0){menu.move(menuFinger,t,e.getX(k),e.getY(k));mine=wasOpen;}
            else if((a==MotionEvent.ACTION_UP || a==MotionEvent.ACTION_POINTER_UP) && e.getPointerId(e.getActionIndex())==menuFinger){
                menu.up(menuFinger,t,e.getX(e.getActionIndex()),e.getY(e.getActionIndex()));menuFinger=-1;mine=true;}
            else if(a==MotionEvent.ACTION_CANCEL){menu.cancel();menuFinger=-1;mine=wasOpen;}
            else mine=wasOpen;
        } else mine=wasOpen;
        if(a==MotionEvent.ACTION_UP || a==MotionEvent.ACTION_CANCEL)menuFinger=-1;
        if(!wasOpen && menu.isOpen()){g.cancel();pad.cancel(t);pointer=second=-1;after();} // (opened: every touch input let go)
        if(menuEvents!=null)menuEvents.run();
        if(menuView!=null)menuView.invalidate();
        return mine || menu.isOpen();
    }
    /** The long-press zones follow the pad (SPEC 3.3): its own zones while the context pad shows, the original ones otherwise. */
    private void zones(){QolGesture.padZones=pad.usesPadZones();}
    /** The side menu's stored touch words on this pad (a pad made after the menu read menu.txt gets them too). */
    private void applyStored(){pad.adsTurns=adsTurnsSetting;pad.fireAims=fireAimsSetting;pad.aimMode(aimTapSetting);pad.autoFire=autoFireSetting;applyCamera();}

    /** The game view's touch listener; the library is scanned for the map-tap macro on a worker thread. */
    static View.OnTouchListener open(File filesDir){
        if(current!=null)current.release();
        current=new QolTouch(filesDir);
        Log.i(TAG,"QOL_TOUCH on long_ms="+QolGesture.LONG_MS+" slop="+QolGesture.LONG_SLOP+" open_ms="+QolGesture.OPEN_MS+" min_stick_ms="+
                QolGesture.MIN_STICK_MS+" close_gap_ms="+QolGesture.CLOSE_GAP_MS+" dead="+QolGesture.DEAD);
        current.scanMapTap(filesDir);
        current.pad.settings(readSettings(filesDir));current.applyStored();current.pad.loadLayout(readLayout(filesDir));
        Log.i(TAG,"TOUCH_PAD mode="+current.pad.mode+" hide="+current.pad.hideWithController+" size="+current.pad.sizeScale+" opacity="+current.pad.opacity);
        current.zones();
        current.handler.postDelayed(current.poll,100);
        if(devicesChanged!=null)devicesChanged.run(); // (the touch pad is a controller: PrivateGamepadInput reports it connected)
        return current;
    }
    /** The drawn controls over the game view (PrivateTraceActivity adds it above the SurfaceView); null before open(). */
    static View overlay(android.content.Context context){
        final QolTouch w=current;if(w==null)return null;
        w.view=new TouchPadView(context,w.pad);return w.view;
    }
    static void attachDevices(Runnable refresh){devicesChanged=refresh;}
    /** PrivateGamepadInput.use: whether a physical controller is detected (the pad auto-hides: TouchPad.controllerPresent). */
    static void controllerPresent(boolean present){
        final QolTouch w=current;if(w==null)return;
        final boolean was=w.pad.hidden();w.pad.controllerPresent(SystemClock.uptimeMillis(),present);
        if(was!=w.pad.hidden()){Log.i(TAG,"TOUCH_PAD hidden: a controller is connected");w.zones();if(w.view!=null)w.view.postInvalidate();}
    }
    /** Whether the touch pad acts as a connected controller (Controls != Off). */
    static boolean padConnected(){final QolTouch w=current;return w!=null && w.pad.mode!=TouchPad.Mode.OFF;}
    private static String readLayout(File filesDir){
        final File f=new File(filesDir,"touch-layout-v1.txt");
        if(!f.isFile() || f.length()>8192)return null;
        try(FileInputStream in=new FileInputStream(f)){final byte[] b=new byte[8192];final int n=in.read(b);return n>0?new String(b,0,n,java.nio.charset.StandardCharsets.US_ASCII):null;}
        catch(IOException error){return null;}
    }
    static String readSettings(File filesDir){
        final File f=new File(filesDir,TOUCH_SETTINGS);
        if(!f.isFile())return null;
        try(FileInputStream in=new FileInputStream(f)){final byte[] b=new byte[512];final int n=in.read(b);return n>0?new String(b,0,n,java.nio.charset.StandardCharsets.US_ASCII):null;}
        catch(IOException error){return null;}
    }
    private void poll(){
        final int word=PrivateTraceActivity.readTouchContext();
        final int before=pad.context();final boolean mapBefore=pad.mapShown();final TouchPad.Overlay overlayBefore=pad.overlay();
        final long keyBefore=pad.visualKey(SystemClock.uptimeMillis());
        final int trainer=PrivateTraceActivity.trainerState(); // (SPEC 3.14: TRAINER while the trainer is on; -100: no export)
        pad.trainerButton(trainer!=-100 && (trainer&SideMenuRows.STATE_ENABLED)!=0);
        {final boolean had=pad.imenuButton();pad.imenuButton(TouchPad.imenuShown(trainer,sandboxLaunch)); // (SPEC 3.17, 1.1.5: GTA Online only)
         if(had!=pad.imenuButton())Log.i(TAG,"TOUCH_PAD interaction menu button "+(pad.imenuButton()?"shown":"hidden")+" (trainer state "+(trainer==-100?"none":Integer.toHexString(trainer))+", sandbox launch "+sandboxLaunch+")");}
        final int phone=PrivateTraceActivity.phoneState(); // (SPEC 3.15: bit 0 the phone is up; -100: no export)
        pad.phone(phone);
        pad.contextWord(word);g.pauseMap(pad.mapShown()); // (3.11: double taps are the map's while it is shown)
        if(++polls%20==0){
            final boolean had=pad.nextWeapon;pad.nextWeapon=PrivateTraceActivity.trainerList(4,-1,null)>=TouchPad.NEXT_WEAPON_MIN_ACTIONS; // (item 2: action 22 in the library, every ~2 s)
            if(polls==20 || had!=pad.nextWeapon)Log.i(TAG,"TOUCH_PAD next weapon by double tap: "+(pad.nextWeapon?"library action 22":"not in this game build (a double tap keeps the weapon wheel open)"));
            final boolean sig=PrivateTraceActivity.aimAssist(RETICLE_OP)>=0; // (2026-11-09: the red-reticle read; -2 / -100 = not in this build)
            if(sig!=SideMenuRows.reticleSignal){SideMenuRows.reticleSignal=sig;Log.i(TAG,"TOUCH_PAD red-reticle signal: "+(sig?"yes":"no"));}
        }
        {final long now=SystemClock.uptimeMillis();pad.weaponTick(now);final QolGesture.Zone z=pad.takeLatch();if(z!=null)g.latch(now,z);} // (item 2: a single tap's wheel, 300 ms on)
        if(pad.overlay()!=overlayBefore){Log.i(TAG,"TOUCH_OVERLAY "+pad.overlay()+" (word "+Integer.toHexString(word)+")");if(view!=null)view.invalidate();}
        if(word!=lastWord && (word&0xFF)!=(lastWord&0xFF) || pad.context()!=before || pad.mapShown()!=mapBefore || (word&0x7FFF0000)!=(lastWord&0x7FFF0000))
            Log.i(TAG,"TOUCH_CONTEXT word="+word+" ctx="+(word<0?word:(word&0xFF))+" type="+(word<0?-1:((word>>8)&0x7F)-1)+" map="+(word>=0 && (word&0x8000)!=0?1:0)+
                  " screens="+Integer.toHexString(word<0?0:(word&0x3FF0000)>>16)+" weapon="+(word<0?-1:(word>>>26)&0xF)+" macro="+(word>=0 && (word&TouchPad.MAP_MACRO)!=0?1:0)+" layout="+pad.context());
        block(SystemClock.uptimeMillis());
        lastWord=word;
        if(pad.context()!=before || pad.mapShown()!=mapBefore){ // (SPEC 3.6: a latched wheel lets go on a context or pause-map change)
            if(g.latched()){g.endLatch(SystemClock.uptimeMillis());after();}
        }
        if(view!=null && pad.visualKey(SystemClock.uptimeMillis())!=keyBefore)view.invalidate(); // (v15: any visible change redraws, no touch needed)
        handler.postDelayed(poll,100);
    }
    static void close(){if(current!=null){current.release();current=null;changed();if(devicesChanged!=null)devicesChanged.run();}}
    /** Focus lost / the controller changed (PrivateGamepadInput.clear): the gesture lets go of everything. */
    static void cancelAll(){if(current!=null){current.g.cancel();current.pad.cancel(SystemClock.uptimeMillis());current.pointer=current.second=-1;current.after();}}
    static void attachPad(Runnable publish){padChanged=publish;}
    /** The pad the game is given: {buttons, LT, RT, LX, LY, RX, RY}. The physical controller wins input by input (SPEC 5): buttons OR'd, a
     *  physical stick past XInput's dead zone or a trigger above 30 replaces the touch one; the long-press gestures' right stick before the
     *  touch pad's. A fresh physical input hides the touch pad when its setting says so. */
    static int[] merge(int buttons,int leftTrigger,int rightTrigger,int leftX,int leftY,int rightX,int rightY){
        final QolTouch w=current;
        if(w==null)return new int[]{buttons,leftTrigger,rightTrigger,leftX,leftY,rightX,rightY};
        final long now=SystemClock.uptimeMillis();
        w.g.pad(now,buttons);w.schedule();w.log("pad");
        final int[] physical={buttons,leftTrigger,rightTrigger,leftX,leftY,rightX,rightY};
        final boolean fresh=TouchPad.freshPhysical(physical,w.lastPhysicalState);
        w.lastPhysical=buttons;w.lastPhysicalState=physical;w.block(now); // (touch-120: edges, not levels - TouchPad.freshPhysical)
        if(fresh)aimInputCall(aimInput.controller(),"controller"); // (OWNER ORDER 2026-11-10: a real controller press won: the game's targeting mode)
        if(fresh && !w.pad.hidden()){w.pad.physical(now,true);if(w.pad.hidden()){Log.i(TAG,"TOUCH_PAD hidden by the controller (physical input)");w.zones();if(w.view!=null)w.view.postInvalidate();}}
        final float[] turn=w.pad.takeCameraTurn(); // (Update 1 touch item 4: what the swipe built up, to the library at once)
        if(turn!=null)camLog(now,turn,PrivateTraceActivity.cameraTurn(turn[0],turn[1]));
        return TouchPad.arbitrate(physical,QolGesture.merge(w.g,buttons,rightX,rightY),w.pad.state(now));
    }

    /** SPEC 3.8: the touch sticks are off while a wheel of the gesture is open or the physical pad holds a wheel's button; the view
     *  redraws when that changes (the sticks appear / disappear). */
    private void block(long now){
        final boolean was=pad.sticksBlocked(now);
        pad.wheelOpen(g.wheelOpen() || TouchPad.physicalWheel(pad.context(),lastPhysical));
        if(was!=pad.sticksBlocked(now) && view!=null)view.postInvalidate();
    }
    // Update 1 touch optimizations (owner 2026-11-08): touch moves are delivered unbuffered (View.requestUnbufferedDispatch from each
    // gesture's first finger), not held to the window's next frame, so a stick / swipe-camera / AIM drag reaches the pad when it happens.
    // TOUCH_LAT (one line per gesture with moves) reports each move's age at the publish. AB_TEST (test APKs only; false in every real
    // build) alternates buffered / unbuffered by gesture so one device run compares both.
    static final boolean AB_TEST=false;
    private final TouchLatency moveAges=new TouchLatency();
    private int gestures;private boolean unbuffered;
    // jank-1117: per gesture, the handler's own time (this method, us) and the overlay's draw time (TouchPadView.onDraw, us), and how many
    // moves needed no redraw - one TOUCH_PERF line at the gesture's end beside TOUCH_LAT, for the Fold check of the "jank" report.
    private final TouchLatency handlerUs=new TouchLatency();
    static final TouchLatency drawUs=new TouchLatency();
    private int gestureMoves;
    @Override public boolean onTouch(View view,MotionEvent e){
        final long started=System.nanoTime();
        try{return route(view,e);}
        finally{handlerUs.add((System.nanoTime()-started)/1000);}
    }
    private boolean route(View view,MotionEvent e){
        if(routeMenu(e))return true; // side-menu
        final long t=e.getEventTime();block(SystemClock.uptimeMillis());
        if(e.getActionMasked()!=MotionEvent.ACTION_DOWN && e.getActionMasked()!=MotionEvent.ACTION_CANCEL){ // (the stale-finger watchdog)
            final int[] ids=new int[e.getPointerCount()];for(int k=0;k<ids.length;++k)ids[k]=e.getPointerId(k);
            final int n=pad.retain(ids,t);
            if(n>0)Log.i(TAG,"TOUCH_PAD stale fingers released n="+n+" (not in the event: a lost lift) (touch input)");
        }
        final float w=view.getWidth(),h=view.getHeight();
        String padDown=null;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:{ // (touch-controls: a finger on a control or a stick is the pad's; anything else the gestures';
                final int id=e.getPointerId(0);  //  a latched wheel's finger, or one while the pad holds a wheel's button, the gesture's first)
                pad.noFingers(t); // (a new gesture: nothing of the pad can still be down)
                aimInputCall(aimInput.touch(assistSetting),"touch"); // (OWNER ORDER 2026-11-10: touch took over: the Aim Magnetism choice)
                unbuffered=!AB_TEST || (gestures++&1)==1;
                if(unbuffered)view.requestUnbufferedDispatch(e); // (touch optimizations: every move of this gesture as it arrives)
                final boolean wasHidden=pad.hidden(),moreWas=pad.moreOpen(),taken=!g.wantsFinger() && pad.down(id,t,e.getX(0),e.getY(0));
                if(taken){padDown=padName(id);final String act=app();if(HapticsWords.padTick(pad.fingerName(id),act,moreWas!=pad.moreOpen()))Haptics.tick(view);} // (Update 1 haptics: a button's tick, SAVE / TRAINER / MORE too)
                if(wasHidden!=pad.hidden())zones();
                if(!taken){pointer=id;second=-1;g.down(t,e.getX(0),e.getY(0),w,h);}
                break;}
            case MotionEvent.ACTION_POINTER_DOWN:{ // (a second gesture finger = the map's pinch; more fingers are the pad's or ignored)
                final int k=e.getActionIndex(),id=e.getPointerId(k);
                final boolean moreWas=pad.moreOpen();
                if(!(pointer<0 && g.wantsFinger()) && pad.down(id,t,e.getX(k),e.getY(k))){padDown=padName(id);final String act=app();if(HapticsWords.padTick(pad.fingerName(id),act,moreWas!=pad.moreOpen()))Haptics.tick(view);}
                else if(pointer<0){pointer=id;second=-1;g.down(t,e.getX(k),e.getY(k),w,h);}
                else if(second<0 && id!=pointer){second=id;g.pointerDown(t,e.getX(k),e.getY(k),w,h);}
                break;}
            case MotionEvent.ACTION_MOVE:{
                for(int k=0;k<e.getPointerCount();++k){final int id=e.getPointerId(k);if(pad.owns(id))pad.move(id,t,e.getX(k),e.getY(k));}
                final int i=pointer<0?-1:e.findPointerIndex(pointer),j=second<0?-1:e.findPointerIndex(second);
                if(i>=0 && j>=0)g.move2(t,e.getX(i),e.getY(i),e.getX(j),e.getY(j),w,h);
                else if(i>=0)g.move(t,e.getX(i),e.getY(i),w,h);
                break;}
            case MotionEvent.ACTION_POINTER_UP:{
                final int id=e.getPointerId(e.getActionIndex());
                if(pad.owns(id)){logUp(id,t);pad.up(id,t);break;}
                if(id==second || id==pointer)g.pointerUp(t);
                if(id==second)second=-1;
                if(id==pointer){pointer=-1;g.up(t);} // (the tracked finger lifted; the others are ignored)
                break;}
            case MotionEvent.ACTION_UP:{
                final int id=e.getPointerId(0);
                if(pad.owns(id)){logUp(id,t);pad.up(id,t);}
                pad.noFingers(t); // (the last finger lifted)
                if(pointer>=0)g.up(t);
                pointer=-1;second=-1;break;}
            case MotionEvent.ACTION_CANCEL:
                Log.i(TAG,"TOUCH_PAD cancel: every finger released (touch input)");
                pad.cancel(t);
                if(pointer>=0)g.up(t);
                pointer=-1;second=-1;break;
            default:break;
        }
        {final QolGesture.Zone z=pad.takeLatch();if(z!=null)g.latch(t,z);} // (a wheel button held still and lifted: kept held, SPEC 3.6)
        app(); // (item 2, Fold check 2 2026-11-08: a double tap's next weapon is set on the second finger's LIFT; hand it on in this event,
               //  not at the next touch - a down already took its action above, so nothing leaves twice)
        block(SystemClock.uptimeMillis());
        final long keyBeforeAfter=drawnKey;
        after();
        if(this.view!=null && e.getActionMasked()!=MotionEvent.ACTION_MOVE){this.view.invalidate();drawnKey=pad.drawKey(SystemClock.uptimeMillis());} // (2026-11-11 MORE row fix: the overlay field, not the touched content FrameLayout the
                                                   //  parameter names; hardware drawing reuses a child's display list, so the row never drew)
        if(padDown!=null)Log.i(TAG,"TOUCH_PAD down "+padDown+" event_to_publish_ms="+(SystemClock.uptimeMillis()-t)+" (touch input)"); // (latency: published by after())
        final int act=e.getActionMasked();
        if(act==MotionEvent.ACTION_MOVE){moveAges.add(SystemClock.uptimeMillis()-t);++gestureMoves;if(drawnKey==keyBeforeAfter)++skippedMoves;} // (touch optimizations: the move's age at the publish)
        else if(act==MotionEvent.ACTION_UP || act==MotionEvent.ACTION_CANCEL){final String lat=moveAges.take();
            if(lat!=null)Log.i(TAG,"TOUCH_LAT "+lat+" unbuffered="+(unbuffered?1:0)+" (touch input)");
            final String hs=handlerUs.take("events","us"),ds=drawUs.take("draws","us");
            Log.i(TAG,"TOUCH_PERF handler "+hs+" | overlay "+(ds==null?"draws=0":ds)+" | moves="+gestureMoves+" redraw_skipped="+skippedMoves+" (touch input)");
            gestureMoves=0;skippedMoves=0;}
        return true;
    }
    /** A stick's lift (the stuck-stick report: every stick touch now has a down and an up line). */
    private void logUp(int id,long t){final String n=pad.fingerName(id);if(n!=null && n.endsWith("-stick"))Log.i(TAG,"TOUCH_PAD up "+n+" (touch input)");}
    private String padName(int id){
        for(TouchPad.Control c:pad.controls())if(pad.held(c))return c.id; // (approximate: the first held control)
        return pad.sticks[0].finger==id?"left-stick":pad.sticks[1].finger==id?"right-stick":"control";
    }
    private int loggedLatchTouches;
    /** One line per latched-wheel touch (3.6 v2): its distance from the wheel's centre in ring radii and what it did (S slot, N near miss,
     *  C centre = closed, F far = waiting for the lift, X far tap = closed, I stick side = nothing). */
    private void logLatch(){
        if(g.latchTouches==loggedLatchTouches && g.lastLatchOutcome==lastLatchLogged)return;
        loggedLatchTouches=g.latchTouches;lastLatchLogged=g.lastLatchOutcome;
        Log.i(TAG,"QOL_LATCH_TOUCH rel="+String.format(java.util.Locale.ROOT,"%.2f",g.lastLatchRel)+" outcome="+g.lastLatchOutcome+" (touch input)");
    }
    private char lastLatchLogged;
    private void tick(){scheduled=QolGesture.NONE;g.tick(SystemClock.uptimeMillis());block(SystemClock.uptimeMillis());after();if(view!=null)view.invalidate();}
    private void after(){saveLayoutIfDirty();schedule();log("touch");logLatch();changed();redrawIfChanged();
        if(!reticleOn && reticleWanted()){reticleOn=true;handler.post(reticleTick);}} // (2026-11-09: auto-fire reads the reticle while aiming)
    // jank-1117 (owner on the Fold test app: "the left stick doesn't always disappear"): every path that lets the fingers go without a touch
    // event - the side menu opened by Back (menuEvents) or by its edge strip (routeMenu returns before onTouch's redraw), focus loss and a
    // controller change (cancelAll) - released the stick but nothing redrew the overlay, so the last frame (the stick under the thumb) stayed
    // on screen until an unrelated redraw. after() runs on all of them: the overlay redraws whenever what the fingers draw changed.
    private long drawnKey=Long.MIN_VALUE;int skippedMoves;
    private void redrawIfChanged(){
        final long k=pad.drawKey(SystemClock.uptimeMillis());
        if(k==drawnKey || view==null)return;
        drawnKey=k;
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())view.invalidate();else view.postInvalidate();
    }
    private void schedule(){
        final long due=Math.min(g.due(),pad.due(SystemClock.uptimeMillis()));
        if(due==scheduled)return;
        handler.removeCallbacks(tick);scheduled=due;
        if(due!=QolGesture.NONE)handler.postAtTime(tick,due);
    }
    private static void changed(){if(padChanged!=null)padChanged.run();}
    private void log(String why){
        final MapTouch.State m=g.map().state();
        final int x=g.stickOn()?g.rightX():0,y=g.stickOn()?g.rightY():0; // (a wheel's push starting / changing / ending is a line too)
        if(g.state()==lastState && m==lastMap && x==lastRx && y==lastRy)return;
        lastState=g.state();lastMap=m;lastRx=x;lastRy=y;
        Log.i(TAG,"QOL_TOUCH state="+g.state()+" zone="+g.held()+" buttons="+Integer.toHexString(g.buttons())+" stick="+
                (g.stickOn()?g.rightX()+","+g.rightY():"rest")+" map="+m+" by="+why+" (touch input)");
    }
    private void saveLayoutIfDirty(){
        if(!pad.takeLayoutDirty())return;
        try{AtomicFiles.write(new File(filesDir,"touch-layout-v1.txt"),pad.layoutText().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            Log.i(TAG,"TOUCH_PAD custom layout saved");}
        catch(IOException error){Log.w(TAG,"TOUCH_PAD custom layout save failed: "+error.getClass().getSimpleName());}
    }
    private void release(){handler.removeCallbacks(tick);handler.removeCallbacks(poll);handler.removeCallbacks(reticleTick);reticleOn=false;scheduled=QolGesture.NONE;g.cancel();pad.cancel(SystemClock.uptimeMillis());pointer=second=-1;saveLayoutIfDirty();}
    /** Whether the game library runs the map macro itself (its marker string), looked up once on a worker thread (the library is read
     *  to its end, ~1 s); until then, and when absent or unreadable, the app presses START and A and the map touch stays off. */
    private void scanMapTap(File filesDir){
        final File so=new File(filesDir,CapabilityScanner.LIBRARY);
        final Thread scan=new Thread(()->{
            boolean found;
            try(FileInputStream in=new FileInputStream(so)){found=!CapabilityScanner.scan(in,java.util.Collections.singletonList(MAP_TAP_MARKER)).isEmpty();}
            catch(IOException|RuntimeException error){found=false;}
            final boolean on=found;
            handler.post(()->{g.nativeMap(on);Log.i(TAG,"QOL_TOUCH map_tap="+(on?"library":"app")+" ("+MAP_TAP_MARKER+(on?" found)":" not found)"));});
        },"qol-maptap-scan");
        scan.setDaemon(true);scan.start();
    }
}
