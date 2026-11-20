package dev.recompbootstrap.smoke;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * qol-touch (2026-10-24; owner: "fold, work on touch controls, simulated x360 pad", "custom touch controls for each action like a normal
 * phone game that dynamically changes when in a vehichle or aircraft", "both analog sticks should be animated"). The on-screen controls:
 * which controls exist and where (per control context or the plain 360 pad), which finger holds which control, the floating sticks with
 * their animation state, and the Xbox 360 pad state the touches give. Plain Java (no Android types): the host test runs it
 * (local-analysis/single-screen-edition/tests/run_test.sh, TouchPadTest). Spec: local-analysis/touch-controls-spec/SPEC.md.
 *
 * Coordinates: view pixels. The game frame (16:9, black bars outside) comes from QolGesture.frame(). Positions are given in frame units
 * and moved into the bars where the bars help (SPEC 3.1). Time: any monotonic milliseconds.
 * A touch that starts on a control is that control for its whole life (no re-targeting); a touch that starts in a long-press zone (and on
 * no control) is not taken (down() returns false: QolGesture gets it); any other touch is the left or right floating stick by side.
 */
final class TouchPad {
    // ---- XInput bits (XINPUT_GAMEPAD_*)
    static final int DU=0x1,DD=0x2,DL=0x4,DR=0x8,START=0x10,BACK=0x20,L3=0x40,R3=0x80,LB=0x100,RB=0x200,A=0x1000,B=0x2000,X=0x4000,Y=0x8000;
    // ---- control contexts (touch_context.h; the library's recomp_touch_context_v1)
    static final int NONE=0,FOOT=1,CAR=2,BIKE=3,BICYCLE=4,BOAT=5,SUB=6,HELI=7,PLANE=8,TRAIN=9;
    enum Mode {CONTEXT,PAD,OFF}
    enum Kind {BUTTON,TRIGGER_L,TRIGGER_R,FIRE_FULL,WHEEL,TAP_OR_WHEEL,DPAD,AIM_LATCH,AUTOTAP,HIDE,MORE,WEAPON_TAP,APP,STEER,IMENU}
    /** A wheel button: held past its tap = the wheel's button held (a slide steers); WEAPON_TAP also opens the latched wheel on a tap. */
    static boolean wheelKind(Kind k){return k==Kind.WHEEL || k==Kind.TAP_OR_WHEEL || k==Kind.WEAPON_TAP;}
    /** One drawn control. fx, fy: its centre in frame units; d: its diameter in frame heights (before the physical floor and the size setting). */
    static final class Control {
        final String id,label,key;final Kind kind;final int bits;final float fx,fy,d;final int color;
        float cx,cy,r; // view pixels (layout())
        Control(String id,String label,String key,Kind kind,int bits,float fx,float fy,float d,int color){
            this.id=id;this.label=label;this.key=key;this.kind=kind;this.bits=bits;this.fx=fx;this.fy=fy;this.d=d;this.color=color;}
        boolean hit(float x,float y){final float dx=x-cx,dy=y-cy;return dx*dx+dy*dy<=r*r;}
    }
    // 360 colours (ARGB) and the neutral one
    static final int GREEN=0xFF5BB53A,RED=0xFFD9312B,BLUE=0xFF2E6FD8,YELLOW=0xFFF2B51C,GREY=0xFF9AA0A6;
    // ---- tunables (SPEC 3, 4, 5)
    static final float STICK_DEAD=0.10f,STICK_CURVE=1.3f,LS_XINPUT_DEAD=7849f/32767f,RS_XINPUT_DEAD=8689f/32767f;
    static final float LS_R=0.13f,RS_R=0.11f; // ring radii (frame heights)
    static final float LS_HOME_X=0.16f,LS_HOME_Y=0.56f,RS_HOME_X=0.56f,RS_HOME_Y=0.68f,LEFT_SIDE=0.45f; // left stick side: x < 45 % of the view
    static final float MIN_BUTTON_MM=7.5f,MIN_RING_MM=22f; // (7.5 mm: above Android's 48 dp target, and the diamond's step still fits a 69 mm tall phone)
    static final long SPRING_MS=90,STICK_FADE_MS=150,FADE_IDLE_MS=4000,FADE_MS=250,TAP_MS=250,DOUBLE_TAP_MS=300,AUTOTAP_HALF_MS=62,PULSE_MS=100;
    // A button tapped shorter than this is held until then (the Fold, 21:30: an 80 ms PAUSE tap never opened the pause menu, 200 ms did):
    // START / BACK 250 ms, the other buttons 100 ms.
    static final long MIN_PRESS_MS=100,MIN_MENU_PRESS_MS=250;
    static final float IDLE_ALPHA=0.35f;
    static final int TRIGGER_DOWN=180,TRIGGER_MIN=77;
    static final int PHYSICAL_TRIGGER=30;

    // ---- settings
    Mode mode=Mode.CONTEXT;boolean hideWithController=true,showOnTouch=true,layoutB=true,rightSwipe=true;float sizeScale=1f,opacity=0.8f; // (layout B: the owner's pick, 2026-10-24)
    // rightSwipe (owner 20:35 via the iPad session: "the right stick map the axis to swipes on the right side of the screen ... The speed of which
    // goes by how far the right stick moves"): the right stick is not drawn; a finger anywhere on the right side (off the controls and zones)
    // still steers it from its touch-down point (the same curve, full at R, drag-follow beyond). rswipe=0 draws it again.
    private boolean moreOpen; // layout B: the MORE row is open
    // ---- view
    private float vw,vh,mmPx=10f;private float[] frame={0,0,0,0};
    private final List<Control> controls=new ArrayList<>();
    private final Map<String,float[]> layoutPositions=new LinkedHashMap<>();
    private boolean editMode,layoutDirty;
    private int context=FOOT,shownContext=-1,pendingContext=-1,pendingCount=0;
    private boolean mapShown;
    // ---- fingers
    static final class Finger {int id;Control control;int stick;float x0,y0,x,y;long downAt;boolean moved,editing;int editStick=-1;float value;float vx,vy;long lastT;} // (vx, vy, lastT: the swipe camera's finger speed, px/s)
    private final List<Finger> fingers=new ArrayList<>();
    // ---- sticks: [0] left, [1] right
    static final class Stick {
        float hx,hy,cx,cy,kx,ky,R;boolean held;int finger=-1;long releasedAt=-1;float relKx,relKy,relCx,relCy;int outX,outY;
    }
    final Stick[] sticks={new Stick(),new Stick()};
    // ---- buttons latched / timed
    private boolean aimLatched;private long lastSprintTap=Long.MIN_VALUE/2;private boolean autotap;private Finger autotapFinger;
    private long pulseUntil=Long.MIN_VALUE;private int pulseBits;
    private long releaseAt=Long.MIN_VALUE;private int releaseBits; // a wheel's button kept CLOSE_GAP_MS after its lift
    private QolGesture.Zone latchRequest; // a wheel's button held and lifted without a slide: QolGesture keeps it held (SPEC 3.6)
    // ---- visibility
    private boolean hidden;private long lastTouch;private long visibleSince;

    // ======== layout
    /** The view's size and its pixels per millimetre (Android: displayMetrics.xdpi / 25.4). */
    void view(float w,float h,float pxPerMm){vw=w;vh=h;mmPx=pxPerMm>0?pxPerMm:10f;frame=QolGesture.frame(w,h);relayout();}
    float[] frame(){return frame;}
    float viewW(){return vw;}
    float viewH(){return vh;}
    private float H(){return frame[3];}
    /** Frame units -> view pixels, moved into the bars by half their size where the bars help (SPEC 3.1). */
    float px(float fx){final float x=frame[0]+fx*frame[2],bar=frame[0];return fx<0.5f?x-bar*0.5f:x+bar*0.5f;}
    float py(float fy){final float y=frame[1]+fy*frame[3],bar=frame[1];return fy>0.5f?y+bar*0.5f:fy<0.15f?y-bar*0.5f:y;} // (only the top row moves up: the right column must stay clear of the weapon icon)
    private float size(float d,float minMm){return Math.max(d*H(),minMm*mmPx)*sizeScale;}
    private void relayout(){
        controls.clear();
        if(mode==Mode.OFF || !(frame[2]>0))return;
        if(mode!=Mode.PAD && overlay!=Overlay.PLAY)controls.addAll(overlayLayout(overlay,skipRevealed,phoneCall,cutscene)); // (SPEC 3.10)
        else {
            controls.addAll(mode==Mode.PAD?padLayout():layoutB?contextLayoutB(shownContext<0?FOOT:shownContext,moreOpen):contextLayout(shownContext<0?FOOT:shownContext));
            if(mode==Mode.CONTEXT){ // (SPEC 3.14: app actions, nothing for the game's pad)
                controls.add(at(TOP_SAVE,"qsave","SAVE","",Kind.APP,0,GREY));
                if(imenuButton)controls.add(at(TOP_IMENU,"imenu","MENU","BACK",Kind.IMENU,BACK,GREY)); // (3.17: the game's Interaction Menu)
                if(trainerButton)controls.add(at(TOP_TRAINER,"trainer","TRAINER","",Kind.APP,0,GREY));
                if(steerArrows(shownContext<0?FOOT:shownContext)){ // (3.16: bits = the direction, STEER_LEFT / STEER_RIGHT, not pad bits)
                    controls.add(at(STEER_L,"steer_l","LEFT","LS",Kind.STEER,STEER_LEFT,GREY));
                    controls.add(at(STEER_R,"steer_r","RIGHT","LS",Kind.STEER,STEER_RIGHT,GREY));
                }
            }
        }
        for(Control c:controls){c.r=size(c.d,MIN_BUTTON_MM)*0.5f; // (kept inside the view: a physical floor can outgrow a frame-unit margin)
            c.cx=Math.max(c.r,Math.min(vw-c.r,px(c.fx)));c.cy=Math.max(c.r,Math.min(vh-c.r,c.id.startsWith("prompt_")?promptY(c.r):py(c.fy)));}
        homeSticks();
        for(Control c:controls){final float[] p=layoutPositions.get(c.id);if(p!=null){c.cx=Math.max(c.r,Math.min(vw-c.r,p[0]*vw));c.cy=Math.max(c.r,Math.min(vh-c.r,p[1]*vh));}}
        for(int i=0;i<2;++i){final Stick st=sticks[i];final float[] p=layoutPositions.get(i==0?"@left":"@right");
            if(p!=null){st.hx=Math.max(st.R,Math.min(vw-st.R,p[0]*vw));st.hy=Math.max(st.R,Math.min(vh-st.R,p[1]*vh));
                if(!st.held && st.releasedAt<0){st.cx=st.hx;st.cy=st.hy;}}}
    }
    private void homeSticks(){
        final float[][] home=layoutB && mode==Mode.CONTEXT?new float[][]{{B_LS_X,B_LS_Y,B_LS_R},{B_RS_X,B_RS_Y,B_RS_R}}:new float[][]{{LS_HOME_X,LS_HOME_Y,LS_R},{RS_HOME_X,RS_HOME_Y,RS_R}};
        for(int i=0;i<2;++i){final Stick s=sticks[i];s.hx=px(home[i][0]);s.hy=py(home[i][1]);s.R=size(2*home[i][2],MIN_RING_MM)*0.5f;
            if(!s.held && s.releasedAt<0){s.cx=s.hx;s.cy=s.hy;}}
    }
    List<Control> controls(){return Collections.unmodifiableList(controls);}
    Control control(String id){for(Control c:controls)if(c.id.equals(id))return c;return null;}

    // The 360 pad's diamond (padLayout) and the small sizes (frame units)
    static final float DIA_X=0.84f,DIA_Y=0.66f,DIA_STEP=0.13f,DIA_D=0.11f,ROW_D=0.07f,TOP_Y=0.06f,TOP_D=0.06f;
    private static float stepX(){return DIA_STEP*720f/1280f;} // the diamond's step in frame widths (the frame is 16:9)
    // The hide button (owner 2026-10-24: "we need a hide touch controls button too on all builds"): top left, always reachable; while the
    // controls are hidden by it, it is the only control drawn (SHOW) and the only touch the pad takes.
    static final float HIDE_X=0.035f,HIDE_Y=0.07f,HIDE_D=0.06f;
    private static Control hideControl(){return new Control("hide","HIDE","",Kind.HIDE,0,HIDE_X,HIDE_Y,HIDE_D,GREY);}
    // Universal layout slots (SPEC v2 3.2, after Rockstar's mobile ports: the thumb cluster round the bottom-right corner, a column of
    // situational actions on the right edge, a small bottom row, the top row): {x, y, diameter in H}.
    static final float[] CORNER_A={0.915f,0.83f,0.12f},CORNER_B={0.80f,0.89f,0.10f},CORNER_C={0.81f,0.68f,0.12f},CORNER_D={0.69f,0.80f,0.10f},
        CORNER_E={0.935f,0.645f,0.09f},CORNER_F={0.70f,0.62f,0.085f},COLUMN_1={0.945f,0.28f,0.085f},COLUMN_2={0.945f,0.40f,0.085f},
        COLUMN_3={0.945f,0.52f,0.085f},ROW_1={0.40f,0.92f,0.07f},ROW_2={0.46f,0.92f,0.07f},TOP_PHONE={0.36f,0.06f,0.06f},
        TOP_CAM={0.45f,0.06f,0.06f},TOP_PAUSE={0.55f,0.06f,0.06f},
        // Fold ship 2026-10-25 (owner: "push all menu and quality life improvements to that build, including the trainer"; the Thor's
        // lower-screen Quick Save and Lester trainer buttons, on one screen): the top row's left end, in the bar in Fit (SPEC 3.14; the right
        // end is the WEAPON button's long-press zone).
        TOP_SAVE={0.27f,0.06f,0.06f},TOP_TRAINER={0.18f,0.06f,0.06f},
        // SPEC 3.17 (1.1.5, owner 2026-11-14: "an INTERACTION MENU touch button, like GTA mobile games' button"): the top row's left end,
        // between HIDE (0.035) and TRAINER (0.18); never at 0.73 (the WEAPON zone).
        TOP_IMENU={0.105f,0.06f,0.06f},
        // SPEC 3.16 (owner 2026-10-27: "we should have for cars left and right arrow buttons like the game"): the steering arrows, left side
        // above the minimap (its long-press zone stays free), the floating stick still on the rest of the left side.
        STEER_L={0.07f,0.52f,0.13f},STEER_R={0.20f,0.52f,0.13f},
        WEAPON_SLOT={0.934f,0.175f,0.10f}; // (v15, owner "GTA 4 style": right-aligned under the game's stars / cash / ammo (stars at guest
                                           //  y 36-57, Thor census hud-stars); drawn as a bare silhouette, the disc only its touch area) // (SPEC 3.12: top right, under the game's ammo count, on the weapon zone it stands for)
    private static Control at(float[] slot,String id,String label,String key,Kind k,int bits,int color){
        return new Control(id,label,key,k,bits,slot[0],slot[1],slot[2],color);
    }
    private static List<Control> base(){ // in every context: HIDE, CAM, PAUSE
        final List<Control> l=new ArrayList<>();
        l.add(hideControl());
        l.add(at(TOP_PAUSE,"pause","PAUSE","START",Kind.BUTTON,START,GREY));
        l.add(at(TOP_CAM,"cam","CAM","BACK",Kind.BUTTON,BACK,GREY));
        return l;
    }
    /** The controls of one context (SPEC v2 2 and 3.2). The colour is the 360 button's (A green, B red, X blue, Y yellow, else grey). */
    static List<Control> contextLayout(int ctx){
        final List<Control> l=base();
        switch(ctx){
            case CAR:case BIKE:case BICYCLE:{
                l.add(at(CORNER_A,"gas","GAS","RT",Kind.TRIGGER_R,0,GREY));
                l.add(at(CORNER_B,"brake","BRAKE","LT",Kind.TRIGGER_L,0,GREY));
                l.add(at(CORNER_C,"handbrake","H-BRAKE","RB",Kind.BUTTON,RB,GREY));
                l.add(at(CORNER_D,"driveby","DRIVE-BY","LB",Kind.BUTTON,LB,GREY));
                l.add(at(COLUMN_1,"radio","RADIO","D-LEFT",Kind.TAP_OR_WHEEL,DL,GREY));
                l.add(at(COLUMN_2,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(COLUMN_3,"horn","HORN","L3",Kind.BUTTON,L3,GREY));
                l.add(at(ROW_1,"look","LOOK","R3",Kind.BUTTON,R3,GREY));
                if(ctx==CAR){
                    l.add(at(CORNER_E,"lights","LIGHTS","D-RIGHT",Kind.BUTTON,DR,GREY));
                    l.add(at(ROW_2,"cinema","CINEMA","B",Kind.BUTTON,B,RED));
                }
                if(ctx==BICYCLE)l.add(at(CORNER_E,"pedal","PEDAL","A",Kind.AUTOTAP,A,GREEN));
                break;}
            case BOAT:case SUB:{
                l.add(at(CORNER_A,"throttle","THROTTLE","RT",Kind.TRIGGER_R,0,GREY));
                l.add(at(CORNER_B,"reverse","REVERSE","LT",Kind.TRIGGER_L,0,GREY));
                l.add(at(COLUMN_1,"radio","RADIO","D-LEFT",Kind.TAP_OR_WHEEL,DL,GREY));
                l.add(at(COLUMN_2,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(COLUMN_3,"horn","HORN","L3",Kind.BUTTON,L3,GREY));
                if(ctx==SUB){
                    l.add(at(CORNER_D,"turnl","TURN-L","LB",Kind.BUTTON,LB,GREY));
                    l.add(at(CORNER_C,"turnr","TURN-R","RB",Kind.BUTTON,RB,GREY));
                    l.add(at(CORNER_E,"dive","DIVE","A",Kind.BUTTON,A,GREEN));
                    l.add(at(CORNER_F,"rise","RISE","X",Kind.BUTTON,X,BLUE));
                }
                break;}
            case HELI:case PLANE:{
                final boolean heli=ctx==HELI;
                l.add(at(CORNER_A,"up",heli?"UP":"THROTTLE","RT",Kind.TRIGGER_R,0,GREY));
                l.add(at(CORNER_B,"down",heli?"DOWN":"BRAKE","LT",Kind.TRIGGER_L,0,GREY));
                l.add(at(CORNER_C,"yawr",heli?"YAW-R":"RUDDER-R","RB",Kind.BUTTON,RB,GREY));
                l.add(at(CORNER_D,"yawl",heli?"YAW-L":"RUDDER-L","LB",Kind.BUTTON,LB,GREY));
                l.add(at(CORNER_E,"missile","FIRE","X",Kind.BUTTON,X,BLUE));
                l.add(at(COLUMN_1,"radio","RADIO","D-LEFT",Kind.TAP_OR_WHEEL,DL,GREY));
                l.add(at(COLUMN_2,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(COLUMN_3,heli?"wcam":"gear",heli?"W-CAM":"GEAR",heli?"R3":"L3",Kind.BUTTON,heli?R3:L3,GREY));
                break;}
            case TRAIN:{
                l.add(at(COLUMN_2,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                break;}
            default:{ // FOOT (and NONE: loading / menus)
                l.add(at(CORNER_A,"sprint","SPRINT","A",Kind.AUTOTAP,A,GREEN));
                l.add(at(CORNER_B,"jump","JUMP","X",Kind.BUTTON,X,BLUE));
                l.add(at(CORNER_C,"fire","FIRE","RT",Kind.FIRE_FULL,0,GREY)); // (SPEC v2 3.4: on foot FIRE is digital, RT 255)
                l.add(at(CORNER_D,"aim","AIM","LT",Kind.AIM_LATCH,0,GREY));
                l.add(at(CORNER_E,"melee","MELEE","B",Kind.BUTTON,B,RED));
                l.add(at(CORNER_F,"weapons","WEAPONS","LB",Kind.WHEEL,LB,GREY));
                l.add(at(COLUMN_1,"switch","SWITCH","D-DOWN",Kind.WHEEL,DD,GREY));
                l.add(at(COLUMN_2,"enter","ENTER","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(COLUMN_3,"cover","COVER","RB",Kind.BUTTON,RB,GREY));
                l.add(at(ROW_1,"stealth","STEALTH","L3",Kind.BUTTON,L3,GREY));
                l.add(at(ROW_2,"look","LOOK","R3",Kind.BUTTON,R3,GREY));
                l.add(at(TOP_PHONE,"phone","PHONE","D-UP",Kind.BUTTON,DU,GREY));
                l.add(at(WEAPON_SLOT,"weapon","WEAPON","LB",Kind.WEAPON_TAP,LB,GREY));
                break;}
        }
        return l;
    }
    // Layout B (SPEC 3.7, the references taken literally): 3-4 BIG buttons round the bottom-right corner, a column of small ones on the
    // right edge (below the weapon icon's zone), bigger sticks with the left one above the minimap, and MORE (top bar) opening a bottom
    // row with the rest. {x, y, diameter in H}.
    static final float[] B_BIG={0.905f,0.79f,0.20f},B_BIG2={0.775f,0.875f,0.16f},B_MID1={0.785f,0.64f,0.15f},B_MID2={0.86f,0.535f,0.15f},
        B_COL_1={0.945f,0.285f,0.085f},B_COL_2={0.945f,0.39f,0.085f},B_COL_3={0.945f,0.495f,0.085f},B_COL_4={0.945f,0.60f,0.085f}, // (v15: 0.035 lower, room for the weapon)
        B_MORE={0.64f,0.06f,0.06f},B_ROW_1={0.40f,0.92f,0.07f},B_ROW_2={0.46f,0.92f,0.07f},B_ROW_3={0.52f,0.92f,0.07f},B_ROW_4={0.58f,0.92f,0.07f};
    static final float B_LS_X=0.17f,B_LS_Y=0.50f,B_LS_R=0.17f,B_RS_X=0.56f,B_RS_Y=0.70f,B_RS_R=0.15f;
    /** Layout B of one context; more = the MORE row shown. Every 360 input of layout A is still reachable (here, in the MORE row, or by a
     *  long-press zone: the weapon wheel on the weapon icon). */
    static List<Control> contextLayoutB(int ctx,boolean more){
        final List<Control> l=base();
        l.add(at(B_MORE,"more","MORE","",Kind.MORE,0,GREY));
        switch(ctx){
            case CAR:case BIKE:case BICYCLE:{
                l.add(at(B_BIG,"gas","GAS","RT",Kind.TRIGGER_R,0,GREY));
                l.add(at(B_BIG2,"brake","BRAKE","LT",Kind.TRIGGER_L,0,GREY));
                l.add(at(new float[]{B_MID1[0],B_MID1[1],0.14f},"handbrake","H-BRAKE","RB",Kind.BUTTON,RB,GREY));
                if(ctx==BICYCLE)l.add(at(new float[]{B_MID2[0],B_MID2[1],0.14f},"pedal","PEDAL","A",Kind.AUTOTAP,A,GREEN));
                l.add(at(B_COL_1,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(B_COL_2,"horn","HORN","L3",Kind.BUTTON,L3,GREY));
                l.add(at(B_COL_3,"radio","RADIO","D-LEFT",Kind.TAP_OR_WHEEL,DL,GREY));
                l.add(at(B_COL_4,"look","LOOK","R3",Kind.BUTTON,R3,GREY));
                if(more){
                    l.add(at(B_ROW_1,"driveby","DRIVE-BY","LB",Kind.BUTTON,LB,GREY));
                    if(ctx==CAR){l.add(at(B_ROW_2,"lights","LIGHTS","D-RIGHT",Kind.BUTTON,DR,GREY));l.add(at(B_ROW_3,"cinema","CINEMA","B",Kind.BUTTON,B,RED));}
                }
                break;}
            case BOAT:case SUB:{
                l.add(at(B_BIG,"throttle","THROTTLE","RT",Kind.TRIGGER_R,0,GREY));
                l.add(at(B_BIG2,"reverse","REVERSE","LT",Kind.TRIGGER_L,0,GREY));
                l.add(at(B_COL_1,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(B_COL_2,"horn","HORN","L3",Kind.BUTTON,L3,GREY));
                l.add(at(B_COL_3,"radio","RADIO","D-LEFT",Kind.TAP_OR_WHEEL,DL,GREY));
                if(ctx==SUB){
                    l.add(at(new float[]{B_MID1[0],B_MID1[1],0.13f},"dive","DIVE","A",Kind.BUTTON,A,GREEN));
                    l.add(at(new float[]{B_MID2[0],B_MID2[1],0.13f},"rise","RISE","X",Kind.BUTTON,X,BLUE));
                    if(more){l.add(at(B_ROW_1,"turnl","TURN-L","LB",Kind.BUTTON,LB,GREY));l.add(at(B_ROW_2,"turnr","TURN-R","RB",Kind.BUTTON,RB,GREY));}
                }
                break;}
            case HELI:case PLANE:{
                final boolean heli=ctx==HELI;
                l.add(at(B_BIG,"up",heli?"UP":"THROTTLE","RT",Kind.TRIGGER_R,0,GREY));
                l.add(at(B_BIG2,"down",heli?"DOWN":"BRAKE","LT",Kind.TRIGGER_L,0,GREY));
                l.add(at(new float[]{B_MID1[0],B_MID1[1],0.13f},"yawl",heli?"YAW-L":"RUDDER-L","LB",Kind.BUTTON,LB,GREY));
                l.add(at(new float[]{B_MID2[0],B_MID2[1],0.13f},"yawr",heli?"YAW-R":"RUDDER-R","RB",Kind.BUTTON,RB,GREY));
                l.add(at(B_COL_1,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(B_COL_2,"radio","RADIO","D-LEFT",Kind.TAP_OR_WHEEL,DL,GREY));
                l.add(at(B_COL_3,"missile","FIRE","X",Kind.BUTTON,X,BLUE));
                l.add(at(B_COL_4,heli?"wcam":"gear",heli?"W-CAM":"GEAR",heli?"R3":"L3",Kind.BUTTON,heli?R3:L3,GREY));
                break;}
            case TRAIN:{
                l.add(at(B_COL_1,"exit","EXIT","Y",Kind.BUTTON,Y,YELLOW));
                break;}
            default:{ // FOOT (and NONE)
                l.add(at(B_BIG,"fire","FIRE","RT",Kind.FIRE_FULL,0,GREY));
                l.add(at(B_BIG2,"sprint","SPRINT","A",Kind.AUTOTAP,A,GREEN));
                l.add(at(B_MID1,"jump","JUMP","X",Kind.BUTTON,X,BLUE));
                l.add(at(B_MID2,"aim","AIM","LT",Kind.AIM_LATCH,0,GREY));
                l.add(at(B_COL_1,"enter","ENTER","Y",Kind.BUTTON,Y,YELLOW));
                l.add(at(B_COL_2,"cover","COVER","RB",Kind.BUTTON,RB,GREY));
                l.add(at(B_COL_3,"melee","MELEE","B",Kind.BUTTON,B,RED)); // (B: melee, and reload)
                l.add(at(TOP_PHONE,"phone","PHONE","D-UP",Kind.BUTTON,DU,GREY));
                l.add(at(WEAPON_SLOT,"weapon","WEAPON","LB",Kind.WEAPON_TAP,LB,GREY));
                if(more){
                    l.add(at(B_ROW_1,"stealth","STEALTH","L3",Kind.BUTTON,L3,GREY));
                    l.add(at(B_ROW_2,"look","LOOK","R3",Kind.BUTTON,R3,GREY));
                    l.add(at(B_ROW_3,"switch","SWITCH","D-DOWN",Kind.WHEEL,DD,GREY));
                    l.add(at(B_ROW_4,"weapons","WEAPONS","LB",Kind.WHEEL,LB,GREY));
                }
                break;}
        }
        return l;
    }
    boolean moreOpen(){return moreOpen;}
    // ---- SPEC 3.14: the app's own buttons (SAVE: the library's OpenSaveMenu; TRAINER: the side menu's Trainer tab)
    private String appAction;private boolean trainerButton;
    /** The app button tapped since the last call ("qsave" / "trainer"), once; else null. */
    String takeAppAction(){final String a=appAction;appAction=null;return a;}
    // ---- SPEC 3.16: the steering arrows (cars, bikes, bicycles, boats: the vehicles steered left / right only)
    static final int STEER_LEFT=1,STEER_RIGHT=2;
    static boolean steerArrows(int ctx){return ctx==CAR || ctx==BIKE || ctx==BICYCLE || ctx==BOAT;}
    /** The left stick's x from the held arrows: full left / right, both or none = no override (Integer.MIN_VALUE when none is held). */
    static int steerX(int held){return held==STEER_LEFT?-32767:held==STEER_RIGHT?32767:held!=0?0:Integer.MIN_VALUE;}
    /** TRAINER is shown while the trainer is switched on (the library's state). */
    void trainerButton(boolean on){if(on!=trainerButton){trainerButton=on;relayout();}}
    // ---- SPEC 3.17 (1.1.5, owner decision 2026-11-14): the INTERACTION MENU button. GTA Online opens its Interaction Menu when Back /
    // Select (XInput 0x20) is HELD about 350 ms (a short press is the camera view, the CAM button). A tap here gives the game BACK held
    // for IMENU_HOLD_MS through the same pad state as every other touch button (state() -> QolTouch.merge -> PrivateGamepadInput.publish),
    // longer while the finger stays. Shown only in the GTA Online play mode (imenuShown), in the context layouts' play overlay; hidden
    // with the rest while a controller is connected.
    static final long IMENU_HOLD_MS=600;
    static final int STATE_SANDBOX=1<<21,STATE_CREATOR_MENU=1<<22; // (recomp_trainer_state_v1: SideMenuRows.STATE_SANDBOX, ActionClose.STATE_CREATOR_MENU)
    private boolean imenuButton;
    void imenuButton(boolean on){if(on!=imenuButton){imenuButton=on;relayout();}}
    boolean imenuButton(){return imenuButton;}
    /** Whether the Interaction Menu button belongs on screen: the library's own state says Sandbox (the local GTA Online session, bit 21)
     *  and the character creator is not open (bit 22); a library without the trainer state (-100) falls back to the launch's play mode. */
    static boolean imenuShown(int trainerState,boolean sandboxLaunch){
        if(trainerState==-100)return sandboxLaunch;
        return trainerState>=0 && (trainerState&STATE_SANDBOX)!=0 && (trainerState&STATE_CREATOR_MENU)==0;
    }
    /** The plain 360 pad (SPEC 2, "360 PAD mode"): the face buttons in the 360 colours and places. */
    static List<Control> padLayout(){
        final List<Control> l=new ArrayList<>();
        l.add(hideControl());
        final float sx=stepX();
        l.add(new Control("y","Y","Y",Kind.BUTTON,Y,DIA_X,DIA_Y-DIA_STEP,DIA_D,YELLOW));
        l.add(new Control("a","A","A",Kind.BUTTON,A,DIA_X,DIA_Y+DIA_STEP,DIA_D,GREEN));
        l.add(new Control("x","X","X",Kind.BUTTON,X,DIA_X-sx,DIA_Y,DIA_D,BLUE));
        l.add(new Control("b","B","B",Kind.BUTTON,B,DIA_X+sx,DIA_Y,DIA_D,RED));
        l.add(new Control("lt","LT","LT",Kind.TRIGGER_L,0,0.07f,0.20f,0.11f,GREY));
        l.add(new Control("lb","LB","LB",Kind.BUTTON,LB,0.18f,0.20f,0.10f,GREY));
        l.add(new Control("rb","RB","RB",Kind.BUTTON,RB,0.82f,0.20f,0.10f,GREY));
        l.add(new Control("rt","RT","RT",Kind.TRIGGER_R,0,0.93f,0.20f,0.11f,GREY));
        l.add(new Control("back","BACK","BACK",Kind.BUTTON,BACK,0.44f,TOP_Y,TOP_D,GREY));
        l.add(new Control("start","START","START",Kind.BUTTON,START,0.56f,TOP_Y,TOP_D,GREY));
        l.add(new Control("l3","L3","L3",Kind.BUTTON,L3,0.27f,0.40f,ROW_D,GREY));
        l.add(new Control("r3","R3","R3",Kind.BUTTON,R3,0.52f,0.50f,ROW_D,GREY));
        l.add(new Control("dpad","D-PAD","D-PAD",Kind.DPAD,0,0.36f,0.78f,0.20f,GREY));
        return l;
    }
    /** Long-press zones still active in this mode (SPEC 3.3): the 360 pad keeps only the map (it has LB and the D-pad). */
    boolean zoneActive(QolGesture.Zone z){
        if(mode==Mode.OFF || hidden || userHidden)return true;
        if(mode==Mode.PAD)return z==QolGesture.Zone.MAP;
        return true;
    }

    // ======== context
    /** The library's word (recomp_touch_context_v1), polled every 100 ms: -1 / -2 unknown. A change must be read twice in a row. */
    void contextWord(int word){
        // 2026-11-09 (Fold, the prologue: "Use LS to walk" and no stick): the library's Cutscene bit is IS_CUTSCENE_ACTIVE || IS_CUTSCENE_PLAYING,
        // and ACTIVE stays set while a cutscene is only LOADED (the prologue's bank play; mission starts) with the player in control - the pad
        // then showed only SKIP and blocked both sticks. A real cutscene takes the player's control (ControlOff, bit 21), so the Cutscene bit
        // counts only with it; with control on it is gameplay.
        if(word>=0 && (word&CUTSCENE_SCREEN)!=0 && (word&CONTROL_OFF_SCREEN)==0)word&=~CUTSCENE_SCREEN;
        final boolean map=word>=0 && (word&0x8000)!=0;mapShown=map;
        screens=word>=0?word&SCREENS:0; // (v704: a wheel, the radio wheel, a frontend menu, a cutscene on screen; SPEC 3.8)
        weaponGroup=word>=0?(word>>>26)&0xF:0; // (v707: the selected weapon's group, SPEC 3.12)
        final boolean cut=skippable(word); // (3.10 v2: SKIP only where the game can skip - a cutscene)
        if(cut!=cutscene){cutscene=cut;if(overlay==Overlay.SKIP)relayout();}
        final Overlay target=phoneOverlay(overlayFor(word),phoneUp);
        if(target!=overlay){ // (SPEC 3.10: the map at once, the others after 2 reads in a row)
            final int need=overlay==Overlay.MAP && target==Overlay.MENU?MENU_FROM_MAP_READS:2;
            if(target==Overlay.MAP || (target==pendingOverlay && ++overlayCount>=need)){overlay=target;skipRevealed=false;overlayCount=0;relayout();}
            else if(target!=pendingOverlay){pendingOverlay=target;overlayCount=1;}
        } else {pendingOverlay=target;overlayCount=0;}
        final int ctx=word<0?-1:(word&0xFF);
        if(ctx<0 || ctx==NONE){pendingCount=0;return;} // (loading / no answer: keep the last layout)
        if(ctx==context){pendingCount=0;if(shownContext!=context){shownContext=context;relayout();}return;}
        if(ctx==pendingContext){if(++pendingCount>=2){context=ctx;shownContext=ctx;pendingCount=0;moreOpen=false;relayout();}}
        else {pendingContext=ctx;pendingCount=1;}
    }
    int context(){return shownContext<0?FOOT:shownContext;}
    boolean mapShown(){return mapShown;}
    // ---- SPEC 3.8 (owner 2026-10-24: "the stick needs to disable when on any screens such as radio, character switch, weapon wheel etc."):
    // while a screen is open the sticks give nothing, take no new finger (it goes to the wheel's tap-select) and are not drawn.
    static final int WEAPON_WHEEL_SCREEN=1<<16,RADIO_WHEEL_SCREEN=1<<17,FRONTEND_SCREEN=1<<18,CUTSCENE_SCREEN=1<<19,FADED_SCREEN=1<<20,
        CONTROL_OFF_SCREEN=1<<21,SCREENS=0x3F0000,MAP_MACRO=1<<30;
    // (owner 2026-10-24: "the menu controls briefly pop up when toggling the map": from MAP the menu set needs MENU_FROM_MAP_READS reads in a
    // row - a frontend flag left for a moment after the close is no menu; a real pause from play still shows it after 2)
    static final int MENU_FROM_MAP_READS=15;
    // ---- SPEC 3.10 (owner 2026-10-24: "touch controls should not appear in menus or cutscenes"): the overlay replacing the gameplay pad
    enum Overlay {PLAY,MAP,SKIP,MENU,PHONE}
    private Overlay overlay=Overlay.SKIP,pendingOverlay=Overlay.SKIP;private int overlayCount;private boolean skipRevealed; // (the start: no pad until 2 reads of real gameplay)
    /** The overlay a library word asks for (first match): the pause map, a cutscene / faded-out screen / control off, a frontend menu. */
    static Overlay overlayFor(int word){
        // (owner 2026-10-24 ~23:02: "Fold controls still come up when game first launches": no library / no guest memory yet (-2 / -1) or no
        // player (context NONE: the logos, the loading screen, the save load) = SKIP like a cutscene - the pad only in real gameplay)
        if(word<0 || (word&0xFF)==NONE)return Overlay.SKIP;
        if((word&(0x8000|MAP_MACRO))!=0)return Overlay.MAP; // (v711 bit 30: the map macro passes through the pause menu - no menu flash)
        if((word&FRONTEND_SCREEN)!=0)return Overlay.MENU; // (the Fold, 21:30: the pause menu also turns control off - the menu wins)
        if((word&(CUTSCENE_SCREEN|FADED_SCREEN|CONTROL_OFF_SCREEN))!=0)return Overlay.SKIP;
        return Overlay.PLAY;
    }
    Overlay overlay(){return overlay;}
    // ---- SPEC 3.15 (owner 2026-10-25: "when the phone comes up, we need the touch controls to change also"): the phone pad
    private boolean phoneUp,phoneCall;
    /** The library's phone state (recomp_phone_state_v1: bit 0 up, bit 1 ringing, bit 2 a call ongoing; -100 / negative: none), set
     *  before each contextWord: the phone itself takes effect like any overlay change; a call relabels the shown pad at once. */
    void phone(int bits){
        phoneUp=bits>0 && (bits&1)!=0;
        final boolean call=phoneUp && (bits&6)!=0;
        if(call!=phoneCall){phoneCall=call;if(overlay==Overlay.PHONE)relayout();}
    }
    /** Gameplay with the phone up is the PHONE pad; a menu, the map or a cutscene keep their own overlay. */
    static Overlay phoneOverlay(Overlay o,boolean phone){return o==Overlay.PLAY && phone?Overlay.PHONE:o;}
    static final float[] PHONE_OK={0.90f,0.80f,0.12f},PHONE_BACK={0.96f,0.60f,0.10f}; // (the menu set's A / B slots, right of the game's phone)
    boolean skipRevealed(){return skipRevealed;}
    // SPEC 3.10 v2 (owner 2026-10-27: "intro skip button no work" - he tapped it ~30 times on the load after a save, word 0x100001 =
    // faded out, which no button skips): the SKIP button only where a cutscene can be playing - the cutscene bit (19; A skips it, the
    // owner's own tip), or control off (21) without a fade (bit 19 has never been seen on the Fold; control is off in a cutscene). A
    // fade (loading, the save load), no answer or no player alone: the pad is hidden as before, no button.
    private boolean cutscene;
    static boolean skippable(int word){
        return word>=0 && ((word&CUTSCENE_SCREEN)!=0 || ((word&CONTROL_OFF_SCREEN)!=0 && (word&FADED_SCREEN)==0));
    }
    static final float[] SKIP_SLOT={0.93f,0.86f,0.08f},MENU_DPAD={0.12f,0.74f,0.20f},MENU_A={0.90f,0.80f,0.12f},MENU_B={0.96f,0.60f,0.10f},
        MENU_LB={0.06f,0.27f,0.09f},MENU_RB={0.94f,0.27f,0.09f},MENU_START={0.55f,0.06f,0.06f},
        MENU_X={0.80f,0.62f,0.09f},MENU_Y={0.86f,0.44f,0.09f}; // (map-prompts: the menu's prompts also name X and Y, e.g. "Hide Menu Y")
    /** map-prompts (owner 2026-10-24: "on the map indoors, make the buttons on the bottom work with touch, to switch view and such"): on the
     *  open map, the game's prompt buttons as a row of GTA prompt icons above the game's own prompt strip (bottom right of the frame,
     *  guest y ~0.93): the player reads the prompt and taps its button. The Fold's indoor map (devcheck/fold-v12-1/02-map-open.png) shows
     *  "Switch View" = BACK (the white circle with the left triangle), "Select Blip" = the D-pad, "Back" = B; outdoors "Waypoint" = A.
     *  Zoom stays the pinch. Left to right: BACK, D-pad, Y, X, B, A. */
    static final float PROMPT_Y=0.84f,PROMPT_D=0.075f,PROMPT_X0=0.62f,PROMPT_STEP=0.062f,PROMPT_DPAD_D=0.10f;
    /** The prompt row's centre y: in the middle of the bar below the frame when it has room (the Fold in Fit: nothing of the map covered),
     *  else just above the game's prompt strip (PROMPT_Y of the frame; the strip is at ~0.92). */
    float promptY(float r){final float bar=frame[1];return bar>=2.4f*r?frame[1]+frame[3]+bar*0.5f:frame[1]+PROMPT_Y*frame[3];}
    static final String[] PROMPT_IDS={"prompt_back","prompt_dpad","prompt_y","prompt_x","prompt_b","prompt_a"};
    /** The controls of an overlay: none on the map; SKIP only once revealed by a first touch; the menu set (D-pad, A, B, LB, RB, START). */
    static List<Control> overlayLayout(Overlay o,boolean revealed,boolean call,boolean skippable){
        final List<Control> l=new ArrayList<>();
        switch(o){
            case SKIP:if(revealed && skippable)l.add(at(SKIP_SLOT,"skip","SKIP","A",Kind.BUTTON,A,GREEN));break;
            case MENU:
                l.add(hideControl());
                l.add(at(MENU_START,"pause","START","START",Kind.BUTTON,START,GREY));
                l.add(at(MENU_DPAD,"dpad","D-PAD","",Kind.DPAD,0,GREY));
                l.add(at(MENU_A,"menu_a","A","",Kind.BUTTON,A,GREEN));
                l.add(at(MENU_B,"menu_b","B","",Kind.BUTTON,B,RED));
                l.add(at(MENU_LB,"menu_lb","LB","",Kind.BUTTON,LB,GREY));
                l.add(at(MENU_RB,"menu_rb","RB","",Kind.BUTTON,RB,GREY));
                l.add(at(MENU_X,"menu_x","X","",Kind.BUTTON,X,BLUE));
                l.add(at(MENU_Y,"menu_y","Y","",Kind.BUTTON,Y,YELLOW));
                break;
            case PHONE: // (SPEC 3.15: the phone's D-pad, select as a check, back as an X; no stick, fire or combat button)
                l.add(hideControl());
                l.add(at(MENU_START,"pause","START","START",Kind.BUTTON,START,GREY));
                l.add(at(MENU_DPAD,"dpad","D-PAD","",Kind.DPAD,0,GREY));
                if(call){ // (a call ringing or ongoing: the same A / B as Answer (green) and Decline / hang up (red))
                    l.add(at(PHONE_OK,"call_answer","ANSWER","A",Kind.BUTTON,A,GREEN));
                    l.add(at(PHONE_BACK,"call_decline","DECLINE","B",Kind.BUTTON,B,RED));
                } else {
                    l.add(at(PHONE_OK,"phone_ok","SELECT","A",Kind.BUTTON,A,GREEN));
                    l.add(at(PHONE_BACK,"phone_back","BACK","B",Kind.BUTTON,B,RED));
                }
                break;
            case MAP: // (map-prompts: the row of prompt buttons; the rest of the map stays the map touch's: pan, pinch, waypoint)
                l.add(hideControl());
                for(int i=0;i<PROMPT_IDS.length;++i){
                    final String id=PROMPT_IDS[i],key=id.substring(7).toUpperCase(java.util.Locale.ROOT);
                    final float[] s={PROMPT_X0+i*PROMPT_STEP,PROMPT_Y,key.equals("DPAD")?PROMPT_DPAD_D:PROMPT_D};
                    switch(key){
                        case "BACK":l.add(at(s,id,key,key,Kind.BUTTON,BACK,GREY));break;
                        case "DPAD":l.add(at(s,id,key,"",Kind.DPAD,0,GREY));break;
                        case "Y":l.add(at(s,id,key,key,Kind.BUTTON,Y,YELLOW));break;
                        case "X":l.add(at(s,id,key,key,Kind.BUTTON,X,BLUE));break;
                        case "B":l.add(at(s,id,key,key,Kind.BUTTON,B,RED));break;
                        default:l.add(at(s,id,key,key,Kind.BUTTON,A,GREEN));break;
                    }
                }
                break;
            default:break;
        }
        return l;
    }
    private int screens,weaponGroup;private boolean wheelOutside;
    /** The selected weapon's group (touch_context.h WeaponGroup: 0 unknown, 1 unarmed ... 11 other): the WEAPON button's icon. */
    int weaponGroup(){return weaponGroup;}
    /** SPEC 3.12: the WEAPON button's icon name for a group (wg0 .. wg11; unknown values = wg0). */
    static String weaponIcon(int group){return "wg"+(group>=0 && group<=11?group:0);}
    /** A wheel this pad does not hold is open: a long-press / latched wheel (QolGesture) or a wheel button the physical pad holds. */
    void wheelOpen(boolean open){wheelOutside=open;}
    int screens(){return screens;}
    /** Whether the physical pad holds a wheel's button in this context: on foot LB (weapons) or D-pad DOWN (characters), in a vehicle
     *  D-pad LEFT (the radio). */
    static boolean physicalWheel(int context,int buttons){return context==FOOT || context==NONE?(buttons&(LB|DD))!=0:(buttons&DL)!=0;}
    /** Whether the sticks are off at time t: a screen from the library's word, a wheel outside the pad, or a wheel button of the pad held
     *  past its tap (the wheel is open). */
    boolean sticksBlocked(long t){
        if(screens!=0 || wheelOutside || (mode!=Mode.PAD && overlay!=Overlay.PLAY))return true; // (the 360 pad ignores the overlays, as its layout does)
        for(Finger f:fingers)if(f.control!=null && wheelKind(f.control.kind) && (t-f.downAt>TAP_MS || f.moved))return true;
        return false;
    }

    // ======== fingers
    /** A finger down at (x, y): true when the pad takes it (a control or a stick), false when it belongs to QolGesture (a zone, the map). */
    boolean down(int id,long t,float x,float y){
        if(mode==Mode.OFF)return false;
        if(editorHit(x,y)){editMode=!editMode;cancel(t);lastTouch=visibleSince=t;return true;}
        if(editMode){
            final Finger f=new Finger();f.id=id;f.x0=f.x=x;f.y0=f.y=y;f.downAt=t;f.editing=true;
            final Control c=hitControl(x,y);
            if(c!=null)f.control=c;
            else f.editStick=stickAt(x,y);
            fingers.add(f);lastTouch=t;return true;
        }
        if(userHidden){final Control c=hitControl(x,y);if(c==null || c.kind!=Kind.HIDE)return false;
            userHidden=false;lastTouch=visibleSince=t;return true;} // (SHOW: the controls come back; this finger does nothing else)
        lastTouch=t;
        {final Finger old=finger(id);if(old!=null){fingers.remove(old);release(old,t);}} // (an id still held = its lift was lost: let go of it first)
        if(hidden){if(!showOnTouch)return false;hidden=false;visibleSince=t;} // (SPEC v2 5: a touch shows them again when the setting allows)
        if(mode!=Mode.PAD && overlay==Overlay.SKIP && !skipRevealed){skipRevealed=true;relayout();return true;} // (3.10: the first touch only shows SKIP)
        Control c=hitControl(x,y);
        final Finger f=new Finger();f.id=id;f.x0=f.x=x;f.y0=f.y=y;f.downAt=t;f.lastT=t;
        if(c==null){
            if(sticksBlocked(t))return false; // (SPEC 3.8: a screen is open - the finger is the wheel's, never a stick's)
            final QolGesture.Zone z=QolGesture.zone(x,y,vw,vh);
            if(z!=QolGesture.Zone.NONE && zoneActive(z))return false;
            final int side=x<vw*LEFT_SIDE?0:1;
            final Stick s=sticks[side];
            if(s.held)return false; // (one finger per stick; a second one on that side is not taken)
            s.held=true;s.finger=id;s.releasedAt=-1;s.cx=x;s.cy=y;s.kx=s.ky=0;f.stick=side+1;fingers.add(f);stickOut(s,side);return true;
        }
        if(c.kind==Kind.HIDE){userHidden=true;visibleSince=t;cancel(t);aimLatched=false;return true;} // (HIDE: every touch input released)
        if(c.kind==Kind.MORE){moreOpen=!moreOpen;relayout();return true;} // (layout B: the MORE row opens / closes; nothing for the game)
        if(c.kind==Kind.APP){appAction=c.id;return true;} // (SPEC 3.14: the app acts on it once - takeAppAction; nothing for the game)
        f.control=c;fingers.add(f);press(f,t);return true;
    }
    private boolean userHidden;
    /** Hidden by the HIDE button (only SHOW is drawn and taken). */
    boolean userHidden(){return userHidden;}
    /** side-menu (SPEC 5.2): Show Touch Controls, the HIDE eye's state set from the menu (hiding releases every touch input). */
    void userHidden(boolean hide,long t){
        if(hide==userHidden)return;
        if(hide){userHidden=true;visibleSince=t;cancel(t);aimLatched=false;}else{userHidden=false;lastTouch=visibleSince=t;}
    }
    /** A physical controller is detected (owner 2026-10-24: "when a gamepad is detected, it auto hides the touch controls"): hide when the
     *  setting says so; a touch on the screen shows them again. */
    void controllerPresent(long t,boolean present){
        if(present && hideWithController && !hidden && mode!=Mode.OFF){hidden=true;visibleSince=t;cancel(t);aimLatched=false;}
    }
    void move(int id,long t,float x,float y){
        final Finger f=finger(id);if(f==null)return;
        final float ox=f.x,oy=f.y;
        f.x=x;f.y=y;lastTouch=t;
        if(f.editing){
            if(f.control!=null){f.control.cx=Math.max(f.control.r,Math.min(vw-f.control.r,x));f.control.cy=Math.max(f.control.r,Math.min(vh-f.control.r,y));}
            else if(f.editStick>=0){final Stick st=sticks[f.editStick];st.hx=Math.max(st.R,Math.min(vw-st.R,x));st.hy=Math.max(st.R,Math.min(vh-st.R,y));
                if(!st.held && st.releasedAt<0){st.cx=st.hx;st.cy=st.hy;}}
            f.moved=true;return;
        }
        if(cameraSwipe && (f.stick==2 || (f.control!=null && f.control.kind==Kind.AIM_LATCH && adsTurns))){ // (item 4: the camera itself)
            if(f.stick==0 && Math.hypot(x-f.x0,y-f.y0)>QolGesture.LONG_SLOP*Math.min(frame[2],frame[3]))f.moved=true;
            if(pitchStick())swipeTrack(f,t,x-ox,y-oy); // (2026-11-10: the vertical part goes through the right stick, DIRECT_PITCH off)
            if(!sticksBlocked(t))camera(x-ox,y-oy);
            return;
        }
        if(velocityCamera() && (f.stick==2 || (f.control!=null && f.control.kind==Kind.AIM_LATCH && adsTurns))){ // (2026-11-09: swipe without the export)
            if(Math.hypot(x-f.x0,y-f.y0)>QolGesture.LONG_SLOP*Math.min(frame[2],frame[3]))f.moved=true;
            swipeTrack(f,t,x-ox,y-oy);
            return;
        }
        if(Math.hypot(x-f.x0,y-f.y0)>QolGesture.LONG_SLOP*Math.min(frame[2],frame[3]))f.moved=true;
        if(f.stick>0){final Stick s=sticks[f.stick-1];drag(s,x,y);stickOut(s,f.stick-1);}
        else if(f.control.kind==Kind.TRIGGER_L || f.control.kind==Kind.TRIGGER_R)f.value=triggerValue(f);
        else if(f.control.kind==Kind.STEER){final Control h=hitControl(x,y);if(h!=null && h.kind==Kind.STEER)f.control=h;} // (3.16: a thumb slides from arrow to arrow)
    }
    void up(int id,long t){final Finger f=finger(id);if(f==null)return;fingers.remove(f);lastTouch=t;
        if(f.editing){recordLayout(f);layoutDirty=true;return;}release(f,t);}
    /** ACTION_CANCEL, focus loss, a physical takeover: every touch input released at once. */
    void cancel(long t){for(Finger f:new ArrayList<>(fingers))release(f,t);fingers.clear();autotap=false;autotapFinger=null;releaseBits=0;pulseBits=0;latchRequest=null;}
    boolean owns(int id){return finger(id)!=null;}
    /** The owner (2026-10-24 ~21:45): "the joystick circle pad on the fold can get stuck". Every MotionEvent lists every finger still down:
     *  a finger of the pad whose id is not among them lost its lift somewhere (a dropped POINTER_UP, a gesture taken by the system) and is
     *  released now. Returns how many were released. */
    int retain(int[] ids,long t){
        int n=0;
        for(Finger f:new ArrayList<>(fingers)){
            boolean present=false;for(int id:ids)if(id==f.id){present=true;break;}
            if(!present){fingers.remove(f);release(f,t);++n;}
        }
        return n;
    }
    /** What a pad finger is, for the log: left-stick / right-stick / the control's id / null (not the pad's). */
    String fingerName(int id){final Finger f=finger(id);return f==null?null:f.stick==1?"left-stick":f.stick==2?"right-stick":f.control.id;}
    /** The wheel a lifted wheel button hands over to QolGesture.latch() (once), or null. */
    QolGesture.Zone takeLatch(){final QolGesture.Zone z=latchRequest;latchRequest=null;return z;}
    /** ACTION_DOWN (no other finger can be down) and ACTION_UP (the last one lifted): any finger still held is stale - released, so no
     *  stick or button can stay stuck after the hand leaves the screen (2026-10-24 Fold run: a stale stick kept Michael walking). */
    void noFingers(long t){if(!fingers.isEmpty())for(Finger f:new ArrayList<>(fingers)){fingers.remove(f);release(f,t);}}
    private Finger finger(int id){for(Finger f:fingers)if(f.id==id)return f;return null;}
    private Control hitControl(float x,float y){for(Control c:controls)if(c.hit(x,y))return c;return null;}
    /** side-menu (SPEC 12.1): a shown control lies under (x, y) - the menu's edge strip leaves such a finger to the pad (only the HIDE
     *  eye counts while the player hid the controls; none while the pad is off or hidden by a controller). */
    boolean controlAt(float x,float y){
        if(mode==Mode.OFF || hidden)return false;
        final Control c=hitControl(x,y);
        return c!=null && (!userHidden || c.kind==Kind.HIDE);
    }
    private void press(Finger f,long t){
        final Control c=f.control;
        if(c.kind==Kind.TRIGGER_L || c.kind==Kind.TRIGGER_R)f.value=TRIGGER_DOWN;
        if(c.kind==Kind.AUTOTAP){if(t-lastSprintTap<=DOUBLE_TAP_MS){autotap=true;autotapFinger=f;}lastSprintTap=t;}
        if(c.kind==Kind.IMENU){pulseBits|=c.bits;pulseUntil=Math.max(t<pulseUntil?pulseUntil:t,t+IMENU_HOLD_MS);} // (3.17: held long enough even for a quick tap)
    }
    private void release(Finger f,long t){
        if(f.editing){recordLayout(f);layoutDirty=true;return;}
        if(f.stick>0){final Stick s=sticks[f.stick-1];s.held=false;s.finger=-1;s.releasedAt=t;s.relKx=s.kx;s.relKy=s.ky;s.relCx=s.cx;s.relCy=s.cy;s.outX=s.outY=0;return;}
        final Control c=f.control;
        if(c.kind==Kind.AIM_LATCH && aimTap && t-f.downAt<=TAP_MS && !f.moved)aimLatched=!aimLatched; // (2026-11-09: only in Aim Mode Tap; Hold = aim while held)
        else if(c.kind==Kind.AIM_LATCH)aimLatched=false;
        if(c.kind==Kind.TAP_OR_WHEEL && t-f.downAt<=TAP_MS && !f.moved){pulseBits|=c.bits;pulseUntil=t+PULSE_MS;}
        if(c.kind==Kind.BUTTON){final long min=(c.bits&(START|BACK))!=0?MIN_MENU_PRESS_MS:MIN_PRESS_MS; // (a short tap is held to its minimum)
            if(t-f.downAt<min){pulseBits|=c.bits;pulseUntil=Math.max(t<pulseUntil?pulseUntil:t,f.downAt+min);}}
        if(wheelKind(c.kind) && f.moved){releaseBits|=c.bits;releaseAt=t+QolGesture.CLOSE_GAP_MS;} // (slid: select)
        else if(wheelKind(c.kind) && t-f.downAt>TAP_MS)latchRequest=QolGesture.wheelOf(c.bits); // (held still: latched)
        else if(c.kind==Kind.WEAPON_TAP){ // (SPEC 3.12: the WEAPON button's tap = the latched weapon wheel; item 2: a double tap = next weapon)
            if(!nextWeapon)latchRequest=QolGesture.Zone.WEAPON;
            else if(weaponTapPending && t-weaponTapAt<=DOUBLE_TAP_MS){weaponTapPending=false;appAction="nextweapon";}
            else{weaponTapPending=true;weaponTapAt=t;}
        }
        if(f==autotapFinger){autotap=false;autotapFinger=null;}
        if(c.kind==Kind.FIRE_FULL && fireAims)fireAimUntil=t+FIRE_AIM_TAIL_MS; // (item 3)
    }
    // ---- sticks
    /** The knob follows the finger inside the ring; beyond it the ring's centre trails the finger (drag-follow). */
    static void drag(Stick s,float x,float y){
        float dx=x-s.cx,dy=y-s.cy;final float d=(float)Math.hypot(dx,dy);
        if(d>s.R){final float k=(d-s.R)/d;s.cx+=dx*k;s.cy+=dy*k;dx=x-s.cx;dy=y-s.cy;}
        s.kx=dx;s.ky=dy;
    }
    /** XInput-like output (SPEC 4): radial dead zone, then from the game's own dead zone to full along a 1.3 curve; y up. */
    // 2026-11-09 (lead, the goal's vehicle-stick item; memory stick-dead-zone: the game's vehicle steering ignores stick values < ~0.25, gives
    // ~0.1 deg/m up to 0.30 and ~0.5 deg/m at 0.30-0.35): in a ground vehicle the left stick's output starts at LS_VEHICLE_FLOOR instead of
    // XInput's dead zone (0.24), so the first part of the finger's travel already steers; the same 1.3 curve up to full lock (full lock and
    // the foot / air contexts unchanged). vehicleCurve: on; the device A/B (Fold check 13) switched it per gesture in a test build.
    static final float LS_VEHICLE_FLOOR=0.30f;
    boolean vehicleCurve=true;
    static int[] stickValue(float kx,float ky,float R,boolean left){return stickValue(kx,ky,R,left?LS_XINPUT_DEAD:RS_XINPUT_DEAD);}
    /** As above with the output's start (the fraction of full scale the first travel past STICK_DEAD gives). */
    static int[] stickValue(float kx,float ky,float R,double floor){
        if(!(R>0))return new int[]{0,0};
        final double vx=kx/R,vy=-ky/R,m=Math.min(1.0,Math.hypot(vx,vy));
        if(m<=STICK_DEAD)return new int[]{0,0};
        final double xd=floor;
        final double out=xd+(1-xd)*Math.pow(Math.max(0,m-STICK_DEAD)/(1-STICK_DEAD),STICK_CURVE);
        final double len=Math.hypot(vx,vy);
        return new int[]{(int)Math.round(32767*out*vx/len),(int)Math.round(32767*out*vy/len)};
    }
    private void stickOut(Stick s,int side){
        final double floor=side!=0?RS_XINPUT_DEAD:vehicleCurve && steerArrows(context())?LS_VEHICLE_FLOOR:LS_XINPUT_DEAD;
        final int[] v=stickValue(s.kx,s.ky,s.R,floor);s.outX=v[0];s.outY=v[1];}
    private float triggerValue(Finger f){
        final float h=f.control.r*2,d=(f.y0-f.y)/h,v=TRIGGER_DOWN+d*(d>0?255-TRIGGER_DOWN:TRIGGER_DOWN-TRIGGER_MIN); // (one button height: up to 255, down to 77)
        return Math.max(TRIGGER_MIN,Math.min(255,v));
    }
    // ---- animation (drawing only; the output never waits for it)
    /** Where a stick's ring centre and knob are drawn at time t: {cx, cy, kx, ky}. After a lift the ring stays where it was let go (it fades
     *  there, stickAlpha) while the knob springs back. */
    float[] stickDraw(int side,long t){
        final Stick s=sticks[side];
        if(s.held || s.releasedAt<0)return new float[]{s.cx,s.cy,s.kx,s.ky};
        final float dt=t-s.releasedAt;
        final double w=6.6/SPRING_MS,k=(1+w*dt)*Math.exp(-w*dt); // (critically damped: ~1 % left at SPRING_MS)
        final float[] d={s.relCx,s.relCy,(float)(s.relKx*k),(float)(s.relKy*k)};
        if(!settling(s,t)){s.releasedAt=-1;s.cx=s.hx;s.cy=s.hy;s.kx=s.ky=0;}
        return d;
    }
    /** A stick's opacity at t (owner 2026-10-25, relayed by main: "circle pad on the z flip is still static. Make it disappear"; SPEC 4.2):
     *  1 while a finger holds it, down to 0 over STICK_FADE_MS after the lift, 0 at rest - nothing is drawn at its home. */
    /** Update 1 (player reports, "no analog stick" with the 360 layout): the 360 pad's two sticks are drawn at rest, at their home, at
     *  this alpha, so the layout shows a real analog movement stick (the same floating stick the default layout uses), not only a D-pad.
     *  The default layout keeps its sticks hidden until a finger lands (unchanged). */
    static final float PAD_REST_ALPHA=0.5f;
    // Update 1 touch item 3 (the owner's post: "cod mobile style ads for rotation, toggle able 1 tap ADS/fire"): set by QolTouch from the
    // side menu's menu.txt words (MenuWords). adsTurns: the AIM finger, held and moved, turns the camera (the right stick from its press
    // point, the stick's curve) - default on. fireAims: FIRE also aims (LT with RT); aim is let go FIRE_AIM_TAIL_MS after the shot ends.
    boolean adsTurns=true,fireAims=false;
    static final long FIRE_AIM_TAIL_MS=120;
    private long fireAimUntil=Long.MIN_VALUE;
    // Update 1 touch item 4: cameraSwipe (set by QolTouch only while the library has recomp_camera_turn_v1): the right side's swipe finger,
    // and the AIM finger while ADS turns, move the camera directly by their movement (degrees per centimetre of finger travel), not the
    // right stick. takeCameraTurn() hands over what built up since the last call: {dYaw right +, dPitch up +}.
    boolean cameraSwipe=false,camInvert=false;float camDegPerCm=8f;
    // Update 1 touch item 2 (the owner's post: "double tap on weapon to cycle through them, on all builds"; ALLOCATION section 13): with
    // nextWeapon (QolTouch: the library's trainer has action 22, ActionCount >= 23) a WEAPON tap waits DOUBLE_TAP_MS; a second tap in that
    // time asks for the next owned weapon (app action "nextweapon" -> recomp_trainer_v1(22, 0, 0)), else the tap's latched wheel opens
    // then (weaponTick). Without action 22 a tap opens the wheel at once, as before.
    boolean nextWeapon=false;
    static final int NEXT_WEAPON_ACTION=22,NEXT_WEAPON_MIN_ACTIONS=23;
    private boolean weaponTapPending;private long weaponTapAt;
    void weaponTick(long t){if(weaponTapPending && t-weaponTapAt>DOUBLE_TAP_MS){weaponTapPending=false;latchRequest=QolGesture.Zone.WEAPON;}}
    private float camYaw,camPitch;
    float[] takeCameraTurn(){if(camYaw==0f && camPitch==0f)return null;final float[] d={camYaw,camPitch};camYaw=camPitch=0f;return d;}
    // recomp_camera_turn_v1's yaw is GTA's relative heading: POSITIVE TURNS THE VIEW LEFT (counter-clockwise; ALLOCATION section 14).
    // 2026-11-10 (the owner on the Fold: "why are controls inverted?"; run-v1401-11100013: finger left, view right): a finger moving right
    // must turn the view right, so it sends a NEGATIVE yaw. Pitch is unchanged (finger up = positive pitch): on v1401 ANY pitch request sent
    // the camera to the floor (reading -70, the clamp) whatever its sign (run-v1401-11101857 / -11101905), a library pitch bug, so its sign
    // cannot be judged on the device until that is fixed.
    private void camera(float dx,float dy){final float cm=10f*mmPx;camYaw+=-dx/cm*camDegPerCm;if(DIRECT_PITCH)camPitch+=-dy/cm*camDegPerCm*(camInvert?-1f:1f);}
    // 2026-11-10 (lead's option B): the library's direct pitch is broken (any pitch request sent the camera to the floor, run-v1401-11101857
    // / -11101905), so recomp_camera_turn_v1 gets pitch 0 and the vertical part of a swipe drives the right stick's Y from the finger's speed
    // (the speed stick, as without the export: finger up = RY > 0 = look up; Invert flips it). Re-enabling the direct pitch later = true here
    // plus TouchQolTest's pitch checks, once a library proves its pitch.
    static final boolean DIRECT_PITCH=false;
    /** The direct turn is on and the vertical part goes through the right stick (DIRECT_PITCH off). */
    boolean pitchStick(){return cameraSwipe && !DIRECT_PITCH && mode==Mode.CONTEXT;}
    // 2026-11-09 (owner, Discord "fire to aim jank": "don't mimic the right analog stick for camera movement, move the camera just by
    // swiping, like native mobile games"). Without the library's recomp_camera_turn_v1 the camera can only be reached through the game's
    // right stick. swipeStick (Camera = Swipe, the default) then gives the stick from the finger's SPEED, not its distance from the
    // touch-down point: the camera moves while the finger moves and stops when it stops, as a swipe camera does (MapTouch's drag rule).
    // The game's own stick response (its dead zone and look acceleration) still shapes the turn, so it is softer than the direct turn;
    // Camera = Stick (swipeStick false) is the old floating stick, opt-in only. The 360 pad layout keeps its real stick.
    boolean swipeStick=true;
    static final float SWIPE_FULL_CMS=12f,SWIPE_STILL_CMS=0.4f,SWIPE_SMOOTH_MS=40f;static final long SWIPE_STOP_MS=60;
    /** The right side (and the aim finger while ADS turns) give the speed stick: no direct turn, Camera = Swipe, the context controls. */
    boolean velocityCamera(){return !cameraSwipe && swipeStick && mode==Mode.CONTEXT;}
    private void swipeTrack(Finger f,long t,float dx,float dy){
        final float dt=Math.max(1f,Math.min(t-f.lastT,SWIPE_SMOOTH_MS)),a=Math.min(1f,dt/SWIPE_SMOOTH_MS);
        if(t-f.lastT>SWIPE_STOP_MS){f.vx=f.vy=0f;}  // (a finger that rested starts again from still)
        f.vx+=a*(dx*1000f/dt-f.vx);f.vy+=a*(dy*1000f/dt-f.vy);f.lastT=t;
    }
    private int[] swipeValue(Finger f,long t){
        if(f.lastT<=f.downAt || t-f.lastT>SWIPE_STOP_MS)return new int[]{0,0};
        return swipeStickValue(f.vx,f.vy,mmPx,camDegPerCm/8f,camInvert);
    }
    /** The right stick for a finger moving at (vx, vy) view px/s: rest below SWIPE_STILL_CMS, else from the game's dead zone up to full at
     *  SWIPE_FULL_CMS / gain (gain: Camera Speed, 1 = Medium); the stick's direction is the finger's (up = look up; invert flips it). */
    static int[] swipeStickValue(float vx,float vy,float pxPerMm,float gain,boolean invert){
        final double len=Math.hypot(vx,vy),cms=len/(10.0*(pxPerMm>0?pxPerMm:10f));
        if(!(cms>=SWIPE_STILL_CMS))return new int[]{0,0};
        final double m=Math.min(1.0,cms*Math.max(0.1f,gain)/SWIPE_FULL_CMS),out=RS_XINPUT_DEAD+(1-RS_XINPUT_DEAD)*m;
        return new int[]{(int)Math.round(32767*out*vx/len),(int)Math.round(-32767*out*vy/len*(invert?-1:1))};
    }
    /** Whether the right stick's ring is drawn: the 360 pad, or Camera = Stick with rswipe=0 (a swipe camera draws no ring). */
    boolean rightRingDrawn(){return editMode || mode==Mode.PAD || (!rightSwipe && !cameraSwipe && !velocityCamera());}
    // 2026-11-09 (the owner: "hold to ADS ... and auto fire when highlighted on an enemy"; "aim turns red when aimed at an enemy, maybe
    // that can be the trigger"): Aim Mode Hold (default) = AIM aims while held; Tap = the old 1-tap latch. autoFire (default off): while
    // aiming, RT is held as long as the library reports the game's reticle red (reticle(), fed by QolTouch; a reading older than
    // RETICLE_STALE_MS counts as not red, so a stalled feed never keeps firing).
    boolean aimTap=false,autoFire=false;
    static final long RETICLE_STALE_MS=150;
    private boolean reticleRed;private long reticleAt=Long.MIN_VALUE/2;
    void aimMode(boolean tap){aimTap=tap;if(!tap)aimLatched=false;}
    /** The library's reticle reading at t: red = the game colours the aim reticle red (a hostile under it). */
    void reticle(long t,boolean red){reticleRed=red;reticleAt=t;}
    private boolean reticleRed(long t){return reticleRed && t-reticleAt<=RETICLE_STALE_MS;}
    /** The player is aiming by touch: the AIM finger is down, or the tap latch holds it. */
    boolean adsHeld(){if(aimLatched)return true;for(Finger f:fingers)if(f.control!=null && f.control.kind==Kind.AIM_LATCH)return true;return false;}
    /** The long-press zones QolGesture uses (QolTouch.zones): the pad's small ones while the controls show, in the default layout AND
     *  (Update 1, the "no analog stick" report) in the 360 layout. Before, the 360 layout kept the wide default zones: their MAP zone (the
     *  frame's bottom-left quarter) took the left thumb's touches, and on the Fold's tall screen the left stick's home sat on its edge. */
    boolean usesPadZones(){return (mode==Mode.CONTEXT || mode==Mode.PAD) && !hidden();}
    float stickAlpha(int side,long t){
        final Stick s=sticks[side];
        final float rest=(editMode || mode==Mode.PAD || (mode==Mode.CONTEXT && side==0))?PAD_REST_ALPHA:0f;
        if(s.held)return 1f;
        if(s.releasedAt<0)return rest;
        return Math.max(rest,1f-(t-s.releasedAt)/(float)STICK_FADE_MS);
    }
    // (by time, not by stickDraw clearing releasedAt: a stick that is not drawn - the right swipe's - would keep the view redrawing forever)
    private static boolean settling(Stick s,long t){return s.releasedAt>=0 && t-s.releasedAt<Math.max(SPRING_MS,STICK_FADE_MS);}
    boolean animating(long t){return settling(sticks[0],t) || settling(sticks[1],t) || alphaChanging(t);}

    // ======== output
    /** The pad from the touches at time t: {buttons, LX, LY, RX, RY, LT, RT}. */
    int[] state(long t){
        final boolean blocked=sticksBlocked(t); // (SPEC 3.8: the sticks give nothing; a wheel button's own steering below still does)
        int b=0,lt=0,rt=0,rx=blocked?0:sticks[1].outX,ry=blocked?0:sticks[1].outY,steer=0;
        for(Finger f:fingers){
            if(f.stick>0){if(f.stick==2 && !blocked && velocityCamera()){final int[] v=swipeValue(f,t);rx=v[0];ry=v[1];} // (2026-11-09: the swipe camera without the export)
                else if(f.stick==2 && !blocked && pitchStick())ry=swipeValue(f,t)[1]; // (2026-11-10: the direct turn's vertical part, DIRECT_PITCH off)
                continue;}
            final Control c=f.control;
            switch(c.kind){
                case BUTTON:case IMENU:b|=c.bits;break;
                case TRIGGER_L:lt=Math.max(lt,Math.round(f.value));break;
                case TRIGGER_R:rt=Math.max(rt,Math.round(f.value));break;
                case FIRE_FULL:rt=255;if(fireAims)lt=255;break;
                case AIM_LATCH:lt=255;
                    if(adsTurns && !cameraSwipe && !blocked){
                        if(velocityCamera()){final int[] v=swipeValue(f,t);if(v[0]!=0 || v[1]!=0){rx=v[0];ry=v[1];}} // (2026-11-09: the aim finger swipes the camera)
                        else if(f.moved){final int[] v=stickValue(f.x-f.x0,f.y-f.y0,sticks[1].R,false);rx=v[0];ry=v[1];}} // (item 3: ADS turns; Camera = Stick)
                    else if(adsTurns && pitchStick() && !blocked){final int v=swipeValue(f,t)[1];if(v!=0)ry=v;} // (2026-11-10: the aim finger's vertical part)
                    break;
                case AUTOTAP:if(f!=autotapFinger || ((t-f.downAt)/AUTOTAP_HALF_MS)%2==0)b|=c.bits;break;
                case WHEEL:case TAP_OR_WHEEL:case WEAPON_TAP:
                    if(t-f.downAt>TAP_MS || f.moved){b|=c.bits;
                        if(f.moved && t-f.downAt>=QolGesture.OPEN_MS+TAP_MS){ // (the wheel is open: the finger steers from its press point)
                            final int[] d=QolGesture.direction(f.x-f.x0+frame[2]*0.5f,f.y-f.y0+frame[3]*0.5f,frame[2],frame[3],0.5f,0.5f);
                            if(d!=null){rx=d[0];ry=d[1];}}}
                    break;
                case DPAD:b|=dpadBits(f.x-c.cx,f.y-c.cy,c.r);break;
                case STEER:steer|=c.bits;break; // (3.16)
                default:break;
            }
        }
        if(aimLatched)lt=255;
        if(fireAims && t<fireAimUntil)lt=255; // (item 3: aim kept just past the shot)
        if(autoFire && !blocked && adsHeld() && reticleRed(t))rt=255; // (2026-11-09 owner: auto-fire while aiming and the game's reticle is red)
        if(t<pulseUntil)b|=pulseBits;else pulseBits=0;
        if(t<releaseAt)b|=releaseBits;else releaseBits=0;
        final int sx=steerX(steer); // (3.16: a held arrow sets the left stick's x; the stick's y stays)
        return new int[]{b,blocked?0:sx!=Integer.MIN_VALUE?sx:sticks[0].outX,blocked?0:sticks[0].outY,rx,ry,lt,rt};
    }
    /** The D-pad: 8 directions from the centre, a 25 % dead centre (sin(pi/8) octant rule). */
    static int dpadBits(float dx,float dy,float r){
        final double len=Math.hypot(dx,dy);
        if(len<0.25*r)return 0;
        final double o=0.3826834323650898*len;int b=0;
        if(Math.abs(dx)>=o)b|=dx<0?DL:DR;
        if(Math.abs(dy)>=o)b|=dy<0?DU:DD;
        return b;
    }
    /** When state() can change without a touch (an auto-tap, a pulse's or a wheel's release): the next time to publish. */
    long due(long t){
        long d=Long.MAX_VALUE;
        if(autotap)d=Math.min(d,t+AUTOTAP_HALF_MS-(t-autotapFinger.downAt)%AUTOTAP_HALF_MS);
        if(pulseBits!=0)d=Math.min(d,pulseUntil);
        if(releaseBits!=0)d=Math.min(d,releaseAt);
        if(fireAims && fireAimUntil>t)d=Math.min(d,fireAimUntil); // (item 3: publish the aim's release)
        if(weaponTapPending)d=Math.min(d,weaponTapAt+DOUBLE_TAP_MS+1); // (item 2: the single tap's wheel, once no second tap came)
        if(autoFire && reticleRed(t))d=Math.min(d,reticleAt+RETICLE_STALE_MS+1); // (a red reading too old stops the shot)
        if(velocityCamera() || pitchStick())for(Finger f:fingers)if(f.lastT>f.downAt && t<=f.lastT+SWIPE_STOP_MS && (f.vx!=0f || f.vy!=0f))
            d=Math.min(d,f.lastT+SWIPE_STOP_MS+1); // (the swipe camera: publish the stop when the finger stops)
        for(Finger f:fingers)if(f.control!=null && wheelKind(f.control.kind) && t-f.downAt<=TAP_MS)
            d=Math.min(d,f.downAt+TAP_MS+1);
        return d;
    }
    /** Whether any touch input is active (for the merge: a physical stick wins only while past its dead zone). */
    boolean active(){return !fingers.isEmpty() || aimLatched || pulseBits!=0 || releaseBits!=0;}

    /** The pad the game is given (SPEC 5), input by input: buttons OR'd; a physical stick past XInput's dead zone, or a trigger above 30,
     *  replaces the touch one; the long-press gestures' right stick (gesture = {buttons, rx, ry} from QolGesture.merge) comes before the
     *  pad's. physical, pad and the result: {buttons, LT, RT, LX, LY, RX, RY} (pad: state()'s {buttons, LX, LY, RX, RY, LT, RT}). */
    static int[] arbitrate(int[] physical,int[] gesture,int[] pad){
        final boolean ls=Math.abs(physical[3])>7849 || Math.abs(physical[4])>7849,rs=Math.abs(physical[5])>8689 || Math.abs(physical[6])>8689;
        final boolean gestureStick=gesture[1]!=physical[5] || gesture[2]!=physical[6];
        final boolean padLs=pad[1]!=0 || pad[2]!=0,padRs=pad[3]!=0 || pad[4]!=0;
        final int lx=ls || !padLs?physical[3]:pad[1],ly=ls || !padLs?physical[4]:pad[2];
        final int rx=gestureStick?gesture[1]:rs || !padRs?physical[5]:pad[3],ry=gestureStick?gesture[2]:rs || !padRs?physical[6]:pad[4];
        final int lt=physical[1]>PHYSICAL_TRIGGER?physical[1]:Math.max(physical[1],pad[5]),rt=physical[2]>PHYSICAL_TRIGGER?physical[2]:Math.max(physical[2],pad[6]);
        return new int[]{gesture[0]|pad[0],lt,rt,lx,ly,rx,ry};
    }
    /** A fresh physical input (SPEC 5): a button not held before, a stick that has just left XInput's dead zone, a trigger that has just
     *  gone above 30. previous = the last publish's physical values (null: none yet = at rest).
     *  touch-120 (2026-11-16): the stick and trigger parts are edges, not levels. With levels, an axis that rests outside its dead zone (a
     *  trigger read at -1, a sensor or shoulder key listed as a joystick) was "fresh" at every publish: the touch pad hid itself again at
     *  each one and let go of the held fingers, so it flickered away under the player's thumbs. A real stick pushed from rest still hides
     *  the pad on its first publish past the dead zone. */
    static boolean freshPhysical(int[] physical,int[] previous){
        final int[] p=previous!=null?previous:new int[7];
        return (physical[0]&~p[0])!=0 || past(physical[3],p[3],7849) || past(physical[4],p[4],7849) || past(physical[5],p[5],8689) ||
               past(physical[6],p[6],8689) || (physical[1]>PHYSICAL_TRIGGER && p[1]<=PHYSICAL_TRIGGER) || (physical[2]>PHYSICAL_TRIGGER && p[2]<=PHYSICAL_TRIGGER);
    }
    private static boolean past(int now,int before,int dead){return Math.abs(now)>dead && Math.abs(before)<=dead;}
    /** touch-120 (2026-11-16): the right-edge strip where Android's edge Back gesture must not start while the touch controls are on, in
     *  view pixels {left, top, right, bottom}, or null (controls off, no view). Android's gesture navigation turns a swipe that starts at the
     *  right edge into Back, and Back opens the side menu (PrivateTraceActivity.sideMenuKey): a camera swipe started there opened the menu and
     *  cancelled the finger. Android honours at most 200 dp of exclusion per edge, so the strip covers the lower 200 dp, where the corner
     *  buttons and the swipe camera's thumb are. The left edge is left alone: a swipe in from it opening the side menu is the menu's design. */
    float[] rightEdgeExclusion(float dpPx){
        if(mode==Mode.OFF || !(vw>0) || !(vh>0) || !(dpPx>0))return null;
        final float w=EXCLUDE_WIDTH_DP*dpPx,h=Math.min(vh,EXCLUDE_MAX_DP*dpPx);
        return new float[]{Math.max(0f,vw-w),vh-h,vw,vh};
    }
    static final float EXCLUDE_WIDTH_DP=48f,EXCLUDE_MAX_DP=200f;

    // ======== the physical pad (every publish)
    /** A physical input (a new button, a stick past XInput's dead zone, a trigger above 30) hides the controls when the setting says so. */
    void physical(long t,boolean freshInput){
        if(!freshInput || !hideWithController || hidden || mode==Mode.OFF)return;
        hidden=true;visibleSince=t;cancel(t);aimLatched=false;
    }
    boolean hidden(){return hidden;}
    // ======== drawing alpha (SPEC 5)
    float alpha(long t){
        if(mode==Mode.OFF)return 0f;
        if(editMode)return Math.max(opacity,0.9f);
        final float since=Math.min(1f,(t-visibleSince)/(float)FADE_MS);
        if(hidden)return opacity*(1f-since)*idleFactor(t);
        final float idle=idleFactor(t);
        return opacity*idle*(mapShown?0.2f:1f);
    }
    /** The WEAPON button's opacity (owner 2026-10-24 ~22:55: "still don't see the weapon icon on the fold" - the idle fade had it at 35 %, a
     *  faint grey disc): the controls' opacity without the idle fade; it still goes with HIDE / a controller / the pause map. */
    /** Everything the drawing depends on besides the fingers (v15, owner: "the button does not change to the weapon you are holding until you
     *  tap the button again"): QolTouch compares it at every poll and redraws when it changes - no touch needed. */
    /** jank-1117: what the fingers make the overlay draw (held controls, the sticks' ring and knob in whole pixels, a trigger's depth, a wheel
     *  button's slide, the sticks being blocked). A move that leaves it unchanged needs no redraw: a swipe-camera finger (no ring drawn) or a
     *  finger resting on a button moved, 120-240 times a second, and each redrew the whole overlay. */
    long drawKey(long t){
        long h=(sticksBlocked(t)?1:0)|(aimLatched?2:0);
        for(Finger f:fingers){
            h=h*31+f.id;
            if(f.stick>0){final Stick s=sticks[f.stick-1];
                if(f.stick==1 || rightRingDrawn())h=((h*31+Math.round(s.cx))*31+Math.round(s.cy))*31+(Math.round(s.kx)*4099L+Math.round(s.ky));
                continue;}
            h=(h*31+System.identityHashCode(f.control))*31+(f.moved?1:0);
            if(f.control.kind==Kind.TRIGGER_L || f.control.kind==Kind.TRIGGER_R)h=h*31+Math.round(f.value);
        }
        return h;
    }
    /** jank-1117: when the idle fade begins (FADE_IDLE_MS after the last touch, nothing held), so the overlay can draw it then; before, it
     *  only showed at the next unrelated redraw (a weapon switch, a context change), the controls jumping straight to the faded opacity. */
    long idleFadeAt(){return fingers.isEmpty() && mode!=Mode.OFF?lastTouch+FADE_IDLE_MS:Long.MAX_VALUE;}
    long visualKey(long t){
        return (long)weaponGroup|(long)overlay.ordinal()<<4|(long)context()<<8|(mapShown?1L:0L)<<16|(hidden?1L:0L)<<17|(userHidden?1L:0L)<<18|
               (moreOpen?1L:0L)<<19|(skipRevealed?1L:0L)<<20|(sticksBlocked(t)?1L:0L)<<21|(trainerButton?1L:0L)<<22|(imenuButton?1L:0L)<<23|(long)(screens>>>16)<<24;
    }
    float weaponAlpha(long t){
        if(mode==Mode.OFF || hidden)return alpha(t);
        return opacity*(mapShown?0.2f:1f);
    }
    private float idleFactor(long t){
        if(!fingers.isEmpty())return 1f;
        final long quiet=t-lastTouch;
        if(quiet<FADE_IDLE_MS)return 1f;
        final float k=Math.min(1f,(quiet-FADE_IDLE_MS)/(float)FADE_MS);
        return 1f-(1f-IDLE_ALPHA)*k;
    }
    private boolean alphaChanging(long t){
        final long quiet=t-lastTouch;
        return t-visibleSince<FADE_MS || (quiet>=FADE_IDLE_MS && quiet<FADE_IDLE_MS+FADE_MS);
    }
    boolean held(Control c){for(Finger f:fingers)if(f.control==c)return true;return c.kind==Kind.AIM_LATCH && aimLatched;}
    boolean driveByHeld(){for(Finger f:fingers)if(f.control!=null && f.control.id.equals("driveby"))return true;return false;}

    // ======== touch layout editor (stored in files/touch-layout-v1.txt, separate from game saves/settings)
    boolean editing(){return editMode;}
    private boolean editorHit(float x,float y){return x>=vw-24f*mmPx && x<=vw-2f*mmPx && y<=12f*mmPx;}
    private int stickAt(float x,float y){
        for(int i=0;i<2;i++){final Stick st=sticks[i];if(Math.hypot(x-st.hx,y-st.hy)<=Math.max(st.R*1.5f,22f*mmPx))return i;}
        return -1;
    }
    void loadLayout(String text){
        layoutPositions.clear();
        if(text!=null)for(String line:text.split("\n")){
            final String[] a=line.trim().split(",");
            if(a.length!=3)continue;
            try{final float x=Float.parseFloat(a[1]),y=Float.parseFloat(a[2]);
                if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1)continue;
                if(a[0].equals("@left")||a[0].equals("@right")||a[0].matches("[a-zA-Z0-9_]+"))layoutPositions.put(a[0],new float[]{x,y});
            }catch(NumberFormatException ignored){}
        }
        relayout();
    }
    boolean takeLayoutDirty(){if(!layoutDirty)return false;layoutDirty=false;return true;}
    String layoutText(){
        final StringBuilder out=new StringBuilder();
        for(Map.Entry<String,float[]> e:layoutPositions.entrySet())out.append(e.getKey()).append(',')
                .append(String.format(java.util.Locale.ROOT,"%.5f,%.5f\n",e.getValue()[0],e.getValue()[1]));
        return out.toString();
    }
    private void recordLayout(Finger f){
        if(f.control!=null)layoutPositions.put(f.control.id,new float[]{Math.max(0,Math.min(1,f.control.cx/Math.max(1,vw))),
                Math.max(0,Math.min(1,f.control.cy/Math.max(1,vh)))});
        else if(f.editStick>=0){final Stick st=sticks[f.editStick];layoutPositions.put(f.editStick==0?"@left":"@right",
                new float[]{Math.max(0,Math.min(1,st.hx/Math.max(1,vw))),Math.max(0,Math.min(1,st.hy/Math.max(1,vh)))});}
    }
    // ======== settings (files/settings/touch-controls.txt words; SPEC 5)
    void settings(String text){
        mode=Mode.CONTEXT;hideWithController=true;showOnTouch=true;sizeScale=1f;opacity=0.8f;layoutB=true;moreOpen=false;rightSwipe=true;
        if(text!=null)for(String w:text.trim().split("\\s+")){
            final int eq=w.indexOf('=');if(eq<=0)continue;
            final String k=w.substring(0,eq),v=w.substring(eq+1);
            switch(k){
                case "mode":mode=v.equals("pad")?Mode.PAD:v.equals("off")?Mode.OFF:Mode.CONTEXT;break;
                case "hide":hideWithController=!v.equals("0");break;
                case "show":showOnTouch=!v.equals("0");break;
                case "size":try{final int s=Integer.parseInt(v);if(s>=70 && s<=130)sizeScale=s/100f;}catch(NumberFormatException e){}break;
                case "layout":layoutB=!v.equals("a");break; // (B is the default since the owner picked it; layout=a = all buttons shown)
                case "rswipe":rightSwipe=!v.equals("0");break;
                case "opacity":try{final int o=Integer.parseInt(v);if(o>=20 && o<=100)opacity=o/100f;}catch(NumberFormatException e){}break;
                default:break;
            }
        }
        relayout();
    }
}
