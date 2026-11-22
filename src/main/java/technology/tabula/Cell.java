package technology.tabula;

import java.awt.geom.Point2D;
import java.util.Collections;
import java.util.Comparator;

@SuppressWarnings("serial")
public class Cell extends RectangularTextContainer<TextChunk> {

	public Cell(float top, float left, float width, float height) {
		super(top, left, width, height);
		this.setPlaceholder(false);
		this.setSpanning(false);
	}

	public Cell(Point2D topLeft, Point2D bottomRight) {
		super((float) topLeft.getY(), (float) topLeft.getX(), (float) (bottomRight.getX() - topLeft.getX()), (float) (bottomRight.getY() - topLeft.getY()));
		this.setPlaceholder(false);
		this.setSpanning(false);
	}

	private boolean spanning;
	private boolean placeholder;

	/**
	 * Whether this cell was populated by rotated-text extraction. Only then are
	 * the stored character directions guaranteed to be page-frame reading
	 * directions (0/90/180/270) and may a cell be treated as vertical. In the
	 * legacy extraction path PDFBox reports {@code dir == 90} even for ordinary
	 * horizontal text, so vertical handling must never be inferred from the
	 * direction alone.
	 */
	private boolean verticalTextExtraction = false;

	public void setVerticalTextExtraction(boolean verticalTextExtraction) {
		this.verticalTextExtraction = verticalTextExtraction;
	}

	@Override
	public String getText(boolean useLineReturns) {
		if (this.textElements.size() == 0) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		// Vertical text is read along the vertical axis; keep horizontal text on
		// the historical top-to-bottom/left-to-right ordering so ordinary tables
		// and their expected output are untouched.
		boolean verticalCell = verticalTextExtraction && isVerticalCell();
		this.textElements.sort(verticalCell ? VERTICAL_READING_ORDER : Rectangle.ILL_DEFINED_ORDER);
		double lineAnchor = verticalCell
				? this.textElements.get(0).getLeft()
				: this.textElements.get(0).getTop();
		for (TextChunk tc : this.textElements) {
			if (useLineReturns) {
				boolean newLine = verticalCell ? tc.getLeft() > lineAnchor : tc.getTop() > lineAnchor;
				if (newLine) {
					sb.append('\r');
				}
			}
			sb.append(tc.getText());
			lineAnchor = verticalCell ? tc.getLeft() : tc.getTop();
		}
		return sb.toString().trim();
	}

	/**
	 * A cell is considered vertical when the majority of its chunks contain
	 * vertical (rotated) text. Such a cell is handled with a single reading
	 * direction; horizontal and vertical text are never interleaved.
	 */
	private boolean isVerticalCell() {
		int vertical = 0;
		for (TextChunk chunk : this.textElements) {
			for (TextElement te : chunk.getTextElements()) {
				if (TextOrientation.fromDirection(te.getDirection()).isVertical()) {
					vertical++;
				}
			}
		}
		return vertical > 0 && vertical * 2 > countCharacters();
	}

	private int countCharacters() {
		int count = 0;
		for (TextChunk chunk : this.textElements) {
			count += chunk.getTextElements().size();
		}
		return count;
	}

	/**
	 * Ordering for text laid out vertically in the page frame: first by the
	 * column (left coordinate), then along the reading axis. Direction 90 reads
	 * top-to-bottom (ascending top) and direction 270 bottom-to-top (descending
	 * top).
	 */
	private static final Comparator<TextChunk> VERTICAL_READING_ORDER = new Comparator<TextChunk>() {
		@Override
		public int compare(TextChunk a, TextChunk b) {
			int column = java.lang.Float.compare(a.getLeft(), b.getLeft());
			if (column != 0) {
				return column;
			}
			int along = java.lang.Float.compare(a.getTop(), b.getTop());
			return dominantDirection(a) == 270 ? -along : along;
		}

		private int dominantDirection(TextChunk chunk) {
			for (TextElement te : chunk.getTextElements()) {
				TextOrientation orientation = TextOrientation.fromDirection(te.getDirection());
				if (orientation.isVertical()) {
					return Math.round(te.getDirection()) % 360;
				}
			}
			return 90;
		}
	};

	@Override
	public String getText() {
		return getText(true);
	}

	public boolean isSpanning() {
		return spanning;
	}

	public void setSpanning(boolean spanning) {
		this.spanning = spanning;
	}

	public boolean isPlaceholder() {
		return placeholder;
	}

	public void setPlaceholder(boolean placeholder) {
		this.placeholder = placeholder;
	}
}
