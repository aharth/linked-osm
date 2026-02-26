package com.ontologycentral.osmwrap.webapp;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.stream.StreamSource;

public class Listener implements ServletContextListener {
	private static final Logger _log = Logger.getLogger(Listener.class.getName());

	public static final DateTimeFormatter RFC822 = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);

	public static final String NODE = "/node/";
	public static final String RELATION = "/relation/";
	public static final String WAY = "/way/";
	public static final String NODE_GML = "/node/.gml";
	public static final String WAY_GML = "/way/.gml";
	public static final String RELATION_GML = "/relation/.gml";
	public static final String SEARCH = "search";
	public static final String MAP = "map";
	public static final String POI = "poi";
	public static final String CHANGESET = "changeset";

	public void contextInitialized(ServletContextEvent event) {
		ServletContext ctx = event.getServletContext();

		javax.xml.transform.TransformerFactory tf =
		      javax.xml.transform.TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl",
		    		  Thread.currentThread().getContextClassLoader());

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/node.xsl")));
			ctx.setAttribute(NODE, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/relation.xsl")));
			ctx.setAttribute(RELATION, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/way.xsl")));
			ctx.setAttribute(WAY, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/node-gml.xsl")));
			ctx.setAttribute(NODE_GML, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/way-gml.xsl")));
			ctx.setAttribute(WAY_GML, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/relation-gml.xsl")));
			ctx.setAttribute(RELATION_GML, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/search.xsl")));
			ctx.setAttribute(SEARCH, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/map.xsl")));
			ctx.setAttribute(MAP, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/poi.xsl")));
			ctx.setAttribute(POI, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/changeset.xsl")));
			ctx.setAttribute(CHANGESET, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.log(Level.SEVERE, e.getMessage(), e);
			throw new RuntimeException(e);
		}
	}

	public void contextDestroyed(ServletContextEvent event) {
	}
}
