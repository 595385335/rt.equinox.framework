/*******************************************************************************
 * Copyright (c) 2003, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.runtime.adaptor;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.Constants;
import org.eclipse.osgi.framework.internal.defaultadaptor.DevClassPathHelper;
import org.eclipse.osgi.framework.log.FrameworkLogEntry;
import org.eclipse.osgi.service.pluginconversion.PluginConversionException;
import org.eclipse.osgi.service.pluginconversion.PluginConverter;
import org.eclipse.osgi.util.ManifestElement;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;

public class PluginConverterImpl implements PluginConverter {
	public static boolean DEBUG = false;
	private static final String SEMICOLON = "; "; //$NON-NLS-1$
	private static final String UTF_8 = "UTF-8"; //$NON-NLS-1$
	private static final String LIST_SEPARATOR = ",\n "; //$NON-NLS-1$
	private static final String DOT = "."; //$NON-NLS-1$
	private BundleContext context;
	private BufferedWriter out;
	private IPluginInfo pluginInfo;
	private File pluginManifestLocation;
	private Dictionary generatedManifest;
	private byte manifestType;
	private String target;
	private static final String MANIFEST_VERSION = "Manifest-Version"; //$NON-NLS-1$
	private static final String PLUGIN_PROPERTIES_FILENAME = "plugin"; //$NON-NLS-1$
	private static PluginConverterImpl instance;
	private static final String[] ARCH_LIST = {org.eclipse.osgi.service.environment.Constants.ARCH_PA_RISC, org.eclipse.osgi.service.environment.Constants.ARCH_PPC, org.eclipse.osgi.service.environment.Constants.ARCH_SPARC, org.eclipse.osgi.service.environment.Constants.ARCH_X86, org.eclipse.osgi.service.environment.Constants.ARCH_AMD64};
	protected static final String FRAGMENT_MANIFEST = "fragment.xml"; //$NON-NLS-1$
	protected static final String GENERATED_FROM = "Generated-from"; //$NON-NLS-1$
	protected static final String MANIFEST_TYPE_ATTRIBUTE = "type"; //$NON-NLS-1$
	private static final String[] OS_LIST = {org.eclipse.osgi.service.environment.Constants.OS_AIX, org.eclipse.osgi.service.environment.Constants.OS_HPUX, org.eclipse.osgi.service.environment.Constants.OS_LINUX, org.eclipse.osgi.service.environment.Constants.OS_MACOSX, org.eclipse.osgi.service.environment.Constants.OS_QNX, org.eclipse.osgi.service.environment.Constants.OS_SOLARIS, org.eclipse.osgi.service.environment.Constants.OS_WIN32};
	protected static final String PI_RUNTIME = "org.eclipse.core.runtime"; //$NON-NLS-1$
	protected static final String PI_BOOT = "org.eclipse.core.boot"; //$NON-NLS-1$
	protected static final String PI_RUNTIME_COMPATIBILITY = "org.eclipse.core.runtime.compatibility"; //$NON-NLS-1$
	protected static final String PLUGIN_MANIFEST = "plugin.xml"; //$NON-NLS-1$
	private static final String COMPATIBILITY_ACTIVATOR = "org.eclipse.core.internal.compatibility.PluginActivator"; //$NON-NLS-1$
	private static final String[] WS_LIST = {org.eclipse.osgi.service.environment.Constants.WS_CARBON, org.eclipse.osgi.service.environment.Constants.WS_GTK, org.eclipse.osgi.service.environment.Constants.WS_MOTIF, org.eclipse.osgi.service.environment.Constants.WS_PHOTON, org.eclipse.osgi.service.environment.Constants.WS_WIN32};

	public static PluginConverterImpl getDefault() {
		return instance;
	}

	public PluginConverterImpl() {
		this(null);
	}

	PluginConverterImpl(BundleContext context) {
		this.context = context;
		instance = this;
	}

	private void init() {
		// need to make sure these fields are cleared out for each conversion.
		out = null;
		pluginInfo = null;
		pluginManifestLocation = null;
		generatedManifest = new Hashtable(10);
		manifestType = EclipseBundleData.MANIFEST_TYPE_UNKNOWN;
		target = null;
	}

	private void fillPluginInfo(File pluginBaseLocation) throws PluginConversionException {
		pluginManifestLocation = pluginBaseLocation;
		if (pluginManifestLocation == null)
			throw new IllegalArgumentException();
		URL pluginFile = findPluginManifest(pluginBaseLocation);
		if (pluginFile == null)
			throw new PluginConversionException(EclipseAdaptorMsg.formatter.getString("ECLIPSE_CONVERTER_FILENOTFOUND", pluginBaseLocation.getAbsolutePath())); //$NON-NLS-1$
		pluginInfo = parsePluginInfo(pluginFile);
		String validation = pluginInfo.validateForm();
		if (validation != null)
			throw new PluginConversionException(validation);
	}

	private Set filterExport(Collection exportToFilter, Collection filter) {
		if (filter == null || filter.contains("*")) //$NON-NLS-1$
			return (Set) exportToFilter;
		Set filteredExport = new HashSet(exportToFilter.size());
		for (Iterator iter = exportToFilter.iterator(); iter.hasNext();) {
			String anExport = (String) iter.next();
			for (Iterator iter2 = filter.iterator(); iter2.hasNext();) {
				String aFilter = (String) iter2.next();
				if (anExport.startsWith(aFilter)) {
					filteredExport.add(anExport);
					break;
				}
			}
		}
		return filteredExport;
	}

	private ArrayList findOSJars(File pluginRoot, String path, boolean filter) {
		path = path.substring(4);
		ArrayList found = new ArrayList(0);
		for (int i = 0; i < OS_LIST.length; i++) {
			//look for os/osname/path
			String searchedPath = "os/" + OS_LIST[i] + "/" + path; //$NON-NLS-1$ //$NON-NLS-2$
			if (new File(pluginRoot, searchedPath).exists())
				found.add(searchedPath + (filter ? ";(os=" + WS_LIST[i] + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			//look for os/osname/archname/path
			for (int j = 0; j < ARCH_LIST.length; j++) {
				searchedPath = "os/" + OS_LIST[i] + "/" + ARCH_LIST[j] + "/" + path; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				if (new File(pluginRoot, searchedPath).exists()) {
					found.add(searchedPath + (filter ? ";(& (os=" + WS_LIST[i] + ") (arch=" + ARCH_LIST[j] + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				}
			}
		}
		return found;
	}

	private URL findPluginManifest(File baseLocation) {
		//Here, we can not use the bundlefile because it may explode the jar and returns a location from which we will not be able to derive the jars location 
		URL xmlFileLocation;
		InputStream stream = null;
		URL baseURL = null;
		try {
			if (baseLocation.getName().endsWith(".jar")) { //$NON-NLS-1$
				baseURL = new URL("jar:file:" + baseLocation.toString() + "!/"); //$NON-NLS-1$ //$NON-NLS-2$
				manifestType |= EclipseBundleData.MANIFEST_TYPE_JAR;
			} else {
				baseURL = baseLocation.toURL();
			}
		} catch (MalformedURLException e1) {
			//this can't happen since we are building the urls ourselves from a file
		}
		try {
			xmlFileLocation = new URL(baseURL, PLUGIN_MANIFEST);
			stream = xmlFileLocation.openStream();
			manifestType |= EclipseBundleData.MANIFEST_TYPE_PLUGIN;
			return xmlFileLocation;
		} catch (MalformedURLException e) {
			FrameworkLogEntry entry = new FrameworkLogEntry(EclipseAdaptor.FRAMEWORK_SYMBOLICNAME, e.getMessage(), 0, e, null);
			EclipseAdaptor.getDefault().getFrameworkLog().log(entry);
			return null;
		} catch (IOException ioe) {
			//ignore
		} finally {
			try {
				if (stream != null)
					stream.close();
			} catch (IOException e) {
				//ignore
			}
		}
		try {
			xmlFileLocation = new URL(baseURL, FRAGMENT_MANIFEST);
			xmlFileLocation.openStream();
			manifestType |= EclipseBundleData.MANIFEST_TYPE_FRAGMENT;
			return xmlFileLocation;
		} catch (MalformedURLException e) {
			FrameworkLogEntry entry = new FrameworkLogEntry(EclipseAdaptor.FRAMEWORK_SYMBOLICNAME, e.getMessage(), 0, e, null);
			EclipseAdaptor.getDefault().getFrameworkLog().log(entry);
			return null;
		} catch (IOException ioe) {
			// Ignore
		} finally {
			try {
				if (stream != null)
					stream.close();
			} catch (IOException e) {
				//ignore
			}
		}
		return null;
	}

	private ArrayList findWSJars(File pluginRoot, String path, boolean filter) {
		path = path.substring(4);
		ArrayList found = new ArrayList(0);
		for (int i = 0; i < WS_LIST.length; i++) {
			String searchedPath = "ws/" + WS_LIST[i] + path; //$NON-NLS-1$
			if (new File(pluginRoot, searchedPath).exists()) {
				found.add(searchedPath + (filter ? ";(ws=" + WS_LIST[i] + ")" : "")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
		}
		return found;
	}

	protected void fillManifest(boolean compatibilityManifest) {
		generateManifestVersion();
		generateHeaders();
		generateClasspath();
		generateActivator();
		generatePluginClass();
		generateProvidePackage();
		generateRequireBundle();
		generateLocalizationEntry();
		if (compatibilityManifest) {
			generateTimestamp();
			generateEclipseHeaders();
		}
	}

	public void writeManifest(File generationLocation, Dictionary manifestToWrite, boolean compatibilityManifest) throws PluginConversionException {
		try {
			File parentFile = new File(generationLocation.getParent());
			parentFile.mkdirs();
			generationLocation.createNewFile();
			if (!generationLocation.isFile()) {
				String message = EclipseAdaptorMsg.formatter.getString("ECLIPSE_CONVERTER_ERROR_CREATING_BUNDLE_MANIFEST", this.pluginInfo.getUniqueId(), generationLocation); //$NON-NLS-1$
				throw new PluginConversionException(message);
			}
			// replaces any eventual existing file
			manifestToWrite = new Hashtable((Map) manifestToWrite);
			// MANIFEST.MF files must be written using UTF-8
			out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(generationLocation), UTF_8));
			writeEntry(MANIFEST_VERSION, (String) manifestToWrite.remove(MANIFEST_VERSION));
			writeEntry(GENERATED_FROM, (String) manifestToWrite.remove(GENERATED_FROM)); //Need to do this first uptoDate check expect the generated-from tag to be in the first line
			writeEntry(Constants.BUNDLE_NAME, (String) manifestToWrite.remove(Constants.BUNDLE_NAME));
			writeEntry(Constants.BUNDLE_SYMBOLICNAME, (String) manifestToWrite.remove(Constants.BUNDLE_SYMBOLICNAME));
			writeEntry(Constants.BUNDLE_VERSION, (String) manifestToWrite.remove(Constants.BUNDLE_VERSION));
			writeEntry(Constants.BUNDLE_CLASSPATH, (String) manifestToWrite.remove(Constants.BUNDLE_CLASSPATH));
			writeEntry(Constants.BUNDLE_ACTIVATOR, (String) manifestToWrite.remove(Constants.BUNDLE_ACTIVATOR));
			writeEntry(Constants.BUNDLE_VENDOR, (String) manifestToWrite.remove(Constants.BUNDLE_VENDOR));
			writeEntry(Constants.FRAGMENT_HOST, (String) manifestToWrite.remove(Constants.FRAGMENT_HOST));
			writeEntry(Constants.BUNDLE_MANIFEST_LOCALIZATION, (String) manifestToWrite.remove(Constants.BUNDLE_MANIFEST_LOCALIZATION));
			writeEntry(Constants.PROVIDE_PACKAGE, (String) manifestToWrite.remove(Constants.PROVIDE_PACKAGE));
			writeEntry(Constants.REQUIRE_BUNDLE, (String) manifestToWrite.remove(Constants.REQUIRE_BUNDLE));
			Enumeration keys = manifestToWrite.keys();
			while (keys.hasMoreElements()) {
				String key = (String) keys.nextElement();
				writeEntry(key, (String) manifestToWrite.get(key));
			}
			out.flush();
		} catch (IOException e) {
			String message = EclipseAdaptorMsg.formatter.getString("ECLIPSE_CONVERTER_ERROR_CREATING_BUNDLE_MANIFEST", this.pluginInfo.getUniqueId(), generationLocation); //$NON-NLS-1$
			throw new PluginConversionException(message, e);
		} finally {
			if (out != null)
				try {
					out.close();
				} catch (IOException e) {
					// only report problems writing to/flushing the file
				}
		}
	}

	private void generateLocalizationEntry() {
		generatedManifest.put(Constants.BUNDLE_MANIFEST_LOCALIZATION, PLUGIN_PROPERTIES_FILENAME);
	}

	private void generateManifestVersion() {
		generatedManifest.put(MANIFEST_VERSION, "1.0"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	private boolean requireRuntimeCompatibility() {
		ArrayList requireList = pluginInfo.getRequires();
		for (Iterator iter = requireList.iterator(); iter.hasNext();) {
			if (((PluginParser.Prerequisite) iter.next()).getName().equalsIgnoreCase(PI_RUNTIME_COMPATIBILITY))
				return true;
		}
		return false;
	}

	private void generateActivator() {
		if (!pluginInfo.isFragment())
			if (!requireRuntimeCompatibility()) {
				String pluginClass = pluginInfo.getPluginClass();
				if (pluginClass != null && !pluginClass.trim().equals("")) //$NON-NLS-1$
					generatedManifest.put(Constants.BUNDLE_ACTIVATOR, pluginClass);
			} else {
				generatedManifest.put(Constants.BUNDLE_ACTIVATOR, COMPATIBILITY_ACTIVATOR);
			}
	}

	private void generateClasspath() {
		String[] classpath = pluginInfo.getLibrariesName();
		if (classpath.length != 0)
			generatedManifest.put(Constants.BUNDLE_CLASSPATH, getStringFromArray(classpath, LIST_SEPARATOR));
	}

	private void generateHeaders() {
		generatedManifest.put(Constants.BUNDLE_NAME, pluginInfo.getPluginName());
		generatedManifest.put(Constants.BUNDLE_VERSION, pluginInfo.getVersion());
		generatedManifest.put(Constants.BUNDLE_SYMBOLICNAME, getSymbolicNameEntry());
		String provider = pluginInfo.getProviderName();
		if (provider != null)
			generatedManifest.put(Constants.BUNDLE_VENDOR, provider);
		if (pluginInfo.isFragment()) {
			StringBuffer hostBundle = new StringBuffer();
			hostBundle.append(pluginInfo.getMasterId()).append(SEMICOLON); //$NON-NLS-1$
			hostBundle.append(Constants.BUNDLE_VERSION_ATTRIBUTE).append("="); //$NON-NLS-1$
			hostBundle.append(pluginInfo.getMasterVersion());
			generatedManifest.put(Constants.FRAGMENT_HOST, hostBundle.toString());
		}
	}

	/*
	 * Generates an entry in the form: 
	 * 	<symbolic-name>[; singleton=true]
	 */
	private String getSymbolicNameEntry() {
		// false is the default, so don't bother adding anything 
		if (!pluginInfo.isSingleton())
			return pluginInfo.getUniqueId();
		StringBuffer result = new StringBuffer(pluginInfo.getUniqueId());
		result.append(SEMICOLON); //$NON-NLS-1$
		result.append(Constants.SINGLETON_ATTRIBUTE);
		result.append("=true"); //$NON-NLS-1$
		return result.toString();
	}

	private void generatePluginClass() {
		if (requireRuntimeCompatibility()) {
			String pluginClass = pluginInfo.getPluginClass();
			if (pluginClass != null)
				generatedManifest.put(EclipseAdaptorConstants.PLUGIN_CLASS, pluginClass);
		}
	}

	private void generateProvidePackage() {
		Set exports = getExports();
		if (exports != null && exports.size() != 0) {
			generatedManifest.put(Constants.PROVIDE_PACKAGE, getStringFromCollection(exports, LIST_SEPARATOR));
		}
	}

	private void generateRequireBundle() {
		ArrayList requiredBundles = pluginInfo.getRequires();
		if (requiredBundles.size() == 0)
			return;
		StringBuffer bundleRequire = new StringBuffer();
		for (Iterator iter = requiredBundles.iterator(); iter.hasNext();) {
			PluginParser.Prerequisite element = (PluginParser.Prerequisite) iter.next();
			StringBuffer modImport = new StringBuffer(element.getName());
			if (element.getVersion() != null) {
				modImport.append(';').append(Constants.BUNDLE_VERSION_ATTRIBUTE).append("=").append(element.getVersion()); //$NON-NLS-1$ 
			}
			if (element.isExported()) {
				modImport.append(';').append(Constants.REPROVIDE_ATTRIBUTE).append("=true");//$NON-NLS-1$ 
			}
			if (element.isOptional()) {
				modImport.append(';').append(Constants.OPTIONAL_ATTRIBUTE).append("=true");//$NON-NLS-1$
			}
			if (element.getMatch() != null) {
				modImport.append(';').append(Constants.VERSION_MATCH_ATTRIBUTE).append("="); //$NON-NLS-1$ 
				if (element.getMatch().equalsIgnoreCase(IModel.PLUGIN_REQUIRES_MATCH_PERFECT)) {
					modImport.append(Constants.VERSION_MATCH_QUALIFIER);
				} else if (element.getMatch().equalsIgnoreCase(IModel.PLUGIN_REQUIRES_MATCH_EQUIVALENT)) {
					modImport.append(Constants.VERSION_MATCH_MINOR);
				} else if (element.getMatch().equalsIgnoreCase(IModel.PLUGIN_REQUIRES_MATCH_COMPATIBLE)) {
					modImport.append(Constants.VERSION_MATCH_MAJOR);
				} else if (element.getMatch().equalsIgnoreCase(IModel.PLUGIN_REQUIRES_MATCH_GREATER_OR_EQUAL)) {
					modImport.append(Constants.VERSION_MATCH_GREATERTHANOREQUAL);
				}
			}
			bundleRequire.append(modImport);
			if (iter.hasNext())
				bundleRequire.append(LIST_SEPARATOR);
		}
		generatedManifest.put(Constants.REQUIRE_BUNDLE, bundleRequire.toString());
	}

	private void generateTimestamp() {
		// so it is easy to tell which ones are generated
		generatedManifest.put(GENERATED_FROM, Long.toString(getTimeStamp(pluginManifestLocation, manifestType)) + ";" + MANIFEST_TYPE_ATTRIBUTE + "=" + manifestType);
	}

	private void generateEclipseHeaders() {
		generatedManifest.put(EclipseAdaptorConstants.ECLIPSE_AUTOSTART, "true");
	}

	private Set getExports() {
		Map libs = pluginInfo.getLibraries();
		if (libs == null)
			return null;

		//If we are in dev mode, then add the binary folders on the list libs with the export clause set to be the cumulation of the export clause of the real libs   
		if (DevClassPathHelper.inDevelopmentMode()) {
			String[] devClassPath = DevClassPathHelper.getDevClassPath(pluginInfo.getUniqueId());

			// collect export clauses
			List allExportClauses = new ArrayList(libs.size());
			Set libEntries = libs.entrySet();
			for (Iterator iter = libEntries.iterator(); iter.hasNext();) {
				Map.Entry element = (Map.Entry) iter.next();
				allExportClauses.addAll((List) element.getValue());
			}
			if (devClassPath != null) {
				for (int i = 0; i < devClassPath.length; i++)
					libs.put(devClassPath[i], allExportClauses);
			}
		}

		Set result = new HashSet(7);
		Set libEntries = libs.entrySet();
		for (Iterator iter = libEntries.iterator(); iter.hasNext();) {
			Map.Entry element = (Map.Entry) iter.next();
			List filter = (List) element.getValue();
			if (filter.size() == 0) //If the library is not exported, then ignore it
				continue;
			File libraryLocation = new File(pluginManifestLocation, (String) element.getKey());
			Set exports = null;
			if (libraryLocation.exists()) {
				if (libraryLocation.isFile())
					exports = filterExport(getExportsFromJAR(libraryLocation), filter); //TODO Need to handle $xx$ variables
				else if (libraryLocation.isDirectory())
					exports = filterExport(getExportsFromDir(libraryLocation), filter);
			} else {
				ArrayList expandedLibs = getLibrariesExpandingVariables((String) element.getKey(), false);
				exports = new HashSet();
				for (Iterator iterator = expandedLibs.iterator(); iterator.hasNext();) {
					String libName = (String) iterator.next();
					File libFile = new File(pluginManifestLocation, libName);
					if (libFile.isFile()) {
						exports.addAll(filterExport(getExportsFromJAR(libFile), filter));
					}
				}
			}
			if (exports != null)
				result.addAll(exports);
		}
		return result;
	}

	private Set getExportsFromDir(File location) {
		return getExportsFromDir(location, ""); //$NON-NLS-1$
	}

	private Set getExportsFromDir(File location, String packageName) {
		String prefix = (packageName.length() > 0) ? (packageName + '.') : ""; //$NON-NLS-1$
		String[] files = location.list();
		Set exportedPaths = new HashSet();
		boolean containsFile = false;
		for (int i = 0; i < files.length; i++) {
			if (!isValidPackageName(files[i]))
				continue;
			File pkgFile = new File(location, files[i]);
			if (pkgFile.isDirectory())
				exportedPaths.addAll(getExportsFromDir(pkgFile, prefix + files[i]));
			else
				containsFile = true;
		}
		if (containsFile)
			// Allow the default package to be provided.  If the default package
			// contains a File then use "." as the package name to provide for default.
			if (packageName.length() > 0)
				exportedPaths.add(packageName);
			else
				exportedPaths.add(DOT);
		return exportedPaths;
	}

	private Set getExportsFromJAR(File jarFile) {
		Set names = new HashSet();
		JarFile file = null;
		try {
			file = new JarFile(jarFile);
		} catch (IOException e) {
			String message = EclipseAdaptorMsg.formatter.getString("ECLIPSE_CONVERTER_PLUGIN_LIBRARY_IGNORED", jarFile, pluginInfo.getUniqueId()); //$NON-NLS-1$
			EclipseAdaptor.getDefault().getFrameworkLog().log(new FrameworkLogEntry(EclipseAdaptorConstants.PI_ECLIPSE_OSGI, message, 0, e, null));
			return names;
		}
		//Run through the entries
		for (Enumeration enum = file.entries(); enum.hasMoreElements();) {
			JarEntry entry = (JarEntry) enum.nextElement();
			String name = entry.getName();
			if (!isValidPackageName(name))
				continue;
			int lastSlash = name.lastIndexOf("/"); //$NON-NLS-1$
			//Ignore folders that do not contain files
			if (lastSlash != -1) {
				if (lastSlash != name.length() - 1 && name.lastIndexOf(' ') == -1)
					names.add(name.substring(0, lastSlash).replace('/', '.'));
			} else {
				// Allow the default package to be provided.  If the default package
				// contains a File then use "." as the package name to provide for default.
				names.add(DOT);
			}
		}
		return names;
	}

	private ArrayList getLibrariesExpandingVariables(String libraryPath, boolean filter) {
		String var = hasPrefix(libraryPath);
		if (var == null) {
			ArrayList returnValue = new ArrayList(1);
			returnValue.add(libraryPath);
			return returnValue;
		}
		if (var.equals("ws")) { //$NON-NLS-1$
			return findWSJars(pluginManifestLocation, libraryPath, filter);
		}
		if (var.equals("os")) { //$NON-NLS-1$
			return findOSJars(pluginManifestLocation, libraryPath, filter);
		}
		return new ArrayList(0);
	}

	//return a String representing the string found between the $s
	private String hasPrefix(String libPath) {
		if (libPath.startsWith("$ws$")) //$NON-NLS-1$
			return "ws"; //$NON-NLS-1$
		if (libPath.startsWith("$os$")) //$NON-NLS-1$
			return "os"; //$NON-NLS-1$
		if (libPath.startsWith("$nl$")) //$NON-NLS-1$
			return "nl"; //$NON-NLS-1$
		return null;
	}

	private boolean isValidPackageName(String name) {
		if (name.indexOf(' ') > 0 || name.equalsIgnoreCase("META-INF") || name.startsWith("META-INF/")) //$NON-NLS-1$ //$NON-NLS-2$
			return false;
		return true;
	}

	/**
	 * Parses the plugin manifest to find out: - the plug-in unique identifier -
	 * the plug-in version - runtime/libraries entries - the plug-in class -
	 * the master plugin (for a fragment)
	 */
	private IPluginInfo parsePluginInfo(URL pluginLocation) throws PluginConversionException {
		InputStream input = null;
		try {
			input = new BufferedInputStream(pluginLocation.openStream());
			return new PluginParser(context, target).parsePlugin(input);
		} catch (Exception e) {
			String message = EclipseAdaptorMsg.formatter.getString("ECLIPSE_CONVERTER_ERROR_PARSING_PLUGIN_MANIFEST", pluginManifestLocation); //$NON-NLS-1$
			throw new PluginConversionException(message, e);
		} finally {
			if (input != null)
				try {
					input.close();
				} catch (IOException e) {
					//ignore exception
				}
		}
	}

	public static boolean upToDate(File generationLocation, File pluginLocation, byte manifestType) {
		if (!generationLocation.isFile())
			return false;
		String secondLine = null;
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(generationLocation)));
			reader.readLine();
			secondLine = reader.readLine();
		} catch (IOException e) {
			// not a big deal - we could not read an existing manifest
			return false;
		} finally {
			if (reader != null)
				try {
					reader.close();
				} catch (IOException e) {
					// ignore
				}
		}
		String tag = GENERATED_FROM + ": "; //$NON-NLS-1$
		if (secondLine == null || !secondLine.startsWith(tag))
			return false;

		secondLine = secondLine.substring(tag.length());
		ManifestElement generatedFrom;
		try {
			generatedFrom = ManifestElement.parseHeader(PluginConverterImpl.GENERATED_FROM, secondLine)[0];
		} catch (BundleException be) {
			return false;
		}
		String timestampStr = generatedFrom.getValue();
		try {
			return Long.parseLong(timestampStr.trim()) == getTimeStamp(pluginLocation, manifestType);
		} catch (NumberFormatException nfe) {
			// not a big deal - just a bogus existing manifest that will be ignored
		}
		return false;
	}

	public static long getTimeStamp(File pluginLocation, byte manifestType) {
		if ((manifestType & EclipseBundleData.MANIFEST_TYPE_JAR) != 0)
			return pluginLocation.lastModified();
		else if ((manifestType & EclipseBundleData.MANIFEST_TYPE_PLUGIN) != 0)
			return new File(pluginLocation, PLUGIN_MANIFEST).lastModified();
		else if ((manifestType & EclipseBundleData.MANIFEST_TYPE_FRAGMENT) != 0)
			return new File(pluginLocation, FRAGMENT_MANIFEST).lastModified();
		else if ((manifestType & EclipseBundleData.MANIFEST_TYPE_BUNDLE) != 0)
			return new File(pluginLocation, Constants.OSGI_BUNDLE_MANIFEST).lastModified();
		return -1;
	}

	private void writeEntry(String key, String value) throws IOException {
		if (value != null && value.length() > 0) {
			out.write(key + ": " + value); //$NON-NLS-1$
			out.newLine();
		}
	}

	private String getStringFromArray(String[] values, String separator) {
		if (values == null)
			return ""; //$NON-NLS-1$
		StringBuffer result = new StringBuffer();
		for (int i = 0; i < values.length; i++) {
			if (i > 0)
				result.append(separator);
			result.append(values[i]);
		}
		return result.toString();
	}

	private String getStringFromCollection(Collection collection, String separator) {
		StringBuffer result = new StringBuffer();
		boolean first = true;
		for (Iterator i = collection.iterator(); i.hasNext();) {
			if (first)
				first = false;
			else
				result.append(separator);
			result.append(i.next());
		}
		return result.toString();
	}

	public synchronized Dictionary convertManifest(File pluginBaseLocation, boolean compatibility, String target) throws PluginConversionException {
		if (DEBUG)
			System.out.println("Convert " + pluginBaseLocation); //$NON-NLS-1$
		init();
		this.target = target;
		fillPluginInfo(pluginBaseLocation);
		fillManifest(compatibility);
		return generatedManifest;
	}

	public synchronized File convertManifest(File pluginBaseLocation, File bundleManifestLocation, boolean compatibilityManifest, String target) throws PluginConversionException {
		if (DEBUG)
			System.out.println("Convert " + pluginBaseLocation); //$NON-NLS-1$
		init();
		this.target = target;
		fillPluginInfo(pluginBaseLocation);
		if (bundleManifestLocation == null) {
			String cacheLocation = (String) System.getProperties().get("osgi.manifest.cache"); //$NON-NLS-1$
			bundleManifestLocation = new File(cacheLocation, pluginInfo.getUniqueId() + '_' + pluginInfo.getVersion() + ".MF"); //$NON-NLS-1$
		}
		fillManifest(compatibilityManifest);
		if (upToDate(bundleManifestLocation, pluginManifestLocation, manifestType))
			return bundleManifestLocation;
		writeManifest(bundleManifestLocation, generatedManifest, compatibilityManifest);
		return bundleManifestLocation;
	}

}