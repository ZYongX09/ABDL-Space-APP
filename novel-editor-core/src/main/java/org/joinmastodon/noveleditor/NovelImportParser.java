package org.joinmastodon.noveleditor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipFile;

import static org.joinmastodon.noveleditor.ImportModels.*;

/** Content-sniffed dispatcher; MIME and file extension are hints, not trust boundaries. */
public final class NovelImportParser{
	public Result parse(File file,String displayName,Cancellation cancellation){
		if(file==null || !file.isFile() || file.length()<=0) throw new ImportException("SOURCE_EMPTY","Selected file is empty");
		if(file.length()>ImportLimits.SOURCE_BYTES) throw new ImportException("SOURCE_TOO_LARGE","Selected file is too large");
		String format=format(file,displayName);
		return switch(format){
			case "txt" -> new TxtImportParser().parse(file,cancellation);
			case "docx" -> new DocxImportParser().parse(file,cancellation);
			case "epub" -> new EpubImportParser().parse(file,cancellation);
			default -> throw new ImportException("UNSUPPORTED_FORMAT","Only TXT, DOCX and EPUB are supported");
		};
	}
	private static String format(File file,String name){
		String lower=(name==null?file.getName():name).toLowerCase(Locale.ROOT);
		if(lower.endsWith(".txt")) return "txt";
		try(ZipFile zip=new ZipFile(file)){
			if(zip.getEntry("word/document.xml")!=null) return "docx";
			if(zip.getEntry("META-INF/container.xml")!=null){
				var mimetype=zip.getEntry("mimetype");
				if(mimetype!=null) try(var in=zip.getInputStream(mimetype)){ if("application/epub+zip".equals(new String(in.readNBytes(64),StandardCharsets.US_ASCII))) return "epub"; }
				return "epub";
			}
		}catch(IOException ignored){}
		if(lower.endsWith(".docx") || lower.endsWith(".epub")) throw new ImportException("CORRUPT_ZIP","Document archive is invalid");
		try(FileInputStream input=new FileInputStream(file)){ byte[] first=input.readNBytes(512); for(byte b:first) if(b==0) throw new ImportException("UNSUPPORTED_FORMAT","Binary file is not supported"); return "txt"; }
		catch(IOException error){ throw new ImportException("URI_PERMISSION_DENIED","Unable to read selected file",error); }
	}
}
