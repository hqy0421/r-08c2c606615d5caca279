package technology.tabula;

/**
 * Reading direction of a run of text, expressed relative to the (rotation
 * adjusted) page coordinate system that Tabula shares with the ruling lines.
 *
 * <p>Only axis-aligned orientations are supported. The direction values are
 * measured relative to the displayed page in its top-left-origin coordinate
 * system, clockwise from the positive X axis:
 * <ul>
 *     <li>{@link #HORIZONTAL} left-to-right text (dir 0) and right-to-left /
 *         upside-down text (dir 180), which is still laid out horizontally;</li>
 *     <li>{@link #VERTICAL_DOWN} glyphs stacked so that reading advances from
 *         the top of the cell toward the bottom (dir 90);</li>
 *     <li>{@link #VERTICAL_UP} glyphs stacked so that reading advances from the
 *         bottom of the cell toward the top (dir 270).</li>
 * </ul>
 */
public enum TextOrientation {
    HORIZONTAL,
    VERTICAL_UP,
    VERTICAL_DOWN;

    /**
     * Maps a page-frame reading direction (0, 90, 180 or 270) to a
     * {@link TextOrientation}. 180&deg; text is horizontal layout read
     * right-to-left, so it is grouped with the horizontal run.
     */
    public static TextOrientation fromDirection(float dir) {
        int rounded = Math.round(dir) % 360;
        if (rounded < 0) {
            rounded += 360;
        }
        switch (rounded) {
            case 90:
                return VERTICAL_DOWN;
            case 270:
                return VERTICAL_UP;
            case 0:
            case 180:
            default:
                return HORIZONTAL;
        }
    }

    public boolean isVertical() {
        return this != HORIZONTAL;
    }
}
