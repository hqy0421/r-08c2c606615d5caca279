package technology.tabula;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDFont;

@SuppressWarnings("serial")
public class TextElement extends Rectangle implements HasText {

    private final String text;
    private final PDFont font;
    private float fontSize;
    private float widthOfSpace, dir;
    private static final float AVERAGE_CHAR_TOLERANCE = 0.3f;

    public TextElement(float y, float x, float width, float height,
                       PDFont font, float fontSize, String c, float widthOfSpace) {
        this(y, x, width, height, font, fontSize, c, widthOfSpace, 0f);
    }

    public TextElement(float y, float x, float width, float height,
                       PDFont font, float fontSize, String c, float widthOfSpace, float dir) {
        super();
        this.setRect(x, y, width, height);
        this.text = c;
        this.widthOfSpace = widthOfSpace;
        this.fontSize = fontSize;
        this.font = font;
        this.dir = dir;
    }

    @Override
    public String getText() {
        return text;
    }

    @Override
    public String getText(boolean useLineReturns) {
        return text;
    }

    public float getDirection() {
        return dir;
    }

    public float getWidthOfSpace() {
        return widthOfSpace;
    }

    public PDFont getFont() {
        return font;
    }

    public float getFontSize() {
        return fontSize;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        String s = super.toString();
        sb.append(s.substring(0, s.length() - 1));
        sb.append(String.format(",text=\"%s\"]", this.getText()));
        return sb.toString();
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = super.hashCode();
        result = prime * result + java.lang.Float.floatToIntBits(dir);
        result = prime * result + ((font == null) ? 0 : font.hashCode());
        result = prime * result + java.lang.Float.floatToIntBits(fontSize);
        result = prime * result + ((text == null) ? 0 : text.hashCode());
        result = prime * result + java.lang.Float.floatToIntBits(widthOfSpace);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (!super.equals(obj))
            return false;
        if (getClass() != obj.getClass())
            return false;
        TextElement other = (TextElement) obj;
        if (java.lang.Float.floatToIntBits(dir) != java.lang.Float
                .floatToIntBits(other.dir))
            return false;
        if (font == null) {
            if (other.font != null)
                return false;
        } else if (!font.equals(other.font))
            return false;
        if (java.lang.Float.floatToIntBits(fontSize) != java.lang.Float
                .floatToIntBits(other.fontSize))
            return false;
        if (text == null) {
            if (other.text != null)
                return false;
        } else if (!text.equals(other.text))
            return false;
        return java.lang.Float.floatToIntBits(widthOfSpace) == java.lang.Float
                .floatToIntBits(other.widthOfSpace);
    }

    public static List<TextChunk> mergeWords(List<TextElement> textElements) {
        return mergeWords(textElements, new ArrayList<Ruling>());
    }

