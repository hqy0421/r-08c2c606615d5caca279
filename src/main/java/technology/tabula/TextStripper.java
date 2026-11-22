package technology.tabula;

import org.apache.fontbox.util.BoundingBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TextStripper extends PDFTextStripper {

    private static final String NBSP = " ";
    private static final float AVG_HEIGHT_MULT_THRESHOLD = 6.0f;
    private static final float MAX_BLANK_FONT_SIZE = 40.0f;
    private static final float MIN_BLANK_FONT_SIZE = 2.0f;
    private final PDDocument document;
    private final ArrayList<TextElement> textElements;
    private final RectangleSpatialIndex<TextElement> spatialIndex;
    private float minCharWidth = Float.MAX_VALUE;
    private float minCharHeight = Float.MAX_VALUE;
    private float totalHeight = 0.0f;
    private int countHeight = 0;

    /**
     * When true, the geometry of every {@link TextElement} is expressed in the
     * same page coordinate system as the ruling lines (top-left origin, page
     * rotation already applied), and {@link TextElement#getDirection()} carries
     * the glyph reading direction (0, 90, 180 or 270). This is what makes
     * rotated (vertical) text land in the correct lattice cell. When false, the
     * historical text-direction-adjusted geometry is produced unchanged.
     */
    private final boolean rotateText;
    private final int pageRotation;
    private final int pageNumber;

    public TextStripper(PDDocument document, int pageNumber) throws IOException {
        this(document, pageNumber, false, 0);
    }

    public TextStripper(PDDocument document, int pageNumber, boolean rotateText, int pageRotation) throws IOException {
        super();
        this.document = document;
        this.pageNumber = pageNumber;
        this.rotateText = rotateText;
        this.pageRotation = pageRotation;
        this.setStartPage(pageNumber);
        this.setEndPage(pageNumber);
        this.textElements = new ArrayList<>();
        this.spatialIndex = new RectangleSpatialIndex<>();
    }

    public void process() throws IOException {
        this.getText(this.document);
    }

    @Override
    protected void writeString(String string, List<TextPosition> textPositions) throws IOException
    {
        for (TextPosition textPosition: textPositions)
        {
            if (textPosition == null) {
                continue;
            }

            String c = textPosition.getUnicode();

            // if c not printable, return
            if (!isPrintable(c)) {
                continue;
            }

            Float h = textPosition.getHeightDir();

            if (c.equals(NBSP)) { // replace non-breaking space for space
                c = " ";
            }

            float wos = textPosition.getWidthOfSpace();

            TextElement te;
            if (rotateText) {
                te = buildPageFrameTextElement(textPosition, c, wos);
            } else {
                te = new TextElement(Utils.round(textPosition.getYDirAdj() - h, 2),
                        Utils.round(textPosition.getXDirAdj(), 2), Utils.round(textPosition.getWidthDirAdj(), 2),
                        Utils.round(textPosition.getHeightDir(), 2), textPosition.getFont(), textPosition.getFontSizeInPt(), c,
                        // workaround a possible bug in PDFBox:
                        // https://issues.apache.org/jira/browse/PDFBOX-1755
                        wos, textPosition.getDir());
            }

            this.minCharWidth = (float) Math.min(this.minCharWidth, te.getWidth());
            this.minCharHeight = (float) Math.min(this.minCharHeight, te.getHeight());

            countHeight++;
            totalHeight += te.getHeight();
            float avgHeight = totalHeight / countHeight;

            //We have an issue where tall blank cells throw off the row height calculation
            //Introspect a blank cell a bit here to see if it should be thrown away
            if ((te.getText() == null || te.getText().trim().equals(""))) {
                //if the cell height is more than AVG_HEIGHT_MULT_THRESHOLDxaverage, throw it away
                if (avgHeight > 0
                        && te.getHeight() >= (avgHeight * AVG_HEIGHT_MULT_THRESHOLD)) {
                    continue;
                }

                //if the font size is outside of reasonable ranges, throw it away
                if (textPosition.getFontSizeInPt() > MAX_BLANK_FONT_SIZE || textPosition.getFontSizeInPt() < MIN_BLANK_FONT_SIZE) {
                    continue;
                }
            }

            this.spatialIndex.add(te);
            this.textElements.add(te);
        }
    }

    /**
     * Builds a {@link TextElement} whose rectangle is expressed in the page
     * coordinate system shared with the ruling lines (the frame the
     * {@code ObjectExtractorStreamEngine} produces after applying the page
     * rotation and flipping the Y axis).
     *
     * <p>Everything is derived from the glyph's PDF-space text matrix
     * {@code Matrix(a,b,c,d,e,f)} and then run through the same linear/affine
     * transform the ruling engine applies to PDF coordinates, so a glyph can
     * never land in a neighboring cell regardless of the page rotation:
     * <ul>
     *     <li>the text origin {@code (e,f)} becomes the rectangle anchor,
     *         shared with {@link TextPosition#getX()}/{@link TextPosition#getY()}
     *         on unrotated and 90-degree pages and explicitly corrected on
     *         270-degree pages, where PDFBox rotates around the page center but
     *         the ruling engine rotates around the PDF origin;</li>
     *     <li>{@code (a,b)} is the reading axis; its unit vector times
     *         {@link TextPosition#getWidthDirAdj()} gives the reading extent;</li>
     *     <li>{@code (c,d)} is the font's upward (thickness) axis; its unit
     *         vector times the glyph thickness gives the perpendicular extent.
     *         The thickness is {@link TextPosition#getHeightDir()} (the height
     *         this stripper's {@link #computeFontHeight(PDFont)} computes at the
     *         rendered scale), capped by the full em size
     *         {@link TextPosition#getYScale()}.</li>
     * </ul>
     * The orientation stored on the element is the reading direction relative to
     * the displayed page, {@code (getDir() - pageRotation) mod 360}, because
     * {@code getDir()} alone is measured against the unrotated PDF frame.
     *
     * @return a page-frame aligned text element, never {@code null}
     */
    private TextElement buildPageFrameTextElement(TextPosition textPosition, String c, float widthOfSpace) {
        Matrix matrix = textPosition.getTextMatrix();
        int normalizedRotation = ((pageRotation % 360) + 360) % 360;

        float readingAdvance = Utils.round(textPosition.getWidthDirAdj(), 2);
        // getHeightDir() is the glyph height produced by this stripper's
        // computeFontHeight override (cap height / half the font bounding box at
        // the rendered scale) and is the best available estimate of the visible
        // glyph thickness perpendicular to the reading axis. getYScale() is the
        // full em size and is used only as an upper bound when no usable height
        // is reported.
        float fontHeight = textPosition.getHeightDir();
        if (java.lang.Float.isNaN(fontHeight) || fontHeight <= 0) {
            fontHeight = textPosition.getYScale();
        }
        float glyphThickness = Utils.round(Math.min(fontHeight, textPosition.getYScale()), 2);

        // Text origin in PDF space, converted to the ruling-line frame. The
        // ruling engine rotates around the PDF origin, whereas PDFBox's
        // getX()/getY() keep coordinates non-negative via a page-center rotation;
        // the 270-degree page is the only rotation where the two differ.
        float anchorX;
        float anchorY;
        if (normalizedRotation == 270) {
            PDRectangle cropBox = document.getPage(pageNumber - 1).getCropBox();
            anchorX = textPosition.getX() - cropBox.getHeight();
            anchorY = textPosition.getY() - cropBox.getWidth();
        } else {
            anchorX = textPosition.getX();
            anchorY = textPosition.getY();
        }

        // Reading (a,b) and thickness (c,d) axis vectors in PDF space, rotated
        // into the ruling frame by the transform's linear part only.
        float readX = matrix.getScaleX();
        float readY = matrix.getShearY();
        float thickX = matrix.getShearX();
        float thickY = matrix.getScaleY();
        float readLen = (float) Math.hypot(readX, readY);
        float thickLen = (float) Math.hypot(thickX, thickY);
        readX /= readLen;
        readY /= readLen;
        thickX /= thickLen;
        thickY /= thickLen;

        float readFrameX;
        float readFrameY;
        float thickFrameX;
        float thickFrameY;
        switch (normalizedRotation) {
            case 90:
                // Linear part of the ruling engine's empirically observed
                // rot-90 transform (x, y) -> (y, x): the first component is not
                // sign-flipped (unlike a pure rotation around the origin).
                readFrameX = readY;
                readFrameY = readX;
                thickFrameX = thickY;
                thickFrameY = thickX;
                break;
            case 270:
                // (x, y) -> (-y, -x)
                readFrameX = -readY;
                readFrameY = -readX;
                thickFrameX = -thickY;
                thickFrameY = -thickX;
                break;
            case 0:
            case 180:
            default:
                // (x, y) -> (x, -y): flip the PDF upward Y axis into the
                // top-left page frame.
                readFrameX = readX;
                readFrameY = -readY;
                thickFrameX = thickX;
                thickFrameY = -thickY;
                break;
        }

        // The text origin is the start of the reading extent and the baseline of
        // the thickness extent, so the box spans anchor -> anchor + reading and
        // anchor -> anchor + thickness (both along their frame unit vectors).
        float p1x = anchorX + readingAdvance * readFrameX;
        float p1y = anchorY + readingAdvance * readFrameY;
        float p2x = anchorX + glyphThickness * thickFrameX;
        float p2y = anchorY + glyphThickness * thickFrameY;
        float p3x = p1x + glyphThickness * thickFrameX;
        float p3y = p1y + glyphThickness * thickFrameY;

        float left = Utils.round(min4(anchorX, p1x, p2x, p3x), 2);
        float right = Utils.round(max4(anchorX, p1x, p2x, p3x), 2);
        float top = Utils.round(min4(anchorY, p1y, p2y, p3y), 2);
        float bottom = Utils.round(max4(anchorY, p1y, p2y, p3y), 2);

        // Reading direction relative to the displayed page, measured clockwise in
        // the top-left page frame: 0 = left-to-right, 90 = top-to-bottom,
        // 180 = right-to-left, 270 = bottom-to-top. This is derived from the
        // transformed reading vector, not from getDir() alone, because a glyph
        // that is vertical in the unrotated PDF can read horizontally once the
        // page rotation is applied (and vice-versa).
        int pageFrameDir;
        if (Math.abs(readFrameX) >= Math.abs(readFrameY)) {
            pageFrameDir = readFrameX >= 0 ? 0 : 180;
        } else {
            pageFrameDir = readFrameY >= 0 ? 90 : 270;
        }

        return new TextElement(top, left, right - left, bottom - top,
                textPosition.getFont(), textPosition.getFontSizeInPt(), c,
                widthOfSpace, pageFrameDir);
    }

    private static float min4(float a, float b, float c, float d) {
        return Math.min(Math.min(a, b), Math.min(c, d));
    }

    private static float max4(float a, float b, float c, float d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

  @Override
  protected float computeFontHeight(PDFont font) throws IOException
  {
    BoundingBox bbox = font.getBoundingBox();
    if (bbox.getLowerLeftY() < Short.MIN_VALUE)
    {
      // PDFBOX-2158 and PDFBOX-3130
      // files by Salmat eSolutions / ClibPDF Library
      bbox.setLowerLeftY(- (bbox.getLowerLeftY() + 65536));
    }
    // 1/2 the bbox is used as the height todo: why?
    float glyphHeight = bbox.getHeight() / 2;

    // sometimes the bbox has very high values, but CapHeight is OK
    PDFontDescriptor fontDescriptor = font.getFontDescriptor();
    if (fontDescriptor != null)
    {
      float capHeight = fontDescriptor.getCapHeight();
      if (Float.compare(capHeight, 0) != 0 &&
        (capHeight < glyphHeight || Float.compare(glyphHeight, 0) == 0))
      {
        glyphHeight = capHeight;
      }
      // PDFBOX-3464, PDFBOX-448:
      // sometimes even CapHeight is very high, but Ascent and Descent are ok
      float ascent = fontDescriptor.getAscent();
      float descent = fontDescriptor.getDescent();
      if (ascent > 0 && descent < 0 &&
        ((ascent - descent) / 2 < glyphHeight || Float.compare(glyphHeight, 0) == 0))
      {
        glyphHeight = (ascent - descent) / 2;
      }
    }

    // transformPoint from glyph space -> text space
    float height;
    if (font instanceof PDType3Font)
    {
      height = font.getFontMatrix().transformPoint(0, glyphHeight).y;
    }
    else
    {
      height = glyphHeight / 1000;
    }

    return height;
  }

    private boolean isPrintable(String s) {
        Character c;
        Character.UnicodeBlock block;
        boolean printable = false;
        for (int i = 0; i < s.length(); i++) {
            c = s.charAt(i);
            block = Character.UnicodeBlock.of(c);
            printable |= !Character.isISOControl(c) && block != null && block != Character.UnicodeBlock.SPECIALS;
        }
        return printable;
    }

    public List<TextElement> getTextElements() {
        return this.textElements;
    }

    public RectangleSpatialIndex<TextElement> getSpatialIndex() {
        return spatialIndex;
    }

    public float getMinCharWidth() {
        return minCharWidth;
    }

    public float getMinCharHeight() {
        return minCharHeight;
    }
}
