package technology.tabula;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

public class ObjectExtractor implements java.io.Closeable {

    private final PDDocument pdfDocument;
    private final boolean rotateText;

    public ObjectExtractor(PDDocument pdfDocument) {
        this(pdfDocument, false);
    }

    /**
     * @param rotateText when true, character geometry is expressed in the same
     *                   page coordinate system as the ruling lines and rotated
     *                   (vertical) text is preserved with its reading direction,
     *                   enabling rotated-text extraction in lattice mode.
     */
    public ObjectExtractor(PDDocument pdfDocument, boolean rotateText) {
        this.pdfDocument = pdfDocument;
        this.rotateText = rotateText;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    protected Page extractPage(Integer pageNumber) throws IOException {
        if (pageNumber > pdfDocument.getNumberOfPages() || pageNumber < 1) {
            throw new java.lang.IndexOutOfBoundsException("Page number does not exist.");
        }
        PDPage page = pdfDocument.getPage(pageNumber - 1);

        ObjectExtractorStreamEngine streamEngine = new ObjectExtractorStreamEngine(page);
        streamEngine.processPage(page);

        TextStripper textStripper = new TextStripper(pdfDocument, pageNumber, rotateText, page.getRotation());
        textStripper.process();

        Utils.sort(textStripper.getTextElements(), Rectangle.ILL_DEFINED_ORDER);

        float width, height;
        int rotation = page.getRotation();
        if (Math.abs(rotation) == 90 || Math.abs(rotation) == 270) {
            width = page.getCropBox().getHeight();
            height = page.getCropBox().getWidth();
        } else {
            width = page.getCropBox().getWidth();
            height = page.getCropBox().getHeight();
        }

        return Page.Builder.newInstance()
                .withPageDims(PageDims.of(0, 0, width, height))
                .withRotation(rotation)
                .withNumber(pageNumber)
                .withPdPage(page)
                .withPdDocument(pdfDocument)
                .withRulings(streamEngine.rulings)
                .withTextElements(textStripper.getTextElements())
                .withMinCharWidth(textStripper.getMinCharWidth())
                .withMinCharHeight(textStripper.getMinCharHeight())
                .withIndex(textStripper.getSpatialIndex())
                .build();
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    public PageIterator extract(Iterable<Integer> pages) {
        return new PageIterator(this, pages);
    }

    public PageIterator extract() {
        return extract(Utils.range(1, pdfDocument.getNumberOfPages() + 1));
    }

    public Page extract(int pageNumber) {
        return extract(Utils.range(pageNumber, pageNumber + 1)).next();
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - //
    public void close() throws IOException {
        pdfDocument.close();
    }
    
}
