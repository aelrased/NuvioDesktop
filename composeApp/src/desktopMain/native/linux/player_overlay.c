/*
 * player_overlay.c — Transparent X11 overlay for player controls
 *
 * Creates a borderless, always-on-top, transparent X11 window on top of the
 * mpv video surface. Renders a minimal controls UI (seekbar, play/pause,
 * title, time) and captures mouse events, forwarding them to Kotlin via
 * the existing onPlayerEvent callback.
 *
 * This solves the XWayland problem where the mpv X11 window composites
 * above the Compose rendering layer, hiding Compose controls.
 */
#define _GNU_SOURCE
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>
#include <unistd.h>
#include <math.h>
#include <time.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/extensions/shape.h>
#include <X11/extensions/Xrender.h>

#define OVERLAY_DBG(...) do { \
    fprintf(stderr, "[player_overlay] " __VA_ARGS__); \
    fflush(stderr); \
} while (0)

/* ------------------------------------------------------------------ */
/*  Overlay state                                                      */
/* ------------------------------------------------------------------ */
typedef struct {
    /* X11 */
    Display *dpy;
    Window parent;      /* AWT Canvas window (mpv wid) */
    Window overlay;     /* Our transparent overlay window */
    GC gc;
    int screen;
    Visual *visual;     /* 32-bit ARGB visual */
    Colormap colormap;
    int depth;

    /* geometry */
    int x, y, w, h;

    /* controls state (from Kotlin) */
    double position_ms;
    double duration_ms;
    int    paused;
    int    is_loading;
    int    visible;        /* 0=hidden, 1=visible */
    long   visible_until;  /* epoch ms when visibility should auto-hide */

    /* title */
    char title[512];

    /* event callback */
    JavaVM *jvm;
    jobject event_sink;
    jmethodID event_method;

    /* lifecycle */
    volatile int alive;
    pthread_t event_thread;
} Overlay;

static Overlay g_overlay = {0};

/* ------------------------------------------------------------------ */
/*  Time helpers                                                       */
/* ------------------------------------------------------------------ */
static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ------------------------------------------------------------------ */
/*  32-bit ARGB visual (for transparency)                              */
/* ------------------------------------------------------------------ */
static Visual *find_argb_visual(Display *dpy, int screen, int *depth_out, Colormap *cmap_out) {
    XVisualInfo template = {0};
    template.screen = screen;
    template.depth = 32;
    template.class = TrueColor;

    int n = 0;
    XVisualInfo *infos = XGetVisualInfo(dpy, VisualScreenMask | VisualDepthMask | VisualClassMask,
                                         &template, &n);
    if (!infos) return NULL;

    for (int i = 0; i < n; i++) {
        XRenderPictFormat *fmt = XRenderFindVisualFormat(dpy, infos[i].visual);
        if (fmt && fmt->direct.alphaMask) {
            Visual *v = infos[i].visual;
            int d = infos[i].depth;
            Colormap cm = XCreateColormap(dpy, RootWindow(dpy, screen), v, AllocNone);
            XFree(infos);
            *depth_out = d;
            *cmap_out = cm;
            return v;
        }
    }
    XFree(infos);
    return NULL;
}

/* ------------------------------------------------------------------ */
/*  Find parent window: traverse from overlay's target to root          */
/* ------------------------------------------------------------------ */
static void send_event_to_kotlin(const char *type, double value) {
    if (!g_overlay.jvm || !g_overlay.event_sink || !g_overlay.event_method) return;
    JNIEnv *env = NULL;
    int attached = 0;
    int rc = (*g_overlay.jvm)->GetEnv(g_overlay.jvm, (void**)&env, JNI_VERSION_1_6);
    if (rc == JNI_EDETACHED) {
        (*g_overlay.jvm)->AttachCurrentThread(g_overlay.jvm, (void**)&env, NULL);
        attached = 1;
    }
    if (env && g_overlay.event_sink) {
        jstring jtype = (*env)->NewStringUTF(env, type);
        (*env)->CallVoidMethod(env, g_overlay.event_sink, g_overlay.event_method, jtype, value);
        (*env)->DeleteLocalRef(env, jtype);
    }
    if (attached) (*g_overlay.jvm)->DetachCurrentThread(g_overlay.jvm);
}

