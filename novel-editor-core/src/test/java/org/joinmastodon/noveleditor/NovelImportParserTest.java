package org.joinmastodon.noveleditor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.joinmastodon.noveleditor.ImportModels.Cancellation.NONE;
import static org.junit.Assert.*;

/** JVM behavior tests; Android java.io API availability is checked separately by lint. */
public class NovelImportParserTest{
	@Rule public TemporaryFolder temporary=new TemporaryFolder();

	@Test public void importsPlainTextWithoutTxtExtension() throws IOException{
		File file=temporary.newFile("source");
		Files.write(file.toPath(),"Hello world.\r\nAnother line.".getBytes(StandardCharsets.UTF_8));
		for(String name:new String[]{null,"uploaded", "uploaded.bin"}){
			ImportModels.Result result=new NovelImportParser().parse(file,name,NONE);
			assertEquals("txt",result.metadata().format());
			assertEquals("UTF-8",result.metadata().encoding());
			assertEquals(1,result.chapters().size());
			assertEquals("Hello world.\nAnother line.",result.chapters().get(0).content());
		}
	}

	@Test public void rejectsNulAnywhereInSniffedPrefix() throws IOException{
		File file=temporary.newFile("source");
		for(int position:new int[]{0,17,511}){
			byte[] bytes=new byte[600];
			Arrays.fill(bytes,(byte)'a');
			bytes[position]=0;
			Files.write(file.toPath(),bytes);
			ImportException error=assertThrows(ImportException.class,()->new NovelImportParser().parse(file,"upload.bin",NONE));
			assertEquals("UNSUPPORTED_FORMAT",error.code());
		}
	}

	@Test public void txtExtensionStillBypassesBinarySniffForUtf16() throws IOException{
		File file=temporary.newFile("source");
		Files.write(file.toPath(),"\ufeffHello world.".getBytes(StandardCharsets.UTF_16LE));
		ImportModels.Result result=new NovelImportParser().parse(file,"upload.TXT",NONE);
		assertEquals("UTF-16LE",result.metadata().encoding());
		assertEquals("Hello world.",result.chapters().get(0).content());
	}

	@Test public void recognizesEpubMimetypeWithoutEpubExtension() throws IOException{
		assertEpub(createEpub("application/epub+zip"));
	}

	@Test public void epubContainerFallbackStillAcceptsMissingOrNonstandardMimetype() throws IOException{
		assertEpub(createEpub(null));
		assertEpub(createEpub("application/epub+zip\n"));
		assertEpub(createEpub("x".repeat(80)));
	}

	@Test public void mimetypeAloneDoesNotIdentifyEpub() throws IOException{
		File file=temporary.newFile();
		try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
			entry(zip,"mimetype","application/epub+zip");
		}
		ImportException error=assertThrows(ImportException.class,()->new NovelImportParser().parse(file,"upload.epub",NONE));
		assertEquals("CORRUPT_ZIP",error.code());
	}

	@Test public void prefixContinuesAfterShortReadsAndTrimsAtEof() throws IOException{
		byte[] bytes="application/epub+zip".getBytes(StandardCharsets.US_ASCII);
		assertArrayEquals(bytes,NovelImportParser.readPrefix(shortReads(bytes),64));
	}

	@Test public void prefixDoesNotConsumeBeyondEitherSniffingLimit() throws IOException{
		for(int limit:new int[]{64,512}){
			byte[] bytes=new byte[limit+1];
			Arrays.fill(bytes,(byte)'a');
			bytes[limit]='z';
			InputStream input=shortReads(bytes);
			assertArrayEquals(Arrays.copyOf(bytes,limit),NovelImportParser.readPrefix(input,limit));
			assertEquals('z',input.read());
			assertEquals(-1,input.read());
		}
	}

	@Test public void prefixHandlesEmptyStreamAndZeroLimit() throws IOException{
		assertArrayEquals(new byte[0],NovelImportParser.readPrefix(new ByteArrayInputStream(new byte[0]),512));
		InputStream input=new ByteArrayInputStream(new byte[]{42});
		assertArrayEquals(new byte[0],NovelImportParser.readPrefix(input,0));
		assertEquals(42,input.read());
	}

	@Test public void prefixMakesProgressAfterZeroByteRead() throws IOException{
		byte[] bytes=new byte[]{1,0,2};
		InputStream input=new ByteArrayInputStream(bytes){
			@Override public synchronized int read(byte[] buffer,int offset,int length){ return 0; }
		};
		assertArrayEquals(bytes,NovelImportParser.readPrefix(input,64));
	}

	@Test public void prefixPropagatesReadFailure(){
		IOException failure=new IOException("read failed");
		InputStream input=new InputStream(){
			@Override public int read() throws IOException{ throw failure; }
		};
		assertSame(failure,assertThrows(IOException.class,()->NovelImportParser.readPrefix(input,64)));
	}

	private static InputStream shortReads(byte[] bytes){
		return new ByteArrayInputStream(bytes){
			@Override public synchronized int read(byte[] buffer,int offset,int length){
				return super.read(buffer,offset,Math.min(length,3));
			}
		};
	}

	private static void assertEpub(File file){
		ImportModels.Result result=new NovelImportParser().parse(file,"upload.bin",NONE);
		assertEquals("epub",result.metadata().format());
		assertEquals("Test book",result.metadata().title());
		assertEquals(1,result.chapters().size());
		assertEquals("Hello EPUB.",result.chapters().get(0).content());
	}

	private File createEpub(String mimetype) throws IOException{
		File file=temporary.newFile();
		try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(file))){
			if(mimetype!=null) entry(zip,"mimetype",mimetype);
			entry(zip,"META-INF/container.xml","""
					<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
					  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
					</container>
					""");
			entry(zip,"OEBPS/content.opf","""
					<package xmlns="http://www.idpf.org/2007/opf" version="3.0">
					  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Test book</dc:title></metadata>
					  <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
					  <spine><itemref idref="chapter"/></spine>
					</package>
					""");
			entry(zip,"OEBPS/chapter.xhtml","<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>Chapter</title></head><body><p>Hello EPUB.</p></body></html>");
		}
		return file;
	}

	private static void entry(ZipOutputStream zip,String name,String text) throws IOException{
		zip.putNextEntry(new ZipEntry(name));
		zip.write(text.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}
}
