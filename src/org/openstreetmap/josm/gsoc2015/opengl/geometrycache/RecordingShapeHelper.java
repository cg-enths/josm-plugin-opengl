package org.openstreetmap.josm.gsoc2015.opengl.geometrycache;

import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.Stroke;
import java.nio.FloatBuffer;

import javax.media.opengl.GL;

import org.jogamp.glg2d.impl.AbstractShapeHelper;
import org.jogamp.glg2d.impl.SimplePathVisitor;
import org.openstreetmap.josm.gsoc2015.opengl.pool.VertexBufferProvider;
import org.openstreetmap.josm.gsoc2015.opengl.pool.VertexBufferProvider.ReleasableVertexBuffer;

public class RecordingShapeHelper extends AbstractShapeHelper {

	private final RecordingStrokeLineVisitor lineVisitor;
	private final RecordingStarOrTesselatorVisitor fillVisitor;
	private final RecordingLineVisitor fastLineVisitor;

	private enum Clockwise {
		CLOCKWISE, COUNTER_CLOCKWISE, NOT_SURE;

		public static Clockwise isClockwise(float x1, float y1, float x2,
				float y2, float x3, float y3) {
			final float area = (x2 - x1) * (y1 + y2) + (x3 - x2) * (y2 + y3)
					+ (x1 - x3) * (y1 + y3);
			if (area > .00001) {
				return COUNTER_CLOCKWISE;
			} else if (area < .00001) {
				return CLOCKWISE;
			} else {
				return NOT_SURE;
			}
		}
	}

	/**
	 * This visitor assumes that the current shape has a star form around the
	 * first vertex. This is the case e.g. for all convex shapes.
	 * <p>
	 * It does not record a triangle fan but single triangles instead.
	 * <p>
	 * Let v_1, ... , v_n be the vertexes. We then assume v1 to be the center
	 * point of our start
	 * <p>
	 * First, we add a triangle v1, v2, v3. For each new vertex, we add the
	 * triangle v1, v_(i-1), v_i
	 * <p>
	 * As long as all outer vertexes form a circular shape, we are good. This is
	 * the case if and only if all triangles have the same orientation (either
	 * clockwise or counter-clockwise)
	 *
	 * @author Michael Zangl
	 */
	private static class RecordingStarOrTesselatorVisitor extends
			SimplePathVisitor {
		/**
		 * Most simple shapes should not have more than 16 corners. XXX:
		 * Confirm.
		 */
		private static final int DEFAULT_SIZE = 16 * 3;
		private final RecordingTesselatorVisitor fallback;
		private final Recorder recorder;
		private final RecordingColorHelper colorRecorder;
		private float startPointX;
		private float startPointY;
		private float lastPointX;
		private float lastPointY;
		/**
		 * Drawing the current polygon has failed and is using the fallback.
		 */
		private boolean failed;
		/**
		 * This flag is set as soon as we should start drawing.
		 */
		private boolean inDraw = false;

		private Clockwise isClockwise = Clockwise.NOT_SURE;

		private final VertexBufferProvider VERTEX_BUFFER_PROVIDER = VertexBufferProvider.DEFAULT;
		private ReleasableVertexBuffer vBuffer = VERTEX_BUFFER_PROVIDER
				.getVertexBuffer(DEFAULT_SIZE);

		public RecordingStarOrTesselatorVisitor(
				RecordingColorHelper colorHelper, Recorder recorder) {
			colorRecorder = colorHelper;
			this.recorder = recorder;
			fallback = new RecordingTesselatorVisitor(colorHelper, recorder);
		}

		@Override
		public void setGLContext(GL context) {
			// unused
		}

		@Override
		public void setStroke(BasicStroke stroke) {
			// only supports filling.
		}

		@Override
		public void moveTo(float[] vertex) {
			if (failed) {
				// We send a moveTo to force closing the current loop.
				fallback.moveTo(vertex);
			}
			startPointX = lastPointX = vertex[0];
			startPointY = lastPointY = vertex[1];
			inDraw = false;
			isClockwise = Clockwise.NOT_SURE;
		}

		@Override
		public void lineTo(float[] vertex) {
			if (failed) {
				fallback.lineTo(vertex);
				return;
			}
			if (closeTo(lastPointX, vertex[0])
					&& closeTo(lastPointY, vertex[1])) {
				// The user does not see a difference but we save a triangle.
				return;
			}

			if (!inDraw) {
				inDraw = true;
			} else {
				vBuffer.addVertex(startPointX, startPointY);
				vBuffer.addVertex(lastPointX, lastPointY);
				vBuffer.addVertex(vertex[0], vertex[1]);
				final Clockwise cw = Clockwise.isClockwise(startPointX,
						startPointY, lastPointX, lastPointY, vertex[0],
						vertex[1]);
				if (cw != Clockwise.NOT_SURE
						&& isClockwise != Clockwise.NOT_SURE
						&& cw != isClockwise) {
					switchToFallback();
				}
				isClockwise = cw;
			}
			lastPointX = vertex[0];
			lastPointY = vertex[1];
		}

		private void switchToFallback() {
			// replay the last steps.
			final FloatBuffer buffer = vBuffer.getBuffer();
			final int count = buffer.position();
			if (count < 2 * 3) {
				throw new IllegalStateException(
						"vBuffer was not filled enough.");
			}
			buffer.rewind();
			final float[] vertex = new float[2];
			float lastStartX = Float.NaN, lastStartY = Float.NaN;
			// read all triangles.
			for (int i = 0; i < count; i += 6) {
				buffer.get(vertex);
				if (lastStartX != vertex[0] || lastStartY != vertex[1]) {
					// a new start started.
					lastStartX = vertex[0];
					lastStartY = vertex[1];
					fallback.moveTo(vertex);
					buffer.get(vertex);
					fallback.lineTo(vertex);
				} else {
					// fake get
					buffer.get(vertex);
				}
				buffer.get(vertex);
				fallback.lineTo(vertex);
			}

			buffer.rewind();
			failed = true;
		}

		private boolean closeTo(float d1, float d2) {
			return Math.abs(d1 - d2) < .1;
		}

		@Override
		public void closeLine() {
			if (failed) {
				fallback.closeLine();
			}
			// triangle is auto-closed when not using fallback.
		}

		@Override
		public void beginPoly(int windingRule) {
			fallback.beginPoly(windingRule);
		}

		@Override
		public void endPoly() {
			fallback.endPoly();
			commitIfRequired();
		}

		private void commitIfRequired() {
			if (!failed && vBuffer.getBuffer().position() > 0) {
				recorder.recordGeometry(new RecordedGeometry(GL.GL_TRIANGLES,
						vBuffer, colorRecorder.getActiveColor()));
				vBuffer = VERTEX_BUFFER_PROVIDER.getVertexBuffer(DEFAULT_SIZE);
			}
			inDraw = false;
			isClockwise = Clockwise.NOT_SURE;
			failed = false;
		}

		public void dispose() {
			vBuffer.release();
			vBuffer = null;
			fallback.dispose();
		}
	}

