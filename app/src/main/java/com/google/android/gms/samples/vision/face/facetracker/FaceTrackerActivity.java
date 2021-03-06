/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.gms.samples.vision.face.facetracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.speech.tts.TextToSpeech;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.samples.vision.face.facetracker.models.User;
import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.android.gms.samples.vision.face.facetracker.ui.camera.CameraSourcePreview;
import com.google.android.gms.samples.vision.face.facetracker.ui.camera.GraphicOverlay;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.JsonObject;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
//import com.twilio.Twilio;
//import com.twilio.rest.api.v2010.account.Message;
//import com.twilio.type.PhoneNumber;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * Activity for the face tracker app.  This app detects faces with the rear facing camera, and draws
 * overlay graphics to indicate the position, size, and ID of each face.
 */
public final class FaceTrackerActivity extends AppCompatActivity {
    private static final String TAG = "FaceTracker";

    private CameraSource mCameraSource = null;

    private CameraSourcePreview mPreview;
    private GraphicOverlay mGraphicOverlay;

    private static final int RC_HANDLE_GMS = 9001;
    // permission request codes need to be < 256
    private static final int RC_HANDLE_CAMERA_PERM = 2;

    // Camera trigger options
    private static final long MINIMUM_PHOTO_DELAY_MS = 2000;
    public static final float FACE_PROXIMITY_TRIGGER = 200f;
    public static final int MAX_RECOGNITION_ATTEMPTS = 4;
    private long mLastTriggerTime;

    //Firebase Variables
    DatabaseReference mDatabase = FirebaseDatabase.getInstance().getReference();
    DatabaseReference mWhoOrdered = mDatabase.child("who_ordered");
    DatabaseReference mPackageArrived = mDatabase.child("package_arrived");
    DatabaseReference mPackageUnlock = mDatabase.child("package_unlock");

    //KAIROS Variables
    private static final String RECOGNIZE_ENDPOINT = "https://api.kairos.com/recognize";
    private static final String KAIROS_APP_KEY = "44adf4e638564316508a6132c5433136";
    private static final String KAIROS_APP_ID = "dd62b435";
    private static final String GALLERY_ID = "GalleryOne";

    //TWILIO
    public static final String TWILIO_ACCOUNT_SID = "ACd3892fd1fce2acec2e5824c6fbade95d";
    public static final String TWILIO_AUTH_TOKEN = "003b7c221c616c569f611d435cc63dc9";


    //MISC Vars
    private static TextToSpeech mTextToSpeechObj;
    private User mUser;
    private boolean mAllowImage;
    private int mRecognitionAttempts;
    private ProgressBar mProgressBar;


    //==============================================================================================
    // Activity Methods
    //==============================================================================================

