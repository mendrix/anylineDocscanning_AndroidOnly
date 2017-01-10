/*
 * Anyline Cordova Plugin
 * DocumentActivity.java
 *
 * Copyright (c) 2016 9yards GmbH
 *
 * Created by Martin W. at 2016-05-19
 */
package io.anyline.cordova;

import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.AnimationSet;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import at.nineyards.anyline.camera.AnylineViewConfig;
import at.nineyards.anyline.models.AnylineImage;
import at.nineyards.anyline.modules.document.DocumentResultListener;
import at.nineyards.anyline.modules.document.DocumentScanView;
import at.nineyards.anyline.util.TempFileUtil;

public class DocumentActivity extends AnylineBaseActivity {

    private static final String TAG = DocumentActivity.class.getSimpleName();
    private DocumentScanView documentScanView;
    private Toast notificationToast;
    private ImageView imageViewResult;
    private ProgressDialog progressDialog;
    private ImageView imageViewFull;
    private List<PointF> lastOutline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(getResources().getIdentifier("activity_scan_document", "layout", getPackageName()));
        imageViewResult = (ImageView) findViewById(getResources().getIdentifier("image_result", "id", getPackageName()));
        imageViewFull = (ImageView) findViewById(getResources().getIdentifier("full_image", "id", getPackageName()));
        documentScanView = (DocumentScanView) findViewById(getResources().getIdentifier("document_scan_view", "id", getPackageName()));
        documentScanView.setCameraOpenListener(this);

        JSONObject jsonObject;
        try {
            jsonObject = new JSONObject(configJson);
        } catch (Exception e) {
            //JSONException or IllegalArgumentException is possible, return it to javascript
            finishWithError(Resources.getString(this, "error_invalid_json_data") + "\n" + e.getLocalizedMessage());
            return;
        }

        documentScanView.setConfig(new AnylineViewConfig(this, jsonObject));

