package org.joinmastodon.noveleditor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;

import java.nio.charset.StandardCharsets;

final class XmlDocuments{
	private XmlDocuments(){}
	static Document parse(byte[] bytes,String base){
		String text=new String(bytes, StandardCharsets.UTF_8);
		if(text.regionMatches(true,0,"\ufeff",0,1)) text=text.substring(1);
		if(text.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(java.util.Locale.ROOT).contains("<!ENTITY"))
			throw new ImportException("XML_INVALID","Document type declarations are not allowed");
		try{ return Jsoup.parse(text, base, Parser.xmlParser()); }
		catch(RuntimeException error){ throw new ImportException("XML_INVALID","XML document is invalid",error); }
	}
}
