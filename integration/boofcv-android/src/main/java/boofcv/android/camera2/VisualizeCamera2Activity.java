/*
 * Copyright (c) 2011-2018, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
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
 */

package boofcv.android.camera2;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.media.Image;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.util.Size;
import android.view.*;
import boofcv.android.ConvertBitmap;
import boofcv.android.ConvertYuv420_888;
import boofcv.misc.MovingAverage;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageType;

import java.util.Stack;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Extension of {@link SimpleCamera2Activity} which adds visualization and hooks for image
 * processing. Video frames are automatically converted into a format which can be processed by
 * computer vision. Optionally multiple threads can be generated so that video frames are
 * processed concurrently. The input image is automatically converted into a Bitmap image if
 * requested. If multiple threads are being used the user can toggle if they want visualization
 * to be shown if an old image finished being processed after a newer one.
 *
 * Must call {@link #startCamera(ViewGroup, TextureView)} in onCreate().
 *
 * To customize it's behavior override the following functions:
 * <ul>
 *     <li>{@link #setThreadPoolSize}</li>
 *     <li>{@link #setImageType}</li>
 * </ul>
 *
 * Useful variables
 * <ul>
 *     <li><b>imageToView</b>: Matrix that converts a pixel in video frame to view frame</li>
 *     <li><b>targetResolution</b>: Specifies how many pixels you want in the video frame</li>
 *     <li><b>stretchToFill</b>: true to stretch the video frame to fill the entire view</li>
 *     <li><b>showBitmap</b>: If it should handle convert the video frame into a bitmap and rendering it</li>
 *     <li><b>visualizeOnlyMostRecent</b>: If more than one thread is enabled should it show old results</li>
 * </ul>
 *
 * Configuration variables
 * <ul>
 *     <li>targetResolution</li>
 *     <li>showBitmap</li>
 *     <li>stretchToFill</li>
 *     <li>visualizeOnlyMostRecent</li>
 * </ul>
 *
 * @see SimpleCamera2Activity
 */
public abstract class VisualizeCamera2Activity extends SimpleCamera2Activity {

	private static final String TAG = "VisualizeCamera2";

	protected TextureView textureView; // used to display camera preview directly to screen
	protected DisplayView displayView; // used to render visuals

	//---- START Owned By imagLock
	protected final Object imageLock = new Object();
	protected ImageType imageType = ImageType.single(GrayU8.class);
	protected Stack<ImageBase> stackImages = new Stack<>(); // images which are available for use
	protected byte[] convertWork = new byte[1]; // work space for converting images
	//---- END

	//---- START owned by bitmapLock
	protected final Object bitmapLock = new Object();
	protected Bitmap bitmap = Bitmap.createBitmap(1,1, Bitmap.Config.ARGB_8888);
	protected byte[] bitmapTmp =  new byte[1];
	//---- END

	protected LinkedBlockingQueue threadQueue = new LinkedBlockingQueue();
	protected ThreadPoolExecutor threadPool = new ThreadPoolExecutor(1,1,50, TimeUnit.MILLISECONDS,
			threadQueue);

	/**
	 * Stores the transform from the video image to the view it is displayed on. Handles
	 * All those annoying rotations.
	 */
	protected Matrix imageToView = new Matrix();

	// number of pixels it searches for when choosing camera resolution
	protected int targetResolution = 640*480;

	// If true the bitmap will be shown. otherwise the original preview image will be
	protected boolean showBitmap = true;

	// if true it will sketch the bitmap to fill the view
	protected boolean stretchToFill = false;

	// If an old thread takes longer to finish than a new thread it won't be visualized
	protected boolean visualizeOnlyMostRecent = true;
	protected volatile long timeOfLastUpdated;

	// An identity matrix
	private Matrix identity = new Matrix();

	//START Lock for timing structures
	protected static final int TIMING_WARM_UP = 3; // number of frames that must be processed before it starts
	protected final Object lockTiming = new Object();
	protected int totalConverted; // counter for frames converted since data type was set
	protected final MovingAverage periodConvert = new MovingAverage(0.8); // milliseconds
	//END

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if( verbose )
			Log.i(TAG,"onCreate()");

		// Free up more screen space
		android.app.ActionBar actionBar = getActionBar();
		if( actionBar != null ) {
			actionBar.hide();
		}
	}

	/**
	 * Specifies the number of threads in the thread-pool. If this is set to a value greater than
	 * one you better know how to write concurrent code or else you're going to have a bad time.
	 */
	public void setThreadPoolSize( int threads ) {
		if( threads <= 0 )
			throw new IllegalArgumentException("Number of threads must be greater than 0");
		if( verbose )
			Log.i(TAG,"setThreadPoolSize("+threads+")");

		threadPool.setCorePoolSize(threads);
		threadPool.setMaximumPoolSize(threads);
	}

	/**
	 *
	 * @param layout Where the visualization overlay will be placed inside of
	 * @param view If not null then this will be used to display the camera preview.
	 */
	protected void startCamera(@NonNull ViewGroup layout, @Nullable TextureView view ) {
		if( verbose )
			Log.i(TAG,"startCamera(layout , view="+(view!=null)+")");
		displayView = new DisplayView(this);
		layout.addView(displayView,layout.getChildCount(),
				new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

		if( view == null ) {
			super.startCameraView(displayView);
		} else {
			this.textureView = view;
			super.startCameraTexture(textureView);
		}
	}

	@Override
	protected void startCameraTexture( @Nullable TextureView view ) {
		throw new RuntimeException("Call the other start camera function");
	}
	@Override
	protected void startCameraView( @Nullable View view ) {
		throw new RuntimeException("Call the other start camera function");
	}
	@Override
	protected void startCamera() {
		throw new RuntimeException("Call the other start camera function");
	}

	/**
	 * Selects a resolution which has the number of pixels closest to the requested value
	 */
	@Override
	protected int selectResolution( int widthTexture, int heightTexture, Size[] resolutions  ) {
		// just wanted to make sure this has been cleaned up
		timeOfLastUpdated = 0;

		// select the resolution here
		int bestIndex = -1;
		double bestAspect = Double.MAX_VALUE;
		double bestArea = 0;

		for( int i = 0; i < resolutions.length; i++ ) {
			Size s = resolutions[i];
			int width = s.getWidth();
			int height = s.getHeight();

			double aspectScore = Math.abs(width*height-targetResolution);

			if( aspectScore < bestAspect ) {
				bestIndex = i;
				bestAspect = aspectScore;
				bestArea = width*height;
			} else if( Math.abs(aspectScore-bestArea) <= 1e-8 ) {
				bestIndex = i;
				double area = width*height;
				if( area > bestArea ) {
					bestArea = area;
				}
			}
		}

		return bestIndex;
	}

	@Override
	protected void onCameraResolutionChange(int cameraWidth, int cameraHeight) {
		// predeclare bitmap image used for display
		if( showBitmap ) {
			synchronized (bitmapLock) {
				if (bitmap.getWidth() != cameraWidth || bitmap.getHeight() != cameraHeight)
					bitmap = Bitmap.createBitmap(cameraWidth, cameraHeight, Bitmap.Config.ARGB_8888);
				bitmapTmp = ConvertBitmap.declareStorage(bitmap, bitmapTmp);
			}
		}
		// Compute transform from bitmap to view coordinates
		int rotatedWidth = cameraWidth;
		int rotatedHeight = cameraHeight;

		int rotation = getWindowManager().getDefaultDisplay().getRotation();
		int offsetX=0,offsetY=0;

		if( verbose )
			Log.i(TAG,"camera rotation = "+mSensorOrientation+" display rotation = "+rotation);

		boolean needToRotateView = (Surface.ROTATION_0 == rotation || Surface.ROTATION_180 == rotation) !=
				(mSensorOrientation == 0 || mSensorOrientation == 180);

		if (needToRotateView) {
			rotatedWidth = cameraHeight;
			rotatedHeight = cameraWidth;
			offsetX = (rotatedWidth-rotatedHeight)/2;
			offsetY = (rotatedHeight-rotatedWidth)/2;
		}

		imageToView.reset();
		float scale = Math.min(
				(float)viewWidth/rotatedWidth,
				(float)viewHeight/rotatedHeight);
		if( scale == 0 ) {
			Log.e(TAG,"displayView has zero width and height");
			return;
		}

		imageToView.postRotate(-90*rotation + mSensorOrientation, cameraWidth/2, cameraHeight/2);
		imageToView.postTranslate(offsetX,offsetY);
		imageToView.postScale(scale,scale);
		if( stretchToFill ) {
			imageToView.postScale(
					viewWidth/(rotatedWidth*scale),
					viewHeight/(rotatedHeight*scale));
		} else {
			imageToView.postTranslate(
					(viewWidth - rotatedWidth*scale) / 2,
					(viewHeight - rotatedHeight*scale) / 2);
		}

		Log.i(TAG,imageToView.toString());
		Log.i(TAG,"scale = "+scale);
		Log.i(TAG,"camera resolution cam="+ cameraWidth +"x"+ cameraHeight +" view="+viewWidth+"x"+viewHeight);
	}

	/**
	 * Changes the type of image the camera frame is converted to
	 */
	protected void setImageType( ImageType type ) {
		synchronized (imageLock){
			this.imageType = type;
			// todo check to see if the type is the same or not before clearing
			this.stackImages.clear();

			synchronized (lockTiming) {
				totalConverted = 0;
				periodConvert.reset();
			}
		}
	}

	@Override
	protected void processFrame(Image image) {
		// If there's a thread pending to go into the pool wait until it has been cleared out
		if( threadQueue.size() > 0 )
			return;

		synchronized (imageLock) {
			ImageBase converted;
			if( stackImages.empty()) {
				converted = imageType.createImage(1,1);
			} else {
				converted = stackImages.pop();
			}
			long before = System.nanoTime();
			convertWork = ConvertYuv420_888.declareWork(image, convertWork);
			ConvertYuv420_888.yuvToBoof(image,converted, convertWork);
			long after = System.nanoTime();
//			Log.i(TAG,"processFrame() image="+image.getWidth()+"x"+image.getHeight()+
//					"  boof="+converted.width+"x"+converted.height);

			// record how long it took to convert the image for diagnostic reasons
			synchronized (lockTiming) {
				totalConverted++;
				if( totalConverted >= TIMING_WARM_UP ) {
					periodConvert.update((after - before) * 1e-6);
				}
			}

			threadPool.execute(()->processImageOuter(converted));
		}
	}

	/**
	 * Where all the image processing happens. If the number of threads is greater than one then
	 * this function can be called multiple times before previous calls have finished.
	 *
	 * WARNING: If the image type can change this must be before processing it.
	 *
	 * @param image The image which is to be processed. The image is owned by this function until
	 *              it returns. After that the image and all it's data will be recycled. DO NOT
	 *              SAVE A REFERENCE TO IT.
	 */
	protected abstract void processImage( ImageBase image );

	/**
	 * Internal function which manages images and invokes {@link #processImage}.
	 */
	private void processImageOuter( ImageBase image ) {
		long startTime = System.currentTimeMillis();

		// this image is owned by only this process and no other. So no need to lock it while
		// processing
		processImage(image);

		// If an old image finished being processes after a more recent one it won't be visualized
		if( !visualizeOnlyMostRecent || startTime > timeOfLastUpdated ) {
			timeOfLastUpdated = startTime;

			// Copy this frame
			if (showBitmap) {
				synchronized (bitmapLock) {
//					Log.i(TAG,"boofToBitmap image="+image.width+"x"+image.height+
//							" bitmap="+bitmap.getWidth()+"x"+bitmap.getHeight());
					ConvertBitmap.boofToBitmap(image, bitmap, bitmapTmp);
				}
			}

			// Update the visualization
			runOnUiThread(() -> displayView.invalidate());
		}

		// Put the image into the stack if the image type has not changed
		synchronized (imageLock) {
			if( imageType.isSameType(image.getImageType()))
				stackImages.add(image);
		}
	}

	/**
	 * Renders the visualizations. Override and invoke super to add your own
	 */
	protected void onDrawFrame( SurfaceView view , Canvas canvas ) {

		// On an older Android phone it was discovered that the behavior of drawCanvas was different than
		// on more recent phones. The transform would be concat on top of the canvas' existing transform.
		// wihle the behavior of canvas.setTransform() was the same as on more recent phones.
		// The existing transform would be a transform from screen to view! Below is a hack to make graphics
		// line up. On the older phone the graphics will be shifted upwards towards the action bar since that
		// separation is no longer taken in account. Know of a better way to fix this? this just makes no sense
		// and looks like a bug.
		canvas.setMatrix(identity);

		if( showBitmap ) {
			synchronized (bitmapLock) {
				canvas.drawBitmap(bitmap, imageToView, null);
			}
		}
	}

	/**
	 * Custom view for visualizing results
	 */
	public class DisplayView extends SurfaceView implements SurfaceHolder.Callback {

		SurfaceHolder mHolder;

		public DisplayView(Context context) {
			super(context);
			mHolder = getHolder();

			// configure it so that its on top and will be transparent
			setZOrderOnTop(true);    // necessary
			mHolder.setFormat(PixelFormat.TRANSPARENT);

			// if this is not set it will not draw
			setWillNotDraw(false);
		}


		@Override
		public void onDraw(Canvas canvas) {
			onDrawFrame(this,canvas);
		}

		@Override
		public void surfaceCreated(SurfaceHolder surfaceHolder) {}

		@Override
		public void surfaceChanged(SurfaceHolder surfaceHolder, int i, int i1, int i2) {}

		@Override
		public void surfaceDestroyed(SurfaceHolder surfaceHolder) {}
	}
}