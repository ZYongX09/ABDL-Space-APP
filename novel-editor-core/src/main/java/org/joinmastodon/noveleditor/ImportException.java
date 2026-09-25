package org.joinmastodon.noveleditor;

/** Stable import error code; details must never contain chapter text. */
public final class ImportException extends RuntimeException{
	private final String code;
	public ImportException(String code, String message){ super(message); this.code=code; }
	public ImportException(String code, String message, Throwable cause){ super(message, cause); this.code=code; }
	public String code(){ return code; }
}