/* ------------------------------------------------------------------ */
/*  Draw controls                                                      */
/* ------------------------------------------------------------------ */
static void draw_overlay(void) {
    if (!g_overlay.dpy || !g_overlay.overlay || g_overlay.w <= 0 || g_overlay.h <= 0) return;

    /* Create back-buffer pixmap for double-buffering */
    Pixmap backbuf = XCreatePixmap(g_overlay.dpy, g_overlay.overlay,
                                    g_overlay.w, g_overlay.h, g_overlay.depth);
    GC draw_gc = XCreateGC(g_overlay.dpy, backbuf, 0, NULL);

    /* Clear to transparent */
    XGCValues gcv;
    gcv.foreground = 0x00000000;  /* transparent black */
    XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
    XFillRectangle(g_overlay.dpy, backbuf, draw_gc, 0, 0, g_overlay.w, g_overlay.h);

    int W = g_overlay.w;
    int H = g_overlay.h;

    if (g_overlay.visible) {
        /* --- Top gradient scrim (black -> transparent, 120px) --- */
        for (int i = 0; i < 120 && i < H; i++) {
            uint8_t alpha = (uint8_t)(200 * (1.0 - (double)i / 120.0));
            gcv.foreground = (alpha << 24);
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFillRectangle(g_overlay.dpy, backbuf, draw_gc, 0, i, W, 1);
        }

        /* --- Bottom gradient scrim (transparent -> black, 200px) --- */
        for (int i = 0; i < 200 && i < H; i++) {
            uint8_t alpha = (uint8_t)(220 * ((double)i / 200.0));
            gcv.foreground = (alpha << 24);
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFillRectangle(g_overlay.dpy, backbuf, draw_gc, 0, H - 200 + i, W, 1);
        }

        /* --- Title (top-left) --- */
        if (g_overlay.title[0]) {
            gcv.foreground = 0xDDFFFFFF;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFontStruct *font = XLoadQueryFont(g_overlay.dpy, "fixed");
            if (font) {
                XSetFont(g_overlay.dpy, draw_gc, font->fid);
                XDrawString(g_overlay.dpy, backbuf, draw_gc, 20, 40,
                            g_overlay.title, (int)strlen(g_overlay.title));
                XFreeFont(g_overlay.dpy, font);
            }
        }

        /* --- Seekbar (bottom area) --- */
        int bar_y = H - 60;
        int bar_h = 4;
        int bar_margin = 20;
        int bar_w = W - 2 * bar_margin;

        if (bar_w > 0 && g_overlay.duration_ms > 0) {
            /* Background bar (dark) */
            gcv.foreground = 0x80000000;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFillRectangle(g_overlay.dpy, backbuf, draw_gc,
                           bar_margin, bar_y, bar_w, bar_h);

            /* Progress bar (white) */
            double progress = g_overlay.position_ms / g_overlay.duration_ms;
            if (progress < 0.0) progress = 0.0;
            if (progress > 1.0) progress = 1.0;
            int prog_w = (int)(bar_w * progress);
            gcv.foreground = 0xFFE0E0E0;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFillRectangle(g_overlay.dpy, backbuf, draw_gc,
                           bar_margin, bar_y, prog_w, bar_h);

            /* Handle dot */
            int dot_r = 8;
            gcv.foreground = 0xFFFFFFFF;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFillArc(g_overlay.dpy, backbuf, draw_gc,
                     bar_margin + prog_w - dot_r, bar_y - dot_r + bar_h / 2,
                     dot_r * 2, dot_r * 2, 0, 360 * 64);
        }

        /* --- Time labels (below seekbar) --- */
        {
            int pos_sec = (int)(g_overlay.position_ms / 1000.0);
            int dur_sec = (int)(g_overlay.duration_ms / 1000.0);
            char time_buf[64];
            snprintf(time_buf, sizeof(time_buf), "%d:%02d", pos_sec / 60, pos_sec % 60);
            snprintf(time_buf + strlen(time_buf), sizeof(time_buf) - strlen(time_buf),
                     " / %d:%02d", dur_sec / 60, dur_sec % 60);

            gcv.foreground = 0xCCFFFFFF;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            XFontStruct *font = XLoadQueryFont(g_overlay.dpy, "fixed");
            if (font) {
                XSetFont(g_overlay.dpy, draw_gc, font->fid);
                XDrawString(g_overlay.dpy, backbuf, draw_gc,
                            bar_margin, bar_y + bar_h + 24,
                            time_buf, (int)strlen(time_buf));
                XFreeFont(g_overlay.dpy, font);
            }
        }

        /* --- Play/Pause icon (center) --- */
        {
            int cx = W / 2;
            int cy = H / 2 - 20;
            int size = 30;

            gcv.foreground = 0xCCFFFFFF;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);

            if (g_overlay.paused) {
                /* Play triangle (right-pointing) */
                XPoint points[3];
                points[0].x = cx - size / 3;  points[0].y = cy - size / 2;
                points[1].x = cx + size / 2;  points[1].y = cy;
                points[2].x = cx - size / 3;  points[2].y = cy + size / 2;
                XFillPolygon(g_overlay.dpy, backbuf, draw_gc, points, 3, Convex, CoordModeOrigin);
            } else {
                /* Pause bars (two vertical rectangles) */
                int bar_w = size / 5;
                int gap = size / 4;
                XFillRectangle(g_overlay.dpy, backbuf, draw_gc,
                               cx - gap - bar_w, cy - size / 2, bar_w, size);
                XFillRectangle(g_overlay.dpy, backbuf, draw_gc,
                               cx + gap, cy - size / 2, bar_w, size);
            }
        }

        /* --- Loading spinner (if loading) --- */
        if (g_overlay.is_loading) {
            gcv.foreground = 0xAAFFFFFF;
            XChangeGC(g_overlay.dpy, draw_gc, GCForeground, &gcv);
            int r = 20;
            int cx = W / 2;
            int cy = H / 2 + 30;
            /* Simple spinning arc using time */
            long t = now_ms();
            int angle = (int)((t / 50) % 360);
            XDrawArc(g_overlay.dpy, backbuf, draw_gc,
                     cx - r, cy - r, r * 2, r * 2,
                     angle * 64, 120 * 64);
        }
    }

    /* Commit back-buffer to overlay window */
    XCopyArea(g_overlay.dpy, backbuf, g_overlay.overlay, draw_gc, 0, 0, g_overlay.w, g_overlay.h, 0, 0);
    XFreeGC(g_overlay.dpy, draw_gc);
    XFreePixmap(g_overlay.dpy, backbuf);
    XFlush(g_overlay.dpy);
}