    /**
     * heuristically merge a list of TextElement into a list of TextChunk
     * ported from from PDFBox's PDFTextStripper.writePage, with modifications.
     * Here be dragons
     */
    public static List<TextChunk> mergeWords(List<TextElement> textElements, List<Ruling> verticalRulings) {

        List<TextChunk> textChunks = new ArrayList<>();

        if (textElements.isEmpty()) {
            return textChunks;
        }

        // it's a problem that this `remove` is side-effecty
        // other things depend on `textElements` and it can sometimes lead to the first textElement in textElement
        // not appearing in the final output because it's been removed here.
        // https://github.com/tabulapdf/tabula-java/issues/78
        List<TextElement> copyOfTextElements = new ArrayList<>(textElements);
        textChunks.add(new TextChunk(copyOfTextElements.remove(0)));
        TextChunk firstTC = textChunks.get(0);

        float previousAveCharWidth = (float) firstTC.getWidth();
        float endOfLastTextX = firstTC.getRight();
        float maxYForLine = firstTC.getBottom();
        float maxHeightForLine = (float) firstTC.getHeight();
        float minYTopForLine = firstTC.getTop();
        float lastWordSpacing = -1;
        float wordSpacing, deltaSpace, averageCharWidth, deltaCharWidth;
        float expectedStartOfNextWordX, dist;
        TextElement sp, prevChar;
        TextChunk currentChunk;
        boolean sameLine, acrossVerticalRuling;

        for (TextElement chr : copyOfTextElements) {
            currentChunk = textChunks.get(textChunks.size() - 1);
            prevChar = currentChunk.textElements.get(currentChunk.textElements.size() - 1);

            // if same char AND overlapped, skip
            if ((chr.getText().equals(prevChar.getText())) && (prevChar.overlapRatio(chr) > 0.5)) {
                continue;
            }

            // if chr is a space that overlaps with prevChar, skip
            if (chr.getText().equals(" ") && Utils.feq(prevChar.getLeft(), chr.getLeft()) && Utils.feq(prevChar.getTop(), chr.getTop())) {
                continue;
            }

            // Resets the average character width when we see a change in font
            // or a change in the font size
            if ((chr.getFont() != prevChar.getFont()) || !Utils.feq(chr.getFontSize(), prevChar.getFontSize())) {
                previousAveCharWidth = -1;
            }

            // is there any vertical ruling that goes across chr and prevChar?
            acrossVerticalRuling = false;
            for (Ruling r : verticalRulings) {
                if (
                        (verticallyOverlapsRuling(prevChar, r) && verticallyOverlapsRuling(chr, r)) &&
                                (prevChar.x < r.getPosition() && chr.x > r.getPosition()) || (prevChar.x > r.getPosition() && chr.x < r.getPosition())
                        ) {
                    acrossVerticalRuling = true;
                    break;
                }
            }

            // Estimate the expected width of the space based on the
            // space character with some margin.
            wordSpacing = chr.getWidthOfSpace();
            deltaSpace = 0;
            if (java.lang.Float.isNaN(wordSpacing) || wordSpacing == 0) {
                deltaSpace = java.lang.Float.MAX_VALUE;
            } else if (lastWordSpacing < 0) {
                deltaSpace = wordSpacing * 0.5f; // 0.5 == spacing tolerance
            } else {
                deltaSpace = ((wordSpacing + lastWordSpacing) / 2.0f) * 0.5f;
            }

            // Estimate the expected width of the space based on the
            // average character width with some margin. This calculation does not
            // make a true average (average of averages) but we found that it gave the
            // best results after numerous experiments. Based on experiments we also found that
            // .3 worked well.
            if (previousAveCharWidth < 0) {
                averageCharWidth = (float) (chr.getWidth() / chr.getText().length());
            } else {
                averageCharWidth = (float) ((previousAveCharWidth + (chr.getWidth() / chr.getText().length())) / 2.0f);
            }
            deltaCharWidth = averageCharWidth * AVERAGE_CHAR_TOLERANCE;

            // Compares the values obtained by the average method and the wordSpacing method and picks
            // the smaller number.
            expectedStartOfNextWordX = -java.lang.Float.MAX_VALUE;

            if (endOfLastTextX != -1) {
                expectedStartOfNextWordX = endOfLastTextX + Math.min(deltaCharWidth, deltaSpace);
            }

            // new line?
            sameLine = true;
            if (!Utils.overlap(chr.getBottom(), chr.height, maxYForLine, maxHeightForLine)) {
                endOfLastTextX = -1;
                expectedStartOfNextWordX = -java.lang.Float.MAX_VALUE;
                maxYForLine = -java.lang.Float.MAX_VALUE;
                maxHeightForLine = -1;
                minYTopForLine = java.lang.Float.MAX_VALUE;
                sameLine = false;
            }

            endOfLastTextX = chr.getRight();

            // should we add a space?
            if (!acrossVerticalRuling &&
                    sameLine &&
                    expectedStartOfNextWordX < chr.getLeft() &&
                    !prevChar.getText().endsWith(" ")) {

                sp = new TextElement(prevChar.getTop(),
                        prevChar.getLeft(),
                        expectedStartOfNextWordX - prevChar.getLeft(),
                        (float) prevChar.getHeight(),
                        prevChar.getFont(),
                        prevChar.getFontSize(),
                        " ",
                        prevChar.getWidthOfSpace());

                currentChunk.add(sp);
            } else {
                sp = null;
            }

            maxYForLine = Math.max(chr.getBottom(), maxYForLine);
            maxHeightForLine = (float) Math.max(maxHeightForLine, chr.getHeight());
            minYTopForLine = Math.min(minYTopForLine, chr.getTop());

            dist = chr.getLeft() - (sp != null ? sp.getRight() : prevChar.getRight());

            if (!acrossVerticalRuling &&
                    sameLine &&
                    (dist < 0 ? currentChunk.verticallyOverlaps(chr) : dist < wordSpacing)) {
                currentChunk.add(chr);
            } else { // create a new chunk
                textChunks.add(new TextChunk(chr));
            }

            lastWordSpacing = wordSpacing;
            previousAveCharWidth = (float) (sp != null ? (averageCharWidth + sp.getWidth()) / 2.0f : averageCharWidth);
        }


        List<TextChunk> textChunksSeparatedByDirectionality = new ArrayList<>();
        // count up characters by directionality
        for (TextChunk chunk : textChunks) {
            // choose the dominant direction
            boolean isLtrDominant = chunk.isLtrDominant() != -1; // treat neutral as LTR
            TextChunk dirChunk = chunk.groupByDirectionality(isLtrDominant);
            textChunksSeparatedByDirectionality.add(dirChunk);
        }

        return textChunksSeparatedByDirectionality;
    }

