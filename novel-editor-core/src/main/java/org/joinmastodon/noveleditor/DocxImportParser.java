package org.joinmastodon.noveleditor;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.joinmastodon.noveleditor.ImportModels.*;

/** Plain-text OOXML importer; unsupported rich objects are reported as warnings. */
public final class DocxImportParser{
	public Result parse(File file,Cancellation cancellation){
		if(file.length()>ImportLimits.SOURCE_BYTES) throw new ImportException("SOURCE_TOO_LARGE","DOCX file is too large");
		try(SafeZip zip=new SafeZip(file)){
			if(!zip.has("word/document.xml")) throw new ImportException("PACKAGE_MISSING","word/document.xml is missing");
			Map<String,Integer> headings=zip.has("word/styles.xml") ? styles(XmlDocuments.parse(zip.read("word/styles.xml",ImportLimits.PACKAGE_XML_BYTES,cancellation),"")) : Map.of();
			Document document=XmlDocuments.parse(zip.read("word/document.xml",ImportLimits.DOCX_DOCUMENT_BYTES,cancellation),"");
			ArrayList<Warning> warnings=new ArrayList<>();
			if(!document.getElementsByTag("w:drawing").isEmpty() || !document.getElementsByTag("w:pict").isEmpty()) warnings.add(new Warning("DOCX_IMAGES_SKIPPED","Pictures are not preserved in the editable text",null));
			if(!document.getElementsByTag("w:footnoteReference").isEmpty() || !document.getElementsByTag("w:endnoteReference").isEmpty()) warnings.add(new Warning("DOCX_NOTES_SKIPPED","Footnotes and endnotes are not preserved",null));
			ArrayList<Chapter> chapters=new ArrayList<>(); String title="正文"; StringBuilder body=new StringBuilder(); int ordinal=0, paragraph=0, total=0;
			for(Element p:document.getElementsByTag("w:p")){
				cancellation.throwIfCancelled(); paragraph++;
				String text=paragraph(p); if(text.isBlank()) { if(body.length()>0) body.append('\n'); continue; }
				String style=p.selectFirst("w|pPr > w|pStyle")!=null ? p.selectFirst("w|pPr > w|pStyle").attr("w:val") : "";
				boolean heading=headings.getOrDefault(style,99)<=8 || style.toLowerCase(Locale.ROOT).matches("heading[1-9]|标题[1-9]") || (body.length()>0 && text.matches("^第.{1,18}[章节卷部篇回].{0,40}$"));
				if(heading && body.length()>0){ ordinal=ChapterSplitter.flush(chapters,ordinal,title,body.toString(),"paragraph:"+paragraph); body.setLength(0); title=text; }
				else if(heading && body.length()==0) title=text;
				else{ if(body.length()>0) body.append('\n'); body.append(text); total+=text.length(); if(total>ImportLimits.TOTAL_TEXT) throw new ImportException("TEXT_LIMIT_EXCEEDED","Document contains too much text"); }
			}
			ChapterSplitter.flush(chapters,ordinal,title,body.toString(),"paragraph:"+paragraph);
			if(chapters.isEmpty()) throw new ImportException("NO_READABLE_TEXT","DOCX contains no readable text");
			String suggested=file.getName().replaceFirst("(?i)\\.docx$",""); String author=null;
			if(zip.has("docProps/core.xml")){
				Document core=XmlDocuments.parse(zip.read("docProps/core.xml",ImportLimits.PACKAGE_XML_BYTES,cancellation),"");
				Element t=core.getElementsByTag("dc:title").first(), a=core.getElementsByTag("dc:creator").first();
				if(t!=null && !t.text().isBlank()) suggested=t.text().trim(); if(a!=null && !a.text().isBlank()) author=a.text().trim();
			}
			return new Result(new Metadata(suggested,author,null,"docx",null,100),chapters,warnings);
		}
	}
	private static Map<String,Integer> styles(Document document){
		HashMap<String,Integer> result=new HashMap<>();
		for(Element style:document.getElementsByTag("w:style")){
			if(!"paragraph".equalsIgnoreCase(style.attr("w:type"))) continue;
			String id=style.attr("w:styleId"); Element outline=style.selectFirst("w|pPr > w|outlineLvl");
			if(!id.isBlank() && outline!=null) try{ result.put(id,Integer.parseInt(outline.attr("w:val"))); }catch(NumberFormatException ignored){}
		}
		return result;
	}
	private static String paragraph(Element p){
		StringBuilder result=new StringBuilder();
		for(Element child:p.getAllElements()){
			String tag=child.tagName();
			if(tag.equals("w:del")){ child.empty(); continue; }
			if(tag.equals("w:t") && child.parents().stream().noneMatch(v->v.tagName().equals("w:del"))) result.append(child.ownText());
			else if(tag.equals("w:tab")) result.append('\t');
			else if(tag.equals("w:br") || tag.equals("w:cr")) result.append('\n');
		}
		return EditorText.normalize(result.toString()).strip();
	}
}
