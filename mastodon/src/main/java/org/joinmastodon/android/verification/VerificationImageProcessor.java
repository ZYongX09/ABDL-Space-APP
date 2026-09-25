package org.joinmastodon.android.verification;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import android.util.Base64;

/** Re-encodes trusted camera output, applies orientation, strips EXIF and computes upload digests. */
public final class VerificationImageProcessor{
	public static final int MAX_EDGE=2048;
	public static final long MIN_BYTES=3L*1024*1024;
	public static final long MAX_BYTES=5L*1024*1024;
	private VerificationImageProcessor(){}

	public static Result process(File source, File destination) throws Exception{
		return process(source, destination, MAX_BYTES);
	}

	public static Result process(File source, File destination, long maxEvidenceSize) throws Exception{
		validateMaxEvidenceSize(maxEvidenceSize);
		if(source==null || destination==null || !source.isFile()) throw new IOException("认证照片不存在");
		BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
		BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
		if(bounds.outWidth<=0 || bounds.outHeight<=0) throw new IOException("认证照片格式无效");
		int sample=1; while(Math.max(bounds.outWidth, bounds.outHeight)/sample>MAX_EDGE*2) sample*=2;
		BitmapFactory.Options decode=new BitmapFactory.Options(); decode.inSampleSize=sample; decode.inPreferredConfig=Bitmap.Config.ARGB_8888;
		Bitmap bitmap=BitmapFactory.decodeFile(source.getAbsolutePath(), decode);
		if(bitmap==null) throw new IOException("认证照片解码失败");
		try{
			int orientation=new ExifInterface(source.getAbsolutePath()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
			Bitmap oriented=transform(bitmap, orientation); if(oriented!=bitmap){ bitmap.recycle(); bitmap=oriented; }
			int longest=Math.max(bitmap.getWidth(), bitmap.getHeight());
			if(longest>MAX_EDGE){ float scale=MAX_EDGE/(float)longest; Bitmap scaled=Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth()*scale)), Math.max(1, Math.round(bitmap.getHeight()*scale)), true); bitmap.recycle(); bitmap=scaled; }
			byte[] encoded=encodeToRange(bitmap, maxEvidenceSize);
			File parent=destination.getParentFile(); if(parent==null || (!parent.isDirectory() && !parent.mkdirs())) throw new IOException("无法创建认证目录");
			File part=new File(parent, destination.getName()+".part");
			try(FileOutputStream output=new FileOutputStream(part)){ output.write(encoded); output.getFD().sync(); }
			if(destination.exists() && !destination.delete()){ part.delete(); throw new IOException("无法替换认证照片"); }
			if(!part.renameTo(destination)){ part.delete(); throw new IOException("无法保存认证照片"); }
				return new Result(destination, bitmap.getWidth(), bitmap.getHeight(), encoded.length, digestHex(encoded, "SHA-256"), digestBase64(encoded, "MD5"));
		}finally{ bitmap.recycle(); }
	}

	private static byte[] encodeToRange(Bitmap bitmap, long maxEvidenceSize) throws IOException{
		byte[] best=null;
		for(int quality=100;quality>=72;quality-=2){
			ByteArrayOutputStream output=new ByteArrayOutputStream();
			if(!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) throw new IOException("认证照片编码失败");
			byte[] value=output.toByteArray();
			if(value.length<=maxEvidenceSize){ best=value; if(value.length>=Math.min(MIN_BYTES, maxEvidenceSize)) return value; break; }
		}
		if(best==null) throw new IOException("认证照片超过服务端大小限制");
		// High-detail camera images normally reach the preferred floor. Do not pad or preserve metadata merely to reach it.
		return best;
	}

	public static Result inspectProcessed(File file) throws Exception{ return inspectProcessed(file, MAX_BYTES); }
	public static Result inspectProcessed(File file, long maxEvidenceSize) throws Exception{
		validateMaxEvidenceSize(maxEvidenceSize);
		if(file==null || !file.isFile() || file.length()<=0 || file.length()>maxEvidenceSize) throw new IOException("认证照片不存在或大小无效");
		BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true; BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
		if(bounds.outWidth<=0 || bounds.outHeight<=0 || Math.max(bounds.outWidth, bounds.outHeight)>MAX_EDGE) throw new IOException("已处理认证照片格式无效");
		try(FileInputStream input=new FileInputStream(file)){
			if(input.read()!=0xff || input.read()!=0xd8) throw new IOException("已处理认证照片不是 JPEG");
		}
		return new Result(file, bounds.outWidth, bounds.outHeight, file.length(), digestHex(file, "SHA-256"), digestBase64(file, "MD5"));
	}

	private static void validateMaxEvidenceSize(long value) throws IOException{
		if(value<1024 || value>50L*1024L*1024L) throw new IOException("认证照片大小限制无效");
	}
	private static Bitmap transform(Bitmap source, int orientation){
		Matrix matrix=new Matrix();
		switch(orientation){
			case ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1, 1);
			case ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180);
			case ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1, -1);
			case ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90); matrix.postScale(-1, 1); }
			case ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90);
			case ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90); matrix.postScale(-1, 1); }
			case ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90);
			default -> { return source; }
		}
		return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
	}
	private static String digestHex(byte[] bytes, String algorithm) throws Exception{ return hex(MessageDigest.getInstance(algorithm).digest(bytes)); }
	private static String digestHex(File file, String algorithm) throws Exception{ return hex(digest(file, algorithm)); }
	private static String digestBase64(byte[] bytes, String algorithm) throws Exception{ return Base64.encodeToString(MessageDigest.getInstance(algorithm).digest(bytes), Base64.NO_WRAP); }
	private static String digestBase64(File file, String algorithm) throws Exception{ return Base64.encodeToString(digest(file, algorithm), Base64.NO_WRAP); }
	private static byte[] digest(File file, String algorithm) throws Exception{
		MessageDigest digest=MessageDigest.getInstance(algorithm);
		try(FileInputStream input=new FileInputStream(file)){
			byte[] buffer=new byte[8192]; int read; while((read=input.read(buffer))!=-1) digest.update(buffer, 0, read);
		}
		return digest.digest();
	}
	private static String hex(byte[] bytes){ StringBuilder result=new StringBuilder(); for(byte value:bytes) result.append(String.format(java.util.Locale.US, "%02x", value)); return result.toString(); }
	public record Result(File file, int width, int height, long size, String sha256, String md5Base64){}
	public static void deleteTree(File file){ if(file==null || !file.exists()) return; if(file.isDirectory()){ File[] children=file.listFiles(); if(children!=null) for(File child:children) deleteTree(child); } file.delete(); }
}
