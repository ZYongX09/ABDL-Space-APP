package org.joinmastodon.android.api;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.BufferedSink;
import okio.Okio;

public class CosProgressRequestBody extends RequestBody{
	private final RequestBody delegate;
	private final ProgressListener listener;

	public CosProgressRequestBody(RequestBody delegate, ProgressListener listener){
		this.delegate=delegate;
		this.listener=listener;
	}

	@Override
	public MediaType contentType(){ return delegate.contentType(); }

	@Override
	public long contentLength() throws IOException{ return delegate.contentLength(); }

	@Override
	public void writeTo(BufferedSink sink) throws IOException{
		long length=contentLength();
		BufferedSink countingSink=Okio.buffer(new CountingSink(length, listener, sink));
		delegate.writeTo(countingSink);
		countingSink.flush();
	}
}
