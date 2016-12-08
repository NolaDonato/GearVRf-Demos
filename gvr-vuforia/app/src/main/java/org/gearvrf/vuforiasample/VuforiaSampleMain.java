package org.gearvrf.vuforiasample;

import java.io.IOException;
import java.util.List;

import org.gearvrf.GVRAndroidResource;
import org.gearvrf.GVRCameraRig;
import org.gearvrf.GVRContext;
import org.gearvrf.GVRDrawFrameListener;
import org.gearvrf.GVRMaterial;
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
import org.joml.Vector4f;

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
import com.vuforia.VideoBackgroundTextureInfo;
import com.vuforia.samples.SampleApplication.SampleApplicationSession;

import android.util.Log;

import static org.joml.Matrix4f.CORNER_NXNYNZ;
import static org.joml.Matrix4f.CORNER_PXPYNZ;

public class VuforiaSampleMain extends GVRMain implements GVRDrawFrameListener
{

    private static final String TAG = "gvr-vuforia";

    private GVRContext gvrContext = null;
    private GVRSceneObject[] markers = new GVRSceneObject[2];
    private GVRSceneObject passThroughObject = null;
    SampleApplicationSession vuforiaAppSession = null;

    static final int VUFORIA_CAMERA_WIDTH = 1024;
    static final int VUFORIA_CAMERA_HEIGHT = 1024;
    private float mFarPlane;
    private float mAspect;
    private GVRScene mainScene;
    private float[] vuforiaMVMatrix;
    private Matrix4f gvrProjMapping;
    boolean isReady = false;
    boolean isPassThroughVisible = false;
    GVRTexture passThroughTexture;
    GVRSceneObject mRoot;

    @Override
    public void onInit(GVRContext gvrContext)
    {
        GVRSceneObject marker;

        this.gvrContext = gvrContext;
        mainScene = gvrContext.getMainScene();
        //mRoot = mainScene.getMainCameraRig().getHeadTransformObject();
        mRoot = mainScene.getRoot();
        marker = createMarker1();
        if (marker != null)
        {
            markers[0] = marker;
        }
        marker = createMarker2();
        if (marker != null)
        {
            markers[1] = marker;
        }
        vuforiaMVMatrix = new float[16];
        mainScene.setFrustumCulling(false);
     }

