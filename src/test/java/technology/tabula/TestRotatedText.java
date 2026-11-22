package technology.tabula;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import technology.tabula.extractors.SpreadsheetExtractionAlgorithm;

/**
 * Verifies the optional rotated-text extraction path for lattice tables.
 *
 * <p>The fixture is generated at runtime so the test is fully deterministic and
 * does not depend on a binary blob. It draws a ruled table whose narrow first
 * column contains two vertical codes (one reading bottom-to-top, one
 * top-to-bottom) while the wider columns keep ordinary horizontal numbers and
 * labels, and it is rendered with page rotations of 0, 90 and 270 degrees.
 */
public class TestRotatedText {

    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private static final float FONT_SIZE = 10f;

    // Vertical codes and horizontal payload used by every generated page.
    private static final String VERTICAL_CODE_UP = "ABC01";   // matrix (0,1,-1,0)
    private static final String VERTICAL_CODE_DOWN = "ABC02"; // matrix (0,-1,1,0)

    private static File dir;
    private static File[] pdfs;

    @BeforeClass
    public static void generateFixtures() throws IOException {
        dir = Files.createTempDirectory("tabula-rotated-text").toFile();
        pdfs = new File[3];
        int[] rotations = {0, 90, 270};
        for (int i = 0; i < rotations.length; i++) {
            File pdf = new File(dir, "ledger_rot" + rotations[i] + ".pdf");
            try (PDDocument document = new PDDocument()) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setRotation(rotations[i]);
                document.addPage(page);
                drawLedger(document, page);
                document.save(pdf);
            }
            pdfs[i] = pdf;
        }
    }

    @AfterClass
    public static void deleteFixtures() {
        if (pdfs != null) {
            for (File pdf : pdfs) {
                if (pdf != null) {
                    pdf.delete();
                }
            }
        }
        if (dir != null) {
            dir.delete();
        }
    }

    private static void drawLedger(PDDocument document, PDPage page) throws IOException {
        float x0 = 60f;
        float y0 = 100f;
        float rowHeight = 90f;
        float[] columnWidths = {26f, 100f, 80f};
        float totalWidth = columnWidths[0] + columnWidths[1] + columnWidths[2];

        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.setLineWidth(1f);
            for (int r = 0; r <= 2; r++) {
                float y = y0 + r * rowHeight;
                cs.moveTo(x0, y);
                cs.lineTo(x0 + totalWidth, y);
            }
            float x = x0;
            cs.moveTo(x, y0);
            cs.lineTo(x, y0 + 2 * rowHeight);
            for (float width : columnWidths) {
                x += width;
                cs.moveTo(x, y0);
                cs.lineTo(x, y0 + 2 * rowHeight);
            }
            cs.stroke();

            cs.setFont(HELVETICA, FONT_SIZE);

            // Vertical codes in the narrow first column, one per row.
            writeVerticalUp(cs, VERTICAL_CODE_UP, x0 + 24f, y0 + 12f);
            writeVerticalDown(cs, VERTICAL_CODE_DOWN, x0 + 2f, y0 + rowHeight + 78f);

            // Horizontal payload keeps using the ordinary text matrix.
            writeHorizontal(cs, "123.45", x0 + columnWidths[0] + 6f, y0 + rowHeight - 14f);
            writeHorizontal(cs, "OK", x0 + columnWidths[0] + columnWidths[1] + 6f, y0 + rowHeight - 14f);
            writeHorizontal(cs, "67.89", x0 + columnWidths[0] + 6f, y0 + 14f);
            writeHorizontal(cs, "NG", x0 + columnWidths[0] + columnWidths[1] + 6f, y0 + 14f);
        }
    }

    private static void writeVerticalUp(PDPageContentStream cs, String word, float rightX, float baseY)
            throws IOException {
        cs.beginText();
        cs.setTextMatrix(new Matrix(0, 1, -1, 0, rightX, baseY));
        cs.showText(word);
        cs.endText();
    }

    private static void writeVerticalDown(PDPageContentStream cs, String word, float leftX, float topY)
            throws IOException {
        cs.beginText();
        cs.setTextMatrix(new Matrix(0, -1, 1, 0, leftX, topY));
        cs.showText(word);
        cs.endText();
    }

    private static void writeHorizontal(PDPageContentStream cs, String word, float x, float baselineY)
            throws IOException {
        cs.beginText();
        cs.setTextMatrix(Matrix.getTranslateInstance(x, baselineY));
        cs.showText(word);
        cs.endText();
    }

    // --------------------------------------------------------------------- //

    /** Runs the rotated lattice pipeline and returns every extracted table. */
    private static List<Table> extractRotated(File pdf) throws IOException {
        try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf);
                ObjectExtractor extractor = new ObjectExtractor(document, true)) {
            Page page = extractor.extract(1);
            return new SpreadsheetExtractionAlgorithm(true).extract(page);
        }
    }

    private static List<String> allCellTexts(Table table) {
        List<String> texts = new ArrayList<>();
        for (List<RectangularTextContainer> row : table.getRows()) {
            for (RectangularTextContainer cell : row) {
                texts.add(cell.getText());
            }
        }
        return texts;
    }

    private static String joinedCellTexts(Table table) {
        StringBuilder sb = new StringBuilder();
        for (String text : allCellTexts(table)) {
            sb.append(text).append('|');
        }
        return sb.toString();
    }

    @Test
    public void verticalCodesAreReadInOrderForEveryPageRotation() throws IOException {
        for (File pdf : pdfs) {
            List<Table> tables = extractRotated(pdf);
            assertEquals("expected one table in " + pdf.getName(), 1, tables.size());
            String joined = joinedCellTexts(tables.get(0));

            // The codes must appear whole, never reversed (e.g. "10CBA") and never
            // split across neighboring cells.
            assertTrue(pdf.getName() + " missing vertical-up code, got: " + joined,
                    joined.contains(VERTICAL_CODE_UP));
            assertTrue(pdf.getName() + " missing vertical-down code, got: " + joined,
                    joined.contains(VERTICAL_CODE_DOWN));
            assertFalse(pdf.getName() + " reversed up code leaked through: " + joined,
                    joined.contains(reverse(VERTICAL_CODE_UP)));
            assertFalse(pdf.getName() + " reversed down code leaked through: " + joined,
                    joined.contains(reverse(VERTICAL_CODE_DOWN)));
        }
    }

    @Test
    public void horizontalPayloadCoexistsWithVerticalCodes() throws IOException {
        for (File pdf : pdfs) {
            Table table = extractRotated(pdf).get(0);
            String joined = joinedCellTexts(table);
            for (String token : new String[] {"123.45", "67.89", "OK", "NG"}) {
                assertTrue(pdf.getName() + " lost horizontal token " + token + ": " + joined,
                        joined.contains(token));
            }
        }
    }

    @Test
    public void verticalCodesNeverLeakIntoAdjacentCells() throws IOException {
        for (File pdf : pdfs) {
            Table table = extractRotated(pdf).get(0);
            for (List<RectangularTextContainer> row : table.getRows()) {
                for (RectangularTextContainer cell : row) {
                    String text = cell.getText();
                    // A cell is either empty, holds a whole vertical code, or holds
                    // only horizontal payload. It must never mix a vertical code
                    // with content from the adjacent numeric/label columns.
                    if (text.contains("ABC")) {
                        assertTrue("vertical code mixed with foreign content: " + text,
                                text.equals(VERTICAL_CODE_UP) || text.equals(VERTICAL_CODE_DOWN));
                    }
                }
            }
        }
    }

    @Test
    public void characterDirectionsArePageFrameDirections() throws IOException {
        // With rotation enabled every glyph direction must be one of the four
        // axis-aligned page-frame reading directions.
        for (File pdf : pdfs) {
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf);
                    ObjectExtractor extractor = new ObjectExtractor(document, true)) {
                Page page = extractor.extract(1);
                for (TextElement te : page.getText()) {
                    int dir = ((Math.round(te.getDirection()) % 360) + 360) % 360;
                    assertTrue("unexpected frame direction " + dir + " in " + pdf.getName(),
                            dir == 0 || dir == 90 || dir == 180 || dir == 270);
                }
            }
        }
    }

    @Test
    public void disabledModePreservesLegacyBehavior() throws IOException {
        // The whole point of the opt-in flag: with rotation disabled the
        // extracted cells must be byte-for-byte the same as those produced by the
        // default extractor on the same document.
        for (File pdf : pdfs) {
            String legacy;
            String defaulted;
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                Page page = new ObjectExtractor(document, false).extract(1);
                legacy = joinedCellTexts(new SpreadsheetExtractionAlgorithm(false).extract(page).get(0));
            }
            try (PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                // Default constructors must behave exactly like the explicit
                // rotateText=false path.
                Page page = new ObjectExtractor(document).extract(1);
                defaulted = joinedCellTexts(new SpreadsheetExtractionAlgorithm().extract(page).get(0));
            }
            assertEquals(pdf.getName(), legacy, defaulted);
        }
    }

    private static String reverse(String s) {
        return new StringBuilder(s).reverse().toString();
    }
}