/* ------------------------------------------------------------------ */
/*  Event thread                                                       */
/* ------------------------------------------------------------------ */
static void *event_thread_func(void *arg) {
    (void)arg;
    OVERLAY_DBG("event thread started\n");

    while (g_overlay.alive && g_overlay.dpy && g_overlay.overlay) {
        /* Process pending X events */
        while (XPending(g_overlay.dpy)) {
            XEvent ev;
            XNextEvent(g_overlay.dpy, &ev);

            switch (ev.type) {
            case ButtonPress: {
                /* Click = toggle play/pause */
                send_event_to_kotlin("togglePlayback", 0.0);
                /* Show controls on click */
                g_overlay.visible = 1;
                g_overlay.visible_until = now_ms() + 3000;
                draw_overlay();
                break;
            }
            case MotionNotify: {
                /* Mouse movement = show controls */
                g_overlay.visible = 1;
                g_overlay.visible_until = now_ms() + 3000;

                /* Click-drag on seekbar = seek */
                int bar_y = g_overlay.h - 60;
                if (ev.xmotion.y >= bar_y - 20 && ev.xmotion.y <= bar_y + 30 && g_overlay.duration_ms > 0) {
                    double frac = (double)(ev.xmotion.x - 20) / (double)(g_overlay.w - 40);
                    if (frac < 0.0) frac = 0.0;
                    if (frac > 1.0) frac = 1.0;
                    double seek_ms = frac * g_overlay.duration_ms;
                    send_event_to_kotlin("scrubChange", seek_ms);
                }
                draw_overlay();
                break;
            }
            case ButtonRelease: {
                /* If released on seekbar, commit seek */
                int bar_y = g_overlay.h - 60;
                if (ev.xbutton.y >= bar_y - 20 && ev.xbutton.y <= bar_y + 30 && g_overlay.duration_ms > 0) {
                    double frac = (double)(ev.xbutton.x - 20) / (double)(g_overlay.w - 40);
                    if (frac < 0.0) frac = 0.0;
                    if (frac > 1.0) frac = 1.0;
                    double seek_ms = frac * g_overlay.duration_ms;
                    send_event_to_kotlin("scrubFinish", seek_ms);
                }
                break;
            }
            case ConfigureNotify: {
                /* Window resized/repositioned */
                if (ev.xconfigure.window == g_overlay.overlay) {
                    g_overlay.w = ev.xconfigure.width;
                    g_overlay.h = ev.xconfigure.height;
                    draw_overlay();
                }
                /* Parent ConfigureNotify — ignore for now, handled via JNI */
                break;
            }
            case LeaveNotify: {
                /* Mouse left overlay area — hide after delay */
                g_overlay.visible_until = now_ms() + 1000;
                break;
            }
            default:
                break;
            }
        }

        /* Periodically check parent position (in case ConfigureNotify not received) */
        /* Using XGetGeometry only — no cross-window translation needed */

        /* Keep overlay above parent */
        XRaiseWindow(g_overlay.dpy, g_overlay.overlay);

        /* Auto-hide check */
        if (g_overlay.visible && now_ms() > g_overlay.visible_until) {
            g_overlay.visible = 0;
            draw_overlay();
        }

        /* Redraw loading spinner animation */
        if (g_overlay.visible && g_overlay.is_loading) {
            draw_overlay();
            usleep(50000); /* 20fps for spinner */
        } else {
            usleep(16000); /* ~60fps */
        }
    }

    OVERLAY_DBG("event thread stopped\n");
    return NULL;
}

