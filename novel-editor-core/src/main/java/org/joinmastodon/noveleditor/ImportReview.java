package org.joinmastodon.noveleditor;

import java.util.ArrayList;
import java.util.List;

/** Reversible import-only changes. This model cannot access published content or other drafts. */
public final class ImportReview{
	private final List<ImportModels.Chapter> original;
	private final ArrayList<ImportModels.Chapter> chapters;
	public ImportReview(List<ImportModels.Chapter> input){if(input.isEmpty())throw new IllegalArgumentException("Empty import");original=List.copyOf(input);chapters=new ArrayList<>(input);}
	public List<ImportModels.Chapter> chapters(){return List.copyOf(chapters);}
	public void reset(){chapters.clear();chapters.addAll(original);}
	public void move(int from,int to){if(from<0||to<0||from>=chapters.size()||to>=chapters.size())throw new IllegalArgumentException("Invalid position");if(from!=to)chapters.add(to,chapters.remove(from));}
	public void rename(int index,String title){ImportModels.Chapter c=chapters.get(index);chapters.set(index,new ImportModels.Chapter(index,EditorText.bounded(title,EditorLimits.CHAPTER_TITLE,"Title"),c.content(),c.sourceAnchor()));}
	public void merge(List<Integer> selected){
		if(selected.size()<2)throw new IllegalArgumentException("Select adjacent chapters");List<Integer> sorted=selected.stream().sorted().toList();for(int i=1;i<sorted.size();i++)if(sorted.get(i)!=sorted.get(i-1)+1)throw new IllegalArgumentException("Select adjacent chapters");
		int first=sorted.get(0),last=sorted.get(sorted.size()-1);StringBuilder body=new StringBuilder();for(int i=first;i<=last;i++){if(i>first)body.append("\n\n");body.append(chapters.get(i).content());}if(body.length()>EditorLimits.CHAPTER_CONTENT)throw new IllegalArgumentException("Merged chapter exceeds editor limit");ImportModels.Chapter c=chapters.get(first);chapters.subList(first,last+1).clear();chapters.add(first,new ImportModels.Chapter(first,c.title(),body.toString(),c.sourceAnchor()));
	}
	/** Offsets are after newline runs, so split+concatenation preserves every original character. */
	public static List<Integer> paragraphBoundaries(String body){ArrayList<Integer> result=new ArrayList<>();int first=-1,last=-1;for(int i=0;i<body.length();i++)if(!Character.isWhitespace(body.charAt(i))){if(first<0)first=i;last=i;}for(int i=0;i<body.length();i++)if(body.charAt(i)=='\n'){while(i+1<body.length()&&body.charAt(i+1)=='\n')i++;int p=i+1;if(first>=0&&p>first&&p<=last)result.add(p);}return result;}
	public void split(int index,int offset,String firstTitle,String secondTitle){ImportModels.Chapter c=chapters.get(index);if(!paragraphBoundaries(c.content()).contains(offset))throw new IllegalArgumentException("Select a paragraph boundary");String a=EditorText.bounded(firstTitle,EditorLimits.CHAPTER_TITLE,"Title"),b=EditorText.bounded(secondTitle,EditorLimits.CHAPTER_TITLE,"Title");chapters.set(index,new ImportModels.Chapter(index,a,c.content().substring(0,offset),c.sourceAnchor()));chapters.add(index+1,new ImportModels.Chapter(index+1,b,c.content().substring(offset),c.sourceAnchor()));}
	public ImportModels.Result result(ImportModels.Result source){ArrayList<ImportModels.Chapter> rows=new ArrayList<>();for(int i=0;i<chapters.size();i++){ImportModels.Chapter c=chapters.get(i);rows.add(new ImportModels.Chapter(i,c.title(),c.content(),c.sourceAnchor()));}return new ImportModels.Result(source.metadata(),rows,source.warnings());}
}
