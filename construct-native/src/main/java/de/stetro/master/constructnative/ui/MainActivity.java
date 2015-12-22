package de.stetro.master.constructnative.ui;


import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.projecttango.rajawali.ar.TangoRajawaliView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import de.stetro.master.constructnative.R;
import de.stetro.master.constructnative.rendering.PointCloudARRenderer;
import de.stetro.master.constructnative.util.PointCloudManager;

public class MainActivity extends BaseActivity implements View.OnTouchListener {
    private static final String tag = MainActivity.class.getSimpleName();
    private TangoRajawaliView glView;
    private PointCloudARRenderer renderer;
    private PointCloudManager pointCloudManager;
    private Tango tango;
    private boolean isConnected;
    private boolean isPermissionGranted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadAssetLibrary("libflann.so.1.8");
        loadAssetLibrary("libflann_cpp.so.1.8");
        glView = new TangoRajawaliView(this);
        renderer = new PointCloudARRenderer(this);
        glView.setSurfaceRenderer(renderer);
        glView.setOnTouchListener(this);
        setContentView(R.layout.activity_main);
        RelativeLayout wrapper = (RelativeLayout) findViewById(R.id.wrapper_view);
        tango = new Tango(this);
        startActivityForResult(
                Tango.getRequestPermissionIntent(Tango.PERMISSIONTYPE_MOTION_TRACKING),
                Tango.TANGO_INTENT_ACTIVITYCODE);
        wrapper.addView(glView);

    }

    private void loadAssetLibrary(String libraryName) {
        try {
            InputStream in = getAssets().open(libraryName);
            File file = new File(Environment.getExternalStorageDirectory(), libraryName);
            FileOutputStream outputStream = new FileOutputStream(file);
            int read = 0;
            byte[] bytes = new byte[1024];
            while ((read = in.read(bytes)) != -1) {
                outputStream.write(bytes, 0, read);
            }
            System.load(file.getPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == Tango.TANGO_INTENT_ACTIVITYCODE) {
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(this, "Motion Tracking Permissions Required!", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                startAugmentedReality();
                isPermissionGranted = true;
            }
        }
    }

    private void startAugmentedReality() {
        if (!isConnected) {
            isConnected = true;
            glView.connectToTangoCamera(tango, TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
            TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
            config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
            config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
            tango.connect(config);

            ArrayList<TangoCoordinateFramePair> framePairs = new ArrayList<>();
            tango.connectListener(framePairs, new Tango.OnTangoUpdateListener() {
                @Override
                public void onPoseAvailable(TangoPoseData pose) {
                }

                @Override
                public void onFrameAvailable(int cameraId) {
                    if (cameraId == TangoCameraIntrinsics.TANGO_CAMERA_COLOR) {
                        glView.onFrameAvailable();
                    }
                }

                @Override
                public void onXyzIjAvailable(TangoXyzIjData xyzIj) {
                    TangoCoordinateFramePair framePair = new TangoCoordinateFramePair(
                            TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                            TangoPoseData.COORDINATE_FRAME_DEVICE);
                    TangoPoseData cloudPose = tango.getPoseAtTime(xyzIj.timestamp, framePair);

                    pointCloudManager.updateXyzIjData(xyzIj, cloudPose);
                }

                @Override
                public void onTangoEvent(TangoEvent event) {

                }
            });

            setupExtrinsic();

            pointCloudManager = new PointCloudManager(tango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR));
            renderer.setPointCloudManager(pointCloudManager);
        }
    }

    private void setupExtrinsic() {
        // Create Camera to IMU Transform
        TangoCoordinateFramePair framePair = new TangoCoordinateFramePair();
        framePair.baseFrame = TangoPoseData.COORDINATE_FRAME_IMU;
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_COLOR;
        TangoPoseData imuTrgbPose = tango.getPoseAtTime(0.0, framePair);

        // Create Device to IMU Transform
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_DEVICE;
        TangoPoseData imuTdevicePose = tango.getPoseAtTime(0.0, framePair);

        // Create Depth camera to IMU Transform
        framePair.targetFrame = TangoPoseData.COORDINATE_FRAME_CAMERA_DEPTH;
        TangoPoseData imuTdepthPose = tango.getPoseAtTime(0.0, framePair);

        renderer.setupExtrinsics(imuTdevicePose, imuTrgbPose, imuTdepthPose);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.activity_main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.activity_main_menu_toggle_pointcloud:
                renderer.togglePointCloudVisibility();
                return true;
            case R.id.activity_main_menu_delete:
                renderer.clearPoints();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isConnected) {
            glView.disconnectCamera();
            tango.disconnect();
            isConnected = false;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isConnected && isPermissionGranted) {
            startAugmentedReality();
        }
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
            renderer.capturePoints();
        }
        return true;
    }
}