/* Forward declaration */
JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_destroyOverlay(
    JNIEnv *env, jobject thiz);

/* ------------------------------------------------------------------ */
/*  JNI: createOverlay                                                 */
/* ------------------------------------------------------------------ */
JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_createOverlay(
    JNIEnv *env, jobject thiz, jlong parentWindowPtr, jint x, jint y, jint w, jint h) {
    (void)thiz;

    OVERLAY_DBG("createOverlay(parent=%lld, %d,%d %dx%d)\n",
                (long long)parentWindowPtr, x, y, w, h);

    if (g_overlay.alive) {
        OVERLAY_DBG("createOverlay: destroying previous overlay\n");
        Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_destroyOverlay(env, thiz);
    }

    memset(&g_overlay, 0, sizeof(g_overlay));
    g_overlay.parent = (Window)parentWindowPtr;
    g_overlay.x = x;
    g_overlay.y = y;
    g_overlay.w = w;
    g_overlay.h = h;

    /* Get JVM */
    (*env)->GetJavaVM(env, &g_overlay.jvm);

    /* Event sink is cached on first updateOverlayState call */

    g_overlay.dpy = XOpenDisplay(NULL);
    if (!g_overlay.dpy) {
        OVERLAY_DBG("createOverlay: XOpenDisplay failed\n");
        return 0;
    }

    g_overlay.screen = DefaultScreen(g_overlay.dpy);

    /* Find 32-bit ARGB visual */
    g_overlay.visual = find_argb_visual(g_overlay.dpy, g_overlay.screen,
                                         &g_overlay.depth, &g_overlay.colormap);
    if (!g_overlay.visual) {
        OVERLAY_DBG("createOverlay: no ARGB visual, falling back to default\n");
        /* Fallback: use default visual (no transparency) */
        g_overlay.visual = DefaultVisual(g_overlay.dpy, g_overlay.screen);
        g_overlay.depth = DefaultDepth(g_overlay.dpy, g_overlay.screen);
        g_overlay.colormap = DefaultColormap(g_overlay.dpy, g_overlay.screen);
    }

    /* Get parent's absolute screen position — use passed coordinates directly */
    int parent_root_x = g_overlay.x;
    int parent_root_y = g_overlay.y;
    unsigned int parent_w = (unsigned int)g_overlay.w, parent_h = (unsigned int)g_overlay.h;

    /* Create overlay as a top-level window (child of root) at absolute screen position */
    XSetWindowAttributes attr;
    attr.colormap = g_overlay.colormap;
    attr.border_pixel = 0;
    attr.background_pixel = 0;
    attr.event_mask = ButtonPressMask | ButtonReleaseMask | ButtonMotionMask |
                      PointerMotionMask | StructureNotifyMask | LeaveWindowMask |
                      ExposureMask | PropertyChangeMask;
    attr.override_redirect = True;

    g_overlay.overlay = XCreateWindow(
        g_overlay.dpy, DefaultRootWindow(g_overlay.dpy),
        parent_root_x, parent_root_y, parent_w, parent_h,
        0, g_overlay.depth, InputOutput, g_overlay.visual,
        CWColormap | CWBorderPixel | CWBackPixel | CWEventMask | CWOverrideRedirect,
        &attr);

    if (!g_overlay.overlay) {
        OVERLAY_DBG("createOverlay: XCreateWindow failed\n");
        XCloseDisplay(g_overlay.dpy);
        g_overlay.dpy = NULL;
        return 0;
    }

    /* Update stored geometry */
    g_overlay.x = parent_root_x;
    g_overlay.y = parent_root_y;
    g_overlay.w = (int)parent_w;
    g_overlay.h = (int)parent_h;

    /* Set window type to POPUP_MENU for overlay behavior (WM managed, no decorations, above parent) */
    Atom type_atom = XInternAtom(g_overlay.dpy, "_NET_WM_WINDOW_TYPE", False);
    Atom popup_atom = XInternAtom(g_overlay.dpy, "_NET_WM_WINDOW_TYPE_POPUP_MENU", False);
    XChangeProperty(g_overlay.dpy, g_overlay.overlay, type_atom, XA_ATOM, 32, PropModeReplace,
                    (unsigned char *)&popup_atom, 1);

    /* Set always-on-top via EWMH state */
    Atom state_atom = XInternAtom(g_overlay.dpy, "_NET_WM_STATE", False);
    Atom above_atom = XInternAtom(g_overlay.dpy, "_NET_WM_STATE_ABOVE", False);
    Atom below_atom = XInternAtom(g_overlay.dpy, "_NET_WM_STATE_BELOW", False);
    Atom skip_atom = XInternAtom(g_overlay.dpy, "_NET_WM_STATE_SKIP_TASKBAR", False);
    Atom skip_pager = XInternAtom(g_overlay.dpy, "_NET_WM_STATE_SKIP_PAGER", False);
    Atom sticky_atom = XInternAtom(g_overlay.dpy, "_NET_WM_WINDOW_TYPE_DOCK", False);
    /* Request above + skip taskbar + skip pager */
    {
        Atom states[4] = { above_atom, skip_atom, skip_pager };
        XChangeProperty(g_overlay.dpy, g_overlay.overlay, state_atom, XA_ATOM, 32, PropModeReplace,
                        (unsigned char *)states, 3);
    }

    /* Set input region to full window (we want mouse events) */
    Region region = XCreateRegion();
    XRectangle rect = { 0, 0, g_overlay.w, g_overlay.h };
    XUnionRectWithRegion(&rect, region, region);
    XShapeCombineRegion(g_overlay.dpy, g_overlay.overlay, ShapeInput, 0, 0, region, ShapeSet);
    XDestroyRegion(region);

    /* Create GC */
    g_overlay.gc = XCreateGC(g_overlay.dpy, g_overlay.overlay, 0, NULL);

    /* Show */
    XMapWindow(g_overlay.dpy, g_overlay.overlay);
    XRaiseWindow(g_overlay.dpy, g_overlay.overlay);
    XFlush(g_overlay.dpy);

    /* Start event thread */
    g_overlay.alive = 1;
    g_overlay.visible = 1;
    g_overlay.visible_until = now_ms() + 3000;
    pthread_create(&g_overlay.event_thread, NULL, event_thread_func, NULL);

    /* Initial draw */
    draw_overlay();

    OVERLAY_DBG("createOverlay: overlay created (window=%lld)\n",
                (long long)g_overlay.overlay);

    return (jlong)g_overlay.overlay;
}

