package org.gearvrf.vuforiasample;

import java.io.IOException;

import org.gearvrf.GVRAndroidResource;
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
import android.util.Log;

import static org.joml.Matrix4f.CORNER_NXNYNZ;
import static org.joml.Matrix4f.CORNER_PXPYNZ;
import static org.joml.Matrix4f.CORNER_PXPYPZ;

public class VuforiaSampleMain extends GVRMain implements GVRDrawFrameListener
{

    private static final String TAG = "gvr-vuforia";

    private GVRContext gvrContext = null;
    private GVRSceneObject[] markers = new GVRSceneObject[2];
    private GVRSceneObject passThroughObject = null;
    SampleApplicationSession vuforiaAppSession = null;

    static final int VUFORIA_CAMERA_WIDTH = 1024;
    static final int VUFORIA_CAMERA_HEIGHT = 1024;
    private int mViewWidth;
    private int mViewHeight;
    private float mFarPlane;
    private float mAspect;
    
    private volatile boolean init = false;

    private GVRScene mainScene;
    
    private float[] vuforiaMVMatrix;
    private float[] totalMVMatrix;

    boolean isReady = false;

    ModelShader modelShader = null;

    boolean isPassThroughVisible = false;

    GVRTexture passThroughTexture;
    
    @Override
    public void onInit(GVRContext gvrContext)
    {
        this.gvrContext = gvrContext;
        mainScene = gvrContext.getMainScene();
        markers[0] = createMarker1();
        markers[1] = createMarker2();
        vuforiaMVMatrix = new float[16];
        totalMVMatrix = new float[16];
        init = true;
    }

    private void setupGVRCamera(GVRCameraRig rig)
    {
        CameraCalibration cameraCalibration = CameraDevice.getInstance().getCameraCalibration();
        Matrix44F vufProj = Tool.getProjectionGL(cameraCalibration, 0.1f, 50.0f);
        Matrix4f glProj = new Matrix4f();
        GVRPerspectiveCamera cam = (GVRPerspectiveCamera) rig.getLeftCamera();
        float fovRadians;
        float fovDegrees;
        Vector3f p = new Vector3f();
        float w;
        float h;

        glProj.set(vufProj.getData());
        glProj.frustumCorner(CORNER_PXPYNZ, p);
        w = p.x;
        h = p.y;
        glProj.frustumCorner(CORNER_NXNYNZ, p);
        w -= p.x;
        h = p.y - h;
        mAspect = w / h;
        mFarPlane = 50.0f;
        fovRadians = glProj.perspectiveFov();
        fovDegrees = (float) Math.toDegrees(fovRadians) * 2.0f;
        rig.setNearClippingDistance(0.1f);
        rig.setFarClippingDistance(mFarPlane);
        cam.setFovY(fovDegrees);
        cam = (GVRPerspectiveCamera) rig.getRightCamera();
        cam.setFovY(fovDegrees);
    }

    @Override
    public void onStep()
    {

    }

    @Override
    public SplashMode getSplashMode() {
        return SplashMode.NONE;
    }

    void onVuforiaInitialized()
    {
        isReady = true;
        setupGVRCamera(mainScene.getMainCameraRig());
        gvrContext.runOnGlThread(new Runnable()
        {
            @Override
            public void run()
            {
                createCameraPassThrough();
            }
        });
    }

    private void createCameraPassThrough()
    {
        float scale = mFarPlane * 0.9f;

        passThroughTexture = new GVRRenderTexture(gvrContext, VUFORIA_CAMERA_WIDTH, VUFORIA_CAMERA_HEIGHT);
        passThroughObject = new GVRSceneObject(gvrContext, 1.0f, 1.0f,
                                               passThroughTexture, GVRMaterial.GVRShaderType.Texture.ID);
        passThroughObject.getTransform().setPosition(0.0f, 0.0f, -scale);
        passThroughObject.getTransform().setScaleX(2 * scale);
        passThroughObject.getTransform().setScaleY(2 * scale);
        mTextureUnit = new GLTextureUnit(0);

        GLTextureData textureData = new GLTextureData(passThroughTexture.getId());
        final boolean result = Renderer.getInstance().setVideoBackgroundTexture(textureData);
        if (!result)
        {
            Log.e(TAG, "Vuforia's setVideoBackgroundTexture failed");
            gvrContext.getActivity().finish();
            return;
        }

        gvrContext.registerDrawFrameListener(this);
    }