    private static boolean verticallyOverlapsRuling(TextElement te, Ruling r) {
        return Math.max(0, Math.min(te.getBottom(), r.getY2()) - Math.max(te.getTop(), r.getY1())) > 0;
    }

    /**
     * Merge a list of page-frame aligned {@link TextElement}s into words,
     * honoring the reading direction of every character. The input geometry must
     * be expressed in the page coordinate system shared with the ruling lines,
     * i.e. it must have been produced with rotated-text extraction enabled.
     *
     * <p>Every cell is processed with a single reading direction: horizontal
     * characters (dir 0/180) are joined left-to-right exactly like
     * {@link #mergeWords(List, List)}, while vertical characters (dir 90/270)
     * are joined along the vertical reading axis, bottom-to-top for
     * {@link TextOrientation#VERTICAL_UP} and top-to-bottom for
     * {@link TextOrientation#VERTICAL_DOWN}. Characters of different
     * orientations are never merged together, so a vertical code can never be
     * read backwards or spill into a neighboring cell.
     *
     * @param textElements    page-frame aligned characters
     * @param verticalRulings ruling lines that must not be crossed by a word
     * @return one {@link TextChunk} per detected word, each of a single orientation
     */
    public static List<TextChunk> mergeWordsRotated(List<TextElement> textElements, List<Ruling> verticalRulings) {
        List<TextChunk> chunks = new ArrayList<>();
        if (textElements.isEmpty()) {
            return chunks;
        }

        List<TextElement> sorted = new ArrayList<>(textElements);
        Utils.sort(sorted, new ReadingOrderComparator());

        TextChunk current = new TextChunk(sorted.get(0));
        chunks.add(current);

        for (int i = 1; i < sorted.size(); i++) {
            TextElement chr = sorted.get(i);
            TextElement prev = current.textElements.get(current.textElements.size() - 1);

            // a change of reading direction always starts a new word
            TextOrientation chrOrientation = TextOrientation.fromDirection(chr.getDirection());
            TextOrientation prevOrientation = TextOrientation.fromDirection(prev.getDirection());
            if (!chrOrientation.equals(prevOrientation)) {
                current = new TextChunk(chr);
                chunks.add(current);
                continue;
            }

            // duplicated/overlapping glyph and overlapping space handling,
            // mirroring the historical mergeWords heuristics
            if (chr.getText().equals(prev.getText()) && prev.overlapRatio(chr) > 0.5) {
                continue;
            }
            if (chr.getText().equals(" ") && Utils.feq(prev.getLeft(), chr.getLeft()) && Utils.feq(prev.getTop(), chr.getTop())) {
                continue;
            }

            boolean acrossRuling = crossesRuling(prev, chr, verticalRulings);
            boolean sameRun = isSameReadingRun(prev, chr, prevOrientation, acrossRuling);

            if (sameRun) {
                current.add(chr);
            } else {
                current = new TextChunk(chr);
                chunks.add(current);
            }
        }

        return chunks;
    }

