package org.joinmastodon.android.verification;

import android.content.Context;
import org.joinmastodon.android.ui.media.MediaCameraContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import androidx.annotation.Nullable;

/** Private, account-bound recovery state. Never infer ownership of legacy files. */
public record VerificationPendingCapture(String sessionId, String qq, String declarationVersion, String requirement, long expiresAt, long maxEvidenceSize, String accountId, long userId){
	private static final String FILE_NAME="pending.properties";

	public boolean belongsTo(String account, long user){ return accountId!=null && accountId.equals(account) && userId>0 && userId==user; }

	public void save(Context context) throws IOException{
		if(!validSessionId(sessionId) || accountId==null || accountId.isBlank() || userId<=0) throw new IOException("无法保存认证拍摄状态");
		Properties values=new Properties();
		values.setProperty("session_id", sessionId);
		values.setProperty("qq", qq);
		values.setProperty("declaration_version", declarationVersion);
		values.setProperty("requirement", requirement);
		values.setProperty("expires_at", Long.toString(expiresAt));
		values.setProperty("max_evidence_size", Long.toString(maxEvidenceSize));
		values.setProperty("account_id", accountId);
		values.setProperty("user_id", Long.toString(userId));
		File root=root(context, sessionId);
		if(!MediaCameraContract.isControlledFile(context, root)) throw new IOException("无法保存认证拍摄状态");
		if(!root.isDirectory() && !root.mkdirs()) throw new IOException("无法保存认证拍摄状态");
		File pending=new File(root, FILE_NAME+".part"), destination=new File(root, FILE_NAME);
		if(!MediaCameraContract.isControlledFile(context, pending) || !MediaCameraContract.isControlledFile(context, destination)) throw new IOException("无法保存认证拍摄状态");
		Properties existing=properties(context, sessionId);
		if(destination.exists() && (existing==null || !accountId.equals(existing.getProperty("account_id")) || !Long.toString(userId).equals(existing.getProperty("user_id"))))
			throw new IOException("认证拍摄状态所属账号不匹配");
		try(FileOutputStream output=new FileOutputStream(pending)){ values.store(output, null); output.getFD().sync(); }
		if(destination.exists() && !destination.delete()){ pending.delete(); throw new IOException("无法更新认证拍摄状态"); }
		if(!pending.renameTo(destination)){ pending.delete(); throw new IOException("无法保存认证拍摄状态"); }
	}

	private static Properties properties(Context context, String sessionId){
		if(!validSessionId(sessionId)) return null;
		File file=new File(root(context, sessionId), FILE_NAME);
		try{
			if(!MediaCameraContract.isControlledFile(context, file) || !file.isFile()) return null;
			Properties values=new Properties();
			try(FileInputStream input=new FileInputStream(file)){ values.load(input); }
			return values;
		}catch(Exception ignored){ return null; }
	}

	@Nullable public static VerificationPendingCapture load(Context context, String sessionId, String account, long user){
		Properties values=properties(context, sessionId);
		if(values==null || account==null || user<=0 || !account.equals(values.getProperty("account_id")) || !Long.toString(user).equals(values.getProperty("user_id"))) return null;
		try{
			String qq=values.getProperty("qq"), version=values.getProperty("declaration_version"), requirement=values.getProperty("requirement");
			long expires=Long.parseLong(values.getProperty("expires_at", "0")), max=Long.parseLong(values.getProperty("max_evidence_size", "0"));
			if(!sessionId.equals(values.getProperty("session_id")) || qq==null || !qq.matches("\\d{5,20}") || version==null || version.isBlank() || requirement==null || requirement.isBlank() || expires<=0 || max<1024) return null;
			return new VerificationPendingCapture(sessionId, qq, version, requirement, expires, max, account, user);
		}catch(Exception ignored){ return null; }
	}

	/** Includes expired captures so the UI can explicitly explain why a retake is needed. */
	@Nullable public static VerificationPendingCapture findPending(Context context, String account, long user){
		File[] children=new File(context.getNoBackupFilesDir(), "verification").listFiles(File::isDirectory);
		if(children==null) return null;
		VerificationPendingCapture newest=null;
		for(File child:children){
			VerificationPendingCapture value=load(context, child.getName(), account, user);
			if(value==null){
				Properties metadata=properties(context, child.getName());
				// Legacy/no-owner data is never shown or sent to the server. Controlled local cleanup only.
				if(metadata==null || (metadata.getProperty("account_id")==null && metadata.getProperty("user_id")==null)) deleteControlled(context, child.getName());
				continue;
			}
			if(newest==null || value.expiresAt>newest.expiresAt) newest=value;
		}
		return newest;
	}

	@Nullable public static VerificationPendingCapture findActive(Context context, long now, String account, long user){
		VerificationPendingCapture value=findPending(context, account, user);
		if(value!=null && value.expiresAt<=now){ value.delete(context, account, user); return null; }
		return value;
	}
	public File photoFile(Context context){ return new File(root(context, sessionId), "capture.jpg"); }
	public File rawFile(Context context){ return new File(root(context, sessionId), "raw-0.jpg"); }
	public boolean hasRecoverablePhoto(Context context){ return controlledNonempty(context, photoFile(context)) || controlledNonempty(context, rawFile(context)); }
	private static boolean controlledNonempty(Context context, File file){
		try{ return MediaCameraContract.isControlledFile(context, file) && file.isFile() && file.length()>0; }
		catch(IOException ignored){ return false; }
	}
	public void deleteMetadata(Context context, String account, long user){
		if(!belongsTo(account, user) || load(context, sessionId, account, user)==null) return;
		File file=new File(root(context, sessionId), FILE_NAME);
		try{ if(MediaCameraContract.isControlledFile(context, file)) file.delete(); }catch(IOException ignored){}
	}
	public void delete(Context context, String account, long user){
		if(belongsTo(account, user) && load(context, sessionId, account, user)!=null) deleteControlled(context, sessionId);
	}
	private static void deleteControlled(Context context, String sessionId){
		try{ File directory=root(context, sessionId); if(validSessionId(sessionId) && MediaCameraContract.isControlledFile(context, directory)) VerificationImageProcessor.deleteTree(directory); }
		catch(IOException ignored){}
	}
	public static File root(Context context, String sessionId){ return new File(context.getNoBackupFilesDir(), "verification/"+sessionId); }
	private static boolean validSessionId(String value){ return value!=null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"); }
}
