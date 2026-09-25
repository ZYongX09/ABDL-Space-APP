package org.joinmastodon.android.verification;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

import androidx.annotation.Nullable;

/** Short-lived private state used only to reconnect a controlled camera result after recreation. */
public record VerificationPendingCapture(String sessionId, String qq, String declarationVersion, String requirement, long expiresAt, long maxEvidenceSize){
	private static final String FILE_NAME="pending.properties";

	public void save(Context context) throws IOException{
		Properties values=new Properties();
		values.setProperty("session_id", sessionId);
		values.setProperty("qq", qq);
		values.setProperty("declaration_version", declarationVersion);
		values.setProperty("requirement", requirement);
		values.setProperty("expires_at", Long.toString(expiresAt));
		values.setProperty("max_evidence_size", Long.toString(maxEvidenceSize));
		File root=root(context, sessionId);
		if(!root.isDirectory() && !root.mkdirs()) throw new IOException("无法保存认证拍摄状态");
		File pending=new File(root, FILE_NAME+".part");
		try(FileOutputStream output=new FileOutputStream(pending)){
			values.store(output, null);
			output.getFD().sync();
		}
		File destination=new File(root, FILE_NAME);
		if(destination.exists() && !destination.delete()){ pending.delete(); throw new IOException("无法更新认证拍摄状态"); }
		if(!pending.renameTo(destination)){ pending.delete(); throw new IOException("无法保存认证拍摄状态"); }
	}

	@Nullable
	public static VerificationPendingCapture load(Context context, String sessionId){
		if(!validSessionId(sessionId)) return null;
		File file=new File(root(context, sessionId), FILE_NAME);
		if(!file.isFile()) return null;
		Properties values=new Properties();
		try(FileInputStream input=new FileInputStream(file)){
			values.load(input);
			String storedId=values.getProperty("session_id");
			String qq=values.getProperty("qq");
			String declarationVersion=values.getProperty("declaration_version");
			String requirement=values.getProperty("requirement");
			long expiresAt=Long.parseLong(values.getProperty("expires_at", "0"));
			long maxEvidenceSize=Long.parseLong(values.getProperty("max_evidence_size", Long.toString(VerificationImageProcessor.MAX_BYTES)));
			if(!sessionId.equals(storedId) || qq==null || !qq.matches("\\d{5,20}") || declarationVersion==null || declarationVersion.isBlank()
					|| requirement==null || requirement.isBlank() || expiresAt<=0 || maxEvidenceSize<1024) return null;
			return new VerificationPendingCapture(sessionId, qq, declarationVersion, requirement, expiresAt, maxEvidenceSize);
		}catch(Exception ignored){
			return null;
		}
	}

	@Nullable
	public static VerificationPendingCapture findActive(Context context, long nowSeconds){
		File parent=new File(context.getNoBackupFilesDir(), "verification");
		File[] children=parent.listFiles(File::isDirectory);
		if(children==null) return null;
		VerificationPendingCapture newest=null;
		for(File child:children){
			VerificationPendingCapture value=load(context, child.getName());
			if(value==null) continue;
			if(value.expiresAt<=nowSeconds){ value.delete(context); continue; }
			if(newest==null || value.expiresAt>newest.expiresAt) newest=value;
		}
		return newest;
	}

	public File photoFile(Context context){
		return new File(root(context, sessionId), "capture.jpg");
	}

	public void deleteMetadata(Context context){
		new File(root(context, sessionId), FILE_NAME).delete();
	}

	public void delete(Context context){
		VerificationImageProcessor.deleteTree(root(context, sessionId));
	}

	public static File root(Context context, String sessionId){
		return new File(context.getNoBackupFilesDir(), "verification/"+sessionId);
	}

	private static boolean validSessionId(String value){
		return value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");
	}
}
