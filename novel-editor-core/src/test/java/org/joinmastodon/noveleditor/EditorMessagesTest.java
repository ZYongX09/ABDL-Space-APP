package org.joinmastodon.noveleditor;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="en-rUS")
public class EditorMessagesTest {
    private final Context context=ApplicationProvider.getApplicationContext();

    @Test public void constructorsAndDiagnosticsStayCompatibleWhileDisplayUsesStableCode(){
        RuntimeException cause=new RuntimeException("private diagnostic");
        ImportException simple=new ImportException("SOURCE_EMPTY","original diagnostic");
        ImportException wrapped=new ImportException("SOURCE_EMPTY","original diagnostic",cause);
        assertEquals("original diagnostic",simple.getMessage());
        assertEquals("SOURCE_EMPTY",simple.code());
        assertSame(cause,wrapped.getCause());
        assertEquals("The selected file is empty.",simple.userMessage(context));
        ImportModels.Warning warning=new ImportModels.Warning("DOCX_IMAGES_SKIPPED","original warning",null);
        assertEquals("original warning",warning.message());
        assertEquals("Images are not preserved in editable text.",warning.userMessage(context));
    }

    @Test @Config(qualifiers="zh-rCN") public void chineseDisplayDoesNotChangeWarningOrExceptionData(){
        ImportModels.Warning warning=new ImportModels.Warning("DOCX_NOTES_SKIPPED","original warning",null);
        assertEquals("不保留脚注及尾注。",warning.userMessage(context));
        assertEquals("original warning",warning.message());
        assertEquals("请选择两个或更多相邻章节。",EditorMessages.localize(context,"Select adjacent chapters"));
    }
}
