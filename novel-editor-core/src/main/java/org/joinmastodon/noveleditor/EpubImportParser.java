package org.joinmastodon.noveleditor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.joinmastodon.noveleditor.ImportModels.*;

/** EPUB 2/3 importer: package/spine order is authoritative; remote resources are never loaded. */
public final class EpubImportParser{
	private static final Set<String> BLOCKS=Set.of("p","div","li","blockquote","h1","h2","h3","h4","h5","h6","section","article","tr");
	public Result parse(File file,Cancellation cancellation){
		if(file.length()>ImportLimits.SOURCE_BYTES) throw new ImportException("SOURCE_TOO_LARGE","EPUB file is too large");
		try(SafeZip zip=new SafeZip(file)){
			if(zip.has("META-INF/encryption.xml")){
				Document encryption=XmlDocuments.parse(zip.read("META-INF/encryption.xml",ImportLimits.PACKAGE_XML_BYTES,cancellation),"");
				for(Element reference:encryption.getElementsByTag("CipherReference")){
					String uri=reference.attr("URI").toLowerCase(java.util.Locale.ROOT);
					if(uri.endsWith(".xhtml") || uri.endsWith(".html") || uri.endsWith(".htm")) throw new ImportException("DRM_EPUB","Encrypted EPUB text cannot be imported");
				}
			}
			Document container=XmlDocuments.parse(zip.read("META-INF/container.xml",ImportLimits.PACKAGE_XML_BYTES,cancellation),"");
			Element rootfile=container.getElementsByTag("rootfile").first();
			if(rootfile==null || rootfile.attr("full-path").isBlank()) throw new ImportException("PACKAGE_MISSING","EPUB package path is missing");
			String opfPath=SafeZip.path(rootfile.attr("full-path"));
			Document opf=XmlDocuments.parse(zip.read(opfPath,ImportLimits.PACKAGE_XML_BYTES,cancellation),opfPath);
			String title=text(opf,"dc:title",file.getName().replaceFirst("(?i)\\.epub$",""));
			String author=text(opf,"dc:creator",null), language=text(opf,"dc:language",null);
			Map<String,Item> manifest=new HashMap<>(); String navPath=null, ncxPath=null;
			for(Element item:opf.getElementsByTag("item")){
				String id=item.attr("id"), href=item.attr("href"), type=item.attr("media-type");
				if(id.isBlank() || href.isBlank()) continue;
				String path=SafeZip.resolve(opfPath,href); manifest.put(id,new Item(path,type));
				if(item.attr("properties").contains("nav")) navPath=path;
				if("application/x-dtbncx+xml".equals(type)) ncxPath=path;
			}
			Map<String,String> labels=navPath!=null ? navLabels(zip,navPath,cancellation) : ncxPath!=null ? ncxLabels(zip,ncxPath,cancellation) : Map.of();
			ArrayList<Chapter> chapters=new ArrayList<>(); ArrayList<Warning> warnings=new ArrayList<>(); int ordinal=0,total=0;
			if(zip.has("META-INF/encryption.xml")) warnings.add(new Warning("EPUB_ENCRYPTION_PRESENT","Font or resource encryption was detected; editable text was imported where possible",null));
			for(Element itemref:opf.getElementsByTag("itemref")){
				cancellation.throwIfCancelled();
				if("no".equalsIgnoreCase(itemref.attr("linear"))) continue;
				Item item=manifest.get(itemref.attr("idref"));
				if(item==null || !("application/xhtml+xml".equals(item.mediaType) || "text/html".equals(item.mediaType))) continue;
				byte[] bytes=zip.read(item.path,ImportLimits.XHTML_BYTES,cancellation);
				Document xhtml=Jsoup.parse(new String(bytes,StandardCharsets.UTF_8),item.path,Parser.xmlParser());
				Element body=xhtml.body(); if(body==null) continue;
				if(!body.select("img,svg,image").isEmpty()) warnings.add(new Warning("EPUB_IMAGES_SKIPPED","Pictures are not preserved in editable text",item.path));
				body.select("script,style,nav,noscript").remove();
				String content=plainText(body);
				if(content.isBlank()) continue;
				total+=content.length(); if(total>ImportLimits.TOTAL_TEXT) throw new ImportException("TEXT_LIMIT_EXCEEDED","EPUB contains too much text");
				String label=labels.get(item.path);
				if(label==null || label.isBlank()){
					Element heading=body.selectFirst("h1,h2,h3"); label=heading!=null && !heading.text().isBlank() ? heading.text().trim() : item.path.substring(item.path.lastIndexOf('/')+1).replaceFirst("\\.[^.]+$","");
				}
				ordinal=ChapterSplitter.flush(chapters,ordinal,label,content,item.path);
			}
			if(chapters.isEmpty()) throw new ImportException("NO_READABLE_TEXT","EPUB contains no readable chapters");
			return new Result(new Metadata(title,author,language,"epub","UTF-8",100),chapters,List.copyOf(new HashSet<>(warnings)));
		}
	}
	private static String text(Document document,String tag,String fallback){ Element value=document.getElementsByTag(tag).first(); return value==null || value.text().isBlank()?fallback:value.text().trim(); }
	private static Map<String,String> navLabels(SafeZip zip,String path,Cancellation cancellation){
		Document nav=Jsoup.parse(new String(zip.read(path,ImportLimits.XHTML_BYTES,cancellation),StandardCharsets.UTF_8),path,Parser.xmlParser());
		HashMap<String,String> labels=new HashMap<>();
		for(Element link:nav.select("nav a[href]")) try{ labels.put(SafeZip.resolve(path,link.attr("href")),link.text().trim()); }catch(ImportException ignored){}
		return labels;
	}
	private static Map<String,String> ncxLabels(SafeZip zip,String path,Cancellation cancellation){
		Document ncx=XmlDocuments.parse(zip.read(path,ImportLimits.PACKAGE_XML_BYTES,cancellation),path); HashMap<String,String> labels=new HashMap<>();
		for(Element point:ncx.getElementsByTag("navPoint")){ Element content=point.getElementsByTag("content").first(), label=point.getElementsByTag("text").first(); if(content!=null && label!=null) try{ labels.put(SafeZip.resolve(path,content.attr("src")),label.text().trim()); }catch(ImportException ignored){} }
		return labels;
	}
	private static String plainText(Element root){
		StringBuilder out=new StringBuilder();
		NodeTraversor.traverse(new NodeVisitor(){
			@Override public void head(Node node,int depth){
				if(node instanceof TextNode text) append(out,text.getWholeText());
				else if(node instanceof Element element && (element.tagName().equals("br") || element.tagName().equals("hr"))) newline(out);
			}
			@Override public void tail(Node node,int depth){ if(node instanceof Element element && BLOCKS.contains(element.tagName())) newline(out); }
		},root);
		return out.toString().replaceAll("[ \\t]+\\n","\n").replaceAll("\\n{3,}","\n\n").strip();
	}
	private static void append(StringBuilder out,String text){ String normalized=text.replace('\u00a0',' '); if(normalized.isBlank()){ if(out.length()>0 && out.charAt(out.length()-1)!='\n' && out.charAt(out.length()-1)!=' ') out.append(' '); } else out.append(normalized); }
	private static void newline(StringBuilder out){ if(out.length()>0 && out.charAt(out.length()-1)!='\n') out.append('\n'); }
	private record Item(String path,String mediaType){}
}
