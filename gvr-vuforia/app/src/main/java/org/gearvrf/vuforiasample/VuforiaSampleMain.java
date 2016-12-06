package org.gearvrf.vuforiasample;

import java.io.IOException;

import org.gearvrf.GVRAndroidResource;
import org.gearvrf.GVRCameraRig;
import org.gearvrf.GVRContext;
import org.gearvrf.GVRDrawFrameListener;
import org.gearvrf.GVRMaterial;
import org.gearvrf.GVRMaterial.GVRShaderType;
import org.gearvrf.GVRPerspectiveCamera;
import org.gearvrf.GVRRenderData.GVRRenderingOrder;
import org.gearvrf.GVRMesh;
import org.gearvrf.GVRRenderData;
import org.gearvrf.GVRRenderPass;
import org.gearvrf.GVRRenderTexture;
import org.gearvrf.GVRScene;
import org.gearvrf.GVRSceneObject;
import org.gearvrf.GVRMain;
import org.gearvrf.GVRTexture;
import org.gearvrf.scene_objects.GVRCubeSceneObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.vuforia.GLTextureData;
import com.vuforia.GLTextureUnit;
import com.vuforia.ImageTarget;
import com.vuforia.Matrix44F;
import com.vuforia.Renderer;
import com.vuforia.State;
import com.vuforia.Tool;
import com.vuforia.Trackable;
import com.vuforia.TrackableResult;
import com.vuforia.VideoBackgroundTextureInfo;
import com.vuforia.samples.SampleApplication.SampleApplicationSession;

import android.graphics.Point;
import android.opengl.Matrix;
import android.util.Log;

public class VuforiaSampleMain extends GVRMain {

    private static final String TAG = "gvr-vuforia";

    private GVRContext gvrContext = null;
    private GVRSceneObject teapot = null;
    private GVRSceneObject marker2 = null;
    private GVRSceneObject passThroughObject = null;
    private Renderer mRenderer = null;
    SampleApplicationSession vuforiaAppSession = null;

    static final int VUFORIA_CAMERA_WIDTH = 1024;
    static final int VUFORIA_CAMERA_HEIGHT = 1024;
    private int mViewWidth;
    private int mViewHeight;
    
    private volatile boolean init = false;

    private GVRScene mainScene;
    
    private float[] vuforiaMVMatrix;
    private float[] totalMVMatrix;

    private boolean teapotVisible = false;
    boolean isReady = false;

    ModelShader modelShader = null;

    boolean isPassThroughVisible = false;

    GVRTexture passThroughTexture;
    
    @Override
    public void onInit(GVRContext gvrContext) {
        this.gvrContext = gvrContext;
        mainScene = gvrContext.getMainScene();

        setupGVRCamera(mainScene.getMainCameraRig());
        createMarker2();
        createTeaPotObject();

        vuforiaMVMatrix = new float[16];
        totalMVMatrix = new float[16];

        initRendering();

        init = true;
    }

    private void setupGVRCamera(GVRCameraRig rig)
    {
        VideoBackgroundTextureInfo texInfo = Renderer.getInstance().getVideoBackgroundTextureInfo();
        GVRPerspectiveCamera cam = (GVRPerspectiveCamera) rig.getLeftCamera();
        float fov = 66.0f;

        rig.setNearClippingDistance(0.1f);
        rig.setFarClippingDistance(50);
        cam.setFovY(fov);
        cam = (GVRPerspectiveCamera) rig.getLeftCamera();
        cam.setFovY(fov);
    }

    private void initRendering() {
        mRenderer = Renderer.getInstance();
    }

    @Override
    public void onStep() {
        if (!isReady)
            return;

        updateObjectPose();
    }

    @Override
    public SplashMode getSplashMode() {
        return SplashMode.NONE;
    }

    void onVuforiaInitialized() {
        gvrContext.runOnGlThread(new Runnable() {
            @Override
            public void run() {
                createCameraPassThrough();
            }
        });
    }

    public boolean isInit() {
        return init;
    }