    /**
     * Orders characters along their reading axis inside the page-frame aligned
     * coordinate system. The stored direction is the page-frame reading
     * direction (clockwise from the positive X axis: 0 left-to-right,
     * 90 top-to-bottom, 180 right-to-left, 270 bottom-to-top), so ordering is
     * performed by projecting each glyph anchor onto its reading axis:
     * horizontal runs (dir 0/180) order by left coordinate, vertical runs
     * (dir 90/270) by top coordinate, each in the reading direction. The
     * perpendicular coordinate orders parallel lines.
     */
    static class ReadingOrderComparator implements Comparator<TextElement> {
        @Override
        public int compare(TextElement a, TextElement b) {
            int da = normalizedDirection(a.getDirection());
            int db = normalizedDirection(b.getDirection());
            TextOrientation oa = TextOrientation.fromDirection(da);
            TextOrientation ob = TextOrientation.fromDirection(db);
            int orientationCompare = Integer.compare(oa.ordinal(), ob.ordinal());
            if (orientationCompare != 0) {
                return orientationCompare;
            }
            if (oa.isVertical()) {
                // order parallel vertical lines left-to-right first
                int line = java.lang.Float.compare(a.getLeft(), b.getLeft());
                if (line != 0) {
                    return line;
                }
                // dir 90 reads toward larger top, dir 270 toward smaller top
                int along = java.lang.Float.compare(a.getTop(), b.getTop());
                return da == 270 ? -along : along;
            }
            // order parallel horizontal lines top-to-bottom first
            int line = java.lang.Float.compare(a.getTop(), b.getTop());
            if (line != 0) {
                return line;
            }
            // dir 0 reads toward larger left, dir 180 toward smaller left
            int along = java.lang.Float.compare(a.getLeft(), b.getLeft());
            return da == 180 ? -along : along;
        }

        private static int normalizedDirection(float dir) {
            int d = Math.round(dir) % 360;
            return d < 0 ? d + 360 : d;
        }
    }

    private static boolean isSameReadingRun(TextElement prev, TextElement chr,
                                            TextOrientation orientation, boolean acrossRuling) {
        if (acrossRuling) {
            return false;
        }

        int prevDir = normalizedFrameDirection(prev.getDirection());
        int chrDir = normalizedFrameDirection(chr.getDirection());

        float wordSpacing = chr.getWidthOfSpace();
        float spacingTolerance;
        if (java.lang.Float.isNaN(wordSpacing) || wordSpacing == 0) {
            spacingTolerance = java.lang.Float.MAX_VALUE;
        } else {
            spacingTolerance = wordSpacing * 0.5f;
        }

        if (orientation.isVertical()) {
            // Stacked glyphs belong to the same word while they stay on the same
            // vertical line and the gap between them stays within a space width.
            // The reading axis is vertical: dir 90 advances downward, dir 270 up.
            if (!Utils.overlap(chr.getRight(), (float) chr.getWidth(), prev.getRight(), (float) prev.getWidth())) {
                return false;
            }
            float gap;
            if (prevDir == 270 || chrDir == 270) {
                gap = prev.getTop() - chr.getBottom();
            } else {
                gap = chr.getTop() - prev.getBottom();
            }
            return gap >= 0 ? gap < wordSpacing : prev.horizontallyOverlaps(chr);
        }

        // Horizontal run, same heuristics as the historical merger. dir 180
        // reads right-to-left, so the gap sign is reversed.
        float averageCharWidth = (float) ((prev.getWidth() + chr.getWidth()) / 2.0);
        float charTolerance = averageCharWidth * AVERAGE_CHAR_TOLERANCE;
        if (!Utils.overlap(chr.getBottom(), (float) chr.getHeight(), prev.getBottom(), (float) prev.getHeight())) {
            return false;
        }
        boolean rightToLeft = prevDir == 180 || chrDir == 180;
        float gap = rightToLeft ? prev.getLeft() - chr.getRight() : chr.getLeft() - prev.getRight();
        return gap < 0 ? prev.verticallyOverlaps(chr) : gap < Math.min(spacingTolerance, charTolerance);
    }

    private static int normalizedFrameDirection(float dir) {
        int d = Math.round(dir) % 360;
        return d < 0 ? d + 360 : d;
    }

    private static boolean crossesRuling(TextElement prev, TextElement chr, List<Ruling> rulings) {
        for (Ruling r : rulings) {
            if (verticallyOverlapsRuling(prev, r) && verticallyOverlapsRuling(chr, r)
                    && ((prev.x < r.getPosition() && chr.x > r.getPosition())
                    || (prev.x > r.getPosition() && chr.x < r.getPosition()))) {
                return true;
            }
        }
        return false;
    }

}