        // initialize Anyline with the license key and a Listener that is called if a result is found
        documentScanView.initAnyline(licenseKey, new DocumentResultListener() {
            @Override
            public void onResult(AnylineImage transformedImage, AnylineImage fullFrame) {

                // handle the result document images here
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                showToast(Resources.getString(DocumentActivity.this, "document_picture_success"));

                performScaleOutAnimation(transformedImage);

                JSONObject jsonResult = new JSONObject();
                try {
                    File imageFile = TempFileUtil.createTempFileCheckCache(DocumentActivity.this,
                            UUID.randomUUID().toString(), ".jpg");

                    transformedImage.save(imageFile, 90);
                    jsonResult.put("imagePath", imageFile.getAbsolutePath());

                } catch (IOException e) {
                    Log.e(TAG, "Image file could not be saved.", e);
                } catch (JSONException jsonException) {
                    //should not be possible
                    Log.e(TAG, "Error while putting image path to json.", jsonException);
                }

                // release the images
                transformedImage.release();
                fullFrame.release();

                if (documentScanView.getConfig().isCancelOnResult()) {
                    ResultReporter.onResult(jsonResult, true);
                    setResult(AnylinePlugin.RESULT_OK);
                    finish();
                } else {
                    ResultReporter.onResult(jsonResult, false);
                }

            }

            @Override
            public void onPreviewProcessingSuccess(AnylineImage anylineImage) {
                // this is called after the preview of the document is completed, and a full picture will be
                // processed automatically

                performScaleInAnimation(anylineImage);

                showToast(Resources.getString(DocumentActivity.this, "document_preview_success"));
            }

            @Override
            public void onPreviewProcessingFailure(DocumentScanView.DocumentError documentError) {
                // this is called on any error while processing the document image
                // Note: this is called every time an error occurs in a run, so that might be quite often
                // An error message should only be presented to the user after some time
            }

            @Override
            public void onPictureProcessingFailure(DocumentScanView.DocumentError documentError) {

                // handle an error while processing the full picture here
                // the preview will be restarted automatically
                String text = Resources.getString(DocumentActivity.this, "document_picture_error");
                switch (documentError) {
                    case DOCUMENT_NOT_SHARP:
                        text += Resources.getString(DocumentActivity.this, "document_error_not_sharp");
                        break;
                    case DOCUMENT_SKEW_TOO_HIGH:
                        text += Resources.getString(DocumentActivity.this, "document_error_skew_too_high");
                        break;
                    case DOCUMENT_OUTLINE_NOT_FOUND:
                        text += Resources.getString(DocumentActivity.this, "document_error_outline_not_found");
                        break;
                    case GLARE_DETECTED:
                        text += Resources.getString(DocumentActivity.this, "document_error_glare_detected");
                        break;
                    case IMAGE_TOO_DARK:
                        text += Resources.getString(DocumentActivity.this, "document_error_too_dark");
                        break;
                    case UNKNOWN:
                    default:
                        text += Resources.getString(DocumentActivity.this, "document_error_unknown");
                        break;
                }

                showToast(text);
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }

                // cancel the animation on error
                imageViewFull.clearAnimation();
                imageViewFull.setVisibility(View.INVISIBLE);
            }

            @Override
            public boolean onDocumentOutlineDetected(List<PointF> list, boolean anglesValid) {
                // is called when the outline of the document is detected. return true if the outline is consumed by
                // the implementation here, false if the outline should be drawn by the DocumentScanView
                lastOutline = list; // saving the outline for the animations
                return false;
            }

            @Override
            public void onTakePictureSuccess() {
                // this is called after the image has been captured from the camera and is about to be processed
                progressDialog = ProgressDialog.show(DocumentActivity.this,
                                                     Resources.getString(DocumentActivity.this, "document_processing"),
                                                     Resources.getString(DocumentActivity.this, "document_processing_picture_please_wait"),
                                                     true);
                if (notificationToast != null) {
                    notificationToast.cancel();
                }
            }

            @Override
            public void onTakePictureError(Throwable throwable) {
                // This is called if the image could not be captured from the camera (most probably because of an
                // OutOfMemoryError)
                finishWithError(Resources.getString(DocumentActivity.this, "error_occured") + "\n"
                        + throwable.getLocalizedMessage());
            }

        });
        documentScanView.getAnylineController().setWorkerThreadUncaughtExceptionHandler(this);

    }

    /**
     * Performs an animation on a successful preview. This is just an example.
     *
     * @param anylineImage The cropped successful preview image
     */
    private void performScaleInAnimation(AnylineImage anylineImage) {
        final AlphaAnimation scanPulseAnimation = new AlphaAnimation(0.05f, 0.3f);
        scanPulseAnimation.setDuration(500);
        scanPulseAnimation.setFillAfter(true);
        scanPulseAnimation.setRepeatMode(Animation.REVERSE);
        scanPulseAnimation.setRepeatCount(Animation.INFINITE);

        if (lastOutline != null) {
            RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams
                    .WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT); //WRAP_CONTENT param can be
            // FILL_PARENT
            params.leftMargin = (lastOutline.get(0).x < lastOutline.get(3).x) ? (int) lastOutline.get(0).x :
                    (int) lastOutline.get(3).x; //XCOORD
            params.topMargin = (lastOutline.get(0).y < lastOutline.get(1).y) ? (int) lastOutline.get(0).y :
                    (int) lastOutline.get(0).y; //YCOORD
            params.width = (lastOutline.get(1).x > lastOutline.get(2).x) ? (int) (lastOutline.get(1).x -
                    params.leftMargin) : (int) (lastOutline.get(2).x - params.leftMargin);
            params.height = (lastOutline.get(2).y > lastOutline.get(3).y) ? (int) (lastOutline.get(2).y -
                    params.topMargin) : (int) (lastOutline.get(3).y - params.topMargin);
            imageViewFull.setLayoutParams(params);
        }


        imageViewFull.setImageBitmap(anylineImage.getBitmap());


        final AlphaAnimation alphaAnimation = new AlphaAnimation(0.1f, 1.0f);
        alphaAnimation.setDuration(500);
        alphaAnimation.setFillAfter(true);
        alphaAnimation.setRepeatCount(0);


        float scaleWidth = (float) documentScanView.getWidth() / imageViewFull.getLayoutParams().width;
        float scaleHeight = (float) documentScanView.getHeight() / imageViewFull.getLayoutParams().height;

        float maxScale = (scaleWidth > scaleHeight) ? scaleWidth : scaleHeight;
        ScaleAnimation scaleAnimation = new ScaleAnimation(1f, maxScale, 1f, maxScale, Animation
                .RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f);
        scaleAnimation.setDuration(500);
        scaleAnimation.setInterpolator(new AccelerateDecelerateInterpolator());
        scaleAnimation.setRepeatCount(0);
        scaleAnimation.setFillAfter(true);
        scaleAnimation.setFillEnabled(true);
        scaleAnimation.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
                imageViewFull.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) imageViewFull
                        .getLayoutParams();
                params.addRule(RelativeLayout.CENTER_HORIZONTAL);
                params.addRule(RelativeLayout.CENTER_VERTICAL);
                imageViewFull.setLayoutParams(params);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {
            }
        });
        AnimationSet set = new AnimationSet(false);
        set.addAnimation(scaleAnimation);
        set.addAnimation(alphaAnimation);
        set.setFillAfter(true);
        set.setFillEnabled(true);
        imageViewFull.startAnimation(set);
    }

    /**
     * Performs an animation after the final image was successfully processed. This is just an example.
     *
     * @param transformedImage The transformed final image
     */
    private void performScaleOutAnimation(AnylineImage transformedImage) {
        float targetHeight = transformedImage.getHeight() * (100.0f / transformedImage.getWidth());

        ScaleAnimation scaleAnimation = new ScaleAnimation(1f, (float) imageViewResult.getWidth() / imageViewFull
                .getWidth(), 1f, targetHeight / imageViewFull.getHeight(), Animation.RELATIVE_TO_SELF, 1f,
                Animation.RELATIVE_TO_SELF, 1f);
        scaleAnimation.setDuration(500);
        scaleAnimation.setInterpolator(new AccelerateDecelerateInterpolator());

        AlphaAnimation animation1 = new AlphaAnimation(1f, 0.0f);
        animation1.setDuration(500);
        animation1.setFillAfter(true);

        AnimationSet set = new AnimationSet(false);
        set.addAnimation(scaleAnimation);
        set.addAnimation(animation1);

        imageViewFull.setImageBitmap(Bitmap.createScaledBitmap(transformedImage.getBitmap(), imageViewFull
                        .getLayoutParams().width,
                imageViewFull.getLayoutParams().height, false));
        imageViewFull.startAnimation(set);
        set.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {
            }

            @Override
            public void onAnimationEnd(Animation animation) {
                imageViewFull.setVisibility(View.INVISIBLE);
                imageViewResult.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAnimationRepeat(Animation animation) {

            }
        });

        imageViewResult.setImageBitmap(Bitmap.createScaledBitmap(transformedImage.getBitmap(), 100, 160, false));
        imageViewResult.setVisibility(View.INVISIBLE);
    }

    private void showToast(String text) {
        try {
            notificationToast.setText(text);
        } catch (Exception e) {
            notificationToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
        }
        notificationToast.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        //start the actual scanning
        documentScanView.startScanning();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //stop the scanning
        documentScanView.cancelScanning();
        //release the camera (must be called in onPause, because there are situations where
        // it cannot be auto-detected that the camera should be released)
        documentScanView.releaseCameraInBackground();
    }

}