    private void createCameraPassThrough() {
        float scale = 40.0f;

        passThroughObject = new GVRSceneObject(gvrContext, 1.0f, 1.0f);

        passThroughObject.getTransform().setPosition(0.0f, 0.0f, -scale);
        passThroughObject.getTransform().setScaleX(2 * scale);
        passThroughObject.getTransform().setScaleY(2 * scale);

        passThroughTexture = new GVRRenderTexture(gvrContext,
                VUFORIA_CAMERA_WIDTH, VUFORIA_CAMERA_HEIGHT);

        mTextureUnit = new GLTextureUnit(0);
        GLTextureData textureData = new GLTextureData(passThroughTexture.getId());
        final boolean result = Renderer.getInstance().setVideoBackgroundTexture(textureData);
        if (!result) {
            Log.e(TAG, "Vuforia's setVideoBackgroundTexture failed");
            gvrContext.getActivity().finish();
            return;
        }

        gvrContext.registerDrawFrameListener(new GVRDrawFrameListener() {
            @Override
            public void onDrawFrame(float frameTime) {
                Renderer.getInstance().begin();
                Renderer.getInstance().updateVideoBackgroundTexture(mTextureUnit);

                if (!isPassThroughVisible) {

                    VideoBackgroundTextureInfo texInfo = Renderer.getInstance()
                            .getVideoBackgroundTextureInfo();
                    mViewWidth = texInfo.getImageSize().getData()[0];
                    mViewHeight = texInfo.getImageSize().getData()[0];

                    if ((mViewWidth == 0) ||
                        (mViewHeight == 0)) {
                        Renderer.getInstance().end();
                        return;
                    }

                    // These calculate a slope for the texture coords
                    float uRatio = ((float) texInfo.getImageSize().getData()[0] / (float) texInfo
                            .getTextureSize().getData()[0]);
                    float vRatio = ((float) texInfo.getImageSize().getData()[1] / (float) texInfo
                            .getTextureSize().getData()[1]);

                    GVRRenderData renderData = passThroughObject.getRenderData();
                    GVRMaterial material = new GVRMaterial(gvrContext);
                    renderData.setMaterial(material);
                    material.setMainTexture(passThroughTexture);
                    material.setShaderType(GVRShaderType.Texture.ID);

                    float[] texCoords = { 0.0f, 0.0f, 0.0f, vRatio, uRatio, 0.0f, uRatio, vRatio };
                    GVRMesh mesh = renderData.getMesh();
                    mesh.setTexCoords(texCoords);
                    renderData.setMesh(mesh);
                    renderData.setDepthTest(false);

                    mainScene.getMainCameraRig().addChildObject(passThroughObject);
                    isPassThroughVisible = true;
                }

                Renderer.getInstance().end();
            }
        });
    }

