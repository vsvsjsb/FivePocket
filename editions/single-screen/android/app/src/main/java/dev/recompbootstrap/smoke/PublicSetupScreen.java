package dev.recompbootstrap.smoke;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.view.View;
import java.io.*;

/** File-picker and background-work adapter. The disc page keeps its existing verified copier. */
final class PublicSetupScreen implements Screen {
    private static final int PICK_CODE = SetupPickerRoute.CODE;
    private final PublicSetupModel model = new PublicSetupModel();
    private ScreenHost host;
    private UiKit.SceneView view;
    private volatile boolean cancel;
    private volatile long done, total;
    private boolean shown;
    private final Runnable tick = new Runnable() {
        public void run() {
            if (!shown || !model.busy) return;
            model.done = done; model.total = total; render(); view.postDelayed(this, 250);
        }
    };
    public View createView(ScreenHost h) {
        host = h;
        view = new UiKit.SceneView(h.activity(), h.unit(), new UiKit.Listener() {
            public void onHit(int id) {
                int base = id / 100 * 100, row = id - base;
                if (base == MenuLayout.HIT_ROW && row >= 0 && row < 3 && !model.busy) {
                    model.focus = row;
                    action(model.confirm(host.gameRunning()));
                    render();
                } else if (base == MenuLayout.HIT_PROMPT) onNav(row, false);
            }
            public void onScroll(int lines) {}
            public void onResized() { render(); }
        });
        LauncherScreen.showBackdrop(host, view); render(); return view;
    }
    public void onShown() {
        shown = true;
        if (model.busy) { view.postDelayed(tick, 250); return; }
        host.background(() -> {
            String apk = host.activity().getApplicationInfo().sourceDir;
            ShipReady state = ShipReady.of(apk, host.filesDir());
            model.ready = state.ready();
            model.codeMissing = state.stands == ShipReady.Stands.CODE || state.stands == ShipReady.Stands.PIECE;
            model.imported = importedValid(apk);
        }, this::render);
    }
    /** An imported bundle is on this device and passes the app's check against its own record. */
    private boolean importedValid(String apk) {
        try (ShipBundle source = PublicGameCode.open(apk, host.filesDir())) {
            return source instanceof PublicGameCode
                    && PublicGameCode.installedProblem(InstallGuard.root(host.filesDir()), ((PublicGameCode) source).config) == null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
    /** Finish Setup: the activation that follows an import, from the bundle already imported (no picker, nothing copied again). */
    private void finishSetup() {
        if (host.gameRunning()) { model.notice = PublicCodeWords.RUNNING; return; }
        cancel = false; done = total = 0; model.finishing(); view.postDelayed(tick, 250);
        final String[] activation = new String[1];
        final String[] seal = new String[1];
        host.background(() -> {
            try (ShipBundle source = PublicGameCode.open(host.activity().getApplicationInfo().sourceDir, host.filesDir())) {
                if (!(source instanceof PublicGameCode)) { activation[0] = PublicCodeWords.APP_RECORD; return; }
                try { seal[0] = PublicGameCode.seal(InstallGuard.root(host.filesDir())); } catch (IOException e) { seal[0] = null; }
                activation[0] = ((PublicGameCode) source).activate((step, d, t) -> { done = d; total = t; });
            } catch (IOException | RuntimeException e) {
                activation[0] = PublicCodeWords.STORAGE;
            }
        }, () -> {
            view.removeCallbacks(tick); model.finished(VerifiedExtractor.Outcome.done(), activation[0], seal[0]); render();
            onShown();          // read the state again (the disc files may still be missing: then Play is not offered yet)
        });
    }
    public void onHidden() { shown = false; if (model.busy) cancel = true; view.removeCallbacks(tick); }
    public void onAppLeft() { if (model.busy) cancel = true; }
    public void onAppBack() { if (!model.busy) onShown(); }
    public void onWindowBack() {}
    public boolean onNav(int nav, boolean repeat) {
        if (nav == NavInput.UP || nav == NavInput.DOWN) model.move(nav == NavInput.UP ? -1 : 1, repeat);
        else if (nav == NavInput.CONFIRM) { if (!repeat) action(model.confirm(host.gameRunning())); }
        else if (nav == NavInput.BACK) { if (!repeat) action(model.back()); }
        else return false;
        render(); return true;
    }
    private void action(int action) {
        if (action == PublicSetupModel.STOP) { cancel = true; model.notice = "Stopping the import. Your previous files are kept."; }
        else if (action == PublicSetupModel.LEAVE) host.pop();
        else if (action == PublicSetupModel.DISCS) host.push(new SetupScreen(true));
        else if (action == PublicSetupModel.FINISH) finishSetup();
        else if (action == PublicSetupModel.PICK) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            // Ask Android for read access to the exact document the player selects; the importer copies it into app-private storage.
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
            try { host.startForResult(intent, PICK_CODE); }
            catch (ActivityNotFoundException e) { model.notice = PublicCodeWords.PICKER; }
        }
    }
    public void onActivityResult(int request, int result, Intent data) {
        if (request != PICK_CODE || model.busy) return;
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) { model.notice = PublicCodeWords.NOT_CHOSEN; render(); return; }
        if (host.gameRunning()) { model.notice = PublicCodeWords.RUNNING; render(); return; }
        cancel = false; done = total = 0; model.started(); render(); view.postDelayed(tick, 250);
        final VerifiedExtractor.Outcome[] outcome = new VerifiedExtractor.Outcome[1];
        final String[] activation = new String[1];
        final String[] seal = new String[1];
        host.background(() -> {
            try (ShipBundle source = PublicGameCode.open(host.activity().getApplicationInfo().sourceDir, host.filesDir());
                    InputStream in = host.activity().getContentResolver().openInputStream(uri)) {
                if (!(source instanceof PublicGameCode)) { outcome[0] = VerifiedExtractor.Outcome.failed(PublicCodeWords.APP_RECORD); return; }
                if (in == null) throw new IOException();
                PublicGameCode code = (PublicGameCode)source;
                outcome[0] = CodeBundleImporter.importPublic(in, InstallGuard.root(host.filesDir()), new VerifiedExtractor.Progress() {
                    public void update(long d, long t, int f, int fs, String path) { done = d; total = t; }
                    public boolean cancelled() { return cancel; }
                }, SetupCatalog.spec(), VerifiedExtractor.DISK, code.config);
                if (outcome[0].complete) {
                    try { seal[0] = PublicGameCode.seal(InstallGuard.root(host.filesDir())); } catch (IOException e) { seal[0] = null; }
                }
                if (outcome[0].complete && !cancel) {
                    if (host.gameRunning()) activation[0] = PublicCodeWords.RUNNING;
                    else activation[0] = code.activate((step, d, t) -> { done = d; total = t; });
                } else if (outcome[0].complete) activation[0] = "Choose Install Game Code again to finish setting up.";
            } catch (IOException | RuntimeException e) {
                outcome[0] = VerifiedExtractor.Outcome.failed(VerifiedExtractor.Problem.SOURCE_UNREADABLE, PublicCodeWords.READ);
            } catch (Error e) {
                outcome[0] = VerifiedExtractor.Outcome.failed(PublicCodeWords.STORAGE);
            }
        }, () -> { view.removeCallbacks(tick); model.finished(outcome[0], activation[0], seal[0]); render(); });
    }
    private void render() {
        if (view == null) return;
        MenuScenes.Page page = model.page();
        page.touchIcons = view.touchIcons();
        { MenuLayout.Frame sf = view.frame();   // (2026-11-17: the setup pages in the Enhanced frame, SPEC §125)
          float sk = page.touchIcons ? EnhancedScenes.touchScale(view.xdpi(), view.unit(), sf.h) : 1f;
          Scene ss = EnhancedScenes.build(page, view, sf.h, sk, page.touchIcons);
          ss.panelW = sf.w; ss.panelH = sf.h; view.show(ss); }
        view.setKeepScreenOn(model.busy);
    }
}
