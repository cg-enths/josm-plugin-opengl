package org.openstreetmap.josm.gsoc2015.opengl;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;

import javax.swing.JPanel;
import javax.swing.SpringLayout;

import org.openstreetmap.josm.gsoc2015.opengl.install.JOGLInstaller;
import org.openstreetmap.josm.gsoc2015.opengl.install.JOGLInstaller.JOGLInstallProgress;
import org.openstreetmap.josm.gsoc2015.opengl.jogl.MapPanelFactory;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.plugins.PluginInformation;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * This class is our version of {@link MapView}. Since the {@link MapView} class
 * also handles global state, we cannot extend it. Some of the operations here
 * are just copy+pasted from {@link MapView}.
 * 
 * @author michael
 *
 */
public class OpenGLMapView extends JPanel {
	private MapView mapView;

	public OpenGLMapView(MapView mapView, PluginInformation pluginInformation) {
		super(new BorderLayout());
		this.mapView = mapView;
		if (Shortcut.findShortcut(KeyEvent.VK_TAB, 0) != null) {
			setFocusTraversalKeysEnabled(false);
		}

		final ProgressPanel progressMonitor = new ProgressPanel();
		JOGLInstaller.requireJOGLInstallation(new JOGLInstallProgress() {
			@Override
			public void progressChanged(float progress, String message) {
				progressMonitor.progressChanged(progress, message);
			}

			@Override
			public void joglInstalled() {
				OpenGLMapView.this.remove(progressMonitor);
				addOpenglView();
			}
		}, pluginInformation);
		add(progressMonitor);

	}

	protected void addOpenglView() {
		removeAll();
		SpringLayout springLayout = new SpringLayout();
		setLayout(springLayout);

		// This adds some absolutely positioned elements to this view.
		MapView.addMapNavigationComponents(this, mapView);

		// Convert all bounds to swing constrains.
		for (Component c : getComponents()) {
			Rectangle absBounds = c.getBounds();
			if (absBounds == null) {
				Point location = c.getLocation();
				if (location != null) {
					absBounds = new Rectangle(c.getPreferredSize());
					absBounds.translate(location.x, location.y);
				} else {
					continue;
				}
			}

			springLayout.putConstraint(SpringLayout.WEST, c, absBounds.x,
					SpringLayout.WEST, this);
			springLayout.putConstraint(SpringLayout.NORTH, c, absBounds.y,
					SpringLayout.NORTH, this);
			springLayout.putConstraint(SpringLayout.EAST, c, absBounds.width
					+ absBounds.x, SpringLayout.WEST, this);
			springLayout.putConstraint(SpringLayout.SOUTH, c, absBounds.height
					+ absBounds.y, SpringLayout.NORTH, this);
		}

		// The last added component is painted first.
		JPanel mouseEventIntercept = new InterceptMouseEvents(mapView);
		add(mouseEventIntercept);
		setBackground(new Color(0, 0, 0, 0));
		setToFullSize(springLayout, this, mouseEventIntercept);
		
		Component renderer = MapPanelFactory.getMapRenderer(mapView);
		add(renderer);
		setToFullSize(springLayout, mouseEventIntercept, renderer);
	}

	private void setToFullSize(SpringLayout springLayout, Component parent, Component child) {
		springLayout.putConstraint(SpringLayout.WEST, child, 0,
				SpringLayout.WEST, parent);
		springLayout.putConstraint(SpringLayout.NORTH, child, 0,
				SpringLayout.NORTH, parent);
		springLayout.putConstraint(SpringLayout.EAST, parent, 0,
				SpringLayout.EAST, child);
		springLayout.putConstraint(SpringLayout.SOUTH, parent, 0,
				SpringLayout.SOUTH, child);
	}

}