    private void createTeaPotObject() {
        try {
            modelShader = new ModelShader(gvrContext);
            GVRMesh teapotMesh = gvrContext.loadMesh(
                    new GVRAndroidResource(gvrContext, "teapot.obj"));
            GVRTexture teapotTexture = gvrContext.loadTexture(
                    new GVRAndroidResource(gvrContext.getContext(), "teapot_tex1.jpg"));
            teapot = new GVRSceneObject(gvrContext, teapotMesh);

            GVRMaterial material = new GVRMaterial(gvrContext, modelShader.getShaderId());
            material.setTexture(ModelShader.TEXTURE_KEY, teapotTexture);

            teapot.getRenderData().setMaterial(material);
            teapot.getRenderData().setDepthTest(false);
            teapot.getRenderData().setRenderingOrder(GVRRenderingOrder.OVERLAY);
            teapot.getRenderData().setCullFace(GVRRenderPass.GVRCullFaceEnum.None);
            mainScene.addSceneObject(teapot);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void createMarker2() {
        modelShader = new ModelShader(gvrContext);
        GVRCubeSceneObject cube = new GVRCubeSceneObject(gvrContext, true);
        GVRTexture teapotTexture = gvrContext.getAssetLoader().loadTexture(
                new GVRAndroidResource(gvrContext.getContext(), "teapot_tex1.jpg"));

        cube.getRenderData().getMaterial().setMainTexture(teapotTexture);
        cube.getRenderData().setDepthTest(false);
        cube.getRenderData().setRenderingOrder(GVRRenderingOrder.OVERLAY);
        cube.getRenderData().setCullFace(GVRRenderPass.GVRCullFaceEnum.None);
        marker2 = cube;
        cube.setEnable(false);
        mainScene.getMainCameraRig().addChildObject(marker2);
    }

    private void showTeapot() {
        if (teapotVisible == false) {
            //teapot.setEnable(true);
            marker2.setEnable(true);
            teapotVisible = true;
        }
    }

    private void hideTeapot() {
        if (teapotVisible) {
            teapot.setEnable(false);
            marker2.setEnable(false);
            teapotVisible = false;
        }
    }

    public void updateObjectPose() {
        State state = mRenderer.begin();

        if (state == null)
        {
            return;
        }
        // did we find any trackables this frame?
        int numDetectedMarkers = state.getNumTrackableResults();

        if (numDetectedMarkers == 0) {
            hideTeapot();
            return;
        }

        for (int tIdx = 0; tIdx < numDetectedMarkers; tIdx++) {
            TrackableResult result = state.getTrackableResult(tIdx);
            Trackable trackable = result.getTrackable();

            if (trackable.getId() == 1 || trackable.getId() == 2) {
                float scaleFactor = (((ImageTarget) trackable).getSize().getData()[0])/2.0f;
                Matrix44F modelViewMatrix_Vuforia = Tool.convertPose2GLMatrix(result.getPose());

                computeTeapotMatrix(modelViewMatrix_Vuforia, scaleFactor);
                computeMarkerMatrix(modelViewMatrix_Vuforia, scaleFactor);
                showTeapot();
                break;
            } else {
                hideTeapot();
            }
        }
    }

    private void computeTeapotMatrix(Matrix44F vuforiaMV, float scaleFactor)
    {
        vuforiaMVMatrix = vuforiaMV.getData();

        Matrix.rotateM(vuforiaMVMatrix, 0, 90, 1, 0, 0);
        Matrix.scaleM(vuforiaMVMatrix, 0, scaleFactor, scaleFactor, scaleFactor);

        showMatrix("\nTeapot", vuforiaMVMatrix);

        Matrix.multiplyMM(totalMVMatrix, 0,
                          vuforiaAppSession.getProjectionMatrix().getData(), 0, vuforiaMVMatrix, 0);

        teapot.getRenderData().getMaterial().setMat4(ModelShader.MVP_KEY,
                                                     totalMVMatrix[0], totalMVMatrix[1], totalMVMatrix[2], totalMVMatrix[3],
                                                     totalMVMatrix[4], totalMVMatrix[5], totalMVMatrix[6], totalMVMatrix[7],
                                                     totalMVMatrix[8], totalMVMatrix[9], totalMVMatrix[10], totalMVMatrix[11],
                                                     totalMVMatrix[12], totalMVMatrix[13], totalMVMatrix[14], totalMVMatrix[15]);
    }

    private void computeMarkerMatrix(Matrix44F vuforiaMV, float scaleFactor)
    {
        vuforiaMVMatrix = vuforiaMV.getData();
        Matrix4f vufMV = new Matrix4f();
        Vector3f pos = new Vector3f();
        Matrix4f total = new Matrix4f();

        total.rotate((float) Math.PI, 1, 0, 0);
        vufMV.set(vuforiaMVMatrix);
        total.mul(vufMV);
        total.getTranslation(pos);
        pos.div(scaleFactor);
        total.setTranslation(pos.x, pos.y * 1.77f, pos.z);
        total.get(vuforiaMVMatrix);
        showMatrix("\nCube", vuforiaMVMatrix);
        marker2.getTransform().setModelMatrix(total);
    }

    @SuppressWarnings("unused")
    private void showMatrix(String name, float[] matrix) {
        Log.d(TAG, name);
        Log.d(TAG, String.format("%5.2f %5.2f %5.2f %5.2f", matrix[0],
                matrix[4], matrix[8], matrix[12]));
        Log.d(TAG, String.format("%5.2f %5.2f %5.2f %5.2f", matrix[1],
                matrix[5], matrix[9], matrix[13]));
        Log.d(TAG, String.format("%5.2f %5.2f %5.2f %5.2f", matrix[2],
                matrix[6], matrix[10], matrix[14]));
        Log.d(TAG, String.format("%5.2f %5.2f %5.2f %5.2f", matrix[3],
                matrix[7], matrix[11], matrix[15]));
        Log.d(TAG, "\n");
    }

    private GLTextureUnit mTextureUnit;
}
