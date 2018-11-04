/*
 * Copyright 2015 Samsung Electronics Co., LTD
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.gearvrf.arpet;

import android.graphics.Color;

import org.gearvrf.GVRBoxCollider;
import org.gearvrf.GVRComponent;
import org.gearvrf.GVRContext;
import org.gearvrf.GVRDrawFrameListener;
import org.gearvrf.GVRMaterial;
import org.gearvrf.GVRMesh;
import org.gearvrf.GVRRenderData;
import org.gearvrf.GVRScene;
import org.gearvrf.GVRSceneObject;
import org.gearvrf.mixedreality.GVRPlane;
import org.gearvrf.mixedreality.GVRTrackingState;
import org.gearvrf.mixedreality.IMixedReality;
import org.gearvrf.mixedreality.IMixedReality;
import org.gearvrf.mixedreality.IPlaneEvents;
import org.gearvrf.physics.GVRRigidBody;
import org.greenrobot.eventbus.EventBus;
import org.joml.Matrix4f;

import java.util.LinkedList;

public final class PlaneHandler implements IPlaneEvents, GVRDrawFrameListener {

    private GVRContext mContext;
    private GVRScene mScene;
    private PetMain mPetMain;
    private int hsvHUE = 0;

    private boolean planeDetected = false;
    private GVRPlane petPlane = null;
    public final static String PLANE_NAME = "Plane";

    // FIXME: move this to a utils or helper class
    private static long newComponentType(Class<? extends GVRComponent> clazz) {
        long hash = (long) clazz.hashCode() << 32;
        long t = System.currentTimeMillis() & 0xfffffff;
        return t | hash;
    }

    private static long PLANEBOARD_COMP_TYPE = newComponentType(PlaneBoard.class);

    // This will create an invisible board in which the static body will be attached. This board
    // will "follow" the A.R. plane that owns it so that it will work as if this plane has physics
    // attached to it.
    private final class PlaneBoard extends GVRComponent {
        private GVRPlane plane;
        private GVRSceneObject box;

        PlaneBoard(GVRContext gvrContext) {
            super(gvrContext, 0);

            mType = PLANEBOARD_COMP_TYPE;

            box = new GVRSceneObject(gvrContext);

            GVRBoxCollider collider = new GVRBoxCollider(gvrContext);
            collider.setHalfExtents(0.5f, 0.5f, 0.5f);
            box.attachComponent(collider);
            // To touch debug
            box.setName("Plane collider");

            // Uncomment if you want a green box to appear at the center of the invisible board.
            // Notice this green box is smaller than the board
//            GVRMaterial green = new GVRMaterial(gvrContext, GVRMaterial.GVRShaderType.Phong.ID);
//            green.setDiffuseColor(0f, 1f, 0f, 1f);
//            GVRSceneObject mark = new GVRCubeSceneObject(gvrContext, true);
//            mark.getRenderData().setMaterial(green);
//            mark.getRenderData().setAlphaBlend(true);
//            mark.getTransform().setScale(0.3f, 0.3f, 1.1f);
//            box.addChildObject(mark);
        }

        @Override
        public void onAttach(GVRSceneObject newOwner) {
            plane = (GVRPlane) newOwner.getComponent(GVRPlane.getComponentType());
            if (plane == null) {
                throw new RuntimeException("PlaneBoard can only be attached to a GVRPlane");
            }
            super.onAttach(newOwner);
            mScene.addSceneObject(box);
        }

        @Override
        public void onDetach(GVRSceneObject oldOwner) {
            super.onDetach(oldOwner);

            plane = null;
            mScene.removeSceneObject(box);
        }

        private void setBoxTransform() {
            Matrix4f targetMtx = plane.getTransform().getModelMatrix4f();
            rootInvMat.mul(targetMtx, targetMtx);

            box.getTransform().setModelMatrix(targetMtx);
            box.getTransform().setScaleZ(1f);
        }

        void update() {
            if (!isEnabled()) {
                return;
            }

            setBoxTransform();

            GVRRigidBody board = (GVRRigidBody) box.getComponent(GVRRigidBody.getComponentType());
            if (board == null) {
                board = new GVRRigidBody(mContext, 0f);
                board.setRestitution(0.5f);
                board.setFriction(1.0f);
                board.setCcdMotionThreshold(0.001f);
                board.setCcdSweptSphereRadius(5f);
                box.attachComponent(board);
            }

            // This will update rigid body according to owner's transform
            board.reset(false);
        }
    }

    private LinkedList<GVRPlane> mPlanes = new LinkedList<>();

    private IMixedReality mixedReality;

    PlaneHandler(PetMain petMain) {
        mContext = petMain.getGVRContext();
        mScene = mContext.getMainScene();
        mPetMain = petMain;
    }

    private GVRSceneObject createQuadPlane() {
        GVRMesh mesh = GVRMesh.createQuad(mContext, "float3 a_position", 1.0f, 1.0f);
        GVRMaterial mat = new GVRMaterial(mContext, GVRMaterial.GVRShaderType.Phong.ID);
        GVRSceneObject polygonObject = new GVRSceneObject(mContext, mesh, mat);
        GVRBoxCollider collider = new GVRBoxCollider(mContext);
        float s = mixedReality.getARToVRScale();

        polygonObject.setName("Plane");
        hsvHUE += 35;
        float[] hsv = new float[3];
        hsv[0] = hsvHUE % 360;
        hsv[1] = 1f;
        hsv[2] = 1f;

        int c = Color.HSVToColor(50, hsv);
        mat.setDiffuseColor(Color.red(c) / 255f, Color.green(c) / 255f,
                Color.blue(c) / 255f, 0.5f);
        polygonObject.getRenderData().setMaterial(mat);
        polygonObject.getRenderData().setRenderingOrder(GVRRenderData.GVRRenderingOrder.TRANSPARENT);
        polygonObject.getRenderData().setAlphaBlend(true);
        polygonObject.getTransform().setRotationByAxis(-90, 1, 0, 0);
        polygonObject.getTransform().setScale(s, s, s);
        GVRSceneObject transformNode = new GVRSceneObject(mContext);
        transformNode.attachCollider(collider);
        transformNode.addChildObject(polygonObject);
        return transformNode;
    }

    private boolean updatePlanes = true;

    /*
     * ARCore session guaranteed to be initialized here.
     */
    @Override
    public void onStartPlaneDetection(IMixedReality mr)
    {
        mixedReality = mr;
        mPetMain.onARInit(mContext);
    }

    @Override
    public void onStopPlaneDetection(IMixedReality mr) { }

    @Override
    public void onPlaneDetected(GVRPlane plane) {
        GVRPlane.Type planeType = plane.getPlaneType();

        // Don't use planes that are downward facing, e.g ceiling
        if (planeType == GVRPlane.Type.HORIZONTAL_DOWNWARD_FACING || petPlane != null) {
            return;
        }
        GVRSceneObject planeGeo = createQuadPlane();

        planeGeo.attachComponent(plane);
        mScene.addSceneObject(planeGeo);

        PlaneBoard board = new PlaneBoard(mContext);
        planeGeo.attachComponent(board);
        mPlanes.add(plane);

        if (!planeDetected && planeType == GVRPlane.Type.HORIZONTAL_UPWARD_FACING) {
            planeDetected = true;

            // Now physics starts working and then boards must be continuously updated
            mContext.registerDrawFrameListener(this);
        }
    }

    @Override
    public void onPlaneStateChange(GVRPlane plane, GVRTrackingState trackingState) {
        if (trackingState != GVRTrackingState.TRACKING) {
            plane.setEnable(false);
        } else {
            plane.setEnable(true);
        }
    }

    @Override
    public void onPlaneMerging(GVRPlane childPlane, GVRPlane parentPlane) {
        // Will remove PlaneBoard from childPlane because this plane is not needed anymore now
        // that parentPlane "contains" childPlane
        childPlane.getOwnerObject().detachComponent(PLANEBOARD_COMP_TYPE);

        mPlanes.remove(childPlane);
    }

    public void stopTracking(GVRPlane mainPlane) {
        for (GVRPlane plane: mPlanes) {
            if (plane != mainPlane) {
                plane.setEnable(false);
            }
        }

        petPlane = mainPlane;
        petPlane.getOwnerObject().setName(PLANE_NAME);
        EventBus.getDefault().post(new PlaneDetectedEvent(petPlane));
    }

    public void resumeTracking() {
        for (GVRPlane plane: mPlanes) {
            plane.setEnable(true);
        }

        petPlane = null;
    }

    private Matrix4f rootInvMat = new Matrix4f();

    @Override
    public void onDrawFrame(float t) {
        updatePlanes = !updatePlanes;

        // Updates on the boards must be synchronized with A.R. updates but can be postponed to
        // the next cycle
        if (!updatePlanes) return;

        rootInvMat.set(mScene.getRoot().getTransform().getModelMatrix());
        rootInvMat.invert();
        for (GVRPlane plane : mPlanes) {
            ((PlaneBoard) plane.getComponent(PLANEBOARD_COMP_TYPE)).update();
        }
    }
}
