package org.joinmastodon.noveleditor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Bounded ZIP access for untrusted EPUB/DOCX files. */
public final class SafeZip implements AutoCloseable{
	private final ZipFile zip;
	private final Map<String, ZipEntry> entries=new HashMap<>();
	private long expanded;

	public SafeZip(File file){
		try{
			zip=new ZipFile(file);
			if(zip.size()>ImportLimits.ZIP_ENTRIES) throw new ImportException("ZIP_ENTRY_LIMIT", "Archive contains too many files");
			Set<String> normalized=new HashSet<>();
			var enumeration=zip.entries();
			while(enumeration.hasMoreElements()){
				ZipEntry entry=enumeration.nextElement();
				String path=path(entry.getName());
				if(!normalized.add(path)) throw new ImportException("ARCHIVE_PATH_INVALID", "Archive contains duplicate paths");
				entries.put(path, entry);
			}
		}catch(IOException error){ throw new ImportException("CORRUPT_ZIP", "Unable to open archive", error); }
	}

	public boolean has(String path){ return entries.containsKey(path(path)); }
	public Set<String> paths(){ return Set.copyOf(entries.keySet()); }

	public byte[] read(String path, long limit, ImportModels.Cancellation cancellation){
		ZipEntry entry=entries.get(path(path));
		if(entry==null) throw new ImportException("PACKAGE_MISSING", "Required archive item is missing");
		long declared=entry.getSize(), compressed=entry.getCompressedSize();
		if(declared>limit) throw new ImportException("ZIP_EXPANDED_SIZE_LIMIT", "Archive item is too large");
		if(declared>1024*1024 && compressed>0 && declared/compressed>ImportLimits.ZIP_RATIO) throw new ImportException("ZIP_RATIO_LIMIT", "Archive compression ratio is unsafe");
		try(InputStream input=zip.getInputStream(entry); ByteArrayOutputStream output=new ByteArrayOutputStream((int)Math.max(0, Math.min(declared, limit)))){
			byte[] buffer=new byte[32*1024]; long read=0;
			for(int count;(count=input.read(buffer))>=0;){
				cancellation.throwIfCancelled();
				read+=count; expanded+=count;
				if(read>limit || expanded>ImportLimits.ZIP_EXPANDED_BYTES) throw new ImportException("ZIP_EXPANDED_SIZE_LIMIT", "Expanded archive content is too large");
				output.write(buffer,0,count);
			}
			return output.toByteArray();
		}catch(IOException error){ throw new ImportException("CORRUPT_ZIP", "Unable to read archive item", error); }
	}

	public static String resolve(String baseFile, String relative){
		String base=baseFile.replace('\\','/');
		int slash=base.lastIndexOf('/');
		return path((slash<0 ? "" : base.substring(0, slash+1))+relative.split("#",2)[0]);
	}
	public static String path(String raw){
		if(raw==null || raw.isBlank() || raw.indexOf('\0')>=0) throw new ImportException("ARCHIVE_PATH_INVALID", "Archive path is invalid");
		String value=raw.replace('\\','/');
		if(value.startsWith("/") || value.matches("^[A-Za-z]:.*")) throw new ImportException("ARCHIVE_PATH_INVALID", "Archive path is absolute");
		java.util.ArrayDeque<String> parts=new java.util.ArrayDeque<>();
		for(String part:value.split("/")){
			if(part.isEmpty() || part.equals(".")) continue;
			if(part.equals("..")){ if(parts.isEmpty()) throw new ImportException("ARCHIVE_PATH_INVALID", "Archive path escapes root"); parts.removeLast(); }
			else parts.addLast(part);
		}
		if(parts.isEmpty()) throw new ImportException("ARCHIVE_PATH_INVALID", "Archive path is empty");
		return String.join("/", parts);
	}
	@Override public void close(){ try{ zip.close(); }catch(IOException ignored){} }
}
