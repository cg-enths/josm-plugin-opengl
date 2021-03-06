package org.openstreetmap.josm.gsoc2015.opengl.geometrycache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.media.opengl.GL2;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gsoc2015.opengl.osm.ViewPosition;
import org.openstreetmap.josm.tools.Pair;

/**
 * This is a list of geometries that are recorded to draw the given osm
 * primitive.
 * <p>
 * This list may contain draw information for multiple geometries and there may
 * be multiple lists per OSM primitive - but always one per style.
 * <p>
 * There is a Z-index stored for this geometry to quickly sort them.
 *
 * @author Michael Zangl
 *
 */
public class RecordedOsmGeometries implements Comparable<RecordedOsmGeometries> {
	/**
	 * The geometries in the order in which they need to be drawn.
	 */
	private List<RecordedGeometry> geometries;
	/**
	 * A list of primitives this geometry is for.
	 */
	private final Set<OsmPrimitive> primitives = new HashSet<>();

	/**
	 * The zoom and viewport that was used to create this geometry.
	 */
	private final ViewPosition viewPosition;

	/**
	 * A number indicating the z-index of this geometry.
	 */
	private final long orderIndex;
	/**
	 * The cached array of hashes for this geometry.
	 */
	private int[] hashes;

	/**
	 * A flag indicating the merge group. This is useful for debugging.
	 */
	public MergeGroup mergeGroup;

	/**
	 *
	 * @param geometries
	 *            The geometries. A copy is created.
	 * @param primitive
	 *            The initial primitive this record is for.
	 */
	public RecordedOsmGeometries(List<RecordedGeometry> geometries,
			OsmPrimitive primitive, long orderIndex, ViewPosition viewPosition) {
		this.orderIndex = orderIndex;
		this.viewPosition = viewPosition;
		this.geometries = new ArrayList<>(geometries);
		primitives.add(primitive);
	}

	/**
	 * Disposes the underlying buffer and frees all allocated resources. The
	 * object may not be used afterwards.
	 */
	public void dispose() {
		for (final RecordedGeometry g : geometries) {
			g.dispose();
		}
	}

	public void draw(GL2 gl, GLState state) {
		state.toViewPosition(viewPosition);
		for (final RecordedGeometry g : geometries) {
			g.draw(gl, state);
		}
	}

	public Set<OsmPrimitive> getPrimitives() {
		return primitives;
	}

	/**
	 * Gets a list of hashes that suggest the combination of two geometries if
	 * most of their hashes are the same.
	 *
	 * @return The combine hashes.
	 */
	public int[] getCombineHashes() {
		if (hashes == null) {
			hashes = getUsedHashes(geometries);
			Arrays.sort(hashes);
			// now remove duplicates from that array.
			int newIndex = 1;
			for (int i = 1; i < hashes.length; i++) {
				if (hashes[i] != hashes[i - 1]) {
					hashes[newIndex++] = hashes[i];
				}
			}
			if (newIndex < hashes.length) {
				hashes = Arrays.copyOf(hashes, newIndex);
			}
		}
		return hashes;
	}

	private static int[] getUsedHashes(List<RecordedGeometry> geometries) {
		final int[] hashes = new int[geometries.size()];
		for (int i = 0; i < hashes.length; i++) {
			hashes[i] = geometries.get(i).getCombineHash();
		}
		return hashes;
	}

	/**
	 * Merges an other geometry in this geometry.
	 * <p>
	 * This method is not thread safe.
	 *
	 * @param other
	 *            The other geometry to merge.
	 * @return <code>true</code> if the merge was successful.
	 */
	public boolean mergeWith(RecordedOsmGeometries other) {
		if (!isMergeable(other)) {
			return false;
		}

		primitives.addAll(other.primitives);
		geometries = merge(geometries, other.geometries);

		// Update the hashes.
		final HashSet<Integer> newHashes = new HashSet<>();
		for (final RecordedGeometry geometry : other.geometries) {
			final int hash = geometry.getCombineHash();
			if (Arrays.binarySearch(hashes, hash) < 0) {
				newHashes.add(hash);
			}
		}

		if (newHashes.size() > 0) {
			int length = hashes.length;
			hashes = Arrays.copyOf(hashes, length + newHashes.size());
			for (final int hash : newHashes) {
				hashes[length++] = hash;
			}
			Arrays.sort(hashes);
		}

		return true;
	}

	private boolean isMergeable(RecordedOsmGeometries other) {
		return other.orderIndex == orderIndex
				&& viewPosition.equals(other.viewPosition);
	}