    /**
     * Initializes the UI and initiates the creation of a face detector.
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        initializeServices();

        setContentView(R.layout.main);
        mProgressBar = (ProgressBar) findViewById(R.id.loading_api);

        initPurchaseListener();
    }

    private void initializeServices() {
        mTextToSpeechObj = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.ERROR){
                    mTextToSpeechObj.setLanguage(Locale.UK);
                    Log.i(TAG, "TTS initialized");
                }
            }
        });

    }

    private void initPurchaseListener() {
        mWhoOrdered.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {

                if((boolean) dataSnapshot.child("order_made").getValue()){

                    mWhoOrdered.removeEventListener(this);

//                    mWhoOrdered.child("order_made").setValue(false);

                    mUser = new User(dataSnapshot.child("name").getValue().toString(),
                            dataSnapshot.child("phone_number").getValue().toString(),
                            dataSnapshot.child("address").getValue().toString());


                    String speech = "Purchase made. Sending package to " + mUser.getName() + " at " + mUser.getAddress();

                    mTextToSpeechObj.speak(speech, TextToSpeech.QUEUE_ADD, null, "purchase_made");
                    initArrivalListener();
                }

            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void initArrivalListener() {
        mPackageArrived.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if((boolean) dataSnapshot.getValue()){
                    mPackageArrived.removeEventListener(this);
                    makeTwilioCalls();
                    mWhoOrdered.child("order_made").setValue(false);
                    mPackageArrived.setValue(false);
                    String speech = "Now texting " + mUser.getName() + " to notify them that their package has arrived.";

                    mTextToSpeechObj.speak(speech, TextToSpeech.QUEUE_ADD, null, "messaging_user");
                    initCamera();
                    new Handler().postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            mTextToSpeechObj.speak("Please look at the camera", TextToSpeech.QUEUE_ADD, null, "messaging_user");
                        }
                    }, 6000);


                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {

            }
        });
    }

    private void makeTwilioCalls() {

        String url = "https://api.twilio.com/2010-04-01/Accounts/"+TWILIO_ACCOUNT_SID+"/SMS/Messages";
        String base64EncodedCredentials = "Basic " + Base64.encodeToString((TWILIO_ACCOUNT_SID + ":" + TWILIO_AUTH_TOKEN).getBytes(), Base64.NO_WRAP);
        String fromNumber = "+12897686440";
//        String toNumber = "+1" + mUser.getPhoneNumber();
        String toNumber = "+12892080810";

        JsonObject json = new JsonObject();

        json.addProperty("From", fromNumber);
        json.addProperty("To", toNumber);
        json.addProperty("Body", "Hello there");

        Ion.with(getApplicationContext())
                .load(url)
                .addHeader("Authorization", base64EncodedCredentials)
                .setBodyParameter("From", fromNumber)
                .setBodyParameter("To", toNumber)
                .setBodyParameter("Body", "Your package has arrived! Head outside to pick it up!")
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        Log.i(TAG, result);
                    }
                });
    }

    private void initCamera() {
        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay) findViewById(R.id.faceOverlay);

        mRecognitionAttempts = 0;
        mAllowImage = true;

        // Check for the camera permission before accessing the camera.  If the
        // permission is not granted yet, request permission.
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource();
        } else {
            requestCameraPermission();
        }
        mLastTriggerTime = System.currentTimeMillis();
        startCameraSource();

    }


    /**
     * Handles the requesting of the camera permission.  This includes
     * showing a "Snackbar" message of why the permission is needed then
     * sending the request.
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};

        if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    /**
     * Creates and starts the camera.  Note that this uses a higher resolution in comparison
     * to other detection examples to enable the barcode detector to detect small barcodes
     * at long distances.
     */
    private void createCameraSource() {

        Context context = getApplicationContext();
        FaceDetector detector = new FaceDetector.Builder(context)
                .setClassificationType(FaceDetector.ALL_CLASSIFICATIONS)
                .build();

        detector.setProcessor(
                new MultiProcessor.Builder<>(new GraphicFaceTrackerFactory())
                        .build());

        if (!detector.isOperational()) {
            // Note: The first time that an app using face API is installed on a device, GMS will
            // download a native library to the device in order to do detection.  Usually this
            // completes before the app is run for the first time.  But if that download has not yet
            // completed, then the above call will not detect any faces.
            //
            // isOperational() can be used to check if the required native library is currently
            // available.  The detector will automatically become operational once the library
            // download completes on device.
            Log.w(TAG, "Face detector dependencies are not yet available.");
        }

        mCameraSource = new CameraSource.Builder(context, detector)
                .setRequestedPreviewSize(640, 480)
                .setFacing(CameraSource.CAMERA_FACING_BACK)
                .setRequestedFps(30.0f)
                .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();

        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if(mPreview != null) mPreview.stop();
    }

