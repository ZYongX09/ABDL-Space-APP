package org.joinmastodon.noveleditor;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.joinmastodon.noveleditor.ImportModels.*;

/** Deterministic TXT parser; ambiguous legacy encodings are surfaced to the import review. */
public final class TxtImportParser{
	public Result parse(File file, Cancellation cancellation){
		if(file.length()>ImportLimits.SOURCE_BYTES) throw new ImportException("SOURCE_TOO_LARGE", "Text file is too large");
		try{
			byte[] bytes=Files.readAllBytes(file.toPath());
			cancellation.throwIfCancelled();
			Decoded decoded=decode(bytes);
			String text=EditorText.normalize(decoded.text);
			if(text.isBlank()) throw new ImportException("NO_READABLE_TEXT", "Text file does not contain readable content");
			List<Chapter> chapters=ChapterSplitter.split(text);
			String title=file.getName().replaceFirst("(?i)\\.txt$", "");
			return new Result(new Metadata(title, null, null, "txt", decoded.encoding, decoded.confidence), chapters,
					decoded.confidence<80 ? List.of(new Warning("ENCODING_AMBIGUOUS", "Please review the detected text encoding", null)) : List.of());
		}catch(IOException error){ throw new ImportException("TEXT_DECODE_FAILED", "Unable to read text file", error); }
	}

	private static Decoded decode(byte[] bytes){
		if(starts(bytes, new int[]{0xef,0xbb,0xbf})) return new Decoded(strict(bytes,3, StandardCharsets.UTF_8), "UTF-8",100);
		if(starts(bytes, new int[]{0xff,0xfe})) return new Decoded(strict(bytes,2, StandardCharsets.UTF_16LE), "UTF-16LE",100);
		if(starts(bytes, new int[]{0xfe,0xff})) return new Decoded(strict(bytes,2, StandardCharsets.UTF_16BE), "UTF-16BE",100);
		try{ return new Decoded(strict(bytes,0,StandardCharsets.UTF_8),"UTF-8",100); }
		catch(ImportException ignored){}
		ArrayList<Decoded> candidates=new ArrayList<>();
		for(String name:new String[]{"GB18030","Big5"}) try{
			String text=strict(bytes,0,Charset.forName(name)); candidates.add(new Decoded(text,name,score(text)));
		}catch(RuntimeException ignored){}
		return candidates.stream().max(java.util.Comparator.comparingInt(v->v.confidence)).orElseThrow(()->new ImportException("TEXT_DECODE_FAILED","Text encoding is not supported"));
	}
	private static String strict(byte[] bytes,int offset,Charset charset){
		try{
			CharBuffer decoded=charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset));
			return decoded.toString();
		}catch(CharacterCodingException error){ throw new ImportException("TEXT_DECODE_FAILED","Invalid "+charset.name()+" text",error); }
	}
	private static int score(String text){
		if(text.isBlank()) return 0; int printable=0, control=0, common=0;
		for(int i=0;i<text.length();i++){ char c=text.charAt(i); if(Character.isISOControl(c) && c!='\n' && c!='\t') control++; else printable++; if(c>=0x3400 && c<=0x9fff) common++; }
		return Math.max(1, Math.min(79, 40+(printable-control*20)*30/Math.max(1,text.length())+common*9/Math.max(1,text.length())));
	}
	private static boolean starts(byte[] bytes,int[] prefix){ if(bytes.length<prefix.length)return false; for(int i=0;i<prefix.length;i++)if((bytes[i]&255)!=prefix[i])return false; return true; }
	private record Decoded(String text,String encoding,int confidence){}
}
