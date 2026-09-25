package org.joinmastodon.noveleditor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.joinmastodon.noveleditor.ImportModels.*;

/** Common plain-text chapter splitter with bounded chapter size. */
public final class ChapterSplitter{
	private static final Pattern CHINESE=Pattern.compile("^第[零〇一二三四五六七八九十百千万两0-9]+[章节卷部篇回].{0,40}$");
	private static final Pattern ENGLISH=Pattern.compile("^(chapter|part|volume|book)\\s+([0-9ivxlcdm]+|[a-z]+).{0,40}$", Pattern.CASE_INSENSITIVE);
	private ChapterSplitter(){}

	public static List<Chapter> split(String text){
		String normalized=EditorText.normalize(text);
		ArrayList<Chapter> chapters=new ArrayList<>(); String title="正文"; StringBuilder body=new StringBuilder(); int ordinal=0;
		for(String line:normalized.split("\n", -1)){
			String clean=line.trim();
			if(!clean.isEmpty() && heading(clean) && body.length()>0){
				ordinal=flush(chapters, ordinal, title, body.toString(), null); body.setLength(0); title=clean;
			}else if(!clean.isEmpty() && heading(clean) && body.length()==0){ title=clean; }
			else{ if(body.length()>0) body.append('\n'); body.append(line); }
		}
		flush(chapters, ordinal, title, body.toString(), null);
		if(chapters.isEmpty()) chapters.add(new Chapter(0, "正文", "", null));
		return chapters;
	}

	private static boolean heading(String line){ return CHINESE.matcher(line).matches() || ENGLISH.matcher(line.toLowerCase(Locale.ROOT)).matches(); }
	static int flush(List<Chapter> out,int ordinal,String title,String content,String anchor){
		String clean=content.strip();
		if(clean.isEmpty() && !out.isEmpty()) return ordinal;
		for(int start=0, part=1; start<Math.max(1,clean.length()); start+=EditorLimits.CHAPTER_CONTENT,part++){
			int end=Math.min(clean.length(), start+EditorLimits.CHAPTER_CONTENT);
			String partTitle=clean.length()>EditorLimits.CHAPTER_CONTENT ? title+"（"+part+"）" : title;
			out.add(new Chapter(ordinal++, partTitle, clean.substring(start,end), anchor));
			if(clean.isEmpty()) break;
			if(out.size()>ImportLimits.CHAPTERS) throw new ImportException("TOO_MANY_CHAPTERS", "Document contains too many chapters");
		}
		return ordinal;
	}
}
