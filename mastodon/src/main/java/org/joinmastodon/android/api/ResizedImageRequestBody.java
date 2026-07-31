package org.joinmastodon.android.api;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import org.joinmastodon.android.MastodonApp;
import org.joinmastodon.android.ui.utils.UiUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

import androidx.annotation.NonNull;
import okhttp3.MediaType;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

public class ResizedImageRequestBody extends CountingRequestBody{
	private File tempFile;
	private Uri uri;
	private MediaType contentType;
	private int maxSize;
	private int width;
	private int height;

	public ResizedImageRequestBody(Uri uri, int maxSize, ProgressListener progressListener) throws IOException{
		this(uri, maxSize, progressListener, ()->false);
	}

	public ResizedImageRequestBody(Uri uri, int maxSize, ProgressListener progressListener, BooleanSupplier canceled) throws IOException{
		super(progressListener);
		this.uri=uri;
		this.maxSize=maxSize;
		BitmapFactory.Options opts=new BitmapFactory.Options();
		opts.inJustDecodeBounds=true;
		if("file".equals(uri.getScheme())){
			BitmapFactory.decodeFile(uri.getPath(), opts);
			contentType=UiUtils.getFileMediaType(new File(uri.getPath()));
		}else{
			try(InputStream in=MastodonApp.context.getContentResolver().openInputStream(uri)){
				BitmapFactory.decodeStream(in, null, opts);
			}
			String mime=MastodonApp.context.getContentResolver().getType(uri);
			contentType=TextUtils.isEmpty(mime) ? null : MediaType.get(mime);
		}
		if(contentType==null)
			contentType=MediaType.get("image/jpeg");
		if(needResize(opts.outWidth, opts.outHeight) || needCrop(opts.outWidth, opts.outHeight)){
			Bitmap bitmap;
			checkCanceled(canceled);
			if(Build.VERSION.SDK_INT>=28){
				ImageDecoder.Source source;
				if("file".equals(uri.getScheme())){
					source=ImageDecoder.createSource(new File(uri.getPath()));
				}else{
					source=ImageDecoder.createSource(MastodonApp.context.getContentResolver(), uri);
				}
				bitmap=ImageDecoder.decodeBitmap(source, (decoder, info, _source)->{
					int[] size=getTargetSize(info.getSize().getWidth(), info.getSize().getHeight());
					decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
					decoder.setTargetSize(size[0], size[1]);
					// Breaks images in mysterious ways
//					if(needCrop(size[0], size[1]))
//						decoder.setCrop(getCropBounds(size[0], size[1]));
				});
				checkCanceled(canceled, bitmap);
				if(needCrop(bitmap.getWidth(), bitmap.getHeight())){
					Rect crop=getCropBounds(bitmap.getWidth(), bitmap.getHeight());
					bitmap=Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width(), crop.height());
				}
				checkCanceled(canceled, bitmap);
			}else{
				int[] size=getTargetSize(opts.outWidth, opts.outHeight);
				int targetWidth=size[0];
				int targetHeight=size[1];
				float factor=opts.outWidth/(float)targetWidth;
				opts=new BitmapFactory.Options();
				opts.inSampleSize=(int)factor;
				if("file".equals(uri.getScheme())){
					bitmap=BitmapFactory.decodeFile(uri.getPath(), opts);
				}else{
					try(InputStream in=MastodonApp.context.getContentResolver().openInputStream(uri)){
						bitmap=BitmapFactory.decodeStream(in, null, opts);
					}
				}
				if(bitmap==null)
					throw new IOException("Invalid image");
				checkCanceled(canceled, bitmap);
				boolean needCrop=needCrop(targetWidth, targetHeight);
				if(factor%1f!=0f || needCrop){
					Rect srcBounds=null;
					Rect dstBounds;
					if(needCrop){
						Rect crop=getCropBounds(targetWidth, targetHeight);
						dstBounds=new Rect(0, 0, crop.width(), crop.height());
						srcBounds=new Rect(
								Math.round(crop.left/(float)targetWidth*bitmap.getWidth()),
								Math.round(crop.top/(float)targetHeight*bitmap.getHeight()),
								Math.round(crop.right/(float)targetWidth*bitmap.getWidth()),
								Math.round(crop.bottom/(float)targetHeight*bitmap.getHeight())
						);
					}else{
						dstBounds=new Rect(0, 0, targetWidth, targetHeight);
					}
					Bitmap scaled=Bitmap.createBitmap(dstBounds.width(), dstBounds.height(), Bitmap.Config.ARGB_8888);
					new Canvas(scaled).drawBitmap(bitmap, srcBounds, dstBounds, new Paint(Paint.FILTER_BITMAP_FLAG));
					bitmap=scaled;
				}
				checkCanceled(canceled, bitmap);
				int orientation=0;
				if("file".equals(uri.getScheme())){
					ExifInterface exif=new ExifInterface(uri.getPath());
					orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
				}else if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.N){
					try(InputStream in=MastodonApp.context.getContentResolver().openInputStream(uri)){
						ExifInterface exif=new ExifInterface(in);
						orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
					}
				}
				Matrix matrix=getExifMatrix(orientation);
				if(!matrix.isIdentity())
					bitmap=Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, false);
				checkCanceled(canceled, bitmap);
			}

			boolean isPNG="image/png".equals(contentType);
			width=bitmap.getWidth();
			height=bitmap.getHeight();
			File outputFile=File.createTempFile("mastodon_tmp_resized", null);
			boolean encoded=false;
			try(FileOutputStream out=new FileOutputStream(outputFile)){
				checkCanceled(canceled);
				if(isPNG){
					encoded=bitmap.compress(Bitmap.CompressFormat.PNG, 0, out);
				}else{
					encoded=bitmap.compress(Bitmap.CompressFormat.JPEG, 97, out);
					contentType=MediaType.get("image/jpeg");
				}
			}finally{
				bitmap.recycle();
				if(!encoded)
					outputFile.delete();
			}
			if(!encoded)
				throw new IOException("Unable to encode resized image");
			if(canceled.getAsBoolean() || Thread.currentThread().isInterrupted()){
				outputFile.delete();
				throw new IOException("Upload canceled");
			}
			tempFile=outputFile;
			length=tempFile.length();
		}else{
			width=opts.outWidth;
			height=opts.outHeight;
			int orientation=getOrientation();
			if(orientation==ExifInterface.ORIENTATION_ROTATE_90 || orientation==ExifInterface.ORIENTATION_ROTATE_270 || orientation==ExifInterface.ORIENTATION_TRANSPOSE || orientation==ExifInterface.ORIENTATION_TRANSVERSE){
				width=opts.outHeight;
				height=opts.outWidth;
			}
			if("file".equals(uri.getScheme())){
				length=new File(uri.getPath()).length();
			}else{
				try(Cursor cursor=MastodonApp.context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)){
					cursor.moveToFirst();
					length=cursor.getInt(0);
				}
			}
		}
	}

	@Override
	protected Source openSource() throws IOException{
		if(tempFile==null){
			return Okio.source(MastodonApp.context.getContentResolver().openInputStream(uri));
		}else{
			return Okio.source(tempFile);
		}
	}

	@Override
	public MediaType contentType(){
		return contentType;
	}

	@Override
	public void writeTo(BufferedSink sink) throws IOException{
		try{
			super.writeTo(sink);
		}finally{
			if(tempFile!=null){
				tempFile.delete();
			}
		}
	}

	protected int[] getTargetSize(int srcWidth, int srcHeight){
		int targetWidth=Math.round((float)Math.sqrt((float)maxSize*((float)srcWidth/srcHeight)));
		int targetHeight=Math.round((float)Math.sqrt((float)maxSize*((float)srcHeight/srcWidth)));
		return new int[]{targetWidth, targetHeight};
	}

	protected boolean needResize(int srcWidth, int srcHeight){
		return srcWidth*srcHeight>maxSize;
	}

	protected boolean needCrop(int srcWidth, int srcHeight){
		return false;
	}

	protected Rect getCropBounds(int srcWidth, int srcHeight){
		return null;
	}

	public void cleanup(){
		if(tempFile!=null)
			tempFile.delete();
	}

	public int getWidth(){ return width; }
	public int getHeight(){ return height; }

	private int getOrientation() throws IOException{
		if("file".equals(uri.getScheme()))
			return new ExifInterface(uri.getPath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
		try(InputStream in=MastodonApp.context.getContentResolver().openInputStream(uri)){
			return new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
		}
	}

	private static Matrix getExifMatrix(int orientation){
		Matrix matrix=new Matrix();
		switch(orientation){
			case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
			case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
			case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
			case ExifInterface.ORIENTATION_TRANSPOSE -> matrix.setValues(new float[]{0, 1, 0, 1, 0, 0, 0, 0, 1});
			case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
			case ExifInterface.ORIENTATION_TRANSVERSE -> matrix.setValues(new float[]{0, -1, 0, -1, 0, 0, 0, 0, 1});
			case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90);
		}
		return matrix;
	}

	private static void checkCanceled(BooleanSupplier canceled) throws IOException{
		if(canceled.getAsBoolean() || Thread.currentThread().isInterrupted())
			throw new IOException("Upload canceled");
	}

	private static void checkCanceled(BooleanSupplier canceled, Bitmap bitmap) throws IOException{
		if(canceled.getAsBoolean() || Thread.currentThread().isInterrupted()){
			bitmap.recycle();
			throw new IOException("Upload canceled");
		}
	}
}