/* ------------------------------------------------------------------ */
/*  JNI: updateOverlayState                                            */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_updateOverlayState(
    JNIEnv *env, jobject thiz, jlong overlayPtr, jstring stateJson) {
    (void)thiz;
    (void)overlayPtr;

    if (!stateJson) return;
    const char *json = (*env)->GetStringUTFChars(env, stateJson, NULL);
    if (!json) return;

    /* Simple JSON parsing for needed fields */
    const char *p;

    /* position_ms */
    p = strstr(json, "\"positionMs\"");
    if (p) { p = strchr(p, ':'); if (p) { p++; g_overlay.position_ms = strtod(p, NULL); } }

    /* duration_ms */
    p = strstr(json, "\"durationMs\"");
    if (p) { p = strchr(p, ':'); if (p) { p++; g_overlay.duration_ms = strtod(p, NULL); } }

    /* paused */
    p = strstr(json, "\"paused\"");
    if (p) { p = strchr(p, ':'); if (p) { p++; g_overlay.paused = (*p == 't') ? 1 : 0; } }

    /* loading */
    p = strstr(json, "\"isLoading\"");
    if (p) { p = strchr(p, ':'); if (p) { p++; g_overlay.is_loading = (*p == 't') ? 1 : 0; } }

    /* title (simple string extraction) */
    p = strstr(json, "\"title\"");
    if (p) {
        p = strchr(p, ':');
        if (p) {
            p++; /* skip ':' */
            while (*p == ' ') p++; /* skip spaces */
            if (*p == '"') {
                p++; /* skip opening quote */
                int i = 0;
                while (*p && *p != '"' && i < (int)(sizeof(g_overlay.title) - 1)) {
                    if (*p == '\\') { p++; } /* skip escape */
                    g_overlay.title[i++] = *p++;
                }
                g_overlay.title[i] = '\0';
            }
        }
    }

    /* Store event sink reference (first call) */
    if (!g_overlay.event_sink) {
        jclass cls = (*env)->GetObjectClass(env, thiz);
        jfieldID sink_fid = (*env)->GetFieldID(env, cls, "overlayEventSink", "Lcom/nuvio/app/features/player/desktop/NativePlayerEventSink;");
        if (sink_fid) {
            jobject sink = (*env)->GetObjectField(env, thiz, sink_fid);
            if (sink) {
                g_overlay.event_sink = (*env)->NewGlobalRef(env, sink);
                jclass sink_cls = (*env)->GetObjectClass(env, sink);
                g_overlay.event_method = (*env)->GetMethodID(env, sink_cls, "onPlayerEvent",
                                                              "(Ljava/lang/String;D)V");
                OVERLAY_DBG("updateOverlayState: event sink cached\n");
            }
        }
    }

    (*env)->ReleaseStringUTFChars(env, stateJson, json);

    /* Redraw */
    draw_overlay();
}