	/**
	 * Merges two list of geometries. The order of geomtries is respected and
	 * stays the same after each merge.
	 *
	 * @param geometries1
	 * @param geometries2
	 * @return A new, merged list of geometries.
	 */
	private List<RecordedGeometry> merge(List<RecordedGeometry> geometries1,
			List<RecordedGeometry> geometries2) {
		final List<RecordedGeometry> ordered = new ArrayList<>();
		final List<RecordedGeometry> ret = new ArrayList<>();

		final LinkedList<Pair<Integer, Integer>> pairs = firstMergePairs(geometries1,
				geometries2);
		final ArrayList<Pair<Integer, Integer>> filtered = removeCrossingPairs(pairs);

		// Sort pairs by a
		Collections.sort(filtered, new Comparator<Pair<Integer, Integer>>() {
			@Override
			public int compare(Pair<Integer, Integer> o1,
					Pair<Integer, Integer> o2) {
				return Integer.compare(o1.a, o2.a);
			}
		});
		// Since all crossing pairs are removed, we can be sure that paris are
		// ordered by a and by b.

		int geometry1Index = 0, geometry2Index = 0;
		for (final Pair<Integer, Integer> p : filtered) {
			for (; geometry1Index < p.a; geometry1Index++) {
				ordered.add(geometries1.get(geometry1Index));
			}
			for (; geometry2Index < p.b; geometry2Index++) {
				ordered.add(geometries2.get(geometry2Index));
			}
		}
		for (; geometry1Index < geometries1.size(); geometry1Index++) {
			ordered.add(geometries1.get(geometry1Index));
		}
		for (; geometry2Index < geometries2.size(); geometry2Index++) {
			ordered.add(geometries2.get(geometry2Index));
		}

		RecordedGeometry last = null;
		final Iterator<RecordedGeometry> iterator = ordered.iterator();
		while (iterator.hasNext()) {
			final RecordedGeometry current = iterator.next();
			if (last != null && last.attemptCombineWith(current)) {
				// all good, we combined this one.
			} else {
				last = current;
				ret.add(current);
			}
		}

		return ret;
	}

	private ArrayList<Pair<Integer, Integer>> removeCrossingPairs(
			LinkedList<Pair<Integer, Integer>> pairs) {
		// TODO: Test if sorting by |pair.a - pair.b| helps.
		final ArrayList<Pair<Integer, Integer>> filtered = new ArrayList<>();
		while (!pairs.isEmpty()) {
			final Pair<Integer, Integer> p = pairs.pollFirst();
			removeAllCrossingPairs(pairs, p);
			filtered.add(p);
		}
		return filtered;
	}

	private void removeAllCrossingPairs(
			LinkedList<Pair<Integer, Integer>> pairs,
			Pair<Integer, Integer> crossingWith) {
		final Iterator<Pair<Integer, Integer>> iterator = pairs.iterator();
		while (iterator.hasNext()) {
			final Pair<Integer, Integer> pair = iterator.next();
			if (pair.a <= crossingWith.a && pair.b >= crossingWith.b
					|| pair.a >= crossingWith.a && pair.b <= crossingWith.b) {
				iterator.remove();
			}
		}
	}

	private LinkedList<Pair<Integer, Integer>> firstMergePairs(
			List<RecordedGeometry> geometries1,
			List<RecordedGeometry> geometries2) {
		final LinkedList<Pair<Integer, Integer>> mergePairs = new LinkedList<>();
		for (int i = 0; i < geometries1.size(); i++) {
			final RecordedGeometry g1 = geometries1.get(i);
			for (int j = 0; j < geometries2.size(); j++) {
				final RecordedGeometry g2 = geometries1.get(i);
				if (g1.couldCombineWith(g2)) {
					mergePairs.add(new Pair<>(i, j));
				}
			}
		}
		return mergePairs;
	}

	@Override
	public String toString() {
		return "RecordedOsmGeometries [geometries=" + geometries
				+ ", primitives=" + primitives + "]";
	}

	@Override
	public int compareTo(RecordedOsmGeometries o) {
		return Long.compare(orderIndex, o.orderIndex);
	}

	/**
	 * A rating how useful it would be to combine those two geometries. Range
	 * 0..1
	 *
	 * @param geometry
	 * @return
	 */
	public float getCombineRating(RecordedOsmGeometries geometry) {
		if (!isMergeable(geometry)) {
			return 0;
		}
		int commonHashes = 0;
		final int[] otherHashes = geometry.getCombineHashes();
		final int[] myHashes = getCombineHashes();
		for (final int h : myHashes) {
			final int inOtherHashes = Arrays.binarySearch(otherHashes, h);
			if (inOtherHashes >= 0) {
				commonHashes++;
			}
		}
		final int totalHashes = otherHashes.length + myHashes.length - commonHashes;

		return (float) commonHashes / totalHashes;
	}

	/**
	 * Attempts to merge all stored {@link RecordedGeometry}s. This optimizes draw performance.
	 */
	public void mergeChildren() {
		final ArrayList<RecordedGeometry> storedGeometries = new ArrayList<>(
				geometries);
		geometries.clear();
		RecordedGeometry last = null;
		for (final RecordedGeometry r : storedGeometries) {
			if (last != null && last.attemptCombineWith(r)) {
				// pass
			} else {
				geometries.add(r);
				last = r;
			}
		}
	}

}
