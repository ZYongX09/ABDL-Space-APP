package org.joinmastodon.android.api;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;

import org.joinmastodon.android.MastodonApp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

import okhttp3.MediaType;
import okio.Okio;
import okio.Source;

public class CosPreviewRequestBody extends CountingRequestBody{
	private final File file;
	private final MediaType contentType;
	private final int width;
	private final int height;

	public CosPreviewRequestBody(Uri uri, ProgressListener listener) throws IOException{
		this(uri, listener, ()->false);
	}

	public CosPreviewRequestBody(Uri uri, ProgressListener listener, BooleanSupplier canceled) throws IOException{
		super(listener);
		Bitmap bitmap=null;
		File tempFile=null;
		MediaType outputType=null;
		int outputWidth=0;
		int outputHeight=0;
		try{
		checkCanceled(canceled);
		if(Build.VERSION.SDK_INT>=28){
			ImageDecoder.Source source="file".equals(uri.getScheme())
					? ImageDecoder.createSource(new File(uri.getPath()))
					: ImageDecoder.createSource(MastodonApp.context.getContentResolver(), uri);
			bitmap=ImageDecoder.decodeBitmap(source, (decoder, info, src)->{
				int srcWidth=info.getSize().getWidth();
				int srcHeight=info.getSize().getHeight();
				float scale=Math.min(1f, 540f/Math.max(srcWidth, srcHeight));
				decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
				decoder.setTargetSize(Math.max(1, Math.round(srcWidth*scale)), Math.max(1, Math.round(srcHeight*scale)));
			});
			checkCanceled(canceled);
		}else{
			BitmapFactory.Options bounds=new BitmapFactory.Options();
			bounds.inJustDecodeBounds=true;
			try(InputStream input=MastodonApp.context.getContentResolver().openInputStream(uri)){
				BitmapFactory.decodeStream(input, null, bounds);
			}
			if(bounds.outWidth<=0 || bounds.outHeight<=0)
				throw new IOException("Invalid image");
			float scale=Math.min(1f, 540f/Math.max(bounds.outWidth, bounds.outHeight));
			BitmapFactory.Options decode=new BitmapFactory.Options();
			decode.inSampleSize=Math.max(1, Integer.highestOneBit(Math.max(1, Math.round(1f/scale))));
			try(InputStream input=MastodonApp.context.getContentResolver().openInputStream(uri)){
				bitmap=BitmapFactory.decodeStream(input, null, decode);
			}
			if(bitmap==null)
				throw new IOException("Invalid image");
			checkCanceled(canceled);
			int targetWidth=Math.max(1, Math.round(bounds.outWidth*scale));
			int targetHeight=Math.max(1, Math.round(bounds.outHeight*scale));
			if(bitmap.getWidth()!=targetWidth || bitmap.getHeight()!=targetHeight)
				bitmap=Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
			checkCanceled(canceled);
			int orientation=ExifInterface.ORIENTATION_NORMAL;
			try(InputStream input=MastodonApp.context.getContentResolver().openInputStream(uri)){
				orientation=new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
			}
			Matrix matrix=getExifMatrix(orientation);
			if(!matrix.isIdentity())
				bitmap=Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
			checkCanceled(canceled);
		}
		outputWidth=bitmap.getWidth();
		outputHeight=bitmap.getHeight();
		boolean transparent=bitmap.hasAlpha();
		tempFile=File.createTempFile("cos_preview", transparent ? ".webp" : ".jpg", MastodonApp.context.getCacheDir());
		checkCanceled(canceled);
		try(FileOutputStream output=new FileOutputStream(tempFile)){
			Bitmap.CompressFormat format=transparent
					? (Build.VERSION.SDK_INT>=30 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP)
					: Bitmap.CompressFormat.JPEG;
			if(!bitmap.compress(format, transparent ? 100 : 78, output))
				throw new IOException("Unable to encode COS preview");
		}
		checkCanceled(canceled);
		length=tempFile.length();
		outputType=MediaType.get(transparent ? "image/webp" : "image/jpeg");
		}catch(IOException | RuntimeException x){
			if(tempFile!=null)
				tempFile.delete();
			throw x;
		}finally{
			if(bitmap!=null && !bitmap.isRecycled())
				bitmap.recycle();
		}
		file=tempFile;
		contentType=outputType;
		width=outputWidth;
		height=outputHeight;
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

	public int getWidth(){ return width; }
	public int getHeight(){ return height; }

	@Override
	public MediaType contentType(){ return contentType; }

	@Override
	protected Source openSource() throws IOException{
		return Okio.source(file);
	}

	public void close(){ file.delete(); }
}
