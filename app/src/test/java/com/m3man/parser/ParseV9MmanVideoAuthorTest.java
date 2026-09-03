package com.m3man.parser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ParseV9MmanVideoAuthorTest {

    @Test
    public void authorLinkUsesNameInsteadOfWorkCount() {
        String html = "<html><body>"
                + "<a href=\"uvideos.php?UID=author-token\">"
                + "<span class=\"username\">正确作者</span>"
                + "<span class=\"video-count\">123</span>"
                + "</a>"
                + "</body></html>";

        assertEquals("正确作者", ParseV9MmanVideo.extractOwnerNameForDisplay(html));
    }
}
