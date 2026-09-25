package org.joinmastodon.noveleditor;

public final class ImportLimits{
	private ImportLimits(){}
	public static final long SOURCE_BYTES=50L*1024*1024;
	public static final int ZIP_ENTRIES=4096;
	public static final long ZIP_EXPANDED_BYTES=128L*1024*1024;
	public static final long PACKAGE_XML_BYTES=2L*1024*1024;
	public static final long XHTML_BYTES=8L*1024*1024;
	public static final long DOCX_DOCUMENT_BYTES=64L*1024*1024;
	public static final int CHAPTERS=10_000;
	public static final int TOTAL_TEXT=20_000_000;
	public static final int ZIP_RATIO=100;
}