    private void initCameraPassThrough(Renderer renderer)
    {
        VideoBackgroundTextureInfo texInfo = renderer.getVideoBackgroundTextureInfo();
        mViewWidth = texInfo.getImageSize().getData()[0];
        mViewHeight = texInfo.getImageSize().getData()[1];

        if ((mViewWidth == 0) ||
            (mViewHeight == 0))
        {
            return;
        }

        // These calculate a slope for the texture coords
        float uRatio = (mViewWidth / (float) texInfo.getTextureSize().getData()[0]);
        float vRatio = (mViewHeight / (float) texInfo.getTextureSize().getData()[1]);
        float[] texCoords = { 0.0f, 0.0f, 0.0f, vRatio, uRatio, 0.0f, uRatio, vRatio };
        GVRRenderData renderData = passThroughObject.getRenderData();
        GVRMesh mesh = renderData.getMesh();

        mesh.setTexCoords(texCoords);
        renderData.setMesh(mesh);
        renderData.setDepthTest(false);
        mainScene.getMainCameraRig().addChildObject(passThroughObject);
        isPassThroughVisible = true;
    }

    private GVRSceneObject createMarker1()
    {
        try
        {
            modelShader = new ModelShader(gvrContext);
            GVRMesh teapotMesh = gvrContext.loadMesh(new GVRAndroidResource(gvrContext, "teapot.obj"));
            GVRAndroidResource file = new GVRAndroidResource(gvrContext.getContext(), "teapot_tex1.jpg");
            GVRTexture teapotTexture = gvrContext.getAssetLoader().loadTexture(file);
            GVRSceneObject teapot = new GVRSceneObject(gvrContext, teapotMesh, teapotTexture, GVRMaterial.GVRShaderType.Texture.ID);
            GVRRenderData rdata = teapot.getRenderData();

            rdata.setDepthTest(false);
            rdata.setRenderingOrder(GVRRenderingOrder.OVERLAY);
            rdata.setCullFace(GVRRenderPass.GVRCullFaceEnum.None);
            teapot.getTransform().rotateByAxis(90.0f, 1, 0, 0);
            teapot.getTransform().setPositionZ(-5.0f);
            teapot.setEnable(false);
            mainScene.getMainCameraRig().addChildObject(teapot);
            return teapot;
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    private GVRSceneObject createMarker2()
    {
        modelShader = new ModelShader(gvrContext);
        GVRCubeSceneObject cube = new GVRCubeSceneObject(gvrContext, true);
        GVRRenderData rdata = cube.getRenderData();

        rdata.getMaterial().setDiffuseColor(0.2f, 0.1f, 1.0f, 1.0f);
        rdata.setShaderTemplate(GVRPhongShader.class);
        rdata.setDepthTest(false);
        rdata.setRenderingOrder(GVRRenderingOrder.OVERLAY);
        rdata.setCullFace(GVRRenderPass.GVRCullFaceEnum.None);
        rdata.bindShader(mainScene);
        cube.setEnable(false);
        cube.getTransform().setPositionZ(-10.0f);
        mainScene.getMainCameraRig().addChildObject(cube);
        return cube;
    }

    private void hideMarkers()
    {
        markers[0].setEnable(false);
        markers[1].setEnable(false);
    }

    public void onDrawFrame(float frameTime)
    {
        Renderer renderer = Renderer.getInstance();
        State state = renderer.begin();
        renderer.updateVideoBackgroundTexture(mTextureUnit);

        if (!isPassThroughVisible)
        {
            initCameraPassThrough(renderer);
        }
        else if (state != null)
        {
            updateObjectPose(state);
        }
        renderer.end();
    }

    public void updateObjectPose(State state)
    {
        // did we find any trackables this frame?
        int numDetectedMarkers = state.getNumTrackableResults();

        if (numDetectedMarkers == 0)
        {
            hideMarkers();
            return;
        }

        for (int tIdx = 0; tIdx < numDetectedMarkers; tIdx++)
        {
            TrackableResult result = state.getTrackableResult(tIdx);
            Trackable trackable = result.getTrackable();
            int id = trackable.getId() - 1;

            if ((id < 0) || (id >= markers.length))
            {
                continue;
            }
            float scaleFactor = (((ImageTarget) trackable).getSize().getData()[0]) / 2.0f;
            Matrix44F vufMV = Tool.convertPose2GLMatrix(result.getPose());
            Matrix4f mtx = computeMarkerMatrix(vufMV, scaleFactor);

            markers[id].getTransform().setModelMatrix(mtx);
            markers[id].setEnable(true);
        }
    }

    private Matrix4f computeMarkerMatrix(Matrix44F vuforiaMV, float scaleFactor)
    {
        vuforiaMVMatrix = vuforiaMV.getData();
        Matrix4f vufMV = new Matrix4f();
        Vector3f pos = new Vector3f();
        Matrix4f total = new Matrix4f();

        total.rotate((float) Math.PI, 0, 1, 0);
        vufMV.set(vuforiaMVMatrix);
        total.mul(vufMV);
        total.getTranslation(pos);
        pos.div(scaleFactor);
        total.setTranslation(pos.x, pos.y * mAspect, pos.z);
        total.get(vuforiaMVMatrix);
        showMatrix("\nCube", vuforiaMVMatrix);
        return total;
    }

    @SuppressWarnings("unused")
    private void showMatrix(String name, float[] matrix)
    {
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