    /**
     * Releases the resources associated with the camera source, the associated detector, and the
     * rest of the processing pipeline.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCameraSource != null) {
            mCameraSource.release();
        }
    }

    /**
     * Callback for the result from requesting permissions. This method
     * is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong> It is possible that the permissions request interaction
     * with the user is interrupted. In this case you will receive empty permissions
     * and results arrays which should be treated as a cancellation.
     * </p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // we have permission, so create the camerasource
            createCameraSource();
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Face Tracker sample")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    //==============================================================================================
    // Camera Source Preview
    //==============================================================================================

    /**
     * Starts or restarts the camera source, if it exists.  If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
    private void startCameraSource() {

        // check that the device has play services available.
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    private void snapImageAndRecognize() {
        mRecognitionAttempts++;
        mLastTriggerTime = System.currentTimeMillis();
        mCameraSource.takePicture(new CameraSource.ShutterCallback() {
            @Override
            public void onShutter() {

            }
        }, new CameraSource.PictureCallback() {
            @Override
            public void onPictureTaken(final byte[] bytes) {
                new AsyncTask<Void, Void, String>() {
                    @Override
                    protected String doInBackground(Void... params) {
                        return Base64.encodeToString(bytes, 0);
                    }

                    @Override
                    protected void onPostExecute(String bitmap) {
                        Log.i(TAG, "IMAGE CAPTURED");
                        JsonObject json = new JsonObject();
                        json.addProperty("image", bitmap);
                        json.addProperty("gallery_name", GALLERY_ID);

                        Ion.with(getApplicationContext())
                                .load(RECOGNIZE_ENDPOINT)
                                .addHeader("app_id", KAIROS_APP_ID)
                                .addHeader("app_key", KAIROS_APP_KEY)
                                .addHeader("Content-Type", "application/json")
                                .setJsonObjectBody(json)
                                .asString()
                                .setCallback(new FutureCallback<String>() {
                                    @Override
                                    public void onCompleted(Exception e, String result) {
                                        try {
                                            JSONObject jsonObject = new JSONObject(result);
                                            JSONArray imagesArray = jsonObject.getJSONArray("images");
                                            JSONObject firstImageObject = imagesArray.getJSONObject(0);
                                            JSONObject transactionObject =
                                                    firstImageObject.getJSONObject("transaction");


//                                            if (transactionObject.getString("status").equals("failure")) {
//                                                mTextToSpeechObj.speak("You don't appear to be in our database!", TextToSpeech.QUEUE_ADD, null, "recognition_error");
//                                                return;
//                                            }

                                            JSONObject candidateObject =
                                                    firstImageObject.getJSONArray("candidates").getJSONObject(0);

                                            String candidate = candidateObject.getString("subject_id");

                                            String speech;

                                            if(candidate.equalsIgnoreCase(mUser.getName())){
                                                speech = "Face recognized. Thank you " + mUser.getName() + ". Please take your package!";
                                                mPackageUnlock.setValue(true);
                                                Log.i(TAG, "IS USER");
                                                mRecognitionAttempts = 0;
                                                mTextToSpeechObj.speak(speech, TextToSpeech.QUEUE_ADD, null, "recognition_success");
                                                initPurchaseListener();
                                            }
                                            else{
                                                speech = "Hi " + candidate + ". You are not " + mUser.getName() + ". Please get " + mUser.getName() + " to get his own package!";
                                                Log.i(TAG, "not the user");
                                                mTextToSpeechObj.speak(speech, TextToSpeech.QUEUE_ADD, null, "recognition_success");
                                                new Handler().postDelayed(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        mAllowImage = true;
                                                    }
                                                }, 5000);
                                            }



                                        } catch (JSONException e1) {
                                            Log.i(TAG, e1.toString());
                                            if(mRecognitionAttempts < MAX_RECOGNITION_ATTEMPTS){
                                                mTextToSpeechObj.speak("I could not recognize you, please try again.", TextToSpeech.QUEUE_ADD, null, "recognition_error");
                                                new Handler().postDelayed(new Runnable() {
                                                    @Override
                                                    public void run() {
                                                        mAllowImage = true;
                                                    }
                                                }, 5000);
                                            }else{
                                                mTextToSpeechObj.speak("Cannot recognize your face. No more attempts allowed!.", TextToSpeech.QUEUE_ADD, null, "recognition_error");
                                                initPurchaseListener();
                                            }

                                        }

                                    }
                                });
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);

            }
        });
    }

    //==============================================================================================
    // Graphic Face Tracker
    //==============================================================================================

    /**
     * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
     * uses this factory to create face trackers as needed -- one for each individual.
     */
    private class GraphicFaceTrackerFactory implements MultiProcessor.Factory<Face> {
        @Override
        public Tracker<Face> create(Face face) {
            return new GraphicFaceTracker(mGraphicOverlay);
        }
    }

    /**
     * Face tracker for each detected individual. This maintains a face graphic within the app's
     * associated face overlay.
     */
    private class GraphicFaceTracker extends Tracker<Face> {
        private GraphicOverlay mOverlay;
        private FaceGraphic mFaceGraphic;

        GraphicFaceTracker(GraphicOverlay overlay) {
            mOverlay = overlay;
            mFaceGraphic = new FaceGraphic(overlay);
        }

        /**
         * Start tracking the detected face instance within the face overlay.
         */
        @Override
        public void onNewItem(int faceId, Face item) {
            mFaceGraphic.setId(faceId);
        }

        /**
         * Update the position/characteristics of the face within the overlay.
         */
        @Override
        public void onUpdate(FaceDetector.Detections<Face> detectionResults, Face face) {
            mOverlay.add(mFaceGraphic);
            mFaceGraphic.updateFace(face);

            if(face.getWidth() >= FACE_PROXIMITY_TRIGGER){

                if(System.currentTimeMillis() - mLastTriggerTime > MINIMUM_PHOTO_DELAY_MS && mAllowImage){
                    Log.i(TAG, "Snapping image...");
                    snapImageAndRecognize();
                    mAllowImage = false;
                }

            }
        }


        /**
         * Hide the graphic when the corresponding face was not detected.  This can happen for
         * intermediate frames temporarily (e.g., if the face was momentarily blocked from
         * view).
         */
        @Override
        public void onMissing(FaceDetector.Detections<Face> detectionResults) {
            mOverlay.remove(mFaceGraphic);
        }

        /**
         * Called when the face is assumed to be gone for good. Remove the graphic annotation from
         * the overlay.
         */
        @Override
        public void onDone() {
            mOverlay.remove(mFaceGraphic);
        }
    }


}
