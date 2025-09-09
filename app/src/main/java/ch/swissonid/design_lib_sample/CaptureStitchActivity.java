package ch.swissonid.design_lib_sample;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import ch.swissonid.design_lib_sample.util.ScrollStitchUtil;

public class CaptureStitchActivity extends AppCompatActivity {

    private static final int REQ_CAPTURE = 1001;

    private MediaProjectionManager mpm;
    private MediaProjection projection;
    private ImageReader imageReader;
    private VirtualDisplay virtualDisplay;
    private HandlerThread imageThread;
    private Handler imageHandler;

    private ImageView resultImage;
    private TextView statusText;
    private ScrollView demoScroll;
    private Button startBtn;

    private int captureWidth;
    private int captureHeight;
    private int captureDensityDpi;

    private final ArrayList<Bitmap> capturedFrames = new ArrayList<>();
    private volatile int pendingCaptures = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_capture_stitch);

        resultImage = findViewById(R.id.resultImage);
        statusText = findViewById(R.id.statusText);
        demoScroll = findViewById(R.id.demoScroll);
        startBtn = findViewById(R.id.startBtn);

        startBtn.setOnClickListener(v -> startFlow());
        setupMetrics();
    }

    private void setupMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            Display display = wm.getDefaultDisplay();
            display.getRealMetrics(dm);
        } else {
            dm = getResources().getDisplayMetrics();
        }
        captureWidth = dm.widthPixels;
        captureHeight = dm.heightPixels;
        captureDensityDpi = dm.densityDpi;
    }

    private void startFlow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            statusText.setText("MediaProjection requires API 21+");
            return;
        }
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            statusText.setText("MediaProjectionManager not available");
            return;
        }
        Intent intent = mpm.createScreenCaptureIntent();
        startActivityForResult(intent, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                startCapture(resultCode, data);
            } else {
                statusText.setText("User denied capture");
            }
        }
    }

    private void startCapture(int resultCode, Intent data) {
        imageThread = new HandlerThread("image-listener");
        imageThread.start();
        imageHandler = new Handler(imageThread.getLooper());

        projection = mpm.getMediaProjection(resultCode, data);
        imageReader = ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(reader -> {
            if (pendingCaptures <= 0) return;
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img == null) return;
                Bitmap bmp = imageToBitmap(img);
                if (bmp != null) {
                    capturedFrames.add(bmp);
                    pendingCaptures--;
                    runOnUiThread(() -> statusText.setText("Captured: " + capturedFrames.size()));
                }
            } finally {
                if (img != null) img.close();
            }
        }, imageHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "cap",
                captureWidth,
                captureHeight,
                captureDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                null
        );

        runSequence();
    }

    private void runSequence() {
        // Scroll the demo ScrollView and capture frames at each step
        final int steps = 6;
        final int scrollBy = Math.round(captureHeight * 0.35f);
        capturedFrames.clear();
        statusText.setText("Capturing...");

        Handler ui = new Handler(getMainLooper());
        long t = 300; // initial delay to stabilize
        for (int i = 0; i < steps; i++) {
            final int stepIndex = i;
            ui.postDelayed(() -> {
                demoScroll.smoothScrollBy(0, scrollBy);
            }, t);
            t += 450; // allow scroll animation
            ui.postDelayed(() -> requestOneCapture(), t);
            t += 350; // allow capture
        }
        // After last capture, stitch
        ui.postDelayed(this::finishAndStitch, t + 200);
    }

    private void requestOneCapture() {
        pendingCaptures++;
    }

    private void finishAndStitch() {
        stopCapture();
        if (capturedFrames.isEmpty()) {
            statusText.setText("No frames captured");
            return;
        }
        ScrollStitchUtil.StitchOptions opts = new ScrollStitchUtil.StitchOptions();
        opts.pyramidLevels = 3;
        opts.maxSearchPercent = 0.45f;
        opts.refineWindowPx = 12;
        opts.sampleXStep = 2;
        opts.sampleYStep = 2;
        opts.blendBandPx = 28;
        opts.cropTopPx = Math.round(captureHeight * 0.08f); // likely status/action bar
        opts.cropBottomPx = 0;

        ScrollStitchUtil.StitchResult sr = ScrollStitchUtil.stitch(capturedFrames, opts);
        resultImage.setImageBitmap(sr.bitmap);
        StringBuilder sb = new StringBuilder();
        sb.append("Frames: ").append(capturedFrames.size()).append("\n");
        for (ScrollStitchUtil.OffsetResult o : sr.offsets) {
            sb.append(o.offsetPx).append(", ").append(String.format("%.3f", o.confidence)).append("\n");
        }
        statusText.setText(sb.toString());
    }

    private void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        if (imageThread != null) {
            imageThread.quitSafely();
            imageThread = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCapture();
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) return null;
        Image.Plane plane = planes[0];
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * captureWidth;
        Bitmap bmp = Bitmap.createBitmap(captureWidth + rowPadding / pixelStride, captureHeight, Bitmap.Config.ARGB_8888);
        ByteBuffer buffer = plane.getBuffer();
        bmp.copyPixelsFromBuffer(buffer);
        if (bmp.getWidth() != captureWidth) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0, captureWidth, captureHeight);
            bmp.recycle();
            return cropped;
        }
        return bmp;
    }
}

