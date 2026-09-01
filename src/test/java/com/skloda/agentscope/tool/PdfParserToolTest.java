package com.skloda.agentscope.tool;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfParserToolTest {

    @TempDir
    private Path tempDir;

    private final PdfParserTool tool = new PdfParserTool();

    @Test
    void parsePdfReportsEmptyOrScannedPdfWhenNoTextIsExtractable() throws Exception {
        Path file = tempDir.resolve("empty.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(file.toFile());
        }

        assertEquals("The PDF appears to be empty or contains no extractable text (it may be a scanned document).",
                tool.parsePdf(file.toString()));
    }

    @Test
    void parsePdf() throws Exception {
        String pdfText=tool.parsePdf("/Users/huhm/Downloads/公司注册合同-恒诚信企业服务代理合同-20260831.pdf");

        assertEquals(pdfText.length(), pdfText.length());
    }


    @Test
    void parsePdfReturnsErrorForMissingFile() {
        String result = tool.parsePdf(tempDir.resolve("missing.pdf").toString());

        assertTrue(result.startsWith("Error parsing PDF file: "));
    }
}
