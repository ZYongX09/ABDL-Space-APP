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

import okhttp3.MediaType;
import okio.Okio;
import okio.Source;

public class CosPreviewRequestBody extends CountingRequestBody{
	private final File file;
	private final MediaType contentType;
	private final int width;
	private final int height;

	public CosPreviewRequestBody(Uri uri, ProgressListener listener) throws IOException{
		super(listener);
		Bitmap bitmap;
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
			int targetWidth=Math.max(1, Math.round(bounds.outWidth*scale));
			int targetHeight=Math.max(1, Math.round(bounds.outHeight*scale));
			if(bitmap.getWidth()!=targetWidth || bitmap.getHeight()!=targetHeight)
				bitmap=Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
			int orientation=ExifInterface.ORIENTATION_NORMAL;
			try(InputStream input=MastodonApp.context.getContentResolver().openInputStream(uri)){
				orientation=new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
			}
			int rotation=switch(orientation){
				case ExifInterface.ORIENTATION_ROTATE_90 -> 90;
				case ExifInterface.ORIENTATION_ROTATE_180 -> 180;
				case ExifInterface.ORIENTATION_ROTATE_270 -> 270;
				default -> 0;
			};
			if(rotation!=0){
				Matrix matrix=new Matrix();
				matrix.setRotate(rotation);
				bitmap=Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
			}
		}
		width=bitmap.getWidth();
		height=bitmap.getHeight();
		boolean transparent=bitmap.hasAlpha();
		file=File.createTempFile("cos_preview", transparent ? ".webp" : ".jpg", MastodonApp.context.getCacheDir());
		try(FileOutputStream output=new FileOutputStream(file)){
			Bitmap.CompressFormat format=transparent
					? (Build.VERSION.SDK_INT>=30 ? Bitmap.CompressFormat.WEBP_LOSSLESS : Bitmap.CompressFormat.WEBP)
					: Bitmap.CompressFormat.JPEG;
			if(!bitmap.compress(format, transparent ? 100 : 78, output))
				throw new IOException("Unable to encode COS preview");
		}
		bitmap.recycle();
		length=file.length();
		contentType=MediaType.get(transparent ? "image/webp" : "image/jpeg");
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
