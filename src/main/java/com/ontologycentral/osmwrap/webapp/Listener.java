package com.ontologycentral.osmwrap.webapp;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.MutableConfiguration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.stream.StreamSource;

public class Listener implements ServletContextListener {
	Logger _log = Logger.getLogger(this.getClass().getName());

	public static SimpleDateFormat RFC822 = new SimpleDateFormat("EEE', 'dd' 'MMM' 'yyyy' 'HH:mm:ss' 'Z", Locale.US);
	public static SimpleDateFormat ISO8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
	
	public static String FACTORY = "f";
	public static String TOC = "t";
	public static String CACHE = "c";
	
	public static String NODE = "/node/";
	public static String RELATION = "/relation/";
	public static String WAY = "/way/";
	public static String NODE_GML = "/node/.gml";
	public static String WAY_GML = "/way/.gml";
	public static String RELATION_GML = "/relation/.gml";
	public static String SEARCH = "search";
	public static String MAP = "map";
	public static String POI = "poi";
	public static String CHANGESET = "changeset";
	
	public void contextInitialized(ServletContextEvent event) {
		ServletContext ctx = event.getServletContext();

	    XMLOutputFactory factory = XMLOutputFactory.newInstance();

	    ctx.setAttribute(FACTORY, factory);
	    
        Cache cache = null;

        try {
            CacheManager cacheManager = Caching.getCachingProvider().getCacheManager();
            cache = cacheManager.createCache("osmCache", new MutableConfiguration<>());
    		ctx.setAttribute(CACHE, cache);
        } catch (CacheException e) {
        	e.printStackTrace();
        }

		javax.xml.transform.TransformerFactory tf =
		      javax.xml.transform.TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl",
		    		  Thread.currentThread().getContextClassLoader()); 

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/node.xsl")));
			ctx.setAttribute(NODE, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/relation.xsl")));
			ctx.setAttribute(RELATION, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/way.xsl")));
			ctx.setAttribute(WAY, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/node-gml.xsl")));
			ctx.setAttribute(NODE_GML, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/way-gml.xsl")));
			ctx.setAttribute(WAY_GML, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/relation-gml.xsl")));
			ctx.setAttribute(RELATION_GML, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/search.xsl")));
			ctx.setAttribute(SEARCH, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/map.xsl")));
			ctx.setAttribute(MAP, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/poi.xsl")));
			ctx.setAttribute(POI, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}

		try {
			Templates tmpl = tf.newTemplates(new StreamSource(ctx.getRealPath("/WEB-INF/xsl/changeset.xsl")));
			ctx.setAttribute(CHANGESET, tmpl);
		} catch (TransformerConfigurationException e) {
			_log.severe(e.getMessage());
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	public void contextDestroyed(ServletContextEvent event) {
		// TODO Auto-generated method stub		
	}
}