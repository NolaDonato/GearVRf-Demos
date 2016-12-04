package org.gearvrf.vuforiasample;

import java.io.IOException;

import org.gearvrf.GVRAndroidResource;
import org.gearvrf.GVRCamera;
import org.gearvrf.GVRCameraRig;
import org.gearvrf.GVRContext;
import org.gearvrf.GVRDrawFrameListener;
import org.gearvrf.GVRMaterial;
import org.gearvrf.GVRMaterial.GVRShaderType;
import org.gearvrf.GVRPerspectiveCamera;
import org.gearvrf.GVRPhongShader;
import org.gearvrf.GVRRenderData.GVRRenderingOrder;
import org.gearvrf.GVRMesh;
import org.gearvrf.GVRRenderData;
import org.gearvrf.GVRRenderPass;
import org.gearvrf.GVRRenderTexture;
import org.gearvrf.GVRScene;
import org.gearvrf.GVRSceneObject;
import org.gearvrf.GVRMain;
import org.gearvrf.GVRTexture;
import org.gearvrf.GVRTransform;
import org.gearvrf.scene_objects.GVRCubeSceneObject;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.vuforia.CameraCalibration;
import com.vuforia.CameraDevice;
import com.vuforia.GLTextureData;
import com.vuforia.GLTextureUnit;
import com.vuforia.ImageTarget;
import com.vuforia.Matrix44F;
import com.vuforia.Renderer;
import com.vuforia.State;
import com.vuforia.Tool;
import com.vuforia.Trackable;
import com.vuforia.TrackableResult;
import com.vuforia.Vec2F;
import com.vuforia.VideoBackgroundTextureInfo;
import com.vuforia.samples.SampleApplication.SampleApplicationSession;

import android.opengl.Matrix;
import org.gearvrf.utility.Log;

public class VuforiaSampleMain extends GVRMain {

    private static final String TAG = "gvr-vuforia";

    private GVRContext gvrContext = null;
    private GVRSceneObject teapot = null;
    private GVRSceneObject passThroughObject = null;
    private Renderer mRenderer = null;
    SampleApplicationSession vuforiaAppSession = null;

    static final int VUFORIA_CAMERA_WIDTH = 1024;
    static final int VUFORIA_CAMERA_HEIGHT = 1024;
    
    private volatile boolean init = false;

    private GVRScene mainScene;
    private GVRSceneObject mTestObject;
    
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
        GVRCameraRig rig = mainScene.getMainCameraRig();
        GVRPerspectiveCamera cam = (GVRPerspectiveCamera) rig.getLeftCamera();
        float fov = getFOVY();

        rig.setNearClippingDistance(2);
        rig.setFarClippingDistance(2000);
        cam.setFovY(fov);
        cam = (GVRPerspectiveCamera) rig.getRightCamera();
        cam.setFovY(fov);
        GVRMaterial blue = new GVRMaterial(gvrContext, GVRMaterial.GVRShaderType.BeingGenerated.ID);
        GVRSceneObject cube = new GVRCubeSceneObject(gvrContext, true, blue);

        mTestObject = new GVRSceneObject(gvrContext);
        cube.getTransform().setScale(30, 30, 30);
        cube.getRenderData().setShaderTemplate(GVRPhongShader.class);
        cube.getRenderData().setRenderingOrder(GVRRenderingOrder.OVERLAY);
        blue.setDiffuseColor(0, 0, 1, 1);
        rig.getOwnerObject().getTransform().rotateByAxis(180, 0, 1, 0);
        mainScene.addSceneObject(mTestObject);
        createTeaPotObject();

        vuforiaMVMatrix = new float[16];
        totalMVMatrix = new float[16];

        initRendering();

        init = true;
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

    float getFOVY()
    {
        CameraCalibration cameraCalibration = CameraDevice.getInstance().getCameraCalibration();

        Vec2F size = cameraCalibration.getSize();
        Vec2F focalLength = cameraCalibration.getFocalLength();

        double fovRadians = 2 * Math.atan(0.5f * size.getData()[1] / focalLength.getData()[1]);
        double fovDegrees = fovRadians * 180.0f / Math.PI;
        return (float) fovDegrees;
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
        passThroughObject = new GVRSceneObject(gvrContext, 1.0f, 1.0f);
        passThroughObject.getTransform().rotateByAxis(180, 0, 1, 0);
        passThroughObject.getTransform().setPosition(0.0f, 0.0f, 100.0f);
        passThroughObject.getTransform().setScaleX(200f);
        passThroughObject.getTransform().setScaleY(200f);

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

                    if ((texInfo.getImageSize().getData()[0] == 0)
                            || (texInfo.getImageSize().getData()[1] == 0)) {
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
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showTeapot() {
        if (teapotVisible == false) {
            mainScene.addSceneObject(teapot);
            teapotVisible = true;
        }
    }

    private void hideTeapot() {
        if (teapotVisible) {
            mainScene.removeSceneObject(teapot);
            teapotVisible = false;
        }
    }

    public void updateObjectPose() {
        State state = mRenderer.begin();

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
                Matrix44F modelViewMatrix_Vuforia = Tool.convertPose2GLMatrix(result.getPose());
                float scaleFactor = (((ImageTarget) trackable).getSize().getData()[0])/2.0f;

                vuforiaMVMatrix = modelViewMatrix_Vuforia.getData();
                Matrix4f modelMatrix = new Matrix4f();
                modelMatrix.set(vuforiaMVMatrix);
                GVRTransform t = mTestObject.getTransform();
                t.setModelMatrix(modelMatrix);
                Log.e("VUFORIA", "pos = %f %f %f", t.getPositionX(), t.getPositionY(), t.getPositionZ());

                Matrix.rotateM(vuforiaMVMatrix, 0, 90, 1, 0, 0);
                Matrix.scaleM(vuforiaMVMatrix, 0, scaleFactor, scaleFactor, scaleFactor);
                Matrix.multiplyMM(totalMVMatrix, 0,
                        vuforiaAppSession.getProjectionMatrix().getData(), 0, vuforiaMVMatrix, 0);

                teapot.getRenderData().getMaterial().setMat4(ModelShader.MVP_KEY,
                        totalMVMatrix[0], totalMVMatrix[1], totalMVMatrix[2], totalMVMatrix[3],
                        totalMVMatrix[4], totalMVMatrix[5], totalMVMatrix[6], totalMVMatrix[7],
                        totalMVMatrix[8], totalMVMatrix[9], totalMVMatrix[10], totalMVMatrix[11],
                        totalMVMatrix[12], totalMVMatrix[13], totalMVMatrix[14], totalMVMatrix[15]);
                showTeapot();
                break;
            } else {
                hideTeapot();
            }
        }
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