    private void setupGVRCamera(GVRCameraRig rig)
    {
        float near = 10.0f;
        mFarPlane = 5000.0f;
        CameraCalibration cameraCalibration = CameraDevice.getInstance().getCameraCalibration();
        Matrix44F vufProj = Tool.getProjectionGL(cameraCalibration, near, mFarPlane);
        GVRPerspectiveCamera cam = (GVRPerspectiveCamera) rig.getLeftCamera();
        float fovRadians;
        float fovDegrees;
        Vector3f p = new Vector3f();
        float w;
        float h;

        Matrix4f glProj = new Matrix4f();
        glProj.set(vufProj.getData());
        glProj.frustumCorner(CORNER_PXPYNZ, p);
        w = p.x;
        h = -p.y;
        glProj.frustumCorner(CORNER_NXNYNZ, p);
        w -= p.x;
        h += p.y;
        mAspect = w / h;
        fovRadians = glProj.perspectiveFov();
        fovDegrees = (float) Math.toDegrees(fovRadians) * 2.0f;
        rig.setNearClippingDistance(near);
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
        float viewWidth = texInfo.getImageSize().getData()[0];
        float viewHeight = texInfo.getImageSize().getData()[1];

        if ((viewWidth == 0) ||
            (viewHeight == 0))
        {
            return;
        }

        // These calculate a slope for the texture coords
        float uRatio = (viewWidth / (float) texInfo.getTextureSize().getData()[0]);
        float vRatio = (viewHeight / (float) texInfo.getTextureSize().getData()[1]);
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
            GVRMesh teapotMesh = gvrContext.loadMesh(new GVRAndroidResource(gvrContext, "teapot.obj"));
            GVRAndroidResource file = new GVRAndroidResource(gvrContext.getContext(), "teapot_tex1.jpg");
            GVRTexture teapotTexture = gvrContext.getAssetLoader().loadTexture(file);
            GVRSceneObject teapot = new GVRSceneObject(gvrContext, teapotMesh, teapotTexture, GVRMaterial.GVRShaderType.Texture.ID);
            GVRSceneObject marker1 = new GVRSceneObject(gvrContext);

            marker1.addChildObject(teapot);
            setupModel(marker1, 100.0f);
            mRoot.addChildObject(marker1);
            return marker1;
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    private GVRSceneObject createMarker2()
    {
        GVRSceneObject marker2 = new GVRSceneObject(gvrContext);

        try
        {
            marker2 = gvrContext.getAssetLoader().loadModel(marker2, "astro_boy.dae", null);
        }
        catch (IOException ex)
        {
            Log.e(TAG, ex.getMessage());
            return null;
        }
        mRoot.addChildObject(marker2);
        setupModel(marker2, 50.0f);
        return marker2;
    }

    private void setupModel(GVRSceneObject marker, float scale)
    {
        GVRSceneObject.BoundingVolume bv = marker.getBoundingVolume();
        List<GVRRenderData> rdatas = marker.getAllComponents(GVRRenderData.getComponentType());
        GVRSceneObject root = marker.getChildByIndex(0);

        marker.setEnable(false);
        float sf = scale;
        root.getTransform().setScale(sf, sf, sf);
        root.getTransform().rotateByAxis(90.0f, 1, 0, 0);
        bv = marker.getBoundingVolume();
        root.getTransform().setPosition(-bv.center.x, -bv.center.y, -bv.center.z);
        for (GVRRenderData rdata : rdatas)
        {
            rdata.setDepthTest(false);
            rdata.setRenderingOrder(GVRRenderingOrder.OVERLAY);
            rdata.setCullFace(GVRRenderPass.GVRCullFaceEnum.None);
        }
    }

    private void hideMarkers()
    {
        for (GVRSceneObject marker : markers)
        {
            if (marker != null)
            {
                marker.setEnable(false);
            }
        }
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

        hideMarkers();
        for (int tIdx = 0; tIdx < numDetectedMarkers; tIdx++)
        {
            TrackableResult result = state.getTrackableResult(tIdx);
            Trackable trackable = result.getTrackable();
            int id = trackable.getId() - 1;

            if ((id < 0) || (id >= markers.length) || (markers[id] == null))
            {
                continue;
            }

            Matrix44F vufMV = Tool.convertPose2GLMatrix(result.getPose());
            Matrix4f mtx = computeMarkerMatrix(vufMV);

            markers[id].getTransform().setModelMatrix(mtx);
            markers[id].setEnable(true);
        }
    }

    private Matrix4f computeMarkerMatrix(Matrix44F vuforiaMV)
    {
        vuforiaMVMatrix = vuforiaMV.getData();
        Matrix4f vufMV = new Matrix4f();
        Vector4f v = new Vector4f(1, 0, 0, 0);
        Matrix4f total = new Matrix4f();
        Matrix4f gvrView = mainScene.getMainCameraRig().getHeadTransform().getModelMatrix4f();
        float[] temp = new float[16];

        total.mul(gvrView);
        total.rotate((float) Math.PI, 1, 0, 0);
        total.scale(1.0f / mAspect, 1.0f, 1.0f);
        vufMV.set(vuforiaMV.getData());
        showMatrix("\nVuforia Pose Matrix", vuforiaMV.getData());
        total.mul(vufMV);
        //total.getTranslation(v);
        //total.setTranslation(v.x, v.y, v.z);
        total.get(temp);
        showMatrix("\nMarker Matrix", temp);
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