/* ------------------------------------------------------------------ */
/*  JNI: destroyOverlay                                                */
/* ------------------------------------------------------------------ */
JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_destroyOverlay(
    JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    OVERLAY_DBG("destroyOverlay\n");

    g_overlay.alive = 0;

    if (g_overlay.event_thread) {
        pthread_join(g_overlay.event_thread, NULL);
        g_overlay.event_thread = 0;
    }

    if (g_overlay.overlay && g_overlay.dpy) {
        XUnmapWindow(g_overlay.dpy, g_overlay.overlay);
        XDestroyWindow(g_overlay.dpy, g_overlay.overlay);
        g_overlay.overlay = 0;
    }
    if (g_overlay.gc && g_overlay.dpy) {
        XFreeGC(g_overlay.dpy, g_overlay.gc);
        g_overlay.gc = 0;
    }
    if (g_overlay.colormap && g_overlay.dpy) {
        XFreeColormap(g_overlay.dpy, g_overlay.colormap);
        g_overlay.colormap = 0;
    }
    if (g_overlay.dpy) {
        XCloseDisplay(g_overlay.dpy);
        g_overlay.dpy = NULL;
    }
    if (g_overlay.event_sink) {
        (*env)->DeleteGlobalRef(env, g_overlay.event_sink);
        g_overlay.event_sink = NULL;
    }

    OVERLAY_DBG("destroyOverlay: done\n");
}