	public RecordingShapeHelper(RecordingColorHelper colorHelper,
			Recorder recorder) {
		// fillVisitor = new RecordingTesselatorVisitor(colorHelper, recorder);
		fillVisitor = new RecordingStarOrTesselatorVisitor(colorHelper,
				recorder);
		lineVisitor = new RecordingStrokeLineVisitor(colorHelper, recorder);
		fastLineVisitor = new RecordingLineVisitor(colorHelper, recorder);
	}

	@Override
	public void draw(Shape shape) {
		final Stroke stroke = getStroke();
		// long time1 = System.currentTimeMillis();
		if (stroke instanceof BasicStroke) {
			final BasicStroke basicStroke = (BasicStroke) stroke;
			final GLLineStippleDefinition stripple = GLLineStippleDefinition
					.generate(basicStroke);
			if (stripple != null) {
				fastLineVisitor.setStripple(stripple);
				traceShape(shape, fastLineVisitor);
				return;
			} else if (basicStroke.getDashArray() == null) {
				lineVisitor.setStroke(basicStroke);
				// System.out.println("Slow line beign? lineVisitor");
				traceShape(shape, lineVisitor);
				// System.out.println("Slow line end? lineVisitor "
				// + (System.currentTimeMillis() - time1) + "ms ");
				return;
			}
		}
		// System.out.println("Slow line beign?");
		final Shape strokedShape = stroke.createStrokedShape(shape);
		// long time2 = System.currentTimeMillis();
		fill(strokedShape);
		// System.out.println("Slow line end? " + (time2 - time1) + "ms, "
		// + (System.currentTimeMillis() - time2) + "ms, ");
	}

	@Override
	protected void fill(Shape shape, boolean isDefinitelySimpleConvex) {
		// System.out.println("Slow fill beign? " + shape);
		// long time1 = System.currentTimeMillis();
		traceShape(shape, fillVisitor);
		// System.out.println("Slow fill end?"
		// + (System.currentTimeMillis() - time1) + "ms, ");
	}

	@Override
	public void dispose() {
		lineVisitor.dispose();
		fillVisitor.dispose();
		fastLineVisitor.dispose();
		super.dispose();
	}
